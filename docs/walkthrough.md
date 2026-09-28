# A walkthrough

Forms to paste, one at a time, rather than a script that runs past you. Every
output below was captured from an actual run.

It comes in halves, and the split is not arbitrary:

| | runs where | why |
|---|---|---|
| **Concerto → EDN → Malli** | babashka | pure data; no database |
| **storage and execution** | JVM REPL | XTDB is a JVM library and does not run under babashka |
| **the same execution, over HTTP** | curl | Part 4, once the app is running |
| **who may attest, and signing what they attest** | JVM REPL, then curl | Part 5 — DIDs, Verifiable Credentials, and certification |

That is the same seam the code is built along: `concerto-clj` knows nothing about
storage, and the XTDB adapter supplies the parts that are XTDB's problem. The
web app adds nothing to the model — Part 4 is Part 3 reached through a URL.

The example is a small self-contained municipal contract,
[`docs/examples/snow-removal.cto`](examples/snow-removal.cto) — a plowing
agreement that pays a fixed rate per visit when snowfall clears a trigger depth.
It imports nothing, so it parses without fetching anything.

Install the package-lock-pinned Accord toolchain, then point bare babashka
(the only command here that does not go through `bin/clj`) at its local CLI:

```sh
npm install
export CONCERTO_CLI="$PWD/node_modules/.bin/concerto"
```

