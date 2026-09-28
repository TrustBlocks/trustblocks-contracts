;; # Trustblocks: Clojure, Malli and XTDB, in one sitting
;;
;; *A tour for readers who know TypeScript or Rust and have not met Clojure.*
;;
;; **Trustblocks** runs Accord Project templates as *live* municipal
;; contracts for the Town of China Grove, North Carolina: a street
;; resurfacing contract (STR-27), its monthly pay requests, the notices and
;; forms around it -- each step certified by the Town officer who holds the
;; authority, and every date kept twice: when it happened, and when the Town
;; learned of it.
;;
;; The templates are Accord's own: Concerto models and TemplateMark text
;; ([trustblocks-templates](https://github.com/TrustBlocks/trustblocks-templates)).
;; Everything *around* them --
;; the logic, the database, the app -- is Clojure. This notebook shows why,
;; in four short parts:
;;
;; 1. **Clojure** -- a language where the program's values are plain data
;; 2. **Malli** -- a Concerto model, compiled to a schema you can hold in your hand
;; 3. **XTDB** -- a database of immutable documents, queried with SQL
;; 4. **Bitemporality** -- what was true *when*, and what we knew *when*
;;
;; Every expression below was evaluated to build this page; its value is
;; shown beneath it. Nothing is mocked: the data is the STR-27 demo matter,
;; loaded into a fresh in-memory XTDB node.

(ns accord-overview
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [com.trustblocks.clause :as clause]
            [com.trustblocks.model.malli :as model.malli]
            [com.trustblocks.xtdb.execute :as execute]
            [com.trustblocks.xtdb.note :as note]
            [com.trustblocks.xtdb.store :as store]
            [scicloj.kindly.v4.kind :as kind]
            [street-resurfacing-matter-demo :as demo]
            [xtdb.api :as xt]
            [xtdb.node :as xtn]))

;; An embedded XTDB node -- in memory, in this process, like SQLite -- with
;; the STR-27 matter loaded into it: the award, the notices, the finance
;; forms, three monthly pay requests and every certified step.

(defonce node (xtn/start-node))

(kind/hidden
 (with-out-str
   (demo/load! node (str (java.nio.file.Files/createTempDirectory
                          "notebook" (make-array java.nio.file.attribute.FileAttribute 0))))))

;; ---
;; ## 1. Clojure: the values are data
;;
;; Clojure is a Lisp on the JVM (and, as ClojureScript, in the browser). The
;; parentheses put the function first -- `(f x y)` is `f(x, y)` -- and that is
;; nearly all the syntax there is. What matters more is what it computes
;; *with*: a handful of literal data types that print as they are written.

{:contract "STR-27"                       ; a map -- like a TS object
 :pay-items ["3.1" "5.1" "7.1"]           ; a vector -- like an array
 :status :approved                        ; a keyword -- like a string enum
 :retainage 0.05}

;; A Concerto instance is JSON, and JSON read into Clojure is exactly these
;; maps and vectors -- no classes to map it onto, no ORM. Here is the Notice
;; to Proceed the demo issued, as stored:

(def notice (store/get-instance node "str-27-notice-to-proceed"))

(select-keys notice ["$class" "noticeId" "commencementDate" "completionDays"])

;; ### Immutable by default
;;
;; Nothing is ever changed in place. `assoc` returns a *new* map; the old one
;; is untouched -- as if every value in TypeScript were `Readonly` and deeply
;; frozen, or every Rust binding immutable and every "change" a cheap clone.
;; It is cheap: the new map shares all but the changed entry with the old
;; one (a persistent data structure).

(def extended (assoc notice "completionDays" 300))

[(get notice "completionDays") (get extended "completionDays")]

;; So a value can be handed anywhere -- another thread, a cache, a log --
;; without anyone worrying who else holds it. That is the property the rest
;; of this notebook leans on: a contract's state, a schema, a database
;; snapshot are all just values.

;; ### Functions over data
;;
;; Programs are small functions composed over those values. `->>` threads a
;; value through a pipeline (like method chaining, or Rust's iterator
;; adapters):

(def pay-request (store/get-instance node "str-27-pay-2"))

(->> (get pay-request "payItems")
     (map #(select-keys % ["payItem" "quantityThisPeriod" "unit" "amountThisPeriod"]))
     (kind/table))

;; ### The contract's own logic -- in the same language
;;
;; Accord's templates carry their logic in TypeScript. Ours carry it in
;; Clojure: `logic/clause.clj`, one expression, run in a sandbox
;; ([SCI](https://github.com/babashka/sci)) that holds only a small
;; vocabulary -- no clock, no I/O, no interop -- so the same request always
;; gets the same answer. This is the pay request's clause, as the app
;; stores it:

(def pay-clause
  (:source (execute/get-clause node (get pay-request "$class"))))

(kind/code (str/join "\n" (take 24 (str/split-lines pay-clause))))

;; Run it: the Director certifies pay request 2 -- first before the
;; engineer approved anything, then after.

(defn certify [approved]
  (try
    (clause/evaluate (clause/context) pay-clause
                     {:data    pay-request
                      :request {"$class" "com.trustblocks.municipal.construction.payapplication@1.0.0.PayRequestCertified"}
                      :state   {"approvedPayItems" approved}
                      :now     "2026-09-01T15:00:00Z"})
    (catch Exception e {:refused (ex-message e)})))

(certify [])

(certify ["3.1" "5.1"])

;; The same source, compiled to JavaScript, runs in Trustblocks' fork of
;; the [Template Playground](https://trustblocks.github.io/template-playground/) and
;; gives the same answer, to the character -- 1,223 recorded cases hold the
;; two to it.

;; ---
;; ## 2. Malli: a Concerto model as a schema you can hold
;;
;; [Malli](https://github.com/metosin/malli) is Clojure's schema library --
;; in TypeScript terms, somewhere between Zod and JSON Schema, except the
;; schema is *itself plain data*, so it can be stored, printed, diffed and
;; transformed like any other value.
;;
;; Trustblocks compiles each template's Concerto model to Malli with its own
;; Concerto parser (concerto-clj). Here is the Notice to Proceed's model, as
;; Concerto:

(def ntp "com.trustblocks.municipal.construction.noticetoproceed@1.0.0.NoticeToProceed")

(def reg (store/registry-from-xtdb node))

(kind/code (store/model-source node "com.trustblocks.municipal.construction.noticetoproceed@1.0.0"))

;; And the same model, compiled to a Malli schema -- a vector of vectors:

(model.malli/form reg ntp)

;; ### Validate
;;
;; `nil` means it conforms. Every write to XTDB is checked this way.

(def ntp-schema (model.malli/schema reg ntp))

(model.malli/explain ntp-schema notice)

;; And when it does not, the errors are data too -- humanized, by property:

(model.malli/explain ntp-schema
                     (assoc notice "completionDays" "two hundred seventy"
                            "commencementDate" "July 1st"))

;; ### Generate
;;
;; Because the schema is data, Malli can *make up* valid instances from it
;; alone -- the raw material of property-based testing (think fast-check).
;; Nonsense by design, and every one validates:

(def made-up (model.malli/sample ntp-schema 7))

made-up

(model.malli/explain ntp-schema made-up)

;; ### Describe
;;
;; And the same model exported as JSON Schema, by Malli, for anything
;; outside Clojure:

(-> (model.malli/json-schema ntp-schema)
    (json/generate-string {:pretty true})
    (kind/code))

;; ---
;; ## 3. XTDB: documents in, SQL out, nothing overwritten
;;
;; [XTDB](https://xtdb.com) is an immutable database: you put documents in
;; (any map -- a Concerto instance as it is), query them with SQL or with
;; XTQL (a Clojure-shaped query language), and every version of every
;; document is kept. Here it runs embedded; it also runs as a server that
;; speaks the Postgres wire protocol, so `psql` and any Postgres driver can
;; query it.
;;
;; The notebook's own table, to keep the example small:

(xt/execute-tx node ["INSERT INTO vendors (_id, legal_name, vendor_number)
                      VALUES ('example-paving', 'Example Paving, LLC', NULL)"])

(xt/q node "SELECT * FROM vendors")

;; The Finance Director assigns a vendor number. An UPDATE -- but the old
;; row is not gone:

(xt/execute-tx node ["UPDATE vendors SET vendor_number = 'V-10482' WHERE _id = 'example-paving'"])

(xt/q node "SELECT * FROM vendors")

(xt/q node "SELECT legal_name, vendor_number, _system_from
            FROM vendors FOR ALL SYSTEM_TIME ORDER BY _system_from")

;; The same question in XTQL, which is just data -- a query a program can
;; build:

(xt/q node '(from :vendors [xt/id legal-name vendor-number]))

;; ---
;; ## 4. Bitemporality: what was true *when*, and what we knew *when*
;;
;; Every row in XTDB carries **two** timelines:
;;
;; - **valid time** -- when the fact is true *in the world*: the pay request
;;   was received on August 25.
;; - **system time** -- when the database *learned* it: the clerk entered it
;;   on September 2. Set by the database; nobody can backdate it.
;;
;; A contract dispute asks both kinds of question: *"what was the amount
;; due on August 30?"* (valid time) and *"what did the Town have on record
;; when it paid?"* (system time). Most databases answer neither without an
;; audit table someone remembered to maintain. XTDB answers both, for every
;; table.
;;
;; Pay request 2 arrives on August 25 -- recorded now, *valid from*
;; August 25:

(xt/execute-tx node ["INSERT INTO pay_requests (_id, net_amount_due, _valid_from)
                      VALUES ('pr-2', 26820.00, TIMESTAMP '2026-08-25T15:00:00Z')"])

(def before-correction (java.time.Instant/now))

;; Then the Director finds a transposed figure: the net amount due was
;; 26,280.00 all along. The correction is valid from the same August 25 --
;; it is what was *true* -- but recorded later:

(xt/execute-tx node ["UPDATE pay_requests
                      FOR PORTION OF VALID_TIME FROM TIMESTAMP '2026-08-25T15:00:00Z' TO NULL
                      SET net_amount_due = 26280.00 WHERE _id = 'pr-2'"])

;; **Now**, the amount due is the corrected one:

(xt/q node "SELECT _id, net_amount_due FROM pay_requests")

;; **On August 20**, there was no pay request yet (valid time):

(xt/q node "SELECT _id, net_amount_due FROM pay_requests
            FOR VALID_TIME AS OF TIMESTAMP '2026-08-20T00:00:00Z'")

;; **What the Town had on record before the correction** (system time) --
;; the question to ask if it paid on the old figure:

(xt/q node (str "SELECT _id, net_amount_due FROM pay_requests
                 FOR SYSTEM_TIME AS OF TIMESTAMP '" before-correction "'"))

;; And the whole story, both axes -- nothing was lost:

(kind/table
 (xt/q node "SELECT net_amount_due, _valid_from, _system_from, _system_to
             FROM pay_requests FOR ALL SYSTEM_TIME FOR ALL VALID_TIME
             ORDER BY _system_from"))

;; ### In the app
;;
;; Trustblocks leans on this everywhere, and writes almost no code for it.
;; A pay request's status history is a valid-time query:

(kind/table (execute/status-history node "str-27-pay-2"))

;; Its state *as of any day* is the same read with a date -- the contract
;; dashboard's slider is this one argument:

(defn status-on [day]
  (get (execute/current-state node "str-27-pay-2" :at (java.time.Instant/parse day)) "status"))

(mapv (juxt identity status-on)
      ["2026-08-26T00:00:00Z" "2026-09-02T00:00:00Z" "2026-09-10T00:00:00Z"])

;; And a note on a contract keeps both times: the day it is *about* (valid
;; time, chosen by the writer) and the day it was *recorded* (system time,
;; set by the database) -- so a note written in October about August shows
;; exactly that, and cannot be passed off as having been known in August.

(note/add! node {:contract demo/contract-id
                 :author   "tbarringer@chinagrovenc.gov"
                 :text     "Two suppliers report unpaid invoices from the Contractor."
                 :about    (java.time.Instant/parse "2026-08-15T00:00:00Z")})

(kind/table
 (->> (note/for-contract node demo/contract-id)
      (map #(select-keys % [:text :about :recorded]))))

;; ---
;; ## Where to look next
;;
;; - **Templates, models and the clause runtime** --
;;   [TrustBlocks/trustblocks-templates](https://github.com/TrustBlocks/trustblocks-templates)
;;   (`runtime/` holds the Clojure sandbox;
;;   [docs/runtime.md](https://github.com/TrustBlocks/trustblocks-templates/blob/main/docs/runtime.md) explains it)
;; - **The Template Playground, running Clojure logic** --
;;   [trustblocks.github.io/template-playground](https://trustblocks.github.io/template-playground/)
;; - **Clojure** -- [clojure.org](https://clojure.org), and
;;   [*Clojure for the Brave and True*](https://www.braveclojure.com) (free online)
;; - **Malli** -- [metosin/malli](https://github.com/metosin/malli)
;; - **XTDB** -- [docs.xtdb.com](https://docs.xtdb.com) (its SQL guide is the fastest way in)
;; - **This notebook** -- made with [Clay](https://scicloj.github.io/clay/),
;;   which renders a plain Clojure file, comments and all, to this page:
;;   `notebooks/accord_overview.clj`, built with `bin/notebook`.
