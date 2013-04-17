;;   Copyright 2013 Sonian, Inc.
;;
;;   Licensed under the Apache License, Version 2.0 (the "License");
;;   you may not use this file except in compliance with the License.
;;   You may obtain a copy of the License at
;;
;;       http://www.apache.org/licenses/LICENSE-2.0
;;
;;   Unless required by applicable law or agreed to in writing, software
;;   distributed under the License is distributed on an "AS IS" BASIS,
;;   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;;   See the License for the specific language governing permissions and
;;   limitations under the License.

(ns apodini.auth
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.walk :refer [keywordize-keys]]))

(defn authenticate
  "Authenticate with Swift.
  Arguments: auth-url - the url to swift
             account - an account name
             key - typically, a password
  Returns: {:x-storage-url <url>
            :x-auth-token <token>}"
  [account key auth-url & {:as opts}]
  (let [http-opts (or (:http-opts opts) {})
        resp (keywordize-keys
              (http/get auth-url
                        (merge http-opts
                               {:headers
                                {"X-Auth-User" account
                                 "X-Auth-Key" key}})))]
    (merge {:status (:status resp)
            :http-opts http-opts}
           (:headers resp))))

(defn- auth-v2
  "Authenticate against a v2.0 endpoint, which expects a JSON POST."
  [account key auth-url http-opts]
  (http/post auth-url
             (merge {:headers {"Content-Type" "application/json"}}
                    http-opts
                    {:body (json/generate-string {"auth"
                                                  {"RAX-KSKEY:apiKeyCredentials"
                                                   {"username" account
                                                    "apiKey" key}}})})))

(defn- get-regions
  "Assembles a map of object-store region configs, grouped by the region code."
  [body]
  (let [object-store (first (filter
                             #(= "object-store" (get % "type"))
                             (get-in body ["access" "serviceCatalog"])))]
    (group-by #(get % "region") (get object-store "endpoints"))))

(defn list-available-regions
  "Given valid credentials, request authentication, and return the available
  regions for this account, including which is set for the default.
  Includes no urls; intended for use in discovering available regions to
  provide a region argument to authenticate-with-region.
  Arguments: auth-url
             account
             key
  Returns: {:regions [<region1>, <region2>, ...]
            :defaultRegion <default-region>}"
  [account key auth-url & {:as opts}]
  (let [body (json/parse-string (:body (auth-v2 account
                                                key
                                                auth-url
                                                (or (:http-opts opts) {}))))]
    {:regions (keys (get-regions body))
     :defaultRegion (get-in body ["access" "user" "RAX-AUTH:defaultRegion"])}))

(defn authenticate-with-region
  "Authenticate with cloudfiles, using auth v2.0 and expecting a multiregion
  response in the body. See http://goo.gl/sRN1G for details.
  Arguments: auth-url - the url to authenticate to
             account - an account username
             key - the account API key
  Opts:  :region <region> - a region code (default: nil)
         :internal - default to the internalURL (default)
         :external - default to the externalURL
  Returns: {:x-storage-url <url>
            :x-auth-token <token>
            :response <full clj-http response map>}
  Note: :x-storage-url will be the associated region url, if a region opt was
        provided. If none was given, but RAX-AUTH:defaultRegion was present
        and not empty in the response, that region will be the default.
        Finally, if neither of the above is present, throw an exception.

  Example: (authenticate-with-token
            \"tester\"
            \"testing\"
            \"https://identity.api.rackspacecloud.com/v2.0/tokens\"
             :region \"DFW\"
             :internal true)"
  [account key auth-url & {:as opts}]
  (let [http-opts (or (:http-opts opts) {})
        resp (auth-v2 account key auth-url http-opts)
        body (json/parse-string (:body resp))
        regions (get-regions body)
        default-region (get-in body ["access" "user" "RAX-AUTH:defaultRegion"])
        get-url (fn [region]
                  (get
                   (first (get regions region))
                   (if (:external opts) "publicURL" "internalURL")))]
    (merge {:x-auth-token (get-in body ["access" "token" "id"])
            :response resp
            :http-opts http-opts}
           {:x-storage-url
            (if (:region opts)
              ;; chosen one
              (get-url (:region opts))
              (if-not (empty? default-region)
                ;; configured one
                (get-url default-region)
                ;; they didn't give us anything to go on.
                (throw (Exception. "No region provided."))))})))
