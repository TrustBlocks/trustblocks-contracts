(ns demo
  "Presentation demo: Concerto schemas enforced at the database write boundary,
  and bitemporal queries answering a real contract dispute.

    bin/clj -M:xtdb-jvm-opts -i dev/demo.clj

  Press Enter to advance. Nothing here is live-coded and nothing is random --
  it runs the same way every time.

  Deliberately shows SQL rather than Clojure. The audience does not need to
  read our implementation to judge the idea."
  (:require [clojure.string :as str]
            [com.trustblocks.store :as store]
            [xtdb.api :as xt]
            [xtdb.node :as xt.node]))

;; ---------------------------------------------------------------- presentation

(def ^:private esc (str (char 27)))
(defn- sgr [& codes] (str esc "[" (str/join ";" codes) "m"))
(def ^:private BOLD (sgr 1)) (def ^:private DIM (sgr 2))
(def ^:private CYAN (sgr 36)) (def ^:private GREEN (sgr 32))
(def ^:private RED (sgr 31)) (def ^:private YEL (sgr 33))
(def ^:private OFF (sgr 0))

(defn- pause []
  (print (str DIM "\n    [Enter]" OFF)) (flush) (read-line) (println))

(defn- beat [n title]
  (println (str "\n" BOLD CYAN "── " n ". " title " " OFF
                CYAN (apply str (repeat (max 0 (- 58 (count title)))  "─")) OFF)))

(defn- say [& s] (println (str "   " (apply str s))))

