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

(ns apodini.test.api
  (:require [clojure.java.io :refer [input-stream]]
            [clojure.test :refer :all]
            [apodini.api :as swift]
            [apodini.auth :refer :all]
            [apodini.test.support :as support]
            [clj-http.client :as http])
  (:import (java.io File)
           (java.util UUID)
           (org.apache.commons.codec.digest DigestUtils)))

(def test-config (support/load-test-config))
(def test-container "testcontainer")
(def paged-container "paged-container")

;; fixtures
(use-fixtures
 :each
 (fn [f]
   (let [auth (authenticate (:account test-config)
                            (:key test-config)
                            (:swift-auth-url test-config))]
     ;; clean up any previous test runs
     (try
       (doseq [container [test-container paged-container]]
         (doseq [obj (map :name (swift/ls auth (str container "/")))]
           (swift/delete-object auth (str container "/" obj)))
         (swift/delete-container auth container))
       (catch Exception e
         (when-not (= 404 (get-in e [:object :status]))
           (println "fixture error on cleanup, status: "
                    (get-in e [:object :status]))
           (.printStackTrace e))))
     ;; ensure expected containers exist
     (try
       (swift/create-container auth test-container)
       (swift/create-container auth paged-container)
       (catch Exception _
         (println "fixture error: couldn't create test-container")))
     (f))))

