(ns contract-queries
  "Fill real Accord templates with municipal data, store them, and query across
  them.

    bin/clj -M:xtdb-jvm-opts -i dev/contract_queries.clj"
  (:require [clojure.string]
            [clojure.pprint :as pp]
            [com.trustblocks.contract :as contract]
            [com.trustblocks.xtdb.store :as store]
            [xtdb.api :as xt]
            [xtdb.node :as xt.node]))

(def lib (str (System/getProperty "user.home")
              "/Code/Accord/cicero-template-library/src/"))

(defn money [amount currency]
  {"$class" "org.accordproject.money@0.3.0.MonetaryAmount"
   "doubleValue" amount "currencyCode" currency})

;; Filled instances -- same models as the blank templates, values supplied.
(def contracts
  [{:template "copyright-license"
    :valid-from #inst "2024-03-15T00:00:00.000-00:00"
    :instance
    {"$class" "org.accordproject.copyrightlicense@0.2.0.TemplateModel"
     "effectiveDate" "2024-03-15T00:00:00.000-05:00"
     "licensee" "Tom Brooke"      "licenseeState" "NC"
     "licenseeEntityType" "Individual" "licenseeAddress" "118 W Morgan St, Raleigh, NC"
     "licensor" "City of Raleigh" "licensorState" "NC"
     "licensorEntityType" "Municipality" "licensorAddress" "222 W Hargett St, Raleigh, NC"
     "territory" "Wake County"
     "purposeDescription" "publication of municipal GIS basemaps"
     "workDescription" "the 2024 aerial orthoimagery dataset"
     "paymentClause"
     {"$class" "org.accordproject.copyrightlicense@0.2.0.PaymentClause"
      "amountText" "twelve thousand US Dollars"
      "amount" (money 12000 "USD")
      "paymentProcedure" "ACH transfer within 30 days"
      "clauseId" "cl-0001" "$identifier" "cl-0001"}
     "contractId" "raleigh-gis-license-2024" "$identifier" "raleigh-gis-license-2024"}}

   {:template "promissory-note"
    :valid-from #inst "2025-06-01T00:00:00.000-00:00"
    :instance
    {"$class" "org.accordproject.promissorynote@0.2.0.TemplateModel"
     "amount" (money 250000 "USD")
     "date" "2025-06-01T00:00:00.000-04:00"
     "maker" "Tom Brooke"  "makerAddress" "118 W Morgan St, Raleigh, NC"
     "lender" "Wake County Finance Authority"
     "lenderAddress" "301 S McDowell St, Raleigh, NC"
     "interestRate" 4.25 "individual" true "legalEntity" "CORP"
     "principal" (money 250000 "USD")
     "maturityDate" "2030-06-01T00:00:00.000-04:00"
     "defaultDays" 60 "insolvencyDays" 90 "jurisdiction" "Wake County, NC"
     "clauseId" "wake-note-2025" "$identifier" "wake-note-2025"}}

   {:template "acceptance-of-delivery"
    :valid-from #inst "2025-09-10T00:00:00.000-00:00"
    :instance
    {"$class" "org.accordproject.acceptanceofdelivery@0.1.0.TemplateModel"
     "shipper" "Piedmont Paving LLC" "receiver" "Tom Brooke"
     "deliverable" "resurfacing of Glenwood Ave, segment 4"
     "businessDays" 15 "attachment" "DOT inspection report 2025-114"
     "clauseId" "glenwood-accept-2025" "$identifier" "glenwood-accept-2025"}}])

(let [node (xt.node/start-node)]
  (try
    ;; Load every model these templates need, then discard the disk registry --
    ;; from here on the schema comes out of XTDB.
    (doseq [t (distinct (map :template contracts))
            f (store/model-dir-files (str lib t "/model"))]
      (store/put-model! node f))

    (println "\n=================== INGEST ===================")
    (doseq [{:keys [instance valid-from template]} contracts]
      (let [r (contract/put-contract! node nil instance :valid-from valid-from)]
        (printf "  %-24s %-28s valid from %s\n"
                template (:xt/id r) valid-from)))

    (println "\n=== Q1: what contracts did Tom Brooke sign? ===")
    (doseq [r (sort-by :contract/id (contract/signed-by node "Tom Brooke"))]
      (printf "  %-28s as %-10s (%s)\n"
              (:contract/id r)
              (name (:party/role r))
              (last (clojure.string/split (str (:contract/class r)) #"\."))))

    (println "\n=== Q2: what contracts existed on 2025-05-01? ===")
    (let [rows (contract/as-of node #inst "2025-05-01T00:00:00.000-00:00")]
      (if (seq rows)
        (doseq [r rows] (printf "  %s\n" (:xt/id r)))
        (println "  (none)")))

    (println "\n=== Q2b: and on 2025-12-31? ===")
    (doseq [r (contract/as-of node #inst "2025-12-31T00:00:00.000-00:00")]
      (printf "  %-28s %s\n" (:xt/id r) (:contract/date r)))

    (println "\n=== Q3: the contract data is still intact Concerto ===")
    (pp/pprint (store/get-instance node "glenwood-accept-2025"))
    (finally (.close node))))
