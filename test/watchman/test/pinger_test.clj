(ns watchman.test.pinger-test
  (require [midje.sweet :refer :all]
           [watchman.pinger :refer :all]
           [midje.util :only [testable-privates]]))

(def check-status {:host_id 1
                   :hosts {:hostname "the-hostname.com"}
                   :checks {:path "/the-path"}
                   :status "down"
                   :last_response_body "the-body"
                   :last_response_status_code 500
                   :content_type "text/html"})

(defn- stringify [list] (apply str list))

(facts "alert-html-email"
  (fact "doesn't escape HTML when the last response's content type is HTML"
    (-> check-status
        (assoc :last_response_body "<html_tag>" :last_response_content_type "text/html")
        alert-email-html
        stringify) => (contains "<html_tag>"))
  (fact "escapes HTML chars when the last response's content type is non-HTML"
    (-> check-status
        (assoc :last_response_body "<html_tag>" :last_response_content_type "text/plain")
        alert-email-html
        stringify) => (contains "&lt;html_tag&gt;")))
