(ns watchman.models
  (:require [clj-time.core :as time-core]
            [clojure.core.incubator :refer [-?>]]
            [clojure.string :as string]
            [korma.db :refer [transaction]]
            [korma.incubator.core :as k :refer [belongs-to defentity has-many many-to-many]]
            [watchman.utils :refer [sget sget-in]]
            [clj-time.coerce :as time-coerce]
            [korma.db :refer :all]))

(defdb watchman-db (postgres {:host (or (System/getenv "WATCHMAN_DB_HOST") "localhost")
                              :db (or (System/getenv "WATCHMAN _DB_NAME") "watchman")
                              :user (or (System/getenv "WATCHMAN_DB_USER") (System/getenv "USER"))
                              :password (or (System/getenv "WATCHMAN_DB_PASS") "")}))

(declare check-statuses)

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
(defentity2 checks
  (has-many check-statuses {:fk :check_id}))

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

; A URL to which to post updates to check-statuses
; - url
(defentity2 webhooks)

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

(defn snooze-role [role-id snooze-duration-ms]
  "Snoozes a role for the given duration in ms"
  (let [snooze-until (->> snooze-duration-ms
                          time-core/millis
                          (time-core/plus (time-core/now))
                          time-coerce/to-timestamp)]
    (k/update roles
      (k/set-fields {:snooze_until snooze-until})
      (k/where {:id role-id}))))

(defn get-check-statuses-with-hosts-and-checks
  "Returns a sequence of all check-status records in the database with their associated :hosts and :checks
  relations eagerly loaded."
  []
  ; Korma.incubator's 'with-object' unfortunately does not eagerly load associations.
  ; See: https://github.com/korma/korma.incubator/issues/7
  (letfn [(in-memory-join [model foreign-key relation check-statuses]
            (let [ids (->> check-statuses (map #(sget % foreign-key)) set)
                  id->object (->> (k/select model (k/where {:id [in ids]}))
                                  (reduce (fn [m obj] (assoc m (sget obj :id) obj)) {}))]
              (map (fn [check-status]
                     (assoc check-status relation (sget id->object (sget check-status foreign-key))))
                   check-statuses)))]
    (->> (k/select check-statuses)
         (in-memory-join hosts :host_id :hosts)
         (in-memory-join checks :check_id :checks))))

(defn get-check-status-by-id [id]
  (first (k/select check-statuses
           (k/where {:id id})
           (k/with-object hosts)
           (k/with-object checks))))

(defn get-role-by-id [id]
  (first (k/select roles
           (k/where {:id id})
           (k/with-object hosts)
           (k/with-object checks))))

(defn get-host-by-id [id]
  (first (k/select hosts (k/where {:id id}))))

(defn get-hosts-in-role [role-id]
 (k/select hosts
   (k/join roles-hosts (= :roles_hosts.host_id :id))
   (k/where {:roles_hosts.role_id role-id})))

(defn get-host-by-hostname-in-role
  "Returns a host record by hostname iff it is assigned to the specified role."
  [hostname role-id]
  (first (k/select hosts
           (k/join roles-hosts (= :roles_hosts.host_id :id))
           (k/where {:hostname hostname
                     :roles_hosts.role_id role-id}))))

(defn get-host-by-hostname [hostname]
  (first (k/select hosts (k/where {:hostname hostname}))))

(defn get-check-display-name [check-status]
  (or (sget check-status :nickname)
      (sget check-status :path)))

(defn extract-subdomain-from-hostname
  "Returns the subdomain of the given fully-qualified domain name.
  Given hostname subdomain1.subdomain2.example.com, returns subdomain1.subdomain2.
  Given example.com, returns example.com."
  [hostname]
  (let [parts (string/split hostname #"\.")]
    (if (< (count parts) 3)
      hostname
      (string/join "." (take (- (count parts) 2) parts)))))

(defn get-host-display-name [host]
  (or (sget host :nickname)
      (-> (sget host :hostname)
          extract-subdomain-from-hostname)))

(defn get-url-of-check-status
  "The URL (hostname plus path) that a check-status checks."
  [check-status]
  (str "http://" (sget-in check-status [:hosts :hostname]) (sget-in check-status [:checks :path])))

(defn get-webhooks []
  (k/select webhooks))

(defn create-role [fields]
  (k/insert roles (k/values fields)))

(defn create-check [fields]
  (k/insert checks (k/values fields)))

(defn create-host [fields]
  (k/insert hosts (k/values fields)))

(defn find-or-create-host [hostname]
  (or (first (k/select hosts
                       (k/where {:hostname hostname})))
      (k/insert hosts (k/values {:hostname hostname}))))

(defn update-check-status [id fields]
  {:pre [(number? id)]}
  (k/update check-statuses
    (k/set-fields fields)
    (k/where {:id id})))

(defn role-snoozed? [role-id cur-time]
  "Determine if the specified role is still asleep as specified by a snooze
  cur-time is provided as an argument so that this result can be made consistent
  with other timing related queries"
  (let [role (get-role-by-id role-id)
        snooze-until (-?> (:snooze_until role)
                          time-coerce/to-date-time)]
   (and (not (nil? snooze-until))
        (time-core/before? cur-time snooze-until))))

(defn ready-to-perform?
  "True if enough time has elapsed since we last checked this alert and the alert's role isn't snoozed"
  [check-status]
  (let [cur-time (time-core/now)
        role-id (->> check-status :checks :role_id)
        role-snoozed (role-snoozed? role-id cur-time)
        last-checked-at (-?> (sget check-status :last_checked_at)
                             time-coerce/to-date-time)]
    (and (not role-snoozed)
         (or (nil? last-checked-at)
             (->> (sget-in check-status [:checks :interval])
                  time-core/secs
                  (time-core/plus last-checked-at)
                  (time-core/after? cur-time)))))) 
