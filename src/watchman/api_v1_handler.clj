(ns watchman.api-v1-handler
  (:use compojure.core)
  (:require [compojure.handler :as handler]
            [korma.incubator.core :as k]
            [korma.db :refer [transaction]]
            [cheshire.core :as json]
            [mississippi.core :as m]
            [ring.util.response :refer [redirect]]
            [watchman.utils :refer [validate-hostname log-info snooze-message]]
            [watchman.models :as models]))

(defn- create-json-error-response
  "Returns a JSON response body of the form: {'reason': 'message'}"
  [error-code message]
  {:status error-code
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string {:reason message})})

(defn- create-json-validation-error-response
  "Generates JSON error response for the return value of validate-params"
  [[error-field error-message]]
  (create-json-error-response 400 (str (name error-field) " " error-message)))

(defn- create-json-response
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
  {:hostname [(m/required :msg "is required")]})

(defn- wrap-roles-routes
  "Wraps /roles/:id routes to load the role or halt with 404 if it doesn't exist."
  [handler]
  (fn [{:keys [params] :as request}]
    (if-let [role (models/get-role-by-id (Integer/parseInt (:id params)))]
      (handler (assoc request :role role))
      (create-json-error-response 404 "role does not exist"))))

(defroutes role-api-routes
  (POST "/hosts" {:keys [params role]}
    (if-let [validation-error (validate-params params host-validation-map)]
      (do
        (log-info (str "Invalid API POST to /hosts:\n"
                       "  Params: " params "\n"
                       "  Role: " role "\n"
                       "  Error: " validation-error))
        (create-json-validation-error-response validation-error))
      (let [host (models/find-or-create-host (:hostname params))]
        (models/add-host-to-role (:id host) (:id role))
        (create-json-response host))))

  (DELETE "/hosts/:hostname" {:keys [params role]}
    (if-let [host (models/get-host-by-hostname-in-role (:hostname params) (:id role))]
      (do
        (models/remove-host-from-role (:id host) (:id role))
        {:status 204}) ; No content
      (create-json-error-response 404 "hostname not found in role")))

  (POST "/snooze" {:keys [params role]}
    (let [snooze-duration (-> params :duration Long/parseLong)
          role-id (:id role)
          snooze-until (models/snooze-role role-id snooze-duration)]
      (create-json-response {:msg (snooze-message snooze-until)}))))

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