`bin/clj` sets this automatically for every JVM command. The local CLI is
4.2.x and uses Concerto Core/CTO 5; do not rely on an older globally installed
`concerto` binary.

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
`com.trustblocks.clause`, in trustblocks-templates'
[`runtime/`](https://github.com/TrustBlocks/trustblocks-templates/blob/main/docs/runtime.md) — the
allow-list is meant to be read. The same code runs in the browser as
`trustblocks-logic.js`, with the same answers.

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
| `409 out-of-order` | the request would change state before a state change already recorded (`at` is too early) |
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

## Part 5 — who may say so: identity, credentials, attestation

Parts 1–4 answer *what did we agree and what has happened under it*. Municipal
work also needs *who says so, with what authority*: the Town Clerk certifying
that the council voted, then that the minutes recording the vote were
approved. Part 5 builds that on the real China Grove town-manager contract,
the [`manager-employment-contract`](https://github.com/TrustBlocks/trustblocks-templates/blob/main/templates/manager-employment-contract/)
template from TrustBlocks/trustblocks-templates.

Three layers, each only knowing about the one below it:

| layer | namespace | knows about |
|---|---|---|
| **DIDs and VCs** | `com.trustblocks.did.*` | keys, signatures, JWTs — no database, no app |
| **identity and credential ledger** | `xtdb/identity.clj`, `xtdb/credential.clj` | who exists, what was issued to whom, over what valid time |
| **authorization** | `xtdb/certification.clj` | whether this person may certify this, and whether the contract agrees it is true |

Start a fresh REPL:

```
bin/clj -M:xtdb-jvm-opts
```

```clojure
(require '[manager-employment-demo :as me]   ; runs its own demo on a throwaway node first
         '[com.trustblocks.xtdb.identity :as identity]
         '[com.trustblocks.xtdb.credential :as credential]
         '[com.trustblocks.xtdb.certification :as cert]
         '[com.trustblocks.xtdb.execute :as ex]
         '[com.trustblocks.xtdb.store :as store]
         '[com.trustblocks.did.key :as did-key]
         '[com.trustblocks.did.interop :as interop]
         '[com.trustblocks.did.vc :as vc]
         '[xtdb.node :as xt.node])
(import '[java.time Instant])

(def node (xt.node/start-node))
(me/load! node)                                 ; the template: model (incl. AttestationRequest/Response), clause
(store/put-model! node "model/identity.cto")    ; OfficeGrant, Attestation
```

The contract is loaded but **not yet approved by the Board**. That matters
below.

### Two kinds of identity

A Town gets a `did:web` — an identifier that resolves to a document at a URL
the Town controls, and so can move to the Town's own domain later:

```clojure
(def town (identity/provision-town! node {:slug "china-grove" :name "China Grove"}))
(:xt/id town)
;; => "did:web:trustblocks.com:towns:china-grove"
```

An officeholder gets a `did:key`, where the identifier *is* the public key and
there is no document to host:

```clojure
(def clerk (identity/provision-officeholder!
            node {:email "pmills@chinagrovenc.gov" :name "Pamela Mills" :office "Town Clerk"}))
(:xt/id clerk)
;; => "did:key:z6MkwbnnGGG3zME2S6dNiWrH7mVzZcpBBqt1TgnWzsByhqbf"
;; (yours will differ: every provisioning generates a fresh keypair)

(= (seq (:identity/public-key clerk))
   (seq (did-key/public-key (:xt/id clerk))))
;; => true
```

The raw 32-byte Ed25519 key decodes straight back out of the identifier. The
`z6Mk` prefix is the multibase/multicodec marker for an Ed25519 key, the same
in every `did:key` implementation. Base58 is hand-rolled
([`did/base58.clj`](../src/com/trustblocks/did/base58.clj)) and tested against
the spec's published vectors, rather than pulled in from a Bitcoin library.

The email is how a signed-in session finds this identity again. There is no
separate officeholder login: the session *is* the officeholder.

Keys are **custodial and stored in plaintext** in XTDB. That is deliberate
and scoped to town staff signing routine attestations, not a system meant to
resist a hostile database operator — see `xtdb/identity.clj`.

### Authority is a credential, not a role column

Being provisioned grants nothing. Authority is an `OfficeGrant`, a Verifiable
Credential the Town signs and issues to the Clerk's DID, valid for a term:

```clojure
(def grant
  (cert/grant-office! node (:xt/id town)
                      {:officeholder-did (:xt/id clerk)
                       :name "Pamela Mills" :office "Town Clerk"
                       :authority ["CERTIFY_MINUTES" "ATTEST_ORDINANCES"]
                       :valid-from  (Instant/parse "2025-07-01T00:00:00Z")
                       :valid-until (Instant/parse "2029-06-30T00:00:00Z")}))
;; => "eyJhbGciOiJFZERTQSJ9.eyJpc3MiOiJkaWQ6d2ViOnRydXN0YmxvY2tz....jV5HF4dh..."
```

The credential *is* that string: a VC-JWT, signed EdDSA. Its claims:

```clojure
(interop/jws-claims grant)
;; => {:iss "did:web:trustblocks.com:towns:china-grove",
;;     :sub "did:key:z6MkwbnnGGG3zME2S6dNiWrH7mVzZcpBBqt1TgnWzsByhqbf",
;;     :vc {"@context"   ["https://www.w3.org/ns/credentials/v2"],
;;          "type"       ["VerifiableCredential" "OfficeGrant"],
;;          "issuer"     "did:web:trustblocks.com:towns:china-grove",
;;          "validFrom"  "2025-07-01T00:00:00Z",
;;          "validUntil" "2029-06-30T00:00:00Z",
;;          "credentialSubject" {"id"        "did:key:z6MkwbnnGGG3zME2S6dNiWrH7mVzZcpBBqt1TgnWzsByhqbf",
;;                               "name"      "Pamela Mills",
;;                               "office"    "Town Clerk",
;;                               "authority" ["CERTIFY_MINUTES" "ATTEST_ORDINANCES"]}}}
```

Only `credentialSubject` is modeled in Concerto
([`model/identity.cto`](../model/identity.cto)), and it is validated against
that model before anything is signed. The W3C envelope around it is
open-world JSON-LD by design, so it is built directly rather than forced into
a closed-world schema.

**Why VC-JWT rather than Linked-Data Proofs:** the obvious Java library's
LD-Proof path pulls in bitcoinj, web3j, a native libsodium binding and a third
Maven repository — a full blockchain stack for a project with no blockchain
requirement. VC-JWT is a spec-recognized alternative built on
`nimbus-jose-jwt` plus Google Tink, which Nimbus's Ed25519 signer requires. [`did/vc.clj`](../src/com/trustblocks/did/vc.clj)'s
docstring has the full trace.

Verifying needs the *issuer's* key, resolved independently. Never trust a
key carried inside the credential itself:

```clojure
(vc/verify (:identity/public-key town) grant (:xt/id town) (:xt/id clerk)
           (Instant/parse "2026-01-01T00:00:00Z"))
;; => {"id" "did:key:z6Mkwbnn...", "name" "Pamela Mills", "office" "Town Clerk",
;;     "authority" ["CERTIFY_MINUTES" "ATTEST_ORDINANCES"]}

(vc/verify (:identity/public-key town) grant (:xt/id town) (:xt/id clerk)
           (Instant/parse "2030-01-01T00:00:00Z"))
;; => {:error "expired", :valid-until #xt/instant "2029-06-30T00:00:00Z"}

(vc/verify (:identity/public-key clerk) grant (:xt/id town) (:xt/id clerk)
           (Instant/parse "2026-01-01T00:00:00Z"))
;; => {:error "signature does not verify"}
```

The last one is the Clerk's own key trying to pass as the Town's. The
credential is also stored in XTDB, with its `validFrom`/`validUntil` as the
row's **valid time** rather than as data columns. That makes "what authority
did she hold on March 4th?" the same kind of query as Part 3's "what was the
state on February 10th?".

### Certifying: the contract decides, the host signs

`cert/certify!` is the one place authority to act is decided. Four things
happen, in this order:

1. **Resolve** the officeholder from the session's email.
2. **Authorize** against a verified `OfficeGrant` in force at `valid-from`,
   *the event's own time*, not now. A Clerk certifying a months-old vote needs
   to have held the office then; whether she holds it today is a different
   question.
3. **Ask the contract.** Run `ex/trigger!` with a standard
   `AttestationRequest` ([`attestation.cto`](https://github.com/TrustBlocks/trustblocks-templates/blob/main/shared/model/attestation.cto))
   and the certification just verified. The contract's lifecycle decides
   whether an attestation can happen yet, and under which authority; its
   sandboxed clause decides what is said.
4. **Sign** the clause's *response*, not the caller's input, as an
   `Attestation` VC with the officeholder's key.

Try it before the Board has approved anything:

```clojure
(def vote-meeting (Instant/parse "2025-12-02T00:00:00Z"))
(def term-end     (Instant/parse "2029-06-30T00:00:00Z"))

(defn attest
  "The Clerk certifying the council's vote, unless `overrides` say otherwise."
  [overrides]
  (cert/certify! node (merge {:email "pmills@chinagrovenc.gov"
                              :statement "The council voted to approve the manager's employment contract"
                              :document-hash "sha256:china-grove-vote-2025-12-02"
                              :contract-id me/contract-id
                              :required-authority "CERTIFY_MINUTES"
                              :valid-from vote-meeting :valid-until term-end}
                             overrides)))

(attest {})
;; AttestationRequest cannot happen before the lifecycle has begun
;; {:trustblocks/error :no-transition}
```

The Clerk's authority checked out; the *contract* refused. Its lifecycle
([`lifecycle.json`](https://github.com/TrustBlocks/trustblocks-templates/blob/main/templates/manager-employment-contract/lifecycle.json))
begins with the Board's approval, and allows an attestation only after it.

The approval itself is certified too. Nothing can approve the contract but an
Attestation under `CERTIFY_MINUTES`, so it is the Clerk, recording the
Council's vote, who approves it. `me/approve!` does that through
`com.trustblocks.xtdb.advance`, which takes the authority from the lifecycle:

```clojure
(me/approve! node "pmills@chinagrovenc.gov")

(def vote (attest {}))
(:vc (interop/jws-claims vote))
;; => {"type"       ["VerifiableCredential" "Attestation"],
;;     "issuer"     "did:key:z6MkwbnnGGG3zME2S6dNiWrH7mVzZcpBBqt1TgnWzsByhqbf",
;;     "validFrom"  "2025-12-02T00:00:00Z",
;;     "validUntil" "2029-06-30T00:00:00Z",
;;     "credentialSubject"
;;     {"id"           "did:key:z6MkwbnnGGG3zME2S6dNiWrH7mVzZcpBBqt1TgnWzsByhqbf",
;;      "documentHash" "sha256:china-grove-vote-2025-12-02",
;;      "statement"    "The council voted to approve the manager's employment contract",
;;      "contractId"   "china-grove-gover-2025",
;;      "certifiedAt"  "2026-09-24T13:22:55.388622Z"}, ...}
```

The refused attempt did not consume its request id, so the retry succeeded.

Two times appear, and they mean different things:

- **`validFrom`** is when the vote happened. It is the credential's valid
  time.
- **`certifiedAt`** is when the Clerk actually clicked certify: wall-clock
  time, added by host code.

The clause could not have supplied either `certifiedAt` or the signer's DID:
its sandbox has no clock and no identity (Part 3), and that is what keeps an
attestation replayable. `documentHash` names the document without including
it, so the credential can be shared when the minutes themselves cannot.

The attestation is also an ordinary execution in the contract's audit trail,
next to the Board approval:

```clojure
(mapv #(select-keys % [:at :request-id]) (ex/history node me/contract-id))
;; => [{:at "2025-12-02T00:00:00Z"
;;      :request-id "advance:china-grove-gover-2025:BoardApprovalRequest:2025-12-02T00:00:00Z"}
;;     {:at "2025-12-02T00:00:00Z"
;;      :request-id "attestation:china-grove-gover-2025:CERTIFY_MINUTES:sha256:china-grove-vote-2025-12-02:2025-12-02T00:00:00Z"}]
```

### Evidence: the minutes rest on the vote

At the next meeting, the Clerk certifies that the minutes were approved,
naming the vote certification as `evidence`:

```clojure
(attest {:statement "Minutes of the December 2 meeting were approved as written"
         :document-hash "sha256:china-grove-minutes-2026-01-06"
         :valid-from (Instant/parse "2026-01-06T00:00:00Z")
         :evidence vote})

(mapv #(select-keys % ["valid-from" "statement" "evidence"])
      (cert/attestations-for-contract node me/contract-id))
;; => [{"valid-from" #xt/instant "2025-12-02T00:00:00Z",
;;      "statement"  "The council voted to approve the manager's employment contract"}
;;     {"valid-from" #xt/instant "2026-01-06T00:00:00Z",
;;      "statement"  "Minutes of the December 2 meeting were approved as written",
;;      "evidence"   "eyJhbGciOiJFZERTQSJ9.eyJpc3MiOiJkaWQ6a2V5..."}]
```

`evidence` sits beside `credentialSubject` rather than inside it. The vote
supports this claim; it is not part of what is being claimed. It carries the
whole earlier JWT, so a verifier holding only the minutes credential can check
the vote credential too.

### Refusals

Each refusal carries a `:trustblocks/error` tag to branch on, the same
convention Part 4's HTTP codes come from:

```clojure
(attest {})                                   ; the same certification again
;; This request has already been executed against this contract
;; {:trustblocks/error :duplicate-request, ...}

(attest {:required-authority "SIGN_CONTRACTS"})
;; This office grant does not include the required authority
;; {:trustblocks/error :missing-authority, :required "SIGN_CONTRACTS",
;;  :has #{"ATTEST_ORDINANCES" "CERTIFY_MINUTES"}}

(attest {:valid-from (Instant/parse "2025-03-01T00:00:00Z")})
;; No office grant in force at that time
;; {:trustblocks/error :no-office-grant, :at #xt/instant "2025-03-01T00:00:00Z"}

(attest {:email "nobody@example.gov"})
;; No officeholder identity for this email
;; {:trustblocks/error :no-such-officeholder}
```

The duplicate is caught by the request id `certify!` derives from the contract,
authority, document hash and event time: Part 3's at-most-once guard, reused.
A retried form post cannot mint a second credential for the same act.

### Revocation is bitemporal

Suppose the Town revokes the Clerk's grant on June 1st, 2026, three years
early:

```clojure
(def grant-id
  (->> (credential/current-for-subject node (:xt/id clerk))
       (filter #(= cert/office-grant-class (:concerto/class %)))
       first :xt/id))

(credential/revoke! node grant-id (Instant/parse "2026-06-01T00:00:00Z"))
```

The signed JWT still says `validUntil 2029-06-30`, and that cannot change
without re-signing. What changes is the stored row's valid time, which
`revoke!` closes with `:delete-docs` from June 1st. Nothing is re-written with
an earlier end, so system time still shows the grant was believed valid until
the revocation was recorded.

```clojure
(attest {:statement "Budget amendment minutes" :document-hash "sha256:y"
         :valid-from (Instant/parse "2026-05-05T00:00:00Z")})
;; => "eyJhbGciOiJFZERTQSJ9..."          ; May: before the revocation, still authorized

(attest {:statement "Ordinance vote" :document-hash "sha256:x"
         :valid-from (Instant/parse "2026-07-07T00:00:00Z")})
;; No office grant in force at that time
;; {:trustblocks/error :no-office-grant, :at #xt/instant "2026-07-07T00:00:00Z"}
```

Both calls happen *now*, well after June. Revoking authority going forward
leaves the Clerk's past acts valid. The operator's ledger shows the difference
between a revoked grant and one that simply ran out:

```clojure
(mapv #(select-keys % [:status :valid-until :verified])
      (:grants (first (cert/signature-authorizations node))))
;; => [{:status :revoked, :valid-until #xt/instant "2026-06-01T00:00:00Z", :verified true}]
```

```clojure
(.close node)
```

### The same, from the app

`dev/seed.clj` provisions China Grove, four officeholders with `OfficeGrant`s
(only the Clerk, `pmills@chinagrovenc.gov`, holds `CERTIFY_MINUTES`), and
the vote-then-minutes pair above. Start it as in Part 4, and wait for
`Seeded.` on the console before signing in: the app answers requests before
the seed has provisioned anyone.

- **`/authorizations`** is the read-only ledger: each officeholder, their DID,
  and each `OfficeGrant` verified against the Town's key and badged *Active*,
  *Not yet valid*, *Expired*, *Revoked* or *Verification failed*.
- **`/contracts/china-grove-gover-2025`** lists the contract's certifications
  and, for a signed-in officeholder, a form that posts to
  `POST /contracts/:id/certify`.

Part 4's cookie jar belongs to `you@example.com`, who holds no office, so a
certification posted with it is refused. Sign in again as the Clerk, the same
three requests with her address and the new code from the console:

```sh
curl -sc clerk.txt -o /dev/null http://localhost:8080/signin
curl -sb clerk.txt -c clerk.txt -H 'Origin: http://localhost:8080' \
     -d 'email=pmills@chinagrovenc.gov' http://localhost:8080/_biff/auth/send-code
curl -sb clerk.txt -c clerk.txt -H 'Origin: http://localhost:8080' \
     -d 'email=pmills@chinagrovenc.gov' -d 'code=123456' \
     http://localhost:8080/_biff/auth/verify-code
```

Then certify:

```sh
curl -b clerk.txt -H 'Origin: http://localhost:8080' \
     --data-urlencode 'statement=The council adopted the FY2027 budget ordinance' \
     -d 'documentHash=sha256:china-grove-budget-2026-06-02' \
     -d 'validFrom=2026-06-02T19:00:00Z' \
     -o /dev/null -w '%{http_code} %{redirect_url}\n' \
     http://localhost:8080/contracts/china-grove-gover-2025/certify
```

```
303 http://localhost:8080/contracts/china-grove-gover-2025
```

Unlike the JSON trigger endpoint, this is a browser form, so success and
refusal are both a `303` back to the contract page. A refusal carries its
message on the query string:

```
303 .../contracts/china-grove-gover-2025?certify-error=This+request+has+already+been+executed+against+this+contract
303 .../contracts/china-grove-gover-2025?certify-error=No+office+grant+in+force+at+that+time
```

The form always asks for `CERTIFY_MINUTES`. The contract page now lists the
new certification with the two seeded ones:

```
Certifications (3)
2025-12-02T00:00:00Z -- Pamela Mills, Town Clerk
  The council voted to approve the manager's employment contract
2026-01-06T00:00:00Z -- Pamela Mills, Town Clerk
  Minutes of the December 2 meeting were approved as written
  Based on an earlier certification.
2026-06-02T19:00:00Z -- Pamela Mills, Town Clerk
  The council adopted the FY2027 budget ordinance
```

Provisioning a town, an officeholder or a grant has no page. It is a REPL
procedure, written up in
[`docs/operator-runbook.md`](operator-runbook.md).

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
- **Publish anything a verifier outside this database can check.** The Town's
  `did:web` document is not served, and there is no `credentialStatus`
  endpoint: revocation is real, but it is visible only to queries against this
  XTDB node.
- **Protect signing keys from the database.** Town and officeholder private
  keys are custodial and stored in plaintext. The documented next step is
  encrypting them at rest with a server-held secret.
- **Walk evidence chains.** `evidence` is stored and displayed but never
  verified recursively, and nothing checks a chain for depth or cycles.
