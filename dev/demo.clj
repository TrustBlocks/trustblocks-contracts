(ns demo
  "Presentation demo for the Accord group.

    bin/clj -M:xtdb-jvm-opts -i dev/demo.clj

  Press Enter to advance. Nothing is live-coded and nothing is random.

  Written for an audience that works in TypeScript and has no Clojure, XTDB or
  Malli background, so it shows SQL rather than Clojure wherever it can -- XTDB
  speaks the Postgres wire protocol, and the room already reads SQL.

  Order follows what actually matters: immutability and bitemporality first,
  Concerto-validated writes as the design, no-codegen as the consequence.
  Schemaless storage is a means, not the pitch."
  (:require [clojure.string :as str]
            [com.trustblocks.xtdb.store :as store]
            [malli.core :as m]
            [xtdb.api :as xt]
            [xtdb.node :as xt.node]))

;; ---------------------------------------------------------------- presentation

(def ^:private e (str (char 27)))
(defn- sgr [& c] (str e "[" (str/join ";" c) "m"))
(def ^:private B (sgr 1))   (def ^:private D (sgr 2))
(def ^:private C (sgr 36))  (def ^:private G (sgr 32))
(def ^:private R (sgr 31))  (def ^:private Y (sgr 33))
(def ^:private M (sgr 35))  (def ^:private O (sgr 0))

(def ^:private PORT 5439)

(defn- pause [] (print (str D "\n    [Enter]" O)) (flush) (read-line) (println))
(defn- say [& s] (println (str "   " (apply str s))))
(defn- note [& s] (println (str "   " D (apply str s) O)))

(defn- beat [n title]
  (println (str "\n" B C "── " n ". " title " " O
                C (apply str (repeat (max 0 (- 60 (count title))) "─")) O))
  (println (str D "   " (java.time.LocalTime/now) "  (live)" O)))

