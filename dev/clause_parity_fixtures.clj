(ns clause-parity-fixtures
  "Writes test/com/trustblocks/clause_parity.edn: clause cases and the
  answers the JVM gives them, which com.trustblocks.clause-parity-test then
  holds both the JVM and the ClojureScript build to.

  The cases: the date host functions over generated date-times (every
  offset form, fractions, leap days, dates alone and without a zone), the
  decimal `divide` over generated operands and scales (with the halves that
  test its rounding), the number rules (`str`, `=`), and the templates' own
  clauses on their samples -- including refusals, whose messages quote
  figures.

  Deterministic (a fixed seed), so regenerating without changing the
  vocabulary changes nothing. Run it only when the vocabulary is meant to
  change, and review the diff:

    bin/clj -M:xtdb-jvm-opts:dev -i dev/clause_parity_fixtures.clj"
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.trustblocks.clause :as clause]
            [com.trustblocks.template.library :as library])
  (:import [java.util Random]))

(def out "test/com/trustblocks/clause_parity.edn")

(def ^:private rng (Random. 20260928))

(defn- pick [xs] (nth xs (.nextInt rng (count xs))))
(defn- between [lo hi] (+ lo (.nextInt rng (inc (- hi lo)))))

(defn- pad [n w] (format (str "%0" w "d") n))

(def ^:private zones ["Z" "Z" "+00:00" "-04:00" "-05:00" "+05:30" "+14:00" "-12:00" "+09:45" "-00:30"])

(defn- date-time
  "A random DateTime string, in any of the forms a template's data holds."
  []
  (let [y  (between 1900 2100)
        m  (between 1 12)
        d  (between 1 (.lengthOfMonth (java.time.YearMonth/of (int y) (int m))))
        date (str (pad y 4) "-" (pad m 2) "-" (pad d 2))]
    (case (between 0 9)
      0 date
      1 (str date "T" (pad (between 0 23) 2) ":" (pad (between 0 59) 2) ":" (pad (between 0 59) 2))
      2 (str date "T" (pad (between 0 23) 2) ":" (pad (between 0 59) 2) (pick zones))
      (str date "T" (pad (between 0 23) 2) ":" (pad (between 0 59) 2) ":" (pad (between 0 59) 2)
           (pick ["" "" ".000" ".5" ".123" ".100" ".000001" ".123456789" ".9"])
           (pick zones)))))

(defn- decimal
  "A random operand: an integer or a decimal of up to four places, either sign."
  []
  (let [sign (pick [1 1 1 -1])]
    (case (between 0 3)
      0 (* sign (between 0 100000))
      1 (* sign (/ (between 0 10000000) 100.0))
      2 (* sign (/ (between 1 999999) 10000.0))
      (* sign (/ (between 1 5000) 8.0)))))

(defn- q [s] (pr-str s))

(defn- host-cases []
  (concat
   ;; Hand-picked: the edges.
   (for [[name src] [["leap day plus a year" "(plus-days \"2024-02-29T12:00:00Z\" 365)"]
                     ["across a century that is not leap" "(plus-days \"2100-02-28\" 1)"]
                     ["a fraction kept to its digits" "(plus-days \"2026-07-01T04:00:00.500-04:00\" 30)"]
                     ["seconds written out" "(plus-days \"2026-07-01T04:00Z\" 0)"]
                     ["days-between, a nanosecond short of a day" "(days-between \"2026-07-01T00:00:00.000000001Z\" \"2026-07-02T00:00:00Z\")"]
                     ["days-between, backwards" "(days-between \"2026-07-02T00:00:00Z\" \"2026-07-01T00:00:00.5Z\")"]
                     ["days-between across offsets" "(days-between \"2026-07-01T23:00:00-05:00\" \"2026-07-02T04:00:00Z\")"]
                     ["equal instants, different offsets" "[(before? \"2026-07-01T00:00:00-04:00\" \"2026-07-01T04:00:00Z\") (after? \"2026-07-01T00:00:00-04:00\" \"2026-07-01T04:00:00Z\")]"]
                     ["an impossible date" "(plus-days \"2026-02-30\" 1)"]
                     ["not a date" "(plus-days \"July 1, 2026\" 1)"]
                     ["divide by zero" "(divide 5 0 2)"]
                     ["divide, half up" "[(divide 1 8 2) (divide -1 8 2) (divide 5 2 0) (divide -5 2 0) (divide 2.5 1 0)]"]
                     ["divide, scale 0 and large" "[(divide 123456789012 7 0) (divide 1 3 10)]"]
                     ["str of numbers" "(str \"$\" 6000.0 \" \" 1.0E7 \" \" 0.1 \" \" 1234.5 \" \" 3 \" \" -0.0 \" \" 1.0E-4 \" \" 100)"]
                     ["numbers compare by value" "[(= 6 6.0) (not= 6 6.0) (= 6 7) (= \"a\" \"a\") (= [1] [1])]"]
                     ["durations" "[(duration-days {\"amount\" 36 \"unit\" \"hours\"}) (duration-days {\"amount\" 2 \"unit\" \"weeks\"}) (duration-days {\"amount\" 90 \"unit\" \"minutes\"})]"]]]
     {:name name :src src})
   ;; Generated.
   (for [i (range 300)]
     {:name (str "plus-days " i)
      :src  (str "(plus-days " (q (date-time)) " " (between -4000 4000) ")")})
   (for [i (range 250)]
     {:name (str "days-between " i)
      :src  (str "(days-between " (q (date-time)) " " (q (date-time)) ")")})
   (for [i (range 150)]
     (let [a (date-time) b (pick [(date-time) a])]
       {:name (str "before/after " i)
        :src  (str "[(before? " (q a) " " (q b) ") (after? " (q a) " " (q b) ")]")}))
   (for [i (range 400)]
     {:name (str "divide " i)
      :src  (str "(divide " (decimal) " " (pick [(decimal) (decimal) 3 7 100 12.5]) " " (between 0 6) ")")})
   (for [i (range 100)]
     {:name (str "str " i)
      :src  (str "(str " (decimal) " \"|\" " (* (decimal) (pick [1 1000 1.0E-5])) ")")})))

