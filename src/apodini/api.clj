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

(ns apodini.api
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.java.io :refer [input-stream]]
            [clojure.string :refer [join split]]
            [clojure.walk :refer [keywordize-keys]]
            [slingshot.slingshot :refer [throw+]])
  (:import (java.io BufferedReader ByteArrayInputStream StringReader)
           (java.net ConnectException)
           (org.apache.commons.codec.digest DigestUtils)))

(defn headers
  "Helper function to generate standard header maps out of config."
  ([config]
     {:headers {"X-Auth-Token" (:x-auth-token config)}})
  ([config m]
     (let [headermap (merge m {"X-Auth-Token" (:x-auth-token config)})]
       {:headers headermap})))

(defn validate-marker
  "Helper function to validate that marker is acceptable."
  [marker]
  (when-not (string? marker)
    (throw (Exception. "marker must be a string"))))

(defn validate-page-size
  "Helper function to validate that page-size (which swift calls 'limit')
  is acceptable."
  [limit]
  (when-not (integer? limit)
    (throw (Exception. "limit must be an integer")))
  ;; 10k is swift's per-request maximum
  (when-not (and (> limit 0) (<= limit 10000))
    (throw (Exception. "limit must be between 1 and 10000, inclusive"))))

(defn normalize-item
  "Normalizes ls maps. This includes underscore conversion."
  ;; Maybe someday it will do date parsing on :last-modified too, but
  ;; that's a rathole I'm not going to clamber down into today.
  [item]
  (keywordize-keys
   (into {} (map (fn [[k v]] [(.replaceAll k "_" "-") v]) item))))

