(ns watchman.api-v1-handler
  (:use compojure.core)
  (:require [compojure.handler :as handler]
            [korma.incubator.core :as k]
            [korma.db :refer [transaction]]
            [cheshire.core :as json]
            [mississippi.core :as m]
            [watchman.utils :refer [validate-hostname]]
            [watchman.models :as models]))

(defn- generate-json-error-response
  "Returns a JSON response body of the form: {'reason': 'message'}"
  [error-code message]
  {:status error-code
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string {:reason message})})

(defn- generate-json-validation-error-response
  "Generates JSON error response for the return value of validate-params"
  [[error-field error-message]]
  (generate-json-error-response 400 (str (name error-field) " " error-message)))

(defn- generate-json-response
  "Optional keyword args:
  - :raw: If true, assume response-data is already serialized and do not re-serialize it"
  [response-data & {:keys [raw]}]
  {:headers {"Content-Type" "application/json"}
   :body (if raw response-data (json/generate-string response-data))})

(defn validate-params
  "Runs the validators against the given params map, and returns the first error found, or nil
  if everything validated successfully."
  [params validation-map]
  (let [error-map (m/errors params validation-map)]
    (when-not (empty? error-map)
      (let [[field errors] (first error-map)]
        [field (first errors)]))))

(def host-validation-map
  {:hostname [(m/required :msg "is required")
              [(comp validate-hostname) :msg "is not valid"]]})

(defn- wrap-roles-routes
  "Wraps /roles/:id routes to load the role or halt with 404 if it doesn't exist."
  [handler]
  (fn [{:keys [params] :as request}]
    (if-let [role (models/get-role-by-id (Integer/parseInt (:id params)))]
      (handler (assoc request :role role))
      (generate-json-error-response 404 "role does not exist"))))

(defroutes role-api-routes
  (POST "/hosts" {:keys [params role]}
    (if-let [validation-error (validate-params params host-validation-map)]
      (generate-json-validation-error-response validation-error)
      (let [host (models/find-or-create-host (:hostname params))]
        (models/add-host-to-role (:id host) (:id role))
        (generate-json-response host))))

  (DELETE "/hosts/:hostname" {:keys [params role]}
    (if-let [host (models/get-host-by-hostname-in-role (:hostname params) (:id role))]
      (do
        (models/remove-host-from-role (:id host) (:id role))
        {:status 204}) ; No content
      (generate-json-error-response 404 "hostname not found in role"))))

(defroutes api-routes
  (context "/roles/:id" []
    (wrap-roles-routes role-api-routes))

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