(defn- template-json [template file]
  (json/parse-string (slurp (io/file (library/dir template) file))))

(defn- template-cases []
  (let [pay      "street-resurfacing/pay-application"
        pay-src  (slurp (io/file (library/dir pay) "logic/clause.clj"))
        ;; Two lines whose figures agree -- 4,800 SY of milling at $4.25,
        ;; 320 tons of surface course at $98.75 -- and the totals they give.
        lines    [{"payItem" "3.1" "unit" "SY" "unitPrice" 4.25
                   "quantityThisPeriod" 4800.0 "amountThisPeriod" 20400.0
                   "quantityToDate" 4800.0 "amountToDate" 20400.0}
                  {"payItem" "5.1" "unit" "TON" "unitPrice" 98.75
                   "quantityThisPeriod" 320.0 "amountThisPeriod" 31600.0
                   "quantityToDate" 320.0 "amountToDate" 31600.0}]
        pay-data (assoc (template-json pay "sample.json")
                        "payItems" lines "grossAmount" 52000.0 "retainagePercent" 5.0
                        "retainageAmount" 2600.0 "previousPayments" 0.0 "otherDeductions" 0.0
                        "totalDeductions" 2600.0 "netAmountDue" 49400.0)
        items    (mapv #(get % "payItem") lines)
        certify  {"$class" "com.trustblocks.municipal.construction.payapplication@1.0.0.PayRequestCertified"}
        mgr      "manager-employment-contract"
        mgr-src  (slurp (io/file (library/dir mgr) "logic/clause.clj"))
        mgr-data (template-json mgr "sample.json")
        now      "2026-09-28T15:00:00.000Z"]
    [{:name "pay request: the sample's own request" :src pay-src
      :data pay-data :request (template-json pay "request.json") :state {} :now now}
     {:name "pay request: certified before any approval" :src pay-src
      :data pay-data :request certify :state {"approvedPayItems" []} :now now}
     {:name "pay request: certified with one item approved" :src pay-src
      :data pay-data :request certify :state {"approvedPayItems" ["5.1"]} :now now}
     {:name "pay request: certified with every item approved" :src pay-src
      :data pay-data :request certify :state {"approvedPayItems" items} :now now}
     {:name "pay request: certified, figures that disagree" :src pay-src
      :data (-> pay-data
                (assoc-in ["payItems" 1 "amountThisPeriod"] 31000.5)
                (assoc "grossAmount" 6000.0 "retainageAmount" 12.5 "netAmountDue" 1.0E7
                       "totalDeductions" 100 "liquidatedDamagesDays" 3 "liquidatedDamagesPerDay" 250.0
                       "liquidatedDamagesAmount" 700.25))
      :request certify :state {"approvedPayItems" items} :now now}
     {:name "manager: board approval" :src mgr-src
      :data mgr-data :request (template-json mgr "request.json") :state {} :now now}
     {:name "manager: a request it does not answer" :src mgr-src
      :data mgr-data :request {"$class" "com.example@1.0.0.Nothing"} :state {} :now now}]))

(defn- answer [ctx {:keys [src data request state now]}]
  (try {:ok (clause/evaluate ctx src {:data data :request request :state state :now now})}
       (catch Exception e {:error (ex-message e)})))

(defn write! []
  (let [ctx   (clause/context)
        cases (vec (for [c (concat (host-cases) (template-cases))]
                     (assoc c :expect (answer ctx c))))]
    ;; One case to a line: readable in a diff, and left alone by cljfmt,
    ;; which code-quality runs over every .edn file.
    (with-open [w (io/writer out)]
      (binding [*out* w]
        (println ";; Generated by dev/clause_parity_fixtures.clj from the JVM runtime -- do not edit.")
        (println (str "[" (str/join "\n " (map pr-str cases)) "]"))))
    (println "Wrote" (count cases) "cases to" out
             (str "(" (count (filter (comp :error :expect) cases)) " refusals)"))))

(write!)
