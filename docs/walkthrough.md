# A walkthrough

Forms to paste, one at a time, rather than a script that runs past you. Every
output below was captured from an actual run.

It comes in halves, and the split is not arbitrary:

| | runs where | why |
|---|---|---|
| **Concerto → EDN → Malli** | babashka | pure data; no database |
| **storage and execution** | JVM REPL | XTDB is a JVM library and does not run under babashka |
| **the same execution, over HTTP** | curl | Part 4, once the app is running |

That is the same seam the code is built along: `concerto-clj` knows nothing about
storage, and the XTDB adapter supplies the parts that are XTDB's problem. The
web app adds nothing to the model — Part 4 is Part 3 reached through a URL.

The example is a small self-contained municipal contract,
[`docs/examples/snow-removal.cto`](examples/snow-removal.cto) — a plowing
agreement that pays a fixed rate per visit when snowfall clears a trigger depth.
It imports nothing, so it parses without fetching anything.

You need Accord's CLI on `$PATH` or in `$CONCERTO_CLI`:

```
npm i -g @accordproject/concerto-cli
```

---

## Part 1 — the model as data (babashka)

```
bb
```

```clojure
(require '[com.trustblocks.concerto.cto :as cto]
         '[com.trustblocks.concerto.malli :as cm]
         '[com.trustblocks.concerto.metamodel :as mm]
         '[com.trustblocks.concerto.instance :as inst]
         '[malli.core :as m]
         '[malli.error :as me])

(def reg (cto/load-registry "docs/examples/snow-removal.cto"))
(count reg)
;; => 6
```

The registry is **pure data** — no functions, no closures. It survives a
round trip through `pr-str` and `edn/read-string`, which is what lets it be
stored, diffed and shipped:

```clojure
(get-in reg ["com.trustblocks.snow@1.0.0.Money" :declaration])
;; =>
;; {:$class "concerto.metamodel@1.0.0.ConceptDeclaration",
;;  :name "Money",
;;  :isAbstract false,
;;  :properties
;;  [{:$class "concerto.metamodel@1.0.0.DoubleProperty",
;;    :name "amount",
;;    :isArray false,
;;    :isOptional false}
;;   {:$class "concerto.metamodel@1.0.0.StringProperty",
;;    :name "currency",
;;    :isArray false,
;;    :isOptional false,
;;    :validator
;;    {:$class "concerto.metamodel@1.0.0.StringRegexValidator",
;;     :pattern "^[A-Z]{3}$",
;;     :flags ""}}]}
```

### The schema, compiled at runtime

```clojure
(cm/->edn reg "com.trustblocks.snow@1.0.0.SnowRemoval")
;; =>
;; [:schema
;;  {:registry
;;   {"com.trustblocks.snow@1.0.0.Money"
;;    [:map {:closed true}
;;     [:$class [:= "com.trustblocks.snow@1.0.0.Money"]]
;;     [:$identifier {:optional true} :string]
;;     [:amount :concerto/double]
;;     [:currency [:and :string [:re "^[A-Z]{3}$"]]]]
;;    "com.trustblocks.snow@1.0.0.SnowRemoval"
;;    [:map {:closed true}
;;     [:$class [:= "com.trustblocks.snow@1.0.0.SnowRemoval"]]
;;     [:$identifier {:optional true} :string]
;;     [:contractId :string]
;;     [:contractor :string]
;;     [:route :string]
;;     [:inchesTrigger [:and :concerto/double [:>= 0]]]
;;     [:ratePerVisit [:ref "com.trustblocks.snow@1.0.0.Money"]]]}}
;;  [:ref "com.trustblocks.snow@1.0.0.SnowRemoval"]]
```

Four things worth noticing, because each was a deliberate decision:

- **`[:ref ...]`, not `:map`.** `ratePerVisit` is checked as a `Money`, not merely
  as "some map". References rather than inlining, so a self-referential concept
  terminates.
- **`{:closed true}`** — Concerto rejects undeclared properties, so this does too.
- **`[:re "^[A-Z]{3}$"]`** — the CTO's `regex=` is enforced. It's a *string*, not a
  compiled `#"..."`, because `#"..."` is a Clojure reader literal and not EDN.
- **`[:>= 0]`** — the CTO's `range=[0.0,]`, one-sided.

That whole form is plain EDN. Spit it to a file and it reads back anywhere,
including a browser, with no CLI and no network.

### Validating

Concerto instances arrive as JSON with string keys. `inst/json->edn` makes them
neutral EDN — lossless, and needing no mapping table, because `:$class` and
`:contractId` are both legal keywords:

