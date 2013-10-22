(ns watchman.pinger
  (:require [clojure.core.incubator :refer [-?>]]
            [clj-time.core :as time-core]
            [clj-time.coerce :as time-coerce]
            [clj-http.client :as http]
            [clojure.string :as string]
            [korma.incubator.core :as k]
            [overtone.at-at :as at-at]
            [watchman.models :as models]
            [net.cgrand.enlive-html :refer [content deftemplate do-> html-content set-attr substitute]]
            [watchman.utils :refer [get-env-var log-exception log-info sget sget-in truncate-string]]
            [postal.core :as postal])
  (:import org.apache.http.conn.ConnectTimeoutException))

(def log-emails-without-sending (= "true" (System/getenv "WATCHMAN_LOG_EMAILS_WITHOUT_SENDING")))

(def smtp-credentials {:user (get-env-var "WATCHMAN_SMTP_USERNAME")
                       :pass (get-env-var "WATCHMAN_SMTP_PASSWORD")
                       :host (or (get-env-var "WATCHMAN_SMTP_HOST")
                                 ; Default to Amazon's Simple Email Service.
                                 "email-smtp.us-east-1.amazonaws.com")
                       :port 587})

(def from-email-address (get-env-var "WATCHMAN_FROM_EMAIL_ADDRESS"))
(def to-email-address (get-env-var "WATCHMAN_TO_EMAIL_ADDRESS"))

(def watchman-host (or (System/getenv "WATCHMAN_HOST") "localhost:8130"))

(def polling-frequency-ms 5000)

(def response-body-size-limit-chars 10000)

(def at-at-pool (at-at/mk-pool))

(def checks-in-progress
  "A map check-status-id -> {:in-progress, :attempt-number}. This is book keeping for handling retries."
  (atom {}))

(deftemplate alert-email-html "alert_email.html"
  [check-status]
  [:#check-url] (do-> (set-attr "href" (models/get-url-of-check-status check-status))
                      (content (models/get-url-of-check-status check-status)))
  [:#ssh-link] (set-attr "href" (format "http://%s/ssh_redirect?host_id=%s" watchman-host
                                        (sget check-status :host_id)))
  [:#status] (substitute (sget check-status :status))
  [:#http-status] (substitute (str (sget check-status :last_response_status_code)))
  [:#additional-details] (if (= (sget check-status :status) "down")
                           identity
                           (substitute nil))
  [:#response-body] (if (= (sget check-status :status) "up")
                      (substitute nil)
                      (html-content (-> check-status
                                        (sget :last_response_body)
                                        str
                                        (truncate-string response-body-size-limit-chars)
                                        (string/replace "\n" "<br/>")))))
(defn alert-email-plaintext
  [check-status]
  (let [status (sget check-status :status)
        url (models/get-url-of-check-status check-status)
        up-template (string/join "\n" ["%s"
                                       "Status: %s"])
        down-template (string/join "\n" ["%s"
                                         "Status: %s"
                                         "HTTP status code: %s"
                                         "Body:"
                                         "%s"])]
    (if (= status "up")
      (format up-template url status)
      (format down-template
              url
              status
              (sget check-status :last_response_status_code)
              (-> (sget check-status :last_response_body) (truncate-string response-body-size-limit-chars))))))

(defn send-email
  "Send an email describing the current state of a check-status."
  [check-status]
  (let [host (sget check-status :hosts)
        check (sget check-status :checks)
        subject (format "[%s] %s %s"
                        (-> (sget check :role_id) (models/get-role-by-id) (sget :name))
                        (models/get-host-display-name host)
                        (models/get-check-display-name check))
        html-body (string/join (alert-email-html check-status))
        plaintext-body (alert-email-plaintext check-status)
        email-message {:from from-email-address
                       :to to-email-address
                       :subject subject
                       :body [:alternative
                              {:type "text/html; charset=utf-8" :content plaintext-body}
                              {:type "text/html; charset=utf-8" :content html-body}]}]
    (log-info (format "Emailing for check-status %s: %s %s" (sget check-status :id)
                      (sget host :hostname) (sget check-status :status)))
    (if log-emails-without-sending
      (prn email-message)
      (postal/send-message smtp-credentials email-message))))

(defn- has-remaining-attempts? [check-status]
  (<= (sget-in @checks-in-progress [(sget check-status :id) :attempt-number])
      (sget-in check-status [:checks :max_retries])))

(defn perform-check
  "The HTTP request and response assertions are done in a future."
  [check-status]
  (let [check-status-id (sget check-status :id)]
    (swap! checks-in-progress (fn [old-value]
                                (-> old-value
                                    (assoc-in [check-status-id :in-progress] true)
                                    (update-in [check-status-id :attempt-number] #(inc (or % 0))))))
    (future
      (try
        (let [check (sget check-status :checks)
              host (sget-in check-status [:hosts :hostname])
              url (models/get-url-of-check-status check-status)
              response (try (http/get url {:conn-timeout (-> (sget check :timeout) (* 1000) int)
                                           ; Don't throw exceptions on 500x status codes.
                                           :throw-exceptions false})
                            (catch ConnectTimeoutException exception
                              {:status nil :body (format "Connection to %s timed out after %ss." url
                                                         (sget check :timeout))})
                            (catch Exception exception
                              ; In particular, we can get a ConnectionException if the host exists but is not
                              ; listening on the port, or if it's an unknown host.
                              {:status nil :body (format "Error connecting to %s: %s" url exception)}))
              is-up (= (:status response) (sget check :expected_status_code))
              previous-status (sget check-status :status)
              new-status (if is-up "up" "down")]
          (log-info (format "%s %s\n%s" url (:status response) (-> response :body (truncate-string 300))))
          (if (or is-up (not (has-remaining-attempts? check-status)))
            (do
              (k/update models/check-statuses
                (k/set-fields {:last_checked_at (time-coerce/to-timestamp (time-core/now))
                               :last_response_status_code (:status response)
                               :last_response_body (-> response :body
                                                       (truncate-string response-body-size-limit-chars))
                               :status new-status})
                (k/where {:id check-status-id}))
              (when (not= new-status previous-status)
                (send-email (models/get-check-status-by-id check-status-id)))
              (swap! checks-in-progress dissoc check-status-id))
            (do
              (k/update models/check-statuses
                (k/set-fields {:last_checked_at (time-coerce/to-timestamp (time-core/now))})
                (k/where {:id check-status-id}))
              (log-info (str "Will retry check-status id " check-status-id)))))
        (catch Exception exception
          (log-exception (str "Failed to perform check. check-status id: " check-status-id) exception))
        (finally
          (swap! checks-in-progress assoc-in [check-status-id :in-progress] false))))))

(defn perform-eligible-checks
  "Performs all checks which are scheduled to run."
  []
  (let [check-statuses (k/select models/check-statuses
                         (k/where {:state "enabled"})
                         (k/with-object models/hosts)
                         (k/with-object models/checks))]
    (doseq [check-status check-statuses]
      (when (and (not (get-in @checks-in-progress [(sget check-status :id) :in-progress]))
                 (models/ready-to-perform? check-status))
        (perform-check check-status)))))

(defn start-periodic-polling []
  ; NOTE(philc): For some reason, at-at's jobs do not run from within nREPL.
  (at-at/every polling-frequency-ms
               #(try
                  (perform-eligible-checks)
                  (catch Exception exception
                    (log-exception exception)))
               at-at-pool))

(defn stop-periodic-polling []
  (at-at/stop-and-reset-pool! at-at-pool))
