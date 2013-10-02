(ns lobos.config)

(def db
  {:classname "org.postgresql.Driver"
   :subprotocol "postgresql"
   :subname (let [host (or (System/getenv "WATCHMAN_DB_HOST") "localhost")
                  db-name (or (System/getenv "WATCHMAN_DB_NAME") "watchman")]
              (str "//" host ":5432/" db-name))
   :user (or (System/getenv "WATCHMAN_DB_USER") (System/getenv "USER"))
   :password (or (System/getenv "WATCHMAN_DB_PASS") "")})