```clojure
(def contract-json
  {"$class" "com.trustblocks.snow@1.0.0.SnowRemoval"
   "contractId" "snow-2026-014" "$identifier" "snow-2026-014"
   "contractor" "Glenwood Plowing LLC" "route" "Ward 3 arterials"
   "inchesTrigger" 2.0
   "ratePerVisit" {"$class" "com.trustblocks.snow@1.0.0.Money"
                   "amount" 480.0 "currency" "USD"}})

(def contract (inst/json->edn contract-json))
(def schema   (cm/->schema reg "com.trustblocks.snow@1.0.0.SnowRemoval"))

(m/validate schema contract)
;; => true
```

Now break it four ways. Each error names the path:

```clojure
(defn why [x] (me/humanize (m/explain schema x)))

(why (assoc-in contract [:ratePerVisit :currency] "dollars"))
;; => {:ratePerVisit {:currency ["should match regex"]}}

(why (assoc contract :inchesTrigger -1.0))
;; => {:inchesTrigger ["should be at least 0"]}

(why (assoc contract :kickback 500))
;; => {:kickback ["disallowed key"]}

(why (assoc contract :ratePerVisit {:$class "com.trustblocks.snow@1.0.0.Money"
                                    :currency "USD"}))
;; => {:ratePerVisit {:amount ["missing required key"]}}
```

The last one is the interesting one: the error points *inside* the nested
concept. Every one of these four is also rejected by `concerto validate`.

---

## Part 2 — storing it (JVM REPL)

```
bin/clj -M:xtdb-jvm-opts
```

```clojure
(require '[com.trustblocks.xtdb.store :as store]
         '[com.trustblocks.xtdb.execute :as ex]
         '[xtdb.api :as xt]
         '[xtdb.node :as xt.node])

(def node (xt.node/start-node))

(store/put-model! node "docs/examples/snow-removal.cto")
;; => {:namespace "com.trustblocks.snow@1.0.0"
;;     :declarations ["com.trustblocks.snow@1.0.0.Money"
;;                    "com.trustblocks.snow@1.0.0.Outcome"
;;                    "com.trustblocks.snow@1.0.0.SnowRemoval"
;;                    "com.trustblocks.snow@1.0.0.VisitReport"
;;                    "com.trustblocks.snow@1.0.0.VisitOutcome"
;;                    "com.trustblocks.snow@1.0.0.PaymentState"]}
```

The schema is now **in the database**, and queryable like anything else:

```clojure
(xt/q node '(-> (from :concerto-declaration [{:xt/id fqn} concerto/kind])
                (return fqn concerto/kind)))
;; => [{:fqn "com.trustblocks.snow@1.0.0.Money"
;;      :concerto/kind "concerto.metamodel@1.0.0.ConceptDeclaration"}
;;     {:fqn "com.trustblocks.snow@1.0.0.Outcome"
;;      :concerto/kind "concerto.metamodel@1.0.0.EnumDeclaration"}
;;     ...]
```

Which means the registry can be rebuilt from the database alone, with no
filesystem access — the schema needed to interpret a record lives beside the
record:

```clojure
(def reg (store/registry-from-xtdb node))
(count reg)
;; => 6
```

Writes go through validation. Nothing reaches storage unvalidated:

```clojure
(store/put-instance! node reg contract-json)
;; => {:xt/id "snow-2026-014" :$class "com.trustblocks.snow@1.0.0.SnowRemoval"}

(= contract-json (store/get-instance node "snow-2026-014" :reg reg))
;; => true
```

That equality is doing real work. XTDB lowercases camelCase column names and
reads `$` as a namespace separator, so `businessDays` and `$class` cannot be
stored as-is. The adapter kebab-cases on the way in and restores the exact
declared spelling on the way out, using the model as the authority — deriving it
back with `->camelCase` would turn `vechileID` into `vechileId`.

---

## Part 3 — executing it

The logic is not Concerto and never will be. But its *signature* is: the
request, the response and the state are all declared in the model. So the
boundary is checkable even though the body is opaque.

Clauses are stored as data, checked before they land:

