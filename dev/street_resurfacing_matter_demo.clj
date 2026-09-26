(ns street-resurfacing-matter-demo
  "The STR-27 matter, end to end: an awarded street resurfacing contract,
  and three monthly pay requests moving through the pay application's
  lifecycle -- each step a signed Receipt or Attestation by the China Grove
  officeholder who holds the authority, through their own office grant or,
  for the Zoning Administrator, the Director's designation.

  Illustrative. STR-27 has not been awarded: the contractor, Example Paving,
  LLC, its unit prices, the quantities and every date here are invented,
  and each stored PDF says so. The officeholders are the Town's. Their
  authorities are demo policy (as in certification-demo), not a statement
  of what their offices confer.

    Director of Public Works  David Ketner       receives, and certifies pay requests
    Zoning Administrator      Teresa Barringer   receives pay requests and the
                                                 engineer's certificates, as the
                                                 Director's designated representative
    Town Manager              Franklin Gover     approves and recommends payment
    Finance Director          Rebekah Newsome    certifies payment made

  Pay request 1 (July) is paid; 2 (August) is approved and awaiting payment
  -- overdue if not paid within 30 days (Sec. 6); 3 (September) has just
  been received.

  load! is safe to run repeatedly: what is already on record is left alone.
  Loading this namespace runs it twice against a throwaway node to prove
  that, as the other demos run themselves.

    bin/clj -M:xtdb-jvm-opts -i dev/street_resurfacing_matter_demo.clj"
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [com.trustblocks.template.archive :as archive]
            [com.trustblocks.template.library :as library]
            [com.trustblocks.xtdb.advance :as advance]
            [com.trustblocks.xtdb.certification :as cert]
            [com.trustblocks.xtdb.credential :as credential]
            [com.trustblocks.xtdb.document :as document]
            [com.trustblocks.xtdb.execute :as execute]
            [com.trustblocks.xtdb.identity :as identity]
            [com.trustblocks.xtdb.matter :as matter]
            [com.trustblocks.xtdb.store :as store]
            [xtdb.node :as xt.node])
  (:import [java.math BigDecimal RoundingMode]
           [java.time Instant]))

(def matter-id "str-27")
(def contract-id "china-grove-str-27-example-paving")
(def town-did "did:web:trustblocks.com:towns:china-grove")

(def ketner "dketner@chinagrovenc.gov")
(def barringer "tbarringer@chinagrovenc.gov")
(def gover "fgover@chinagrovenc.gov")
(def newsome "rnewsome@chinagrovenc.gov")

(def ^:private pay "com.trustblocks.municipal.construction.payapplication@1.0.0.")

(defn- at [s] (Instant/parse s))

(def ^:private awarded-at (at "2026-06-16T23:00:00Z"))
(def ^:private grants-until (at "2027-06-30T00:00:00Z"))

(def officeholders
  [{:email ketner :name "David Ketner" :office "Director of Public Works"
    :authority ["RECEIVE_PAY_APPLICATIONS" "RECEIVE_INSPECTION_REPORTS" "CERTIFY_PAY_APPLICATIONS"]}
   {:email barringer :name "Teresa Barringer" :office "Zoning Administrator"
    :authority []}
   {:email gover :name "Franklin Gover" :office "Town Manager"
    :authority ["APPROVE_PAYMENTS"]}
   {:email newsome :name "Rebekah Newsome" :office "Finance Director"
    :authority ["CERTIFY_PAYMENTS"]}])

;; ------------------------------------------------------------------ people

(defn- ensure-town! [node]
  (or (identity/by-did node town-did)
      (identity/provision-town! node {:slug "china-grove" :name "China Grove"})))

