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

(def test-config
  {;; Create a file $HOME/.apodini.conf
   ;; containing something like the following, edited
   ;; as appropriate for your environment:
   ;;
   ;; {:account "test:tester"
   ;;  :key "testing"
   ;;  :swift-auth-url "http://127.0.0.1:8080/auth/v1.0"}
   ;;
   ;; You can override any of the keys in this map this way.
   ;; For example, you may wish to override these defaults,
   ;; which are based on a local Swift All-In-One developer
   ;; environment being set up:
   :account "test:tester"
   :key "testing"
   :swift-auth-url "http://127.0.0.1:8080/auth/v1.0"
   :container "apodini-test-bucket"})

;; NOTE: Testing extra features of rackspace cloudfiles
;; There are some features present in certain installations,
;; particularly rackspace cloudfiles, which are typically
;; not found in a developer's Swift-All-In-One instance.
;; However, Apodini does seek to support these features.

;; To allow testing of these features without breaking the
;; common developer's setup, extra test config keys can be
;; added which will enable those tests, if you have a need
;; to run them, and an appropriate set of credentials to use.

;; These options are listed below.

;; To test multi-region support, add these, with a valid
;; region identifier and v2.0 url endpoint. For example:
;; :test-multi-region "DFW"
;; :test-multi-region-url "https://identity.api.rackspacecloud.com/v2.0/tokens"
