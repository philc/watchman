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
            [watchman.utils :refer [get-env-var log-exception log-info sget sget-in]]
            [postal.core :as postal])
  (:import org.apache.http.conn.ConnectTimeoutException))

(def smtp-credentials {:user (get-env-var "WATCHMAN_SMTP_USERNAME")
                       :pass (get-env-var "WATCHMAN_SMTP_PASSWORD")
                       :host "email-smtp.us-east-1.amazonaws.com"
                       :port 587})

(def from-email-address (get-env-var "WATCHMAN_FROM_EMAIL_ADDRESS"))
(def to-email-address (get-env-var "WATCHMAN_TO_EMAIL_ADDRESS"))

(def watchman-host (or (System/getenv "WATCHMAN_HOST") "localhost:8130"))

(def polling-frequency-ms 5000)

(def at-at-pool (at-at/mk-pool))

(def check-status-ids-in-progress
  "Checks which have an outstanding HTTP request on the wire."
  (atom #{}))

(deftemplate alert-email-html "alert_email.html"
  [check-status]
  [:#check-url] (do-> (set-attr "href" (models/get-url-of-check-status check-status))
                      (content (models/get-url-of-check-status check-status)))
  [:#ssh-link] (set-attr "href" (format "http://%s/ssh_redirect?host_id=%s" watchman-host
                                        (sget check-status :host_id)))
  [:#status] (substitute (str (sget check-status :last_response_status_code)))
  [:#body] (html-content (-> check-status
                             (sget :last_response_body)
                             str
                             (string/replace "\n" "<br/>"))))
(defn alert-email-plaintext
  [check-status]
  (let [template (string/join "\n" ["%s"
                                    "HTTP status: %s"
                                    "Body:"
                                    "%s"])]
    (format template (models/get-url-of-check-status check-status)
            (sget check-status :last_response_status_code)
            (sget check-status :last_response_body))))

(defn send-email
  "Send an email describing the current state of a check-status."
  [check-status]
  (let [host (sget check-status :hosts)
        check (sget check-status :checks)
        subject (format "%s: %s %s"
                        (-> check-status (sget :status) string/capitalize)
                        (models/get-host-display-name host)
                        (models/get-check-display-name check))
        html-body (string/join (alert-email-html check-status))
        plaintext-body (alert-email-plaintext check-status)]
    (log-info (format "Emailing for check-status %s: %s %s" (sget check-status :id)
                      (sget host :hostname) (sget check-status :status)))
    (postal/send-message smtp-credentials
                         {:from from-email-address
                          :to to-email-address
                          :subject subject
                          :body [:alternative
                                 {:type "text/html; charset=utf-8" :content plaintext-body}
                                 {:type "text/html; charset=utf-8" :content html-body}]})))

(defn perform-check
  "The HTTP request and response assertions are done in a future."
  [check-status]
  (swap! check-status-ids-in-progress conj (sget check-status :id))
  (future
    (try
      ; TODO(philc): Add retry behavior.
      (let [check-id (sget check-status :id)
            check (sget check-status :checks)
            host (sget-in check-status [:hosts :hostname])
            url (models/get-url-of-check-status check-status)
            response (try (http/get url {:conn-timeout (-> (sget check :timeout) (* 1000) int)
                                         ; Don't throw exceptions on 500x status codes.
                                         :throw-exceptions false})
                          (catch ConnectTimeoutException exception
                            {:status nil :body (format "Connection to %s timed out after %s." url
                                                       (sget check :timeout))})
                          (catch Exception exception
                            ; In particular, we can get a ConnectionException if the host exists but is not
                            ; listening on the port, or if it's an unknown host.
                            {:status nil :body (format "Error connecting to %s: %s" url exception)}))
            is-up (= (:status response) (sget check :expected_status_code))
            status-has-changed (or (and is-up (= "down" (sget check-status :status)))
                                   (and (not is-up) (= "up" (sget check-status :status))))]
        (log-info (format "%s %s\n%s" url (:status response) (:body response)))
        (k/update models/check-statuses
          (k/set-fields {:last_checked_at (time-coerce/to-timestamp (time-core/now))
                         :last_response_status_code (:status response)
                         :last_response_body (:body response)
                         :status (if is-up "up" "down")})
          (k/where {:id check-id}))
        (when status-has-changed (send-email (models/get-check-status-by-id check-id))))
      (catch Exception exception
        (log-exception (str "Failed to perform check. check-status id: " (sget check-status :id)) exception))
      (finally
        (swap! check-status-ids-in-progress disj (sget check-status :id))))))

(defn perform-eligible-checks
  "Performs all checks which are scheduled to run."
  []
  (let [check-statuses (k/select models/check-statuses
                         (k/where {:state "enabled"})
                         (k/with-object models/hosts)
                         (k/with-object models/checks))]
    (doseq [check-status check-statuses]
      (when (and (nil? (@check-status-ids-in-progress (sget check-status :id)))
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