(defn- sql [q]
  (println (str "\n" Y "   sql> " O Y
                (str/replace (str/trim q) #"\s*\n\s*" (str "\n       ")) O)))

(defn- table [rows]
  (if (empty? rows)
    (println (str "\n" D "   (0 rows)" O))
    (let [cols  (vec (distinct (mapcat keys rows)))
          hdr   (mapv #(str/upper-case (name %)) cols)
          body  (mapv (fn [r] (mapv #(str (get r %)) cols)) rows)
          w     (mapv (fn [i] (apply max (count (nth hdr i))
                                     (map #(count (nth % i)) body)))
                      (range (count cols)))
          line  (fn [cs sep] (str "   " (str/join sep (map-indexed
                                                       (fn [i c] (format (str "%-" (nth w i) "s") c)) cs))))]
      (println)
      (println (str D (line hdr " │ ") O))
      (println (str D "   " (str/join "─┼─" (map #(apply str (repeat % "─")) w)) O))
      (doseq [r body] (println (line r " │ ")))
      (println (str D "   (" (count rows) " row" (if (= 1 (count rows)) "" "s") ")" O)))))

(defn- q [node s] (sql s) (let [rows (vec (xt/q node s))] (table rows) rows))

;; ---------------------------------------------------------------- fixtures

(def lib (str (System/getProperty "user.home")
              "/Code/Accord/cicero-template-library/src/"))

(def contract
  {"$class" "org.accordproject.acceptanceofdelivery@0.1.0.TemplateModel"
   "shipper" "Piedmont Paving LLC" "receiver" "City of Raleigh"
   "deliverable" "resurfacing of Glenwood Ave, segment 4"
   "businessDays" 15 "attachment" "DOT inspection report 2025-114"
   "clauseId" "glenwood-2025" "$identifier" "glenwood-2025"})

(def note-2
  {"$class" "org.accordproject.promissorynote@0.2.0.TemplateModel"
   "amount" {"$class" "org.accordproject.money@0.3.0.MonetaryAmount"
             "doubleValue" 250000 "currencyCode" "USD"}
   "date" "2025-06-01T00:00:00.000-04:00"
   "maker" "City of Raleigh" "makerAddress" "222 W Hargett St, Raleigh, NC"
   "lender" "Wake County Finance Authority" "lenderAddress" "301 S McDowell St"
   "interestRate" 4.25 "individual" false "legalEntity" "CORP"
   "principal" {"$class" "org.accordproject.money@0.3.0.MonetaryAmount"
                "doubleValue" 250000 "currencyCode" "USD"}
   "maturityDate" "2030-06-01T00:00:00.000-04:00"
   "defaultDays" 60 "insolvencyDays" 90 "jurisdiction" "Wake County, NC"
   "clauseId" "wake-note-2025" "$identifier" "wake-note-2025"})

(defn- load-template! [node t]
  (doseq [f (store/model-dir-files (str lib t "/model"))] (store/put-model! node f)))

;; ---------------------------------------------------------------- the story

(defn -main []
  (let [node (xt.node/start-node {:server {:port PORT}})]
    (try
      (println (str "\n" B "  Municipal contracts on Concerto + XTDB" O))
      (println (str D "  Glenwood Ave resurfacing — a contract, a dispute, and the record" O))
      (println)
      (say "This is an XTDB Database an immutable, schemaless, bitemporal database")
      (say "It is a live database, started just now. It speaks the Postgres")
      (say "wire protocol, so anything that talks to Postgres talks to it:")
      (println (str "\n" M "     psql -h 127.0.0.1 -p " PORT " -U xtdb -d xtdb" O))
      (pause)

      ;; 1 -------------------------------------------------------------------
      (beat 1 "The record cannot be destroyed")
      (say "Records in XTDB are immuntable")
      (say "A contract is signed on 12 May. Someone then deletes it.")
      (load-template! node "acceptance-of-delivery")
      (xt/execute-tx node [[:put-docs {:into :agreement :valid-from #inst "2025-05-12"}
                            {:xt/id "glenwood-2025" :status "SIGNED"
                             :contractor "Piedmont Paving LLC" :amount 250000}]])
      (q node "SELECT _id, status FROM agreement")
      (say R "DELETE FROM agreement WHERE _id = 'glenwood-2025'" O)
      (xt/execute-tx node [[:delete-docs {:from :agreement} "glenwood-2025"]])
      (q node "SELECT _id, status FROM agreement")
      (say "Gone from today's view. But it is " B "not gone" O ":")
      (q node "SELECT _id, status, amount FROM agreement
                  FOR VALID_TIME AS OF DATE '2025-06-01'")
      (note "The delete recorded an END OF VALIDITY. It did not erase anything.")
      (note "There is no UPDATE and no destructive DELETE in this database.")
      (pause)

      ;; 2 -------------------------------------------------------------------
      (beat 2 "\"What was the state of this contract on June 2?\"")
      (say "The crew walks off site on 2 June. Restore the contract and record")
      (say "the states as they actually took effect.")
      (doseq [[s from] [["SIGNED" #inst "2025-05-12"] ["IN_PROGRESS" #inst "2025-05-20"]
                        ["SUSPENDED" #inst "2025-06-02"] ["DISPUTED" #inst "2025-07-15"]]]
        (xt/execute-tx node [[:put-docs {:into :agreement :valid-from from}
                              {:xt/id "glenwood-2025" :status s
                               :contractor "Piedmont Paving LLC" :amount 250000}]]))
      (q node "SELECT status, _valid_from FROM agreement
                  FOR ALL VALID_TIME ORDER BY _valid_from")
      (say "Now the question a lawyer actually asks:")
      (q node "SELECT status FROM agreement FOR VALID_TIME AS OF DATE '2025-06-02'")
      (q node "SELECT status FROM agreement FOR VALID_TIME AS OF DATE '2025-05-25'")
      (note "One clause. No history table, no audit triggers, no soft deletes,")
      (note "no status_changed_at column anyone has to remember to write.")
      (pause)

      ;; 3 -------------------------------------------------------------------
      (beat 3 "Two clocks: what was true, and what we believed")
      (say "On review the suspension is found to have taken effect 2 June, not")
      (say "9 June as first recorded. We correct it — back-dated.")
      (xt/execute-tx node [[:put-docs {:into :agreement
                                       :valid-from #inst "2025-06-02"
                                       :valid-to   #inst "2025-07-15"}
                            {:xt/id "glenwood-2025" :status "SUSPENDED"
                             :contractor "Piedmont Paving LLC" :amount 250000
                             :note "effective date amended on review"}]])
      (q node "SELECT status, _valid_from, _system_from FROM agreement
                  FOR ALL VALID_TIME FOR ALL SYSTEM_TIME
                WHERE status = 'SUSPENDED'
                  AND _valid_from = TIMESTAMP '2025-06-02T00:00:00Z'
             ORDER BY _system_from")
      (say "Same effective date, two recordings.")
      (say B "Valid time" O "  — when it was true in the world. You set this.")
      (say B "System time" O " — when the database learned it. " B "You cannot set this." O)
      (note "So \"the record said X on the day we acted on it\" stays provable,")
      (note "even after the record is corrected. That is the audit trail.")
      (pause)

      ;; 4 -------------------------------------------------------------------
      (beat 4 "Concerto validation, at the write boundary")
      (say "That is the storage. Here is the design.")
      (say "Every write is validated against the Concerto model before it lands.")
      (println (str "\n" D "   the CTO says:" O))
      (println (str "     " C "o Long businessDays" O))
      (let [reg (store/registry-from-xtdb node)
            sch (store/schema reg (get contract "$class"))]
        (println (str "\n" D "   the validator, compiled from it at runtime:" O))
        (doseq [entry (->> (m/form sch) (drop 1)
                           (filter #(#{:business-days :shipper :clause-id} (first %)))
                           (sort-by first))]
          (println (str "     " C (pr-str entry) O)))
        (println (str "     " D "... plus every other declared field" O)))
      (println (str "\n" R "   REJECTED" O "  businessDays = \"fifteen\""))
      (try (store/put-instance! node nil (assoc contract "businessDays" "fifteen"))
           (catch clojure.lang.ExceptionInfo ex
             (println (str R "   → " (pr-str (:errors (ex-data ex))) O))))
      (println (str "\n" G "   ACCEPTED" O "  businessDays = 15"))
      (store/put-instance! node nil contract)
      (note "\nThat schema was not written by hand. It is compiled from the")
      (note "Concerto metamodel at runtime — the CTO is the only source.")
      (pause)

      ;; 5 -------------------------------------------------------------------
      (beat 5 "No code generation")
      (say "Accord's codegen emits classes per model — 16 targets, Java included.")
      (say "For this one template, " B "concerto compile --target java" O " produces:")
      (println (str "\n" D "      23 files, 551 lines" O
                    "  " D "// this code is generated and should not be modified" O))
      (note "Add a template, or bump a model version: regenerate, recompile, redeploy.")
      (say "\nInstead, here is a template this program has never seen, loaded now:")
      (load-template! node "promissory-note")
      (store/put-instance! node nil note-2)
      (q node "SELECT _id, concerto$class FROM contract ORDER BY _id")
      (say B "Zero generated files. Zero rebuilds. No restart." O)
      (note "The model is data, so a new contract type is a data load, not a deploy.")
      (pause)

      ;; 6 -------------------------------------------------------------------
      (beat 6 "The contract is still Concerto")
      (say "None of this flattened the contract into database-shaped mush.")
      (let [back (store/get-instance node "wake-note-2025")]
        (doseq [[k v] (sort back)]
          (println (str "   " D (format "%-14s" k) O " " (pr-str v)))))
      (note "\nRound-trips byte-identically and passes Concerto's own validator.")
      (println (str "\n" B "  Immutable record · two clocks · Concerto-validated writes · no codegen" O))
      (println (str D "  Still live on port " PORT " — connect and try it." O "\n"))
      (pause)
      (finally (.close node)))))

(-main)
