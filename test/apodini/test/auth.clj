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

(ns apodini.test.auth
  (:require [clojure.java.io :refer [resource]]
            [clojure.java.shell :refer [sh]]
            [clojure.string :refer [blank?]]
            [clojure.test :refer :all]
            [apodini.auth :as auth]
            [apodini.test.support :as support])
  (:import (java.io BufferedReader StringReader)))

(def test-config (support/load-test-config))

(deftest test-get-auth-url
  (testing "authentication request"
    (let [auth (auth/authenticate
                (:account test-config)
                (:key test-config)
                (:swift-auth-url test-config))]
      (is auth)
      (is (#{200 202 204 230} (:status auth)))
      (is (:x-storage-url auth))
      (is (:x-storage-token auth))
      (is (:x-auth-token auth)))))

(deftest test-list-available-regions
  (when (:test-multi-region test-config)
    (testing "list-available-regions"
      (let [regions (auth/list-available-regions
                     (:account test-config)
                     (:key test-config)
                     (:test-multi-region-url test-config))]
        (is (seq regions))
        (is (some
             (hash-set (:test-multi-region test-config))
             (:regions regions)))))))

(deftest test-authenticate-with-region
  (when (:test-multi-region test-config)
    (testing "authenticate-with-region"
      (let [auth (auth/authenticate-with-region
                   (:account test-config)
                   (:key test-config)
                   (:test-multi-region-url test-config)
                   :region (:test-multi-region test-config))]
        (is auth)
        (is (:x-storage-url auth))
        (is (:x-auth-token auth))
        (is (:response auth))))))

(deftest test-http-opts-get-stored
  (testing ":http-opts are carried into the map"
    (let [auth (auth/authenticate
                (:account test-config)
                (:key test-config)
                (:swift-auth-url test-config)
                :http-opts {:socket-timeout 1000})]
      (is auth)
      (is (#{200 202 204 230} (:status auth)))
      (is (:x-storage-url auth))
      (is (:x-storage-token auth))
      (is (:x-auth-token auth))
      (is (:http-opts auth))
      (is (= (get-in auth [:http-opts :socket-timeout]) 1000)))))

(deftest test-http-opts-with-region
  (when (:test-multi-region test-config)
    (testing "authenticate-with-region with http-opts"
      (let [auth (auth/authenticate-with-region
                   (:account test-config)
                   (:key test-config)
                   (:test-multi-region-url test-config)
                   :region (:test-multi-region test-config)
                   :http-opts {:socket-timeout 1000})]
        (is auth)
        (is (:x-storage-url auth))
        (is (:x-auth-token auth))
        (is (:response auth))
        (is (:http-opts auth))
        (is (= (get-in auth [:http-opts :socket-timeout]) 1000))))))