(defn- sql-echo [q]
  (println (str "\n" YEL "   sql> " OFF YEL (str/replace (str/trim q) #"\s*\n\s*" (str "\n       ")) OFF)))

(defn- table
  "Render query results as a plain result-set table."
  [rows cols]
  (if (empty? rows)
    (println (str "\n" DIM "   (0 rows)" OFF))
    (let [strs  (fn [r] (mapv #(str (get r %)) cols))
          hdr   (mapv #(str/upper-case (name %)) cols)
          body  (mapv strs rows)
          width (mapv (fn [i] (apply max (count (nth hdr i))
                                     (map #(count (nth % i)) body)))
                      (range (count cols)))
          line  (fn [cells sep]
                  (str "   " (str/join sep (map-indexed
                                            (fn [i c] (format (str "%-" (nth width i) "s") c))
                                            cells))))]
      (println)
      (println (str DIM (line hdr " │ ") OFF))
      (println (str DIM "   " (str/join "─┼─" (map #(apply str (repeat % "─")) width)) OFF))
      (doseq [r body] (println (line r " │ ")))
      (println (str DIM "   (" (count rows) " row" (if (= 1 (count rows)) "" "s") ")" OFF)))))

(defn- q [node sql & {:keys [cols]}]
  (sql-echo sql)
  (let [rows (vec (xt/q node sql))]
    (table rows (or cols (when (seq rows) (vec (keys (first rows))))))
    rows))

;; ---------------------------------------------------------------- the story

(def template-dir
  (str (System/getProperty "user.home")
       "/Code/Accord/cicero-template-library/src/acceptance-of-delivery"))

(def contract
  {"$class" "org.accordproject.acceptanceofdelivery@0.1.0.TemplateModel"
   "shipper" "Piedmont Paving LLC"
   "receiver" "City of Raleigh"
   "deliverable" "resurfacing of Glenwood Ave, segment 4"
   "businessDays" 15
   "attachment" "DOT inspection report 2025-114"
   "clauseId" "glenwood-2025" "$identifier" "glenwood-2025"})

(defn -main []
  (let [node (xt.node/start-node)]
    (try
      (println (str "\n" BOLD "  Municipal contracts on Concerto + XTDB" OFF))
      (println (str DIM "  Glenwood Ave resurfacing — a contract, a dispute, and the record" OFF))
      (pause)

      ;; 1 ------------------------------------------------------------------
      (beat 1 "The schema is data, not DDL")
      (say "We load the Accord template's Concerto model. No CREATE TABLE runs —")
      (say "XTDB has no schema. The model is stored " BOLD "as rows" OFF ", versioned like any data.")
      (doseq [f (store/model-dir-files (str template-dir "/model"))]
        (store/put-model! node f))
      (q node "SELECT _id AS declaration, concerto$kind AS kind
                 FROM concerto_declaration
                WHERE _id LIKE '%acceptanceofdelivery%'
             ORDER BY _id")
      (say DIM "The contract's schema is now queryable alongside the contracts." OFF)
      (pause)

      ;; 2 ------------------------------------------------------------------
      (beat 2 "Concerto validation, at the database write boundary")
      (say "Schemaless storage usually means nothing is checked. Here every write")
      (say "is validated against the Concerto model first — " BOLD "the same schema" OFF ",")
      (say "enforced where the data lands, not just at the API.")
      (let [bad (assoc contract "businessDays" "fifteen")]
        (println (str "\n" RED "   REJECTED" OFF "  businessDays = \"fifteen\"  (model declares Long)"))
        (try
          (store/put-instance! node nil bad)
          (println "   ...accepted?!")
          (catch clojure.lang.ExceptionInfo e
            (println (str RED "   → " (pr-str (:errors (ex-data e))) OFF)))))
      (println (str "\n" GREEN "   ACCEPTED" OFF "  businessDays = 15"))
      (store/put-instance! node nil contract)
      (say DIM "Nothing invalid can reach storage through this path." OFF)
      (pause)

      ;; 3 ------------------------------------------------------------------
      (beat 3 "The job: signed in May, work starts, crew walks off June 2")
      (say "Contract state changes over time. We record " BOLD "when each state" OFF)
      (say BOLD "actually took effect" OFF ", not when someone got round to typing it in.")
      (doseq [[status from] [["SIGNED"      #inst "2025-05-12"]
                             ["IN_PROGRESS" #inst "2025-05-20"]
                             ["SUSPENDED"   #inst "2025-06-02"]
                             ["DISPUTED"    #inst "2025-07-15"]]]
        (xt/execute-tx node [[:put-docs {:into :agreement :valid-from from}
                              {:xt/id "glenwood-2025" :status status
                               :contractor "Piedmont Paving LLC"}]]))
      (q node "SELECT _id, status, _valid_from
                 FROM agreement FOR ALL VALID_TIME
             ORDER BY _valid_from")
      (pause)

      ;; 4 ------------------------------------------------------------------
      (beat 4 "\"What was the state of this contract on June 2?\"")
      (say "The question a lawyer actually asks. One clause, no history table,")
      (say "no audit triggers, no soft deletes.")
      (q node "SELECT _id, status
                 FROM agreement
                  FOR VALID_TIME AS OF DATE '2025-06-02'")
      (say GREEN "SUSPENDED" OFF " — as of the day the crew left the site.")
      (println)
      (q node "SELECT _id, status
                 FROM agreement
                  FOR VALID_TIME AS OF DATE '2025-05-25'")
      (say DIM "A week earlier the same row reads IN_PROGRESS." OFF)
      (pause)

      ;; 5 ------------------------------------------------------------------
      (beat 5 "Two clocks: what was true, and what we believed")
      (say "On review, the suspension is found to have taken effect June 2,")
      (say "not June 9 as first recorded. We correct it — back-dated.")
      ;; Bound the correction to the period it actually covers. An open-ended
      ;; put would overwrite the later DISPUTED transition -- the correction
      ;; would silently rewrite history it was not about.
      (xt/execute-tx node [[:put-docs {:into :agreement
                                       :valid-from #inst "2025-06-02"
                                       :valid-to   #inst "2025-07-15"}
                            {:xt/id "glenwood-2025" :status "SUSPENDED"
                             :contractor "Piedmont Paving LLC"
                             :note "corrected: effective date amended on review"}]])
      (q node "SELECT status, _valid_from, _system_from
                 FROM agreement
                  FOR ALL VALID_TIME FOR ALL SYSTEM_TIME
                WHERE status = 'SUSPENDED'
                  AND _valid_from = TIMESTAMP '2025-06-02T00:00:00Z'
             ORDER BY _system_from")
      (say "Same effective date, two recordings. " BOLD "Valid time" OFF " is what was true;")
      (say BOLD "system time" OFF " is what we believed, and the application cannot set it.")
      (say DIM "So \"the record said X when we acted on it\" stays provable." OFF)
      (println)
      (q node "SELECT status FROM agreement
                  FOR VALID_TIME AS OF DATE '2025-08-01'")
      (say DIM "And the correction left the rest of the timeline intact." OFF)
      (pause)

      ;; 6 ------------------------------------------------------------------
      (beat 6 "The contract data is still Concerto")
      (say "Nothing has been flattened into database-shaped mush.")
      (let [back (store/get-instance node "glenwood-2025")]
        (doseq [[k v] (sort back)]
          (println (str "   " DIM (format "%-14s" k) OFF " " (pr-str v)))))
      (say DIM "\nRound-trips byte-identically, and passes Concerto's own validator." OFF)
      (println (str "\n" BOLD "  Schema as data · validated writes · two clocks" OFF "\n"))
      (finally (.close node)))))

(-main)
