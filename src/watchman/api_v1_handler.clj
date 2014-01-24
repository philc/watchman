(ns watchman.api-v1-handler
  (:use compojure.core)
  (:require [compojure.handler :as handler]
            [korma.incubator.core :as k]
            [watchman.models :as models]))

(defroutes api-routes
  (PUT "/check_statuses/:id" {:keys [params body]}
    (let [body (slurp body)
          check-status-id (-> params :id Integer/parseInt)]
      (if (#{"paused" "enabled"} body)
        (do
          (k/update models/check-statuses
            (k/set-fields {:state body})
            (k/where {:id check-status-id}))
          {:status 200})
        {:status 400 :body "Invalid state."}))))