(defn list-objects
  "Helper for ls use to actually obtain an object listing."
  [config path limit marker]
  ;; 'limit' is the swift term for page-size, or 'items per request'
  (let [query-format {"format" "json"}
        query-limit {"limit" limit}
        query-marker (if (count marker)
                       {"marker" marker}
                       {})
        container (if (and
                       (.contains path "/")
                       (not= "/" path))
                    (first (split path #"/"))
                    path)
        prefix (when (.contains path "/")
                 (.replaceFirst path (str container "/") ""))
        query-prefix (if (and prefix (not= "/" prefix))
                       {"prefix" prefix}
                       {})
        query-params (merge query-format query-limit query-marker query-prefix)
        url (str (:x-storage-url config) "/" container)
        response (http/get url (merge (headers config)
                                      (:http-opts config)
                                      {:query-params query-params}))]
    (cond
     (= 204 (:status response))
     []
     (= 200 (:status response))
     (map normalize-item (json/parse-string (:body response)))
     :default
     (throw+ response))))

(defn ^:api ^:dynamic ls
  "List objects at path. Performs prefix listing, more like s3 does than
  swift does by default. Objects are returned as a lazy seq of maps of the form:
  {:name <name>
   :hash <hash>
   :bytes <length>
   :content-type <content-type>
   :last-modified <date>}

  Args: path <url>
  Optional keywords: :page-size <int>
                     :marker <string>"
  [config path & {:keys [page-size marker]
                  :or {page-size 10000
                       marker ""}}]
  (validate-page-size page-size)
  (validate-marker marker)
  ((fn next-ls [current-chunk marker]
     (lazy-seq
      (if (seq current-chunk)
        (cons (first current-chunk)
              (next-ls (rest current-chunk) (:name (first current-chunk))))
        (let [next-chunk (list-objects config path page-size marker)]
          (when (seq next-chunk)
            (cons (first next-chunk)
                  (next-ls (rest next-chunk) (:name (first next-chunk)))))))))
   (list-objects config path page-size marker) marker))

(defn ^:api ^:dynamic create-container
  "Create a container with the given name."
  [config name]
  (let [response (http/put (str (:x-storage-url config) "/" name)
                           (merge (headers config)
                                  (:http-opts config)))]
    (if (#{201 202} (:status response))
      response
      (throw+ response))))

(defn ^:api ^:dynamic delete-container
  "Delete the named container."
  [config name]
  (let [response (http/delete (str (:x-storage-url config) "/" name)
                              (merge (headers config)
                                     (:http-opts config)
                                     {:throw-exceptions false}))]
    (case (:status response)
      204 response
      404 response
      (throw+ response))))

;; TODO: X-Delete-At support
;; TODO: custom metadata at creation time support
(defmulti put-object
  "At path, store the given thing."
  {:api true :dynamic true}
  (fn [_ _ obj & [md5sum]]
    (class obj)))

(defmethod put-object (Class/forName "[B")
  [config path bytes & [md5sum]]
  (let [hdrs (merge (headers config {"ETag" (or md5sum
                                                (DigestUtils/md5Hex bytes))})
                    (:http-opts config)
                    {:body bytes
                     :content-type "application/octet-stream"})
        obj-uri (str (:x-storage-url config) "/" path)
        response (http/put obj-uri hdrs)]
    response))

(defmethod put-object java.io.InputStream
  [config path stream & [md5sum]]
  (http/put (str (:x-storage-url config) "/" path)
            (merge (headers config (if md5sum
                                     {"ETag" md5sum}
                                     {}))
                   (:http-opts config)
                   {:body stream})))

(defmethod put-object java.io.File
  [config path fobj & [md5sum]]
  (http/put (str (:x-storage-url config) "/" path)
            (merge (headers config (if md5sum
                                     {"ETag" md5sum}
                                     {}))
                   (:http-opts config)
                   {:body fobj})))

(defn ^:api ^:dynamic get-object-bytes
  "Retrieves an object from the given location.
  Arguments: path
  Optional: offset, length"
  [config path & [offset length]]
  (let [obj-uri (str (:x-storage-url config) "/" path)
        ranged-get (str "bytes="
                        (or offset "0")
                        "-"
                        (if length (+ offset (dec length)) ""))
        response (http/get obj-uri (merge (headers config (if offset
                                                            {"Range" ranged-get}
                                                            {}))
                                          (:http-opts config)))]
    response))

(defn ^:api ^:dynamic get-object-stream
  "Retrieves an object stream from the given location.
  Arguments: path
  Optional: offset, length"
  [config path & [offset length]]
  (let [ranged-get (str "bytes="
                        (or offset "0")
                        "-"
                        (if length (+ offset (dec length)) ""))]
    (http/get (str (:x-storage-url config) "/" path)
              (merge (headers config (if offset
                                       {"Range" ranged-get}
                                       {}))
                     (:http-opts config)
                     {:as :stream}))))

(defn ^:api ^:dynamic delete-object
  "Deletes the object at path."
  [config path]
  (http/delete (str (:x-storage-url config) "/" path)
               (merge (headers config)
                      (:http-opts config)
                      {:throw-exceptions false})))

(defn ^:api ^:dynamic get-meta
  "Retrieve metadata for the given path. Works for account, container, and
  object levels.

  Account: empty string
           metadata takes the form: X-Account-Meta-*
  Container: container-name
             metadata takes the form: X-Container-Meta-*
  Object: container-name/object-name
          metadata takes the form: X-Object-Meta-*"
  [config path]
  (let [response (http/head (str (:x-storage-url config) "/" path)
                            (merge (headers config)
                                   (:http-opts config)))
        headers (:headers response)]
    (->> (keys headers)
         (filter #(re-find #"(?i)x-(account|container|object)-meta" (name %)))
         (select-keys headers)
         (into (empty headers)))))

(defn ^:api ^:dynamic set-meta
  "Set metadata for the given path. Works for account, container, and
  object levels.

  Account: empty string
           X-Account-Meta-<key>
           <value>
  Container: container-name
             X-Container-Meta-<key>
             <value>
  Object: container-name/object-name
          X-Object-Meta-<key>
          <value>
  "
  ;; TODO: Set multiple key:value metadata pairs by passing them pairwise,
  ;;       as with clojure.core/assoc
  [config path k v]
  (let [h (merge (headers config {k v}) (:http-opts config))
        response (http/post (str (:x-storage-url config) "/" path) h)]
    response))

(defn ^:api ^:dynamic delete-meta
  "Delete metadata for the given path. Works for account, container, and
  object levels.

  Account: empty string
           X-Account-Meta-<key>
  Container: container-name
             X-Container-Meta-<key>
  Object: container-name/object-name
          X-Object-Meta-<key>"
  [config path k]
  (set-meta path k ""))

(defn ^:api ^:dynamic create-manifest
  "A helper to easily create manifest files, for large object support.
  Optionally takes an extra argument, in case you need the manifest to
  *point* to a different location than the manifest itself is in.

  Arguments: path - the path at which the manifest file itself will be created
  Optional: manifest-prefix - the prefix where the object pieces will exist"
  [config path & [manifest-prefix]]
  (http/put (str (:x-storage-url config) "/" path)
            (merge (headers config
                            {"X-Object-Manifest" (or manifest-prefix path)})
                   (:http-opts config)
                   {:body ""})))

(defn ^:api ^:dynamic is-manifest?
  "Tells you if the path is a manifest file for a large object or not."
  [config path]
  (let [resp (http/head (str (:x-storage-url config) "/" path)
                        (merge (headers config)
                               (:http-opts config)))]
    (boolean (get-in resp [:headers "x-object-manifest"]))))

;; thanks, cemerick!
;; http://cemerick.com/2011/10/17/a-la-carte-configuration-in-clojure-apis/
(def public-api (->> (ns-publics *ns*)
                     vals
                     (filter (comp :api meta))
                     doall))

(defmacro with-swift-session
  [config & body]
  `(let [c# ~config]
     (with-bindings (into {} (for [var @#'apodini.api/public-api]
                               [var (partial @var c#)]))
       (try
         ~@body
         (catch java.net.ConnectException ce#
           (if (.contains "Connection refused" (.getMessage ce#))
             (throw+ {:original-exception ce#
                      :given-config c#})
             (throw ce#)))))))
