(ns com.trustblocks.template.street-resurfacing-test
  "The Street Resurfacing template (docs/examples/street-resurfacing-contract)
  ingests and stores through our own Concerto pipeline, not only Accord's.
  bin/template-conformance proves the template drafts under Accord's
  toolchain; this proves the same model and sample survive concerto-clj's
  parse, Malli validation, and XTDB's key rewriting -- arrays of concepts,
  optional Doubles and Booleans included, none of which the manager
  employment template exercises."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [com.trustblocks.template.archive :as archive]
            [com.trustblocks.xtdb.store :as store]
            [xtdb.node :as xt.node]))

(def ^:private dir "docs/examples/street-resurfacing-contract")
(def ^:private ns-prefix "com.trustblocks.municipal.construction@1.0.0.")

(defn- sample [] (json/parse-string (slurp (str dir "/sample.json"))))

(defn- awarded
  "The bid package as issued, filled in the way an award fills it: a
  contractor, a Notice to Proceed Date, a priced pay item, an addendum and
  a street. Illustrative values, not STR-27's -- it has not been awarded."
  []
  (-> (sample)
      (assoc "contractorName" "Example Paving, LLC"
             "contractorEntityType" "a limited liability company"
             "contractorStateOfOrganization" "North Carolina"
             "noticeToProceedDate" "2026-09-01T04:00:00.000Z"
             "addenda" [{"$class" (str ns-prefix "Addendum")
                         "addendumNumber" "1"
                         "dated" "2026-07-01T04:00:00.000Z"}]
             "streets" [{"$class" (str ns-prefix "StreetSegment")
                         "streetName" "Example Street"
                         "fromLocation" "A" "toLocation" "B"
                         "fullMill" true "edgeMill" false
                         "surfaceThicknessInches" 1.5
                         "lengthFeet" 1200.0 "widthFeet" 24.0}])
      (update-in ["resurfacingItems" 0] assoc "estimatedQuantity" 1850.0 "unitPrice" 98.75)))

(deftest the-bid-package-and-an-award-both-store-and-read-back-exactly
  (with-open [node (xt.node/start-node)]
    ;; Ingested beside the manager employment template on purpose: the two
    ;; share Accord's namespaces and MunicipalAgreement, and the registry is
    ;; global, so each must load cleanly with the other already present.
    (archive/ingest-dir! node "docs/examples/manager-employment-contract")
    (archive/ingest-dir! node dir)
    (let [reg (store/registry-from-xtdb node)]
      (doseq [[what instance] [["the blank package" (sample)] ["an award" (awarded)]]]
        (testing what
          (is (nil? (store/validate reg instance)))
          (store/put-instance! node reg instance)
          (is (= instance (store/get-instance node (get instance "contractId") :reg reg)))))
      (testing "a payment term of the wrong type is refused"
        (is (= {:completion-days ["should be an integer"]}
               (store/validate reg (assoc (sample) "completionDays" "270"))))))))

(deftest the-bid-schedule-carries-every-pay-item-in-order
  ;; 37 pay items across the Bid Form's seven sections, numbered <section>.<n>.
  (let [s        (sample)
        sections ["crackAndJointSealingItems" "patchingLevelingSealCoatItems"
                  "millingItems" "reclamationItems" "resurfacingItems"
                  "concreteItems" "pavementMarkingItems"]]
    (is (= 37 (reduce + (map #(count (get s %)) sections))))
    (doseq [[n section] (map-indexed vector sections)]
      (is (= (map #(str (inc n) "." (inc %)) (range (count (get s section))))
             (map #(get % "payItem") (get s section)))
          section))))

(deftest municipal-agreement-is-the-same-in-every-template-that-vendors-it
  ;; Each template carries its own copy of agreement.cto so its archive loads
  ;; with no network (docs/model-repository.md). The registry is global, so
  ;; two copies that drifted apart would silently shadow one another.
  (let [copies (->> (.listFiles (io/file "docs/examples"))
                    (map #(io/file % "model" "agreement.cto"))
                    (filter #(.exists ^java.io.File %)))]
    (is (<= 2 (count copies)))
    (is (apply = (map slurp copies)))))

(def ^:private documented-format
  "The `as \"...\"` formats TemplateMark documents: DateTime tokens (YYYY M MM
  MMM MMMM D DD H HH h hh a A mm ss SSS Z, with punctuation and spaces) and
  amount patterns (0,0 / 0 0 with optional decimals, CCC or K). See
  docs/templatemark-conformance.md."
  #"(?:(?:YYYY|MMMM|MMM|MM|M|DD|D|HH|H|hh|h|mm|ss|SSS|Z|a|A)|[ ,./:-])+|K?0[ ,]0(?:[.,]0+)?(?: ?CCC)?")

(deftest every-grammar-is-commonmark-templatemark
  ;; TemplateMark is CommonMark plus pipe tables -- not GitHub markdown. A
  ;; grammar converted from Word tends to carry indentation as blockquotes,
  ;; bullets as literal characters, and underline as HTML; none of those mean
  ;; in CommonMark what they meant in Word.
  (doseq [^java.io.File d (.listFiles (io/file "docs/examples"))
          :let [g (io/file d "text" "grammar.tem.md")]
          :when (.exists g)
          :let [text (slurp g)]]
    (testing (.getName d)
      (is (not (re-find #"(?m)^>" text)) "blockquote used for indentation")
      (is (not (re-find #"\u2022" text)) "literal bullet character")
      (is (not (re-find #"<[a-zA-Z/][^>]*>" text)) "raw HTML")
      (doseq [[_ fmt] (re-seq #"as \"([^\"]*)\"" text)]
        (is (re-matches documented-format fmt) (str "undocumented format " (pr-str fmt)))))))
