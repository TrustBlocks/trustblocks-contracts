# Trustblocks operator runbook

This is the practical local-operator path for the prototype. There is no
provisioning UI yet: contract browsing, clause inspection, and authorization
inspection are web pages; creating cryptographic identities and OfficeGrant
credentials is deliberately still an explicit REPL operation.

## Start with the complete demo

Prerequisites and the JDK/Node constraints are in the root `AGENTS.md`. From a
clean checkout:

```sh
npm install                         # once; Concerto parsing invokes Node
rm -rf storage log                  # only when you want a fresh XTDB database
bin/clj -M:xtdb-jvm-opts -i dev/seed.clj
```

The process remains attached and serves <http://localhost:8080>. Sign in at
`/signin`; the six-digit passwordless code is printed in that same terminal.

The seed creates four email-linked China Grove DID identities and signed
OfficeGrant credentials for UI/testing:

| Person | Email | Office | Demo authority claims |
| --- | --- | --- | --- |
| Franklin Gover | `fgover@chinagrovenc.gov` | Town Manager | `SIGN_CONTRACTS` |
| Pamela Mills | `pmills@chinagrovenc.gov` | Town Clerk | `CERTIFY_MINUTES`, `ATTEST_ORDINANCES` |
| Rebekah Newsome | `rnewsome@chinagrovenc.gov` | Finance Director | `ATTEST_FINANCIALS` |
| Rodney Phillips | `rphillips@chinagrovenc.gov` | Mayor | `SIGN_CONTRACTS`, `ATTEST_ORDINANCES` |

These are **demo policy**, not a claim that job titles automatically confer
these powers under North Carolina law. Only `CERTIFY_MINUTES` is currently
enforced by an application action: sign in as Pamela Mills to use the
certification form on `/contracts/china-grove-gover-2025`.

After signing in, the primary navigation is:

- `/contracts` — contract warehouse and viewer actions;
- `/clauses` — stored SCI/TypeScript clause inventory;
- `/authorizations` — officeholders and signature-verified OfficeGrant VCs.

Template Playground actions open Trustblocks' hosted playground
(trustblocks.github.io/template-playground). To route them to a local
checkout instead (`bin/playground`), follow
`docs/template-playground-webmcp.md` and set
`TRUSTBLOCKS_TEMPLATE_PLAYGROUND_ORIGIN` before starting the JVM.

## Add an officeholder from the running REPL

`dev/seed.clj` leaves you in a Clojure REPL and exposes `seed/node`. Load the
namespaces, ensure the email is not already provisioned, create the DID, then
issue the Town-signed grant:

```clojure
(require '[com.trustblocks.xtdb.identity :as identity]
         '[com.trustblocks.xtdb.certification :as certification])
(import '[java.time Instant])

(def town-did "did:web:trustblocks.com:towns:china-grove")
(def email "new.clerk@chinagrovenc.gov")

;; Provisioning twice creates a different keypair/DID. Always check first.
(def officeholder
  (or (identity/by-email seed/node email)
      (identity/provision-officeholder!
       seed/node
       {:email email
        :name "New Clerk"
        :office "Deputy Town Clerk"})))

(certification/grant-office!
 seed/node
 town-did
 {:officeholder-did (:xt/id officeholder)
  :name "New Clerk"
  :office "Deputy Town Clerk"
  :authority ["CERTIFY_MINUTES"]
  :valid-from (Instant/parse "2026-01-01T00:00:00Z")
  :valid-until (Instant/parse "2027-01-01T00:00:00Z")})
```

Reload `/authorizations` to see the new DID and verified grant. Re-running
`grant-office!` mints another independently valid credential; there is no
deduplication policy for office grants yet.

## Add a Town

A Town is the root of trust for its OfficeGrants. Its did:web value is derived
from the slug and its private key is generated and stored custodially in XTDB:

```clojure
(identity/provision-town!
 seed/node
 {:slug "another-town"
  :name "Another Town"})
```

This prototype stores private keys in plaintext XTDB documents. That is a
known, explicitly limited design for routine municipal attestations; do not
use this runbook as a production key-management procedure.

## Revoke a credential

Find the credential record through `credential/for-subject`, then end its
valid-time window. Revocation preserves what the database believed before the
revocation in system time:

```clojure
(require '[com.trustblocks.xtdb.credential :as credential])

(def did (:xt/id (identity/by-email seed/node email)))
(def grant
  (first (filter #(= certification/office-grant-class
                     (:concerto/class %))
                 (credential/for-subject seed/node did))))

(credential/revoke!
 seed/node
 (:xt/id grant)
 (Instant/parse "2026-06-01T00:00:00Z"))
```

## Verification before sharing changes

```sh
git add -A
bin/clj -M:run code-quality
```

After UI changes, boot from clean storage and exercise the real signin flow.
The contract and authorization pages are session-protected, so an anonymous
`GET /contracts` should redirect to signin rather than return the dashboard.
