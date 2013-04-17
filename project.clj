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

(defproject sonian/apodini "1.0.0"
  :description "Apodini: a typical swift client"
  :url "http://github.com/sonian/apodini"
  :license {:name "Apache 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :dependencies [[cheshire "5.1.1"]
                 [clj-http "0.7.2"]
                 [commons-codec "1.7"]
                 [slingshot "0.10.3"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.5.1"]]}
             :1.4 {:dependencies [[org.clojure/clojure "1.4.0"]]}}
  :plugins [[lein-bikeshed "0.1.0"]]
  :aliases {"all" ["with-profile" "dev,1.4:dev"]
            "test!" ["do" "clean," "deps," "bikeshed," "test"]}
  :min-lein-version "2.0.0")