(defn- holds-all? [node did authority]
  (every? #(cert/authority-for node did % awarded-at) authority))

(defn- ensure-officeholder!
  "The officeholder, provisioned once, with an office grant for this
  project's authorities unless they already hold them -- the Town Manager
  and Finance Director may already have grants from certification-demo."
  [node town {:keys [email name office authority]}]
  (let [officeholder (or (identity/by-email node email)
                         (identity/provision-officeholder! node {:email email :name name :office office}))
        did (:xt/id officeholder)]
    (when (and (seq authority) (not (holds-all? node did authority)))
      (cert/grant-office! node (:xt/id town)
                          {:officeholder-did did :name name :office office :authority authority
                           :valid-from awarded-at :valid-until grants-until}))
    officeholder))

;; ------------------------------------------------------------------ figures

(defn- cents ^double [x]
  (.doubleValue (.setScale (BigDecimal/valueOf (double x)) 2 RoundingMode/HALF_UP)))

(def ^:private bid-items
  "The pay items this demo's award prices, and so its pay requests draw on:
  [section index unit-price estimated-quantity]."
  {"3.1" ["millingItems" 0 4.25 12000.0]
   "5.1" ["resurfacingItems" 0 98.75 1850.0]
   "7.1" ["pavementMarkingItems" 0 1.50 6000.0]})

(defn- awarded-contract []
  (reduce (fn [c [_ [section i price qty]]]
            (update-in c [section i] assoc "unitPrice" price "estimatedQuantity" qty
                       "amount" (cents (* price qty))))
          (assoc (json/parse-string (library/sample "street-resurfacing/contract"))
                 "contractId" contract-id
                 "contractorName" "Example Paving, LLC"
                 "contractorEntityType" "a limited liability company"
                 "contractorStateOfOrganization" "North Carolina"
                 "noticeToProceedDate" "2026-07-01T04:00:00.000Z")
          bid-items))

(def ^:private contract-amount
  (reduce + (map (fn [[_ [_ _ price qty]]] (* price qty)) bid-items)))

(defn- item [contract pay-item]
  (let [[section i] (bid-items pay-item)]
    (get-in contract [section i])))

(defn- pay-request
  "Pay request `n`, as the Contractor fills it in: `quantities` is
  {pay-item [this-period to-date]}; `previous` the payments made to date."
  [contract n from to quantities previous]
  (let [lines (for [[pi [this to-date]] (sort-by key quantities)
                    :let [{:strs [description unit unitPrice estimatedQuantity]} (item contract pi)]]
                {"$class" (str pay "PayItemLine")
                 "payItem" pi "description" description "unit" unit
                 "contractQuantity" estimatedQuantity "unitPrice" unitPrice
                 "quantityThisPeriod" this "quantityToDate" to-date
                 "amountThisPeriod" (cents (* this unitPrice))
                 "amountToDate" (cents (* to-date unitPrice))
                 "completed" false "approvedByEngineer" false})
        gross     (cents (reduce + (map #(get % "amountToDate") lines)))
        retainage (cents (/ (* gross 5.0) 100))
        deduct    (cents (+ retainage previous))]
    {"$class" (str pay "ContractorPayRequest")
     "payRequestId" (str "str-27-pay-" n)
     "contractNumber" "STR-27"
     "noticeToProceedDate" "2026-07-01T04:00:00.000Z"
     "completionDate" "2027-03-28T04:00:00.000Z"
     "percentPaymentComplete" (cents (* 100 (/ gross contract-amount)))
     "applicationNumber" n "sheetNumber" 1 "sheetCount" 1
     "periodFrom" from "periodTo" to
     "payItems" (vec lines)
     "grossAmount" gross "retainagePercent" 5.0 "retainageAmount" retainage
     "previousPayments" previous "otherDeductions" 0.0
     "totalDeductions" deduct "netAmountDue" (cents (- gross deduct))
     "contractorName" "Example Paving, LLC"
     "contractorSignature" "(signed)" "contractorSignatoryTitle" "President"
     "contractorSignedDate" to}))

;; ------------------------------------------------------------------ files

(defn- pdf
  "A one-page PDF of `lines` -- the stored file a receipt names. Enough of a
  PDF for any viewer; made here so the demo needs no fixtures on disk."
  ^bytes [lines]
  (let [esc     #(str/replace % #"[()\\]" (fn [m] (str "\\" m)))
        content (str "BT /F1 11 Tf 56 760 Td 15 TL "
                     (str/join " " (map #(str "(" (esc %) ") '") lines)) " ET")
        objs    ["<< /Type /Catalog /Pages 2 0 R >>"
                 "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"
                 "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>"
                 (str "<< /Length " (count content) " >>\nstream\n" content "\nendstream")
                 "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
        [body offsets] (reduce (fn [[s offs] [i o]]
                                 [(str s (inc i) " 0 obj\n" o "\nendobj\n") (conj offs (count s))])
                               ["%PDF-1.4\n" []] (map-indexed vector objs))
        xref    (str "xref\n0 " (inc (count objs)) "\n0000000000 65535 f \n"
                     (apply str (map #(format "%010d 00000 n \n" %) offsets)))]
    (.getBytes (str body xref "trailer\n<< /Size " (inc (count objs)) " /Root 1 0 R >>\nstartxref\n"
                    (count body) "\n%%EOF\n")
               "ISO-8859-1")))

(defn- pay-request-pdf [{:strs [applicationNumber periodFrom periodTo payItems grossAmount netAmountDue]}]
  (pdf (concat ["CONTRACTOR PAY REQUEST -- Town of China Grove, Contract No. STR-27"
                "ILLUSTRATIVE -- a Trustblocks demonstration document, not a real pay request."
                ""
                (str "Application for Payment No. " applicationNumber
                     ", period " (subs periodFrom 0 10) " to " (subs periodTo 0 10))
                "Contractor: Example Paving, LLC"
                ""]
               (for [{:strs [payItem unit quantityThisPeriod amountToDate]} payItems]
                 (str "Pay item " payItem ": " quantityThisPeriod " " unit " this period; $" amountToDate " to date"))
               [""
                (str "Gross amount of partial payment: $" grossAmount)
                (str "Net amount due this estimate: $" netAmountDue)])))

(defn- engineer-certificate-pdf [n approved]
  (pdf ["ENGINEER'S INSPECTION CERTIFICATE -- Contract No. STR-27"
        "ILLUSTRATIVE -- a Trustblocks demonstration document, not a real certificate."
        ""
        (str "Pay Request No. " n ": the work of pay items " (str/join ", " approved))
        "has been inspected and is approved as complete."]))

;; ------------------------------------------------------------------ the story

(def ^:private statements
  {"PayRequestCertified" "Quantities and amounts verified (Sec. 6)."
   "PaymentApproved"     "Approved and payment recommended."
   "PaymentCertified"    "Payment made to the Contractor in the net amount due."})

(defn- event [name & {:as fields}] (merge {"$class" (str pay name)} fields))

(defn- step!
  "One certified event on pay request `id`, unless already on record."
  [node id {:keys [email event-name credential when] :as s}]
  (when-not (some #(= (str "advance:" id ":" event-name ":" (at when)) (:request-id %))
                  (execute/history node id))
    (advance/advance! node (merge {:email email :document id :credential credential
                                   :at (at when)
                                   :event (or (:event s) (event event-name))
                                   :statement (statements event-name)}
                                  (select-keys s [:document-hash :received-from :description])))))

(defn- submit!
  "Pay request `request` received on `received` -- stored as a PDF and an
  instance, joined to the matter, and receipted by the Zoning Administrator."
  [node root request received]
  (let [id     (get request "payRequestId")
        stored (document/store! node root (pay-request-pdf request)
                                :filename (str id ".pdf") :media-type "application/pdf")]
    (store/put-instance! node nil request)
    (when-not (matter/matter-of node id)
      (matter/add-document! node matter-id id :role "pay-application"
                            :stored-document (:xt/id stored) :at received))
    (step! node id {:email barringer :event-name "PayRequestReceived" :credential "Receipt"
                    :when received :document-hash (:xt/id stored)
                    :received-from "Example Paving, LLC"
                    :description (str "Pay Request No. " (get request "applicationNumber"))})
    id))

(defn- inspected! [node root id n approved when]
  (let [stored (document/store! node root (engineer-certificate-pdf n approved)
                                :filename (str "engineer-certificate-" n ".pdf")
                                :media-type "application/pdf")]
    (step! node id {:email barringer :event-name "InspectionCertified" :credential "Receipt"
                    :event (event "InspectionCertified" "approvedPayItems" approved)
                    :when when :document-hash (:xt/id stored)
                    :received-from "the Town's consulting engineer"
                    :description (str "Engineer's Inspection Certificate, Pay Request No. " n)})))

(defn load!
  "The whole STR-27 matter, into `node`, storing files under `root`."
  [node root]
  (println "Ingesting the street-resurfacing package's templates ...")
  (archive/ingest-dir! node (library/dir "street-resurfacing/contract"))
  (archive/ingest-dir! node (library/dir "street-resurfacing/pay-application"))
  (store/put-model! node "model/identity.cto")
  (let [town     (ensure-town! node)
        _        (doseq [o officeholders] (ensure-officeholder! node town o))
        contract (awarded-contract)]
    (store/put-instance! node nil contract)
    (when-not (matter/get-matter node matter-id)
      (matter/open! node {:id matter-id :name "Street Resurfacing, Contract No. STR-27 (illustrative award)"
                          :package "street-resurfacing" :terms contract-id :opened-at awarded-at}))
    (when-not (some #(= cert/designation-class (:concerto/class %)) (credential/for-matter node matter-id))
      (cert/designate! node {:designator-email ketner
                             :designee-did (:xt/id (identity/by-email node barringer))
                             :name "Teresa Barringer"
                             :capacity "Designated Representative of the Director of Public Works"
                             :matter matter-id
                             :authority ["RECEIVE_PAY_APPLICATIONS" "RECEIVE_INSPECTION_REPORTS"]
                             :valid-from awarded-at :valid-until grants-until}))
    (let [pr1 (pay-request contract 1 "2026-07-01T04:00:00.000Z" "2026-07-24T04:00:00.000Z"
                           {"3.1" [4800.0 4800.0] "5.1" [320.0 320.0]} 0.0)
          pr2 (pay-request contract 2 "2026-07-25T04:00:00.000Z" "2026-08-24T04:00:00.000Z"
                           {"3.1" [5200.0 10000.0] "5.1" [780.0 1100.0]} (get pr1 "netAmountDue"))
          pr3 (pay-request contract 3 "2026-08-25T04:00:00.000Z" "2026-09-24T04:00:00.000Z"
                           {"3.1" [1500.0 11500.0] "5.1" [600.0 1700.0] "7.1" [5200.0 5200.0]}
                           (cents (+ (get pr1 "netAmountDue") (get pr2 "netAmountDue"))))]
      (println "Pay Request No. 1 ...")
      (let [id (submit! node root pr1 "2026-07-24T15:00:00Z")]
        (inspected! node root id 1 ["3.1" "5.1"] "2026-07-28T15:00:00Z")
        (step! node id {:email ketner :event-name "PayRequestCertified" :credential "Attestation" :when "2026-07-30T15:00:00Z"})
        (step! node id {:email gover :event-name "PaymentApproved" :credential "Attestation" :when "2026-08-04T15:00:00Z"})
        (step! node id {:email newsome :event-name "PaymentCertified" :credential "Attestation" :when "2026-08-20T15:00:00Z"}))
      (println "Pay Request No. 2 ...")
      (let [id (submit! node root pr2 "2026-08-25T15:00:00Z")]
        (inspected! node root id 2 ["3.1" "5.1"] "2026-08-28T15:00:00Z")
        (step! node id {:email ketner :event-name "PayRequestCertified" :credential "Attestation" :when "2026-09-01T15:00:00Z"})
        (step! node id {:email gover :event-name "PaymentApproved" :credential "Attestation" :when "2026-09-03T15:00:00Z"}))
      (println "Pay Request No. 3 ...")
      (submit! node root pr3 "2026-09-25T15:00:00Z")))
  (doseq [{:keys [xt/id role]} (matter/documents node matter-id)]
    (println " " id role (get (execute/current-state node id) "status"))))

(defn run []
  (let [root (str (java.nio.file.Files/createTempDirectory
                   "str-27" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (with-open [node (xt.node/start-node)]
      (load! node root)
      (load! node root))))

(run)