```clojure
(def clause
  "(let [inches  (get request \"inchesFallen\")
         payable (>= inches (get data \"inchesTrigger\"))
         rate    (get-in data [\"ratePerVisit\" \"amount\"])
         due     (if payable rate 0.0)
         paid    (if state (get state \"visitsPaid\") 0)
         total   (if state (get-in state [\"totalPaid\" \"amount\"]) 0.0)]
     {:response {\"$class\" \"com.trustblocks.snow@1.0.0.VisitOutcome\"
                 \"outcomeId\" (get request \"reportId\")
                 \"$identifier\" (get request \"reportId\")
                 \"$timestamp\" now
                 \"outcome\" (if payable \"PAYABLE\" \"BELOW_TRIGGER\")
                 \"amountDue\" {\"$class\" \"com.trustblocks.snow@1.0.0.Money\"
                              \"amount\" due \"currency\" \"USD\"}}
      :state {\"$class\" \"com.trustblocks.snow@1.0.0.PaymentState\"
              \"contractId\" (get data \"contractId\")
              \"$identifier\" (get data \"contractId\")
              \"visitsPaid\" (if payable (+ paid 1) paid)
              \"totalPaid\" {\"$class\" \"com.trustblocks.snow@1.0.0.Money\"
                           \"amount\" (+ total due) \"currency\" \"USD\"}}})")

(ex/put-clause! node "com.trustblocks.snow@1.0.0.SnowRemoval" clause)
;; => {:$class "com.trustblocks.snow@1.0.0.SnowRemoval"}
```

Note what the clause does *not* do: it never reads a clock. `now` arrives as an
argument, which is what makes the execution replayable. Accord's own
acceptance-of-delivery clause calls `new Date()`, so its executions cannot be.

```clojure
(defn visit [id at inches]
  (ex/trigger! node "snow-2026-014"
               {"$class" "com.trustblocks.snow@1.0.0.VisitReport"
                "reportId" id "$identifier" id "$timestamp" at
                "visitedAt" at "inchesFallen" inches}
               at :reg reg))

(visit "v-1" "2026-01-15T06:00:00Z" 3.5)
(visit "v-2" "2026-02-02T06:00:00Z" 1.0)
(visit "v-3" "2026-02-20T06:00:00Z" 4.0)
```

```
2026-01-15T06:00:00Z   3.5 in -> PAYABLE        due 480.00   visits paid 1
2026-02-02T06:00:00Z   1.0 in -> BELOW_TRIGGER  due   0.00   visits paid 1
2026-02-20T06:00:00Z   4.0 in -> PAYABLE        due 480.00   visits paid 2
```

### The state, at a date

```clojure
(ex/state-as-of node "snow-2026-014" #inst "2026-01-20")
;; => {:visits-paid 1 :total-paid {:amount 480.0 :currency "USD"} ...}

(ex/state-as-of node "snow-2026-014" #inst "2026-02-10")
;; => {:visits-paid 1 :total-paid {:amount 480.0 :currency "USD"} ...}

(ex/current-state node "snow-2026-014" :reg reg)
;; => {"visitsPaid" 2 "totalPaid" {"amount" 960.0 "currency" "USD"} ...}
```

The February 10th answer is still one visit and $480 because the February 2nd
snowfall was below the trigger — the state was written but did not advance the
paid total. No history table, no audit triggers, no `status_changed_at` column
anyone has to remember to write.

### And *why* it is in that state

```clojure
(ex/history node "snow-2026-014")
```

```
2026-01-15T06:00:00Z  3.5 in -> PAYABLE
2026-02-02T06:00:00Z  1.0 in -> BELOW_TRIGGER
2026-02-20T06:00:00Z  4.0 in -> PAYABLE
```

Each row carries the request, the response and the clause source that ran. So
the state answers *what*, and the log answers *why this request arrived at this
time and produced this response* — which is the half a dispute actually turns
on. And because the clause is stored as data, "what was the logic on February
10th?" is the same kind of query as "what was the state on February 10th?"

### Refusals

```clojure
(visit "v-4" "2026-03-01T06:00:00Z" "lots")
;; Request failed validation  {"inchesFallen" ["should be a number"]}

(ex/put-clause! node "x@1.0.0.Y" "(slurp \"/etc/passwd\")")
;; Clause refused by the vocabulary: Could not resolve symbol: slurp
```

The request is checked before the clause runs. The clause is checked before it
is stored — by compiling it in the real sandbox rather than by pattern-matching
the source, so it catches `slurp` even nested inside an anonymous function.

Clause logic runs under an explicitly configured vocabulary: no clock, no `/`
(an exact ratio on the JVM and a float in ClojureScript, so identical source
would diverge between a server and a browser), no I/O, no interop. See
[`src/com/trustblocks/clause.clj`](../src/com/trustblocks/clause.clj) — the
allow-list is meant to be read.

```clojure
(.close node)
```

---

## Part 4 — the same trigger, over HTTP

Everything above is the library. The app exposes one write endpoint over it:
`POST /contracts/:id/trigger`.

Start the app:

```sh
rm -rf storage && bin/clj -M:xtdb-jvm-opts -i dev/seed.clj
```

