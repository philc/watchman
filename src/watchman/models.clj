(ns watchman.models
  (:require [clj-time.core :as time-core]
            [clj-time.coerce :as time-coerce]
            [clojure.string :as string]
            [korma.db :refer [transaction]]
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
; - max_retries: how many times to retry before considering the check a failure.
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

(defn upsert
  "Updates rows which match the given where-map. If no row matches this map, insert one first.
   This isn't a truly robust or performant upsert, but it should be sufficient for our purposes."
  ([korma-entity where-map]
     (upsert korma-entity where-map {}))
  ([korma-entity where-map additional-fields]
     (let [row-values (merge where-map additional-fields)]
       (if (empty? (k/select korma-entity (k/where where-map)))
         (k/insert korma-entity (k/values row-values))
         (k/update korma-entity
           (k/set-fields row-values)
           (k/where where-map))))))

(defn delete-check [check-id]
  (transaction
   (k/delete check-statuses (k/where {:check_id check-id}))
   (k/delete checks (k/where {:id check-id}))))

(defn add-check-to-role [check-id role-id]
  (transaction
   (let [host-ids (->> (k/select roles-hosts (k/where {:role_id role-id}))
                       (map :host_id))]
     (doseq [host-id host-ids]
       (upsert check-statuses {:host_id host-id :check_id check-id})))))

(defn add-host-to-role [host-id role-id]
  (transaction
   (upsert roles-hosts {:role_id role-id :host_id host-id})
   ;; Create a check-status entry for every host+check pair.
   (let [check-ids (->> (k/select checks (k/where {:role_id role-id}))
                        (map :id))]
     (doseq [check-id check-ids]
       (upsert check-statuses {:host_id host-id :check_id check-id})))))


(defn remove-host-from-role [host-id role-id]
  (transaction
   (let [check-ids (->> (k/select checks (k/where {:role_id role-id}))
                        (map :id))]
     (k/delete check-statuses (k/where {:host_id host-id :check_id [in check-ids]}))
     (k/delete roles-hosts (k/where {:host_id host-id :role_id role-id})))))

(defn get-role-by-id [id]
  (first (k/select roles
           (k/where {:id id})
           (k/with-object hosts)
           (k/with-object checks))))

(defn get-check-display-name [check-status]
  (or (sget check-status :nickname)
      (sget check-status :path)))

(defn get-host-display-name [host]
  (or (sget host :nickname)
      (sget host :hostname)))

(defn create-role [fields]
  (k/insert roles (k/values fields)))

