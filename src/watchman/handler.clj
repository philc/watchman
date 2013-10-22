(ns watchman.handler
  (:gen-class)
  (:use compojure.core
        [clojure.core.incubator :only [-?> -?>>]]
        [ring.middleware.session.cookie :only [cookie-store]]
        [ring.middleware.stacktrace]
        [ring.middleware.multipart-params :only [wrap-multipart-params]]
        [ring.adapter.jetty :only [run-jetty]])
  (:require [compojure.handler :as handler]
            [cemerick.friend :as friend]
            (cemerick.friend [workflows :as friend-workflows]
                             [credentials :as friend-creds])
            [net.cgrand.enlive-html :refer :all]
            [clj-time.coerce :as time-coerce]
            [clojure.java.io :as clj-io]
            clojure.walk
            [compojure.route :as route]
            [clojure.string :as string]
            [watchman.utils :refer [friendly-timestamp-string indexed sget sget-in]]
            [ring.util.response :refer [redirect]]
            [korma.incubator.core :as k]
            [watchtower.core :as watcher]
            [watchman.pinger :as pinger]
            [watchman.models :as models]))

(def http-auth-username (or (System/getenv "WATCHMAN_USERNAME") "watchman"))
(def http-auth-password (or (System/getenv "WATCHMAN_PASSWORD") "password"))
(def authorized-users {http-auth-username {:username http-auth-username
                                           :password (-> http-auth-password friend-creds/hash-bcrypt)
                                           :roles #{::user}}})

(defn- get-check-status-failure-message
  "A message describing the check's failure. nil if the alert isn't in state 'down'."
  [check-status]
  (when (= (sget check-status :status) "down")
    (format "Status code: %s\nBody:\n%s"
            (sget check-status :last_response_status_code)
            (sget check-status :last_response_body))))

(defsnippet index-page "index.html" [:#index-page]
  [check-statuses]
  [:tr.check-status] (clone-for [check-status check-statuses]
                       [:tr] (do-> (set-attr :data-check-status-id (sget check-status :id))
                                   (set-attr :data-state (sget check-status :state)))
                       [:.host :a] (do->
                                    (set-attr :href (str "/roles/" (sget-in check-status [:checks :role_id])))
                                    (content (models/get-host-display-name (sget check-status :hosts))))
                       [:.name] (content (models/get-check-display-name (sget check-status :checks)))
                       [:.last-checked] (content (-> (sget check-status :last_checked_at)
                                                     time-coerce/to-date-time
                                                     friendly-timestamp-string))
                       [:.status] (add-class (sget check-status :status))
                       [:.failure-reason] (content (get-check-status-failure-message check-status))))

(defsnippet roles-page "roles.html" [:#roles-page]
  [roles]
  [:li] (clone-for [role roles]
          [:a] (do-> (content (:name role))
                     (set-attr :href (str "/roles/" (:id role))))))

; The editing UI for a role and its associated checks and hosts.
; - role: nil if this page is to render a new, unsaved role."
(defsnippet roles-edit-page "roles_edit.html" [:#roles-edit-page]
  [role flash-message]
  [[:input (attr= :name "name")]] (set-attr :value (:name role))
  [:#flash-message] (if flash-message (content flash-message) (substitute nil))
  ; I sense a missing abstraction.
  [:tr.check] (clone-for [[i check] (->> role :checks (sort-by models/get-check-display-name) indexed)]
                [:input.id] (do-> (set-attr :value (sget check :id))
                                  (set-attr :name (format "checks[%s][id]" i)))
                [:input.deleted] (set-attr :name (format "checks[%s][deleted]" i))
                [:input.path] (do-> (set-attr :value (sget check :path))
                                    (set-attr :name (format "checks[%s][path]" i)))
                [:input.nickname] (do-> (set-attr :value (sget check :nickname))
                                        (set-attr :name (format "checks[%s][nickname]" i)))
                [:input.expected-status-code] (do->
                                               (set-attr :value (sget check :expected_status_code))
                                               (set-attr :name (format "checks[%s][expected_status_code]" i)))
                [:input.timeout] (do-> (set-attr :value (:timeout check))
                                       (set-attr :name (format "checks[%s][timeout]" i)))
                [:input.max-retries] (do-> (set-attr :value (sget check :max_retries))
                                           (set-attr :name (format "checks[%s][max_retries]" i))))
  [:tr.host] (clone-for [[i host] (->> role :hosts (sort-by :hostname) indexed)]
               [:input.id] (do-> (set-attr :value (sget host :id))
                                 (set-attr :name (format "hosts[%s][id]" i)))
               [:input.deleted] (set-attr :name (format "hosts[%s][deleted]" i))
               [:input.hostname] (do-> (set-attr :name (format "hosts[%s][hostname]" i))
                                       (set-attr :value (sget host :hostname)))))

(deftemplate layout "layout.html"
  [body nav]
  [:#page-content] (content body)
  [:nav] (content nav))

(defsnippet nav "layout.html" [:nav]
  [selected-section]
  [(->> selected-section name (str "li.") keyword)] (add-class "selected"))

(defn prune-empty-strings
  "Recurisely removes key/value pairs from the map where the value is an empty string. Useful for exclusing
  blank HTTP form params."
  [m]
  (clojure.walk/postwalk (fn [form]
                           (if (map? form)
                             (into {} (filter #(not= (val %) "") form))
                             form))
                         m))

; TODO(caleb): This could ensure the path will parse properly. See issue #7.
(defn sanitize-path
  "Sanitize a user-supplied path. Right now, just adds on a leading / if none exists. A nil path is coerced to
  an empty path (/)."
  [path]
  (if path
    (if (re-find #"^/" path) path (str "/" path))
    "/"))

(defn save-role-from-params
  "Saves a role based on the given params. The params include the list of hosts and checks to associate
  with this role."
  ; TODO(philc): Move all Korma queries in this fn into models.clj.
  [params]
  (let [role-id (-> (sget params :id) Integer/parseInt)
        checks (-> params :checks vals)
        hosts (-> params :hosts vals)
        serialize-to-db-from-params
          (fn [object insert-fn update-fn delete-fn]
            (let [object-id (-?> object :id Integer/parseInt)
                  is-deleted (= (:deleted object) "true")]
              (if object-id
                (if is-deleted (delete-fn) (update-fn))
                (when-not is-deleted (insert-fn)))))]
    (k/update models/roles
      (k/set-fields {:name (sget params :name)})
      (k/where {:id role-id}))
    (doseq [check checks]
      (let [check-id (-?> check :id Integer/parseInt)
            path (sanitize-path (:path check))
            check-db-fields {:path path
                             :nickname (:nickname check)
                             :expected_status_code (-?> check :expected_status_code Integer/parseInt)
                             :timeout (-?> check :timeout Double/parseDouble)
                             :role_id role-id
                             :max_retries (-?> check :max_retries Integer/parseInt)}]
        (serialize-to-db-from-params check
                                     #(let [check-id (-> (k/insert models/checks (k/values check-db-fields))
                                                         (sget :id))]
                                        (models/add-check-to-role check-id role-id))
                                     #(k/update models/checks
                                        (k/set-fields check-db-fields)
                                        (k/where {:id check-id}))
                                     #(models/delete-check check-id))))
    (doseq [host hosts]
      (let [host-id (-?> host :id Integer/parseInt)
            host-db-fields (select-keys host [:hostname])]
        (serialize-to-db-from-params host
                                     #(let [host-record-id
                                           (-> (or (first (k/select models/hosts
                                                            (k/where {:hostname (:hostname host)})))
                                                   (k/insert models/hosts (k/values host-db-fields)))
                                               (sget :id))]
                                       (models/add-host-to-role host-record-id role-id))
                                     #(k/update models/hosts
                                        (k/set-fields host-db-fields)
                                        (k/where {:id host-id}))
                                     #(do
                                        (models/remove-host-from-role host-id role-id)
                                        ; If this host belongs to no other roles, go ahead and delete it.
                                        (when (empty? (k/select models/roles-hosts (k/where {:host_id 3})))
                                          (k/delete models/hosts (k/where {:id host-id})))))))))

(defroutes app-routes
  (GET "/" {:keys [params]}
    (let [sort-key-fn (if (= (:order params) "hosts")
                        #(vector (sget-in % [:hosts :hostname]) (sget-in % [:checks :path]))
                        #(vector (sget % :status) (sget-in % [:hosts :hostname]) (sget-in % [:checks :path])))
          check-statuses (->> (k/select models/check-statuses
                                (k/with-object models/hosts)
                                (k/with-object models/checks))
                              (sort-by sort-key-fn))]
      (layout (index-page check-statuses) (nav :overview))))

  (GET "/alertz" []
    ; TODO(philc): Alert if we've seen recent exceptions.
    ; Make sure we can talk to the DB.
    (if (first (k/exec-raw "select 1 from roles" :results))
      "Healthy\n"
      {:status 500 :body "Unable to talk to the database.\n"}))

  (GET "/roles" []
    (let [roles (k/select models/roles (k/order :name))]
      (layout (roles-page roles) (nav :roles-edit))))

  (GET "/roles/new" []
    (layout (roles-edit-page nil nil) (nav :roles-edit)))

  (POST "/roles/new" {:keys [params]}
    (let [params (prune-empty-strings params)
          role-id (-> (select-keys params [:name]) models/create-role (sget :id))]
      (save-role-from-params (assoc params :id (str role-id)))
      (redirect (str "/roles/" role-id))))

  (GET "/roles/:id" [id]
    (if-let [role (models/get-role-by-id (Integer/parseInt id))]
      (layout (roles-edit-page role nil) (nav :roles-edit))
      {:status 404 :body "Role not found."}))

  ; Redirects the user to ssh://host. We have this redirect because we can't embed links with the ssh protocol
  ; in the body of Gmail emails.
  (GET "/ssh_redirect" [host_id]
    (if-let [host (models/get-host-by-id (Integer/parseInt host_id))]
      (redirect (str "ssh://" (sget host :hostname)))
      {:status 404 :body "Host not found."}))

  (POST "/roles/:id" {:keys [params]}
    (let [params (prune-empty-strings params)
          role-id (Integer/parseInt (:id params))]
      (if-let [role (models/get-role-by-id role-id)]
        (do (save-role-from-params params)
            (layout (roles-edit-page (models/get-role-by-id role-id) "Changes accepted.") (nav :roles-edit)))
        {:status 404})))

  (PUT "/api/check_statuses/:id" {:keys [params body]}
    (let [body (slurp body)
          check-status-id (-> params :id Integer/parseInt)]
      (if (#{"paused" "enabled"} body)
        (do
          (k/update models/check-statuses
            (k/set-fields {:state body})
            (k/where {:id check-status-id}))
          {:status 200})
        {:status 400 :body "Invalid state."})))

  (route/resources "/")
  (route/not-found "Not Found"))

(def app (-> app-routes
             (friend/wrap-authorize #{::user})
             (friend/authenticate {:unauthenticated-handler #(friend-workflows/http-basic-deny "watchman" %)
                                   :workflows [(friend-workflows/http-basic
                                                :credential-fn
                                                  #(friend-creds/bcrypt-credential-fn authorized-users %)
                                                :realm "watchman")]})
             ring.middleware.stacktrace/wrap-stacktrace
             handler/site))

(defn- handle-template-file-change [files]
  (require 'watchman.handler :reload))

(defn init []
  ; Reload this namespace and its templates when one of the templates changes.
  (when-not (= (System/getenv "RING_ENV") "production")
    (watcher/watcher ["resources"]
                     (watcher/rate 50) ;; poll every 50ms
                     (watcher/file-filter (watcher/extensions :html)) ;; filter by extensions
                     (watcher/on-change handle-template-file-change)))
  (pinger/start-periodic-polling))

(defn -main []
  "Starts a Jetty webserver with our Ring app. See here for other Jetty configuration options:
   http://ring-clojure.github.com/ring/ring.adapter.jetty.html"
  (let [port (Integer/parseInt (or (System/getenv "PORT") "8130"))]
    (init)
    (run-jetty app {:port port})))
