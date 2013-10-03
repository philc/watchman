(ns watchman.models
  (:require [clj-time.core :as time-core]
            [clj-time.coerce :as time-coerce]
            [clojure.string :as string]
            [korma.incubator.core :as k :refer [belongs-to defentity has-many many-to-many]]
            [watchman.utils :refer [sget]]
            [clj-time.coerce :as time-coerce])
  (:use korma.db))

; TODO(philc): Describe the data model.
; TODO(philc): Consider using SQLite.

(defdb watchman-db (postgres {:host (or (System/getenv "WATCHMAN_DB_HOST") "localhost")
                              :db (or (System/getenv "WATCHMAN _DB_NAME") "watchman")
                              :user (or (System/getenv "WATCHMAN_DB_USER") (System/getenv "USER"))
                              :password (or (System/getenv "WATCHMAN_DB_PASS") "")}))

(def underscorize
  "Takes a hyphenated keyword (or string) and returns an underscored keyword.
   Appends the optional suffix string arg if supplied."
  (memoize (fn [hyphenated-keyword & {:keys [suffix]}]
             (-> hyphenated-keyword name (string/replace "-" "_") (str suffix) keyword))))


(defmacro defentity2
  "Like defentity, but automatically sets the table name to the underscorized version of the entity name"
  [entity-var & body]
  (let [table-name (underscorize entity-var)]
    `(defentity ~entity-var
       (k/table ~table-name)
       ~@body)))

; A URL to check on one or more hosts.
; - path
; - nickname
; - timeout
; - interval: How frequently to run this check. Defaults to 60s.
; - retry_count: how many times to retry before failing
; - expected_status_code
; - expected_response_contents
(defentity2 checks)

; A host to check, which can belong to one or more roles.
; - hostname
; - nickname
(defentity2 hosts)

; The status of a check for a given host.
; - host_id
; - check_id
; - state: enabled or paused.
; - last_checked_at
; - last_response_status_code
; - last_response_body
; - status: either unknown (i.e. recently created), up or down
(defentity2 check-statuses
  (belongs-to checks {:fk :check_id})
  (belongs-to hosts {:fk :host_id}))

; A role is a set of checks and a set of hosts to apply them to.
; - name
(defentity2 roles
  (has-many checks {:fk :role_id})
  (many-to-many hosts :roles_hosts {:lfk :role_id :rfk :host_id}))

; A join table for roles-hosts.
(defentity2 roles-hosts)

; TODO(philc): Remove this testing function.
(defn create-sample-rows []
  (k/insert roles (k/values {:name "testrole"}))

  (k/insert hosts (k/values {:hostname "localhost:8100"}))
  (k/insert roles-hosts (k/values {:host_id 1 :role_id 1}))
  (k/insert checks (k/values {:path "/alertz"}))

  (k/insert check-statuses (k/values {:host_id 1 :check_id 1 :last_checked_at
                                      (time-coerce/to-timestamp (time-core/now))})))

(defn get-check-display-name [check-status]
  (or (sget check-status :nickname)
      (sget check-status :path)))

(defn get-host-display-name [host]
  (or (sget host :nickname)
      (sget host :hostname)))
