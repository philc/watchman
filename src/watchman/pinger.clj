(ns watchman.pinger
  (:require [clojure.core.incubator :refer [-?>]]
            [clj-time.core :as time-core]
            [clj-time.coerce :as time-coerce]
            [clj-time.format :as time-format]
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
  "A map check-status-id -> {:in-progress, :attempt-number}. This is a bookkeeping map, for handling retries."
  (atom {}))

(defn- add-role-to-email-address
  "Adds the name of the role to the email address using this format: username+role@domain.com. This makes
  filtering emails for a given role easier."
  [role-name email-address]
  (let [[username domain] (string/split email-address #"@")
        ; Make sure the role name is suitable for being embedded in an email address.
        escaped-role-name (-> role-name
                              (string/replace #"\s+" "_")
                              (string/replace #"[^\w]" "")
                              string/lower-case)]
        (format "%s+%s@%s" username escaped-role-name domain)))

(deftemplate alert-email-html-template "alert_email.html"
  [check-status response-body]
  [:#check-url] (do-> (set-attr "href" (models/get-url-of-check-status check-status))
                      (content (models/get-url-of-check-status check-status)))
  [:#ssh-link] (set-attr "href" (format "http://%s/ssh_redirect?host_id=%s" watchman-host
                                        (sget check-status :host_id)))
  [:#status] (substitute (sget check-status :status))
  [:#http-status] (substitute (str (sget check-status :last_response_status_code)))
  [:.unique-message-id] (content (->> (time-core/now)
                                      time-coerce/to-date-time
                                      (time-format/unparse (:date-time time-format/formatters))))
  [:#additional-details] (if (= (sget check-status :status) "down")
                           identity
                           (substitute nil))
  [:#response-body] (if (= (sget check-status :status) "up")
                      (substitute nil)
                      (html-content response-body)))

(defn- escape-html-chars [s]
  (-> s (string/replace "&" "&amp;") (string/replace "<" "&lt;") (string/replace ">" "&gt;")))

(defn- alert-email-html [check-status]
  (let [response-body (-> check-status
                          (sget :last_response_body)
                          str
                          (truncate-string response-body-size-limit-chars))
        response-body (if (= (sget check-status :last_response_content_type) "text/html")
                        response-body
                        (-> response-body escape-html-chars (string/replace "\n" "<br/>")))]
    (alert-email-html-template check-status response-body)))

(defn- alert-email-plaintext [check-status]
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
  ; We commonly call this function from the REPL, so it's nice to have all needed information as arguments.
  [check-status from-email-address to-email-address smtp-credentials]
  (let [host (sget check-status :hosts)
        check (sget check-status :checks)
        check-status-id (sget check-status :id)
        role-name (-> (sget check :role_id) (models/get-role-by-id) (sget :name))
        subject (format "%s %s" (models/get-host-display-name host) (models/get-check-display-name check))
        html-body (string/join (alert-email-html check-status))
        plaintext-body (alert-email-plaintext check-status)
        email-message {:from (add-role-to-email-address role-name from-email-address)
                       :to to-email-address
                       :subject subject
                       :body [:alternative
                              {:type "text/plain; charset=utf-8" :content plaintext-body}
                              {:type "text/html; charset=utf-8" :content html-body}]}]
    (log-info (format "Emailing for check-status %s: %s %s" check-status-id
                      (sget host :hostname) (sget check-status :status)))
    (if log-emails-without-sending
      (prn email-message)
      (let [result (postal/send-message smtp-credentials email-message)]
        (log-info (format "Email body check-status %s: %s" check-status-id email-message))
        (when (not= (:error result) :SUCCESS)
          (log-info (format "Email for check-status %s failed to send:%s\nFull body\n:%s" check-status-id
                            result email-message)))))))

(defn- has-remaining-attempts? [check-status]
  (<= (sget-in @checks-in-progress [(sget check-status :id) :attempt-number])
      (sget-in check-status [:checks :max_retries])))

(defn- perform-http-request-for-check
  "Requests the URL of the given check, catches all exceptions, and returns a map containing {:status, :body}"
  [check-status]
  (let [url (models/get-url-of-check-status check-status)
        timeout (sget-in check-status [:checks :timeout])]
    (try
      (http/get url {:conn-timeout (-> timeout (* 1000) int)
                     ; Don't throw exceptions on 500 status codes.
                     :throw-exceptions false})
      (catch ConnectTimeoutException exception
        {:status nil :body (format "Connection to %s timed out after %ss." url timeout)})
      (catch Exception exception
        ; We can get a ConnectionException if the host exists but is not listening on the port, or if it's
        ; an unknown host.
        {:status nil :body (format "Error connecting to %s: %s" url exception)}))))

(defn- strip-charset-from-content-type
  "Removes the charset from a content type HTTP header, e.g. 'text/html;charset=UTF-8' => 'text/html'"
  [content-type-header]
  (-> content-type-header (string/split #";") first))

(defn perform-check
  "Makes a synchronous HTTP request and updates the corresponding check-status DB object with the results."
  [check-status has-remaining-attempts]
  (let [check-status-id (sget check-status :id)
        check (sget check-status :checks)
        host (sget-in check-status [:hosts :hostname])
        response (perform-http-request-for-check check-status)
        is-up (= (:status response) (sget check :expected_status_code))
        previous-status (sget check-status :status)
        new-status (if is-up "up" "down")
        last-checked-at-timestamp (time-coerce/to-timestamp (time-core/now))]
    (log-info (format "Result for check-status %s: %s %s" check-status-id
                      (models/get-url-of-check-status check-status) (:status response)))
    (when-not is-up
      (log-info (format "check-status %s body: %s" check-status-id
                        (-> response :body string/trim (truncate-string 1000)))))
    (if (or is-up (not has-remaining-attempts))
      (do
        (models/update-check-status
         check-status-id
         {:last_checked_at last-checked-at-timestamp
          :last_response_status_code (:status response)
          :last_response_content_type (-?> (get-in response [:headers "content-type"])
                                           strip-charset-from-content-type)
          :last_response_body (-> response :body
                                  (truncate-string response-body-size-limit-chars))
          :status new-status})
        (when (and (not= new-status previous-status) (sget check :send_email))
          (send-email (models/get-check-status-by-id check-status-id)
                      from-email-address
                      to-email-address
                      smtp-credentials))
        (swap! checks-in-progress dissoc check-status-id))
      (do
        (models/update-check-status check-status-id {:last_checked_at last-checked-at-timestamp})
        (log-info (str "Will retry check-status id " check-status-id))))))

(defn- perform-check-in-background
  "The HTTP request and the response assertions are done inside of a future."
  [check-status]
  (let [check-status-id (sget check-status :id)]
    (swap! checks-in-progress (fn [old-value]
                                (-> old-value
                                    (assoc-in [check-status-id :in-progress] true)
                                    (update-in [check-status-id :attempt-number] #(inc (or % 0))))))
    (future
      (try
        (perform-check check-status (has-remaining-attempts? check-status))
        (catch Exception exception
          (log-exception (str "Failed to perform check. check-status id: " check-status-id) exception))
        (finally
          (swap! checks-in-progress assoc-in [check-status-id :in-progress] false))))))

(defn- perform-eligible-checks
  "Performs all checks which are scheduled to run."
  []
  (let [check-statuses (k/select models/check-statuses
                         (k/where {:state "enabled"})
                         (k/with-object models/hosts)
                         (k/with-object models/checks))]
    (doseq [check-status check-statuses]
      (when (and (not (get-in @checks-in-progress [(sget check-status :id) :in-progress]))
                 (models/ready-to-perform? check-status))
        (perform-check-in-background check-status)))))

(defn start-periodic-polling []
  ; NOTE(philc): For some reason, at-at's jobs do not run from within nREPL.
  (at-at/every polling-frequency-ms
               #(try
                  (perform-eligible-checks)
                  (catch Exception exception
                    (log-exception exception)))
               at-at-pool))

(defn- stop-periodic-polling []
  (at-at/stop-and-reset-pool! at-at-pool))
