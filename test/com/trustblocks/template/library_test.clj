(ns com.trustblocks.template.library-test
  "The app's templates come from TrustBlocks/trustblocks-templates (see
  com.trustblocks.template.library) and must survive our own pipeline --
  concerto-clj's parse, Malli validation, XTDB's key rewriting -- not only
  Accord's, which that repository's bb conformance already checks. And a
  template whose logic is Clojure must arrive runnable: ingesting it
  installs its clause."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [com.trustblocks.template.archive :as archive]
            [com.trustblocks.template.library :as library]
            [com.trustblocks.xtdb.execute :as execute]
            [com.trustblocks.xtdb.store :as store]
            [xtdb.node :as xt.node]))

(def ^:private construction "com.trustblocks.municipal.construction@1.0.0.")

(defn- sample [template-name] (json/parse-string (library/sample template-name)))

(defn- awarded
  "STR-27's bid package as issued, filled in the way an award fills it.
  Illustrative values -- STR-27 has not been awarded."
  []
  (-> (sample "street-resurfacing-contract")
      (assoc "contractorName" "Example Paving, LLC"
             "contractorEntityType" "a limited liability company"
             "contractorStateOfOrganization" "North Carolina"
             "noticeToProceedDate" "2026-09-01T04:00:00.000Z"
             "addenda" [{"$class" (str construction "Addendum")
                         "addendumNumber" "1"
                         "dated" "2026-07-01T04:00:00.000Z"}]
             "streets" [{"$class" (str construction "StreetSegment")
                         "streetName" "Example Street"
                         "fromLocation" "A" "toLocation" "B"
                         "fullMill" true "edgeMill" false
                         "surfaceThicknessInches" 1.5
                         "lengthFeet" 1200.0 "widthFeet" 24.0}])
      (update-in ["resurfacingItems" 0] assoc "estimatedQuantity" 1850.0 "unitPrice" 98.75)))

(deftest a-clojure-template-arrives-with-its-clause-installed
  (with-open [node (xt.node/start-node)]
    (let [{:keys [clause]} (archive/ingest-dir! node (library/dir "manager-employment-contract"))]
      (is (= "com.trustblocks.municipal.employment@1.0.0.ManagerEmployment" clause))
      (is (= :clojure (:runtime (execute/get-clause node clause))))
      (testing "and it runs: the template's own request.json approves the contract"
        (store/put-instance! node nil (sample "manager-employment-contract"))
        (let [request (json/parse-string
                       (slurp (io/file (library/dir "manager-employment-contract") "request.json")))
              {:keys [state]} (execute/trigger! node "china-grove-gover-2025" request
                                                (get request "requestedAt"))]
          (is (= "APPROVED" (get state "status"))))))))

(deftest a-template-without-logic-installs-no-clause
  (with-open [node (xt.node/start-node)]
    (is (nil? (:clause (archive/ingest-dir! node (library/dir "street-resurfacing-contract")))))))

(deftest the-street-contract-stores-and-reads-back-exactly
  (with-open [node (xt.node/start-node)]
    ;; Both templates in one node on purpose: they share Accord's namespaces
    ;; and MunicipalAgreement, and the registry is global.
    (archive/ingest-dir! node (library/dir "manager-employment-contract"))
    (archive/ingest-dir! node (library/dir "street-resurfacing-contract"))
    (let [reg (store/registry-from-xtdb node)]
      (doseq [[what instance] [["the bid package as issued" (sample "street-resurfacing-contract")]
                               ["an award" (awarded)]]]
        (testing what
          (is (nil? (store/validate reg instance)))
          (store/put-instance! node reg instance)
          (is (= instance (store/get-instance node (get instance "contractId") :reg reg)))))
      (testing "a payment term of the wrong type is refused"
        (is (= {:completion-days ["should be an integer"]}
               (store/validate reg (assoc (sample "street-resurfacing-contract")
                                          "completionDays" "270"))))))))

(deftest a-missing-template-is-named
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No template named nope"
                        (library/dir "nope"))))
