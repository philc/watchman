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
            [korma.incubator.core :as k]
            [watchman.models :as models]))

(defsnippet index "index.html" [:#index-page]
  [check-statuses]
  [:tr.check-status] (clone-for [check-status check-statuses]
                       [:.name] (content (models/get-check-display-name (sget check-status :checks)))
                       [:.host] (content (models/get-host-display-name (sget check-status :hosts)))
                       [:.status] (do-> (add-class (sget check-status :status))
                                        (content (sget check-status :status)))))

(deftemplate layout "layout.html"
  [body]
  [:#page-content] (content body))

(defroutes app-routes
  (GET "/" []
    (let [check-statuses
          (k/select models/check-statuses
                    (k/with-object models/hosts)
                    (k/with-object models/checks))]
      (layout (index check-statuses))))
  (route/resources "/")
  (route/not-found "Not Found"))

(def app (-> app-routes
             handler/site))

(defn- handle-template-file-change [files]
  (.setLastModified (clj-io/file "src/watchman/handler.clj") (System/currentTimeMillis)))

; Touch handler.clj when a template file is modified. This causes the ring reloading middleware
; to reload this namespace, picking up the changes to the templates.
(watcher/watcher ["resources"]
  (watcher/rate 50) ;; poll every 50ms
  (watcher/file-filter (watcher/extensions :html)) ;; filter by extensions
  (watcher/on-change handle-template-file-change))

(defn -main []
  "Starts a Jetty webserver with our Ring app. See here for other Jetty configuration options:
   http://ring-clojure.github.com/ring/ring.adapter.jetty.html"
  (let [port (Integer/parseInt (or (System/getenv "PORT") "8130"))]
    (run-jetty app {:port port})))
