(ns watchman.dev-sandbox
  "Helper functions for developing from the repl: creating fixtures in the data model, sending emails, etc."
  (:require [korma.incubator.core :as k]
            [watchman.pinger :as pinger]
            [watchman.models :as models]
            [watchman.utils :refer [sget]]))

(defn get-development-check-status
  "Retrieves the check-status created by `create-development-check`."
  []
  (when-let [development-check (first (k/select models/checks
                                        (k/where {:path "/dev"})
                                        (k/with-object models/check-statuses)))]
    (-> development-check (sget :check_statuses) first (sget :id) models/get-check-status-by-id)))

(defn create-development-role-and-check
  "Creates a role and check for development purposes, if this check doesn't already exist. This is equivalent
  to clicking through the UI to create these entities in the database."
  []
  (when-not (get-development-check-status)
    (let [role-id (-> (models/create-role {:name "dev role"}) (sget :id))
          check-id (-> (models/create-check {:path "/dev" :role_id role-id}) (sget :id))
          host-id (-> (models/create-host {:hostname "localhost"}) (sget :id))]
      (models/add-check-to-role check-id role-id)
      (models/add-host-to-role host-id role-id))))

(defn send-test-email []
  (let [check-status (-> (get-development-check-status)
                         (merge {:status "down"}))]
    (pinger/send-email
     check-status
     "from"
     "to"
     (merge pinger/smtp-credentials
            {:user "username"
             :pass "password"}))))

;
; Commonly used code when developing:
;

#_(send-test-email)

#_(pinger/perform-check (-> (get-development-check-status)
                            (assoc-in [:hosts :hostname] "google.com"))
                        false)