In a browser, sign in at <http://localhost:8080/signin> with any address; the
six-digit code prints on the seed console, because no mail provider is
configured. To get a cookie jar curl can use, do the same three requests by
hand — the `_biff` paths belong to `com.biffweb/authenticate`:

```sh
curl -sc cookies.txt -o /dev/null http://localhost:8080/signin
curl -sb cookies.txt -c cookies.txt -H 'Origin: http://localhost:8080' \
     -d 'email=you@example.com' http://localhost:8080/_biff/auth/send-code
curl -sb cookies.txt -c cookies.txt -H 'Origin: http://localhost:8080' \
     -d 'email=you@example.com' -d 'code=123456' \
     http://localhost:8080/_biff/auth/verify-code
```

The trigger body is an envelope around the Concerto request:

```sh
curl -b cookies.txt -H 'Content-Type: application/json' \
     -H 'Origin: http://localhost:8080' \
     -X POST http://localhost:8080/contracts/snow-2026-014/trigger \
     -d '{"requestId": "visit-v-4",
          "at":        "2026-03-01T06:00:00Z",
          "request":   {"$class":       "com.trustblocks.snow@1.0.0.VisitReport",
                        "reportId":     "v-4",
                        "$identifier":  "v-4",
                        "$timestamp":   "2026-03-01T06:00:00Z",
                        "visitedAt":    "2026-03-01T06:00:00Z",
                        "inchesFallen": 5.0}}'
```

```json
{"contract":"snow-2026-014",
 "requestId":"visit-v-4",
 "at":"2026-03-01T06:00:00Z",
 "response":{"$class":"com.trustblocks.snow@1.0.0.VisitOutcome",
             "outcome":"PAYABLE",
             "amountDue":{"amount":480.0,"currency":"USD", "...":"..."},
             "...":"..."},
 "state":{"$class":"com.trustblocks.snow@1.0.0.PaymentState",
          "visitsPaid":3,
          "totalPaid":{"amount":1440.0,"currency":"USD", "...":"..."},
          "...":"..."}}
```

Neither of the two fields outside `request` could have gone inside it.
Concerto is closed-world, so an instance carrying a `requestId` its model does
not declare fails validation — and `at` is not a property of the request at
all. It is when the execution takes effect, which is the contract's clock, not
the server's. Both are required, and both are the caller's to decide:

- `at` is the valid time the new state is written under. A server reading its
  own clock here would record a back-dated approval as having happened today,
  and would make the execution unreplayable — the same reason
  `ex/trigger!` takes `now` rather than calling one.

- `requestId` makes the execution at-most-once. Send it twice and the second
  one is refused:

  ```json
  {"error":"duplicate-request",
   "message":"This request has already been executed against this contract",
   "contract":"snow-2026-014",
   "requestId":"visit-v-4",
   "executedAt":"2026-03-01T06:00:00Z"}
  ```

  A trigger is not safe to redo — this one pays the contractor another $480 —
  and a caller whose POST timed out has no other way to find out whether the
  first attempt landed. Ids are scoped to the contract, since the thing being
  prevented is always one contract's action arriving twice.

Failures are a code to branch on and a message to read:

| | |
|---|---|
| `400 invalid-envelope` | no `requestId`, no usable `at`, no `request`, or a `request` with no `$class` |
| `400 invalid-request` | the request is not a valid instance of its `$class` |
| `404 no-such-contract` | |
| `409 duplicate-request` | that `requestId` has already run on this contract |
| `422 clause-refused` | the contract declined — *Already approved by the Board* is an answer, not a failure |
| `422 no-clause` | no logic is stored for this contract's type |
| `501 runtime-unavailable` | the clause's runtime is not implemented here (today: `:typescript`) |
| `500 invalid-clause-output` | the clause produced a response or state that does not validate |

Authentication is the session cookie the pages use, so a script signs in the
way a person does and sends an `Origin` header with it — CSRF protection
applies to every cookie-authenticated POST, which is right for as long as the
credential is a cookie. A machine caller wants a bearer credential instead;
that is not built yet.

---

## What this does not do

- **Render contract text.** Turning a template plus data into readable prose is
  TemplateMark and markdown rendering, which is a JavaScript toolchain. Nothing
  here replaces it.
- **Verify the logic.** The boundary is checked, not the behaviour. A clause that
  computes the wrong amount with the right types passes.
- **Parse CTO without Node.** `cto/load-registry` shells out to Accord's CLI.
  Everything downstream of the EDN needs neither Node nor network.
- **Authenticate anything but a person.** Signin is passwordless email, and the
  trigger endpoint rides the same session cookie. There is no API credential,
  and no mail provider — the code prints to the console.
