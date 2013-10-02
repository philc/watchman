(ns lobos.migrations
  "Database migrations using the Lobos framework."
  (:refer-clojure :exclude [alter drop bigint boolean char double float time])
  (:use (lobos [migration :only [defmigration]]
               core schema config)
        [clojure.java.shell :only [sh]]
        ; Some of Korma's functions (like table) collide with lobos.
        [korma.db :only [transaction]]
        [korma.incubator.core :only [select with-object fields where join order group limit insert delete
                                     values set-fields subselect update exec-raw]]))

(defmigration create-data-model-20130908
  (up []
    (create db
      (table :roles
        (integer :id :auto-inc :primary-key)
        (text :name)))
    (create db
      (table :checks
        (integer :id :auto-inc :primary-key)
        (integer :role_id :not-null [:refer :roles :id])
        (text :path :not-null)
        (text :nickname)
        (double :timeout :not-null (default 3))
        ; How often to check, in seconds.
        (integer :interval :not-null (default 60))
        (integer :max_retries :not-null (default 0))
        (integer :expected_status_code :not-null (default 200))
        (text :expected_response_contents)))
    (create db
      (table :hosts
        (integer :id :auto-inc :primary-key)
        (text :hostname :not-null)
        (text :nickname)))
    (create db
      (table :roles_hosts
        (integer :host_id :not-null [:refer :hosts :id])
        (integer :role_id :not-null [:refer :roles :id]))
        (index :roles_hosts_unique_host_id_and_role_id [:host_id :role_id] :unique))
    (create db
      (table :check_statuses
        (integer :id :auto-inc :primary-key)
        (integer :host_id :not-null [:refer :hosts :id])
        (integer :check_id :not-null [:refer :checks :id])
        (text :state :not-null (default "enabled"))
        (timestamp :last_checked_at)
        (integer :last_response_status_code)
        (text :last_response_body)
        ; down, up, or unknown.
        (text :status :not-null (default "unknown"))
        (index :check_statuses_unique_host_id_and_check_id [:host_id :check_id] :unique))))
  (down []
    (doseq [table-name [:check_statuses :roles_hosts :hosts :checks :roles]]
       (drop (table table-name)))))