;; the basics
(deftest test-bare-ls
  (let [resp (into [] (swift/ls (authenticate (:account test-config)
                                              (:key test-config)
                                              (:swift-auth-url test-config))
                                "/"))]
    (is (seq resp))
    (is (some #{test-container} (map :name resp)))))

(deftest test-ls-with-swift
  (swift/with-swift-session (authenticate (:account test-config)
                                          (:key test-config)
                                          (:swift-auth-url test-config))
    (let [resp (into [] (swift/ls "/"))]
      (is (seq resp))
      (is (some #{test-container} (map :name resp))))))

(deftest test-headers
  (is (= "test-token" (get-in (swift/headers {:x-auth-token "test-token"})
                              [:headers "X-Auth-Token"])))
  (let [hdrs (swift/headers {:x-auth-token "test-token"}
                            {:foo "bar"
                             :foo2 "bar2"})]
    (is (= 3 (count (:headers hdrs))))
    (is (contains? (:headers hdrs) :foo))))

;; Beyond this point, use with-test-session
;; in real code you'd cache this instead of asking for the token
;; 90 bazillion times, but i'm not sure yet that i want it to be
;; a fixture, so here it is.
(defmacro with-test-session
  "We'll be using with-swift-session a lot, always the same, so here's
  a helper macro to shorten that boilerplate."
  [& body]
  `(swift/with-swift-session (authenticate (:account test-config)
                                           (:key test-config)
                                           (:swift-auth-url test-config)
                                           :http-opts {:max-redirects 10})
     ~@body))

(defmacro ensure-call-counts [map-of-call-counts & body]
  (let [counters (gensym 'counters)
        originals (gensym 'originals)]
    `(let [~originals ~(zipmap (map #(list 'quote %) (keys map-of-call-counts))
                               (map #(list 'deref
                                           (list 'ns-resolve
                                                 *ns*
                                                 (list 'quote %)))
                                    (keys map-of-call-counts)))
           ~counters ~(zipmap (map #(list 'quote %) (keys map-of-call-counts))
                              (repeat `(atom 0)))]
       (with-redefs ~(vec
                      (for [[var-name call-count] map-of-call-counts
                            form [var-name `(fn [& args#]
                                              (swap!
                                               (get ~counters '~var-name) inc)
                                              (apply
                                               (get ~originals '~var-name)
                                               args#))]]
                        form))
         (let [x# (do ~@body)]
           ~@(for [[var-name expected-call-count] map-of-call-counts]
               `(let [~'actual-call-count @(get ~counters '~var-name)
                      ~'= =] ;; there is a reason for this.
                  (is (~'= ~'actual-call-count ~expected-call-count)
                      (format "%s called %s times, expected %s times"
                              '~var-name ~'actual-call-count
                              ~expected-call-count))))
           x#)))))

(deftest test-ls
  (let [container paged-container
        objs (map #(.getBytes (str "seq-object-" %)) (range))]
    (with-test-session
      (doseq [obj (take 5 objs)]
        (let [checksum (DigestUtils/md5Hex obj)
              name (str container "/" (String. obj))]
          (swift/put-object name obj)))
      (testing "ls-seq"
        (ensure-call-counts
          ;; we expect 4 calls to GET.
          ;; From 5 items:
          ;; Call 1: items 1-2
          ;; Call 2: items 3-4
          ;; Call 3: item 5
          ;; Call 4: is this nil really the end of the container? (yes)
          {clj-http.client/get 4}
          (is (= 5 (count (swift/ls (str container "/") :page-size 2)))))))))

(deftest test-ls-prefix
  (let [container paged-container
        objs (map #(.getBytes (str "prefix-object-" %)) (range))]
    (with-test-session
      (doseq [obj (take 5 objs)]
        (let [checksum (DigestUtils/md5Hex obj)
              name (str container "/" (String. obj))]
          (swift/put-object name obj)))
      (testing "ls in the s3 prefix listing style"
        (is (= 5 (count (swift/ls (str container "/prefix-object")))))))))

(deftest test-create-delete-container
  (let [container (str (UUID/randomUUID))]
    (with-test-session
      (testing "create-container"
        (swift/create-container container)
        (is (not (empty? (some #{container} (map :name (swift/ls "/")))))))
      (testing "delete-container"
        (swift/delete-container container)
        (is (empty? (some #{container} (swift/ls "/"))))))))

(deftest test-put-object-bytes
  (let [obj (.getBytes "HOLLABACK")
        checksum (DigestUtils/md5Hex obj)]
    (with-test-session
      (testing "put object bytes"
        (let [resp (swift/put-object (str test-container "/test-object") obj)]
          (is (= 201 (:status resp)))
          (is (= (str checksum) (get-in resp [:headers "etag"])))))
      (testing "object is listable"
        (is (some #{"test-object"} (map :name (swift/ls test-container))))))))

(deftest test-delete-object
  ;; put by hand -- this test is independent of put-*
  ;; (uses swift/ls)
  (let [teststr "Hello, is there anybody in there?"
        etag (DigestUtils/md5Hex (.getBytes teststr))
        auth (authenticate (:account test-config)
                           (:key test-config)
                           (:swift-auth-url test-config))]
    (http/put (str (:x-storage-url auth) (str "/" test-container "/baleeted"))
              {:headers {"X-Auth-Token" (:x-auth-token auth)
                         "ETag" etag}
               :body (.getBytes teststr)
               :content-type "application/octet-stream"})

    (with-test-session
      (testing "delete object"
        (let [ret (swift/delete-object (str test-container "/baleeted"))]
          (is (= 204 (:status ret))))
        (is (not (some #{"baleeted"} (swift/ls test-container))))))))

(deftest test-get-object-bytes
  ;; hand-put the test data to decrease inter-reliance between the
  ;; get-object and put-object unit tests
  (let [teststr "Hello, is there anybody in there?"
        etag (DigestUtils/md5Hex (.getBytes teststr))
        auth (authenticate (:account test-config)
                           (:key test-config)
                           (:swift-auth-url test-config))]
    (http/put (str (:x-storage-url auth) (str "/" test-container "/holla"))
              {:headers {"X-Auth-Token" (:x-auth-token auth)
                         "ETag" etag}
               :body (.getBytes teststr)
               :content-type "application/octet-stream"})

    (with-test-session
      (testing "get object"
        (let [ret (swift/get-object-bytes (str test-container "/holla"))
              body (:body ret)
              checksum (get-in ret [:headers "etag"])]
          (is (= (str body) teststr))
          (is (= etag checksum)))))))

(deftest test-put-bytes-then-get-bytes
  ;; this test is allowed to be interdependent (uses both)
  (with-test-session
    (testing "put-object then get-object-bytes"
      (let [teststr "Ow my pancreas."
            checksum (DigestUtils/md5Hex (.getBytes teststr))
            obj-name (str test-container "/both-ways")]
        (swift/delete-object obj-name)
        (swift/put-object obj-name (.getBytes teststr))
        (let [obj (swift/get-object-bytes obj-name)]
          (is (= teststr (str (:body obj))))
          (is (= checksum (get-in obj [:headers "etag"]))))))))

(deftest test-put-object-stream
  (testing "put-object-stream"
    (let [obj-name (str test-container "/put-stream-test")]
      (with-test-session
        (swift/put-object obj-name
                          (input-stream (.getBytes "put-stream-test")))
        (is (= "put-stream-test" (:body (swift/get-object-bytes
                                         obj-name))))))))

(deftest put-object-stream-with-checksum
  (testing "put-object-stream with a checksum check"
    (let [obj-name (str test-container "/put-stream-with-md5")
          test-string "put-stream-with-checksum"
          test-bytes (.getBytes test-string)
          checksum (DigestUtils/md5Hex test-bytes)]
      (with-test-session
        (swift/put-object obj-name (input-stream test-bytes) checksum)
        (let [resp (swift/get-object-bytes obj-name)]
          (is (= test-string (:body resp)))
          (is (= checksum (get-in resp [:headers "etag"]))))))))

(deftest put-object-stream-with-bogus-checksum
  (testing "put-object-stream with a user provided bogus checksum"
    (let [obj-name (str test-container "/put-stream-with-bogus-md5")
          test-string "put-stream-with-bogus-checksum"
          test-bytes (.getBytes test-string)
          checksum (str (reverse (DigestUtils/md5Hex test-bytes)))]
      (with-test-session
        (try
          (swift/put-object obj-name (input-stream test-bytes) checksum)
          (catch clojure.lang.ExceptionInfo ei
            (is (= 422 (get-in (.getData ei) [:object :status])))))))))

(deftest test-put-object-file
  (testing "put-object-file"
    (let [temp (doto (File/createTempFile "put-file-test" ".tmp")
                 .deleteOnExit)]
      (spit temp "put-file-test")
      (let [obj-name (str test-container "/put-file-test")]
        (with-test-session
          (swift/put-object obj-name temp)
          (is (= "put-file-test" (:body (swift/get-object-bytes
                                         obj-name)))))))))

(deftest test-put-object-file-with-checksum
  (testing "put-object-file with a custom checksum"
    (let [temp (doto (File/createTempFile "put-file-test-with-md5" ".tmp")
                 .deleteOnExit)]
      (spit temp "put-file-test")
      (let [obj-name (str test-container "/put-file-test")
            checksum (DigestUtils/md5Hex (slurp temp))]
        (with-test-session
          (swift/put-object obj-name temp checksum)
          (let [resp (swift/get-object-bytes obj-name)]
            (is (= "put-file-test" (:body resp)))
            (is (= checksum (get-in resp [:headers "etag"])))))))))

(deftest test-get-object-stream
  (testing "get-object-stream"
    (let [obj-name (str test-container "/get-stream-test")]
      (with-test-session
        (swift/put-object obj-name
                          (.getBytes "hello"))
        (let [ins (swift/get-object-stream obj-name)
              buf (byte-array 5)]
          (.read (:body ins) buf 0 5)
          (is (= "hello" (String. buf))))))))

(deftest test-get-object-stream-ranged
  (testing "get-object-stream-ranged"
    (let [obj-name (str test-container "/get-stream-ranged-test")]
      (with-test-session
        (swift/put-object obj-name
                          (.getBytes "hello ranged get test"))
        (let [ins (swift/get-object-stream obj-name 6 6)
              buf (byte-array 6)]
          (.read (:body ins) buf 0 6)
          (is (= "ranged" (String. buf))))))))

(deftest test-get-object-bytes-ranged
  (testing "get-object-bytes-ranged"
    (let [obj-name (str test-container "/get-bytes-ranged-test")]
      (with-test-session
        (swift/put-object obj-name
                          (.getBytes "hello bytes get test"))
        (let [resp (swift/get-object-bytes obj-name 6 5)]
          (is (= "bytes" (str (:body resp)))))))))

(deftest test-large-object-support
  (testing "large object support"
    (with-test-session
      (let [obj-prefix (str test-container "/large-object")]
        ;; test-container/large-object/1
        (swift/put-object (str obj-prefix "/1")
                          (.getBytes "large object part 1\n"))
        ;; test-container/large-object/2
        (swift/put-object (str obj-prefix "/2")
                          (.getBytes "large object part 2\n"))
        ;; test-container/large-object
        (swift/create-manifest obj-prefix)
        ;; now available as one download
        (let [resp (swift/get-object-bytes obj-prefix)
              body (:body resp)]
          (is (.contains body "part 1"))
          (is (.contains body "part 2")))))))

(deftest test-large-object-with-manifest-path
  (testing "manifest with optional manifest path"
    (with-test-session
      (let [obj-prefix (str test-container "/other-place/other-object")
            manifest-prefix (str test-container "/other-object")]
        ;; test-container/other-place/other-object/1
        (swift/put-object (str obj-prefix "/1")
                          (.getBytes "other object part 1\n"))
        ;; test-container/other-place/other-object/2
        (swift/put-object (str obj-prefix "/2")
                          (.getBytes "other object part 2\n"))
        ;; test-container/other-object
        (swift/create-manifest manifest-prefix obj-prefix)
        ;; now available as one download, at test-container/other-object
        (let [resp (swift/get-object-bytes manifest-prefix)
              body (:body resp)]
          (is (.contains body "part 1"))
          (is (.contains body "part 2")))))))

(deftest test-is-manifest-predicate
  (testing "is-manifest? predicate"
    (with-test-session
      (let [obj-prefix (str test-container "/predicate-place/pred-object")
            manifest-prefix (str test-container "/pred-object")]
        ;; test-container/other-place/other-object/1
        (swift/put-object (str obj-prefix "/1")
                          (.getBytes "predicate object part 1\n"))
        ;; test-container/other-place/other-object/2
        (swift/put-object (str obj-prefix "/2")
                          (.getBytes "predicate object part 2\n"))
        ;; test-container/other-object
        (swift/create-manifest manifest-prefix obj-prefix)
        (is (swift/is-manifest? manifest-prefix))
        (is (not (swift/is-manifest? (str obj-prefix "/1"))))
        (let [resp (swift/get-object-bytes manifest-prefix)
              body (:body resp)]
          (is (.contains body "part 1"))
          (is (.contains body "part 2")))))))

(deftest test-account-metadata
  (with-test-session
    (testing "set account meta"
      (is (= 204 (:status (swift/set-meta "" "X-Account-Meta-Test" "foo")))))
    (testing "get account meta"
      (is (contains? (swift/get-meta "") :x-account-meta-test)))
    (testing "delete account meta"
      (swift/delete-meta "" "X-Account-Meta-Test")
      (is (not (contains? (swift/get-meta "") "X-Account-Meta-Test"))))))

(deftest test-container-metadata
  (with-test-session
    (testing "set container meta"
      (is (= 204
             (:status
              (swift/set-meta test-container "X-Container-Meta-Test" "foo")))))
    (testing "get container meta"
      (is (contains? (swift/get-meta test-container) :x-container-meta-test)))
    (testing "delete container meta"
      (swift/delete-meta test-container "X-Container-Meta-Test")
      (is (not
           (contains? (swift/get-meta test-container)
                      "X-Container-Meta-Test"))))))

(deftest test-trailing-slash-handling
  (let [container "bukkit"
        objs (map #(.getBytes (str "bar-object-" %)) (range))]
    (with-test-session
      (swift/create-container container)
      (swift/put-object (str container "/foo/1") (.getBytes "stuff"))
      (swift/put-object (str container "/foo/2") (.getBytes "stuff"))
      (swift/put-object (str container "/foobar") (.getBytes "stuff"))
      (testing "listing deals properly with all placements of slashes"
        (is (= 3 (count (swift/ls (str container)))))
        (is (= 3 (count (swift/ls (str container "/")))))
        (is (= 3 (count (swift/ls (str container "/f")))))
        (is (= 3 (count (swift/ls (str container "/foo")))))
        (is (= 2 (count (swift/ls (str container "/foo/")))))))))
