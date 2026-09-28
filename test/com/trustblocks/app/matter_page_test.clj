(ns com.trustblocks.app.matter-page-test
  "The matter page over the STR-27 demo matter: the matter held up against
  the street-resurfacing package's manifest -- what is on record for each
  document it lists, as of any day -- and the manifest as EDN or JSON."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.trustblocks.app.matters :as matters]
            [com.trustblocks.model.manifest :as manifest]
            [com.trustblocks.model.timeline :as timeline]
            [com.trustblocks.xtdb.matter :as matter]
            [com.trustblocks.xtdb.store :as store]
            [street-resurfacing-matter-demo :as demo]
            [xtdb.node :as xt.node])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant]))

(defn- loaded! [node]
  (demo/load! node (str (Files/createTempDirectory "matter" (make-array FileAttribute 0)))))

(defn- page [node & [params]]
  (:body (matters/show-page {:biff.xtdb/node node :uri (str "/matters/" demo/matter-id)
                             :params (or params {})})))

(deftest the-manifest-is-on-the-classpath
  (let [m (manifest/for-package "street-resurfacing")]
    (is (= "contract" (:terms m)))
    (is (= ["BID" "AWARD" "CONSTRUCTION" "CLOSEOUT"] (map :name (:phases m))))
    (testing "and as Concerto JSON, each part typed by where it sits"
      (let [j (manifest/->concerto m)]
        (is (= "com.trustblocks.manifest@1.0.0.Manifest" (get j "$class")))
        (is (= "com.trustblocks.manifest@1.0.0.DocumentRole" (get-in j ["documents" 0 "$class"])))
        (is (contains? (first (get j "documents")) "suppliedBy"))
        (is (= "com.trustblocks.manifest@1.0.0.StoredField"
               (get-in (first (filter #(get % "fields") (get j "documents"))) ["fields" 0 "$class"])))))
    (is (nil? (manifest/for-package "no-such-package")))))

(deftest the-matter-held-up-against-its-manifest
  (with-open [node (xt.node/start-node)]
    (loaded! node)
    (let [m        (manifest/for-package "street-resurfacing")
          record   (matter/get-matter node demo/matter-id)
          by-role  (fn [cl] (into {} (map (juxt :role identity) (:documents cl))))
          now      (by-role (manifest/checklist node m record))]
      (testing "the terms, the notices and the vendor form, as instances"
        (is (= :on-record (:standing (now "contract"))))
        (is (true? (:terms (first (:on-record (now "contract"))))))
        (is (= :on-record (:standing (now "notice-to-proceed"))))
        (is (= "VENDOR_NUMBER_ASSIGNED" (:status (first (:on-record (now "vendor-form")))))))
      (testing "the finance forms, received and nothing more"
        (doseq [role ["certificate-of-insurance" "e-verify-and-certifications" "tax-forms"]]
          (is (= :on-record (:standing (now role))) role)
          (is (= :receipt (:kind (first (:on-record (now role))))) role)))
      (testing "three pay requests, and the engineer's certificates received with the first two"
        (is (= 3 (count (:on-record (now "pay-application")))))
        (is (= ["str-27-pay-1" "str-27-pay-2"]
               (map :document (:on-record (now "inspection-certificate"))))))
      (testing "what the package still calls for: the bonds, and the bid-phase certification"
        (is (= :not-on-record (:standing (now "performance-bond"))))
        (is (= :not-on-record (:standing (now "debarment-certification"))))
        (is (= :optional (:standing (now "bid-bond"))))
        (is (= :not-yet (:standing (now "certificate-of-final-acceptance")))))
      (testing "nothing on record goes unlisted"
        (is (empty? (:unlisted (manifest/checklist node m record)))))
      (testing "as of June 20: the Notice of Award, not yet the forms or the Notice to Proceed"
        (let [then (by-role (manifest/checklist node m record :at (Instant/parse "2026-06-20T23:59:59Z")))]
          (is (= :on-record (:standing (then "notice-of-award"))))
          (is (= :not-on-record (:standing (then "certificate-of-insurance"))))
          (is (= :not-yet (:standing (then "notice-to-proceed"))))
          (is (empty? (:on-record (then "pay-application")))))))))

(deftest receipts-that-move-no-lifecycle-are-on-the-timeline
  (with-open [node (xt.node/start-node)]
    (loaded! node)
    (let [items (timeline/items node (store/registry-from-xtdb node) demo/contract-id (Instant/now))]
      (is (= 3 (count (filter #(= :receipt (:kind %)) items))))
      (is (some #(= "Received: IRS Form W-9" (:title %)) items))
      (testing "a receipt that moved a lifecycle is its event, not a receipt too"
        (is (not-any? #(and (= :receipt (:kind %)) (str/includes? (:title %) "Pay Request")) items))))))

(deftest the-matter-page
  (with-open [node (xt.node/start-node)]
    (loaded! node)
    (let [body (page node)]
      (is (str/includes? body "What the package calls for"))
      (is (str/includes? body "Street Resurfacing (unit-price construction, formally bid)"))
      (is (re-find #"(?s)Performance Bond.*?Not on record" body))
      (is (str/includes? body "receive finance forms"))
      (testing "the deadlines of its documents: the Completion Date from the Notice to Proceed"
        (is (re-find #"(?s)Mar 28, 2027.*?Completion Date" body)))
      (is (str/includes? body "N.C. Gen. Stat. § 44A-26"))
      (is (str/includes? body "Contractor certifications"))
      (testing "the manifest, as written"
        (is (str/includes? body "packages/street-resurfacing/manifest.edn"))
        (is (str/includes? body ":reads-terms"))))
    (testing "and as JSON"
      (let [body (page node {:manifest "json"})]
        (is (str/includes? body "com.trustblocks.manifest@1.0.0.DocumentRole"))
        (is (str/includes? body "readsTerms"))))
    (testing "as of a day"
      (is (str/includes? (page node {:as-of "2026-06-20"}) "What was on record on 2026-06-20")))
    (is (= 404 (:status (matters/show-page {:biff.xtdb/node node :uri "/matters/nope" :params {}}))))))

(deftest data-listings-colour-keys
  (let [spans (fn [src] (->> (com.trustblocks.lib.code/data-listing src) flatten
                             (partition 2 1)
                             (keep (fn [[a b]] (when (and (map? a) (string? b)) [b (first (:class a))])))
                             (into {})))]
    (testing "a JSON key, with or without a space before its colon"
      (is (= "text-sky-300" (get (spans "{\"id\" : 1}") "\"id\"")))
      (is (= "text-sky-300" (get (spans "{\"id\": \"x\"}") "\"id\"")))
      (is (= "text-orange-300" (get (spans "{\"id\": \"x\"}") "\"x\""))))
    (testing "an EDN keyword, and a comment"
      (is (= "text-sky-300" (get (spans "{:role \"x\"}") ":role")))
      (is (= "text-emerald-300" (get (spans ";; note") ";; note"))))))
