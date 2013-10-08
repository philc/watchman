(ns watchman.handler
  (:gen-class)
  (:use compojure.core
        [clojure.core.incubator :only [-?> -?>>]]
        [ring.middleware.session.cookie :only [cookie-store]]
        [ring.middleware.multipart-params :only [wrap-multipart-params]]
        [ring.adapter.jetty :only [run-jetty]])
  (:require [compojure.handler :as handler]
            [watchtower.core :as watcher]
            [net.cgrand.reload]
            [net.cgrand.enlive-html :refer :all]
            [clojure.java.io :as clj-io]
            [compojure.route :as route]
            [clojure.string :as string]
            [watchman.utils :refer [sget]]
            [ring.util.response :refer [redirect]]
            [korma.incubator.core :as k]
            [watchman.models :as models]))

; Taken from http://clojuredocs.org/clojure_contrib/clojure.contrib.seq/indexed.
(defn indexed
  "Returns a lazy sequence of [index, item] pairs, where items come from coll and indexes count up from zero.
  (indexed '(a b c d))  =>  ([0 a] [1 b] [2 c] [3 d])
  (indexed '(a b c d) '(x y))  =>  ([0 a x] [1 b y])"
  [& colls]
  (apply map vector (iterate inc 0) colls))

(defsnippet index-page "index.html" [:#index-page]
  [check-statuses]
  [:tr.check-status] (clone-for [check-status check-statuses]
                       [:.name] (content (models/get-check-display-name (sget check-status :checks)))
                       [:.host] (content (models/get-host-display-name (sget check-status :hosts)))
                       [:.status] (do-> (add-class (sget check-status :status))
                                        (content (sget check-status :status)))))

(defsnippet roles-page "roles.html" [:#roles-page]
  [roles]
  [:li] (clone-for [role roles]
          [:a] (do-> (content (:name role))
                     (set-attr :href (str "/roles/" (:id role))))))

; The editing UI for a role and its associated checks and hosts.
; - role: nil if this page is to render a new, unsaved role."
(defsnippet roles-edit-page "roles_edit.html" [:#role-edit-page]
  [role]
  [[:input (attr= :name "name")]] (set-attr :value (:name role))
  ; I sense a missing abstraction.
  [:tr.check] (clone-for [[i check] (->> role :checks (sort-by models/get-check-display-name) indexed)]
                [:input.id] (do-> (set-attr :value (sget check :id))
                                  (set-attr :name (format "checks[%s][id]" i)))
                [:input.deleted] (set-attr :name (format "checks[%s][deleted]" i))
                [:input.path] (do-> (set-attr :value (sget check :path))
                                    (set-attr :name (format "checks[%s][path]" i)))
                [:input.nickname] (do-> (set-attr :value (sget check :nickname))
                                        (set-attr :name (format "checks[%s][nickname]" i)))
                [:input.expected-status-code] (do-> (set-attr :value (sget check :expected_status_code))
                                                    (set-attr :name (format"checks[%s][expected_status_code]"
                                                                           i)))
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
  [body]
  [:#page-content] (content body))

(defn prune-empty-string
  "Returns nil if the string is empty."
  [s]
  (if (empty? s) nil s))

(defn save-role-from-params
  "Saves a role based on the given params. The params include the list of hosts and checks to associate
  with this role."
  [params]
  (let [role-id (-> (sget params :id) Integer/parseInt)
        checks (-> params :checks vals)
        hosts (-> params :hosts vals)
        serialize-to-db-from-params
          (fn [object insert-fn update-fn delete-fn]
            (let [object-id (-?> object :id prune-empty-string Integer/parseInt)
                  is-deleted (= (:deleted object) "true")]
              (if object-id
                (if is-deleted (delete-fn) (update-fn))
                (when-not is-deleted (insert-fn)))))]
    (k/update models/roles
      (k/set-fields {:name (sget params :name)})
      (k/where {:id role-id}))
    (doseq [check checks]
      (let [check-id (-?> check :id prune-empty-string Integer/parseInt)
            check-db-fields
              (merge (select-keys check [:name :path :nickname])
                     {:expected_status_code (-?> check :expected_status_code prune-empty-string
                                                 Integer/parseInt)
                      :timeout (-?> check :timeout prune-empty-string Double/parseDouble)
                      :role_id role-id
                      :max_retries (-?> check :max_retries prune-empty-string Integer/parseInt)})]
        (serialize-to-db-from-params check
                                     #(k/insert models/checks (k/values check-db-fields))
                                     #(k/update models/checks
                                                (k/set-fields check-db-fields)
                                                (k/where {:id check-id}))
                                     #(k/delete models/checks (k/where {:id check-id})))))
    (doseq [host hosts]
      (let [host-id (-?> host :id prune-empty-string Integer/parseInt)
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
  (GET "/" []
    (let [check-statuses
          (k/select models/check-statuses
                    (k/with-object models/hosts)
                    (k/with-object models/checks))]
      (layout (index-page check-statuses))))

  (GET "/roles" []
    (let [roles (k/select models/roles
                  (k/order :name))]
      (layout (roles-page roles))))

  (GET "/roles/new" []
    (layout (roles-edit-page nil)))

  (POST "/roles/new" {:keys [params]}
    (let [role-id (-> (select-keys params [:name]) models/create-role (sget :id))]
      (save-role-from-params (assoc params :id (str role-id)))
      (redirect (str "/roles/" role-id))))

  (GET "/roles/:id" [id]
    (if-let [role (models/get-role-by-id (Integer/parseInt id))]
      (layout (roles-edit-page role))
      {:status 404 :body "Role not found."}))

  (POST "/roles/:id" {:keys [params]}
    (let [role-id (Integer/parseInt (:id params))]
      (if-let [role (models/get-role-by-id role-id)]
        (do (save-role-from-params params)
            (layout (roles-edit-page (models/get-role-by-id role-id))))
        {:status 404})))

  (route/resources "/")
  (route/not-found "Not Found"))

(def app (-> app-routes
             handler/site))

(defn- handle-template-file-change [files]
  (require 'watchman.handler :reload))

(defn init []
  ; Reload this namespace and its templates when one of the templates changes.
  (watcher/watcher ["resources"]
                   (watcher/rate 50) ;; poll every 50ms
                   (watcher/file-filter (watcher/extensions :html)) ;; filter by extensions
                   (watcher/on-change handle-template-file-change)))

(defn -main []
  "Starts a Jetty webserver with our Ring app. See here for other Jetty configuration options:
   http://ring-clojure.github.com/ring/ring.adapter.jetty.html"
  (let [port (Integer/parseInt (or (System/getenv "PORT") "8130"))]
    (run-jetty app {:port port})))
