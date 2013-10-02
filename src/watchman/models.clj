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

; TODO(philc): Document the fields.
(defentity2 hosts)
(defentity2 checks)
(defentity2 roles-hosts)
(defentity2 check-statuses
  (belongs-to checks {:fk :check_id})
  (belongs-to hosts {:fk :host_id}))

(defentity2 roles
  (has-many checks {:fk :role_id})
  (many-to-many hosts :roles_hosts {:lfk :role_id :rfk :host_id}))

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
