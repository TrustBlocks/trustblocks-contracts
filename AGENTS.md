# AGENTS.md — handoff memo

Written for a coding agent with zero prior context on this repo. Read this
fully before making changes; it front-loads decisions you would otherwise
re-derive or re-litigate.

## 1. What this project is

Trustblocks is a platform to run and warehouse municipal government
contracts: real agreements (a town manager's employment contract, a
street-closing proceeding) modeled in Accord Project's Concerto schema
language, executed against Clojure/SCI clause logic, and stored bitemporally
in XTDB 2.1.0 so "what did we agree, and what did we believe on date X" are
both answerable. It targets municipal staff (clerks, managers, councils) who
need contracts that are legally faithful to real signed documents, auditable,
and cryptographically attestable — not a general contract-management SaaS.
Status: **working prototype**, single-operator scale, no production
deployment yet, two real contracts seeded (a synthetic snow-removal contract
and the real China Grove town-manager employment contract), one feature
domain (contract execution + officeholder attestation) built end-to-end from
model to UI. Read on before assuming anything about scope beyond that.

## 2. Architecture & layout

**Start reading at `src/com/trustblocks/xtdb/execute.clj`** (the contract
execution engine) and `src/com/trustblocks/xtdb/certification.clj` (the
newest, most involved subsystem — officeholder attestation running as a
standard clause through `execute/trigger!`). Between them they explain the whole
project's central idea: a contract's clause is untrusted, sandboxed logic;
everything bitemporal and everything cryptographic happens in trusted host
code around it.

```
deps.edn                  Clojure CLI deps + aliases (see §7)
bb.edn                    babashka deps for the no-database half of docs/walkthrough.md
bin/clj                   ALWAYS use this, never bare `clj` — pins JAVA_HOME to JDK 21 (XTDB needs 21-24)
bin/template-conformance  Node/Accord JS toolchain check that templates load+draft correctly (dev-only)
repl.clj                  REPL scratch: (get-node), (refresh)
resources/config.edn      Biff config (port, cookie secret, css paths, main-ns, nrepl-port)
tests.edn                 kaocha config
package.json              pinned Accord JS toolchain: local Concerto 5 CLI + template conformance

src/com/trustblocks/
  trustblocks.clj          app entrypoint: start/stop/-main, boots `modules` in `start-order`, launches nREPL
  modules.clj              THE list of every subsystem actually wired into the running app — check here first
  routes.clj               every URL path (defpath) + contract-id (reads :uri, not :path-params — see its docstring)
  clause.clj               the SCI sandbox contract clauses run in — read before touching ANY clause logic
  contract.clj             cross-contract party querying/projection — NOT in modules.clj, exploratory only

  app/                     HTTP handlers + Biff modules
    auth.clj                 passwordless email signin (com.biffweb/authenticate); no mail provider, prints codes
    landing.clj               "/"
    contracts.clj             contract dashboard + show/model/document views + Certifications section/form
    clauses.clj               read-only inventory of stored clause logic and runtimes
    authorizations.clj        read-only DID identity + verified OfficeGrant credential ledger
    trigger.clj               POST /contracts/:id/trigger — JSON envelope, for scripts/machines
    certify.clj               POST /contracts/:id/certify — HTML form, for a signed-in officeholder

  lib/
    ui.clj                    page chrome (chassis/hiccup), on-error
    middleware.clj            wrap-signed-in

  model/                    (NOTE: src/com/trustblocks/model/, distinct from the repo-root model/ below)
    contract.clj               coarse-grained biff.graph resolvers (:contract/data, :state, :history)
    graph.clj                  generates one biff.graph resolver PER Concerto property from the live registry
    playground.clj             links out to Accord's Concerto/Template Playgrounds — read its docstring before
                                touching anything playground-related; the single-model limit is Template
                                Playground's, not ours, and is fully explained there

  did/                      DID/VC primitives — no XTDB, no app knowledge, pure crypto/data
    base58.clj                 hand-rolled Base58(btc), tested against real spec vectors
    key.clj                    did:key derive/resolve
    interop.clj                the ONLY namespace touching java.security/com.nimbusds.*/foundation.identity.did
    vc.clj                     W3C Verifiable-Credential-as-JWT issue/verify (not Linked-Data-Proofs)

  xtdb/                      storage + domain logic — the real backend
    keys.clj                   Concerto instance <-> XTDB record key mapping (XTDB mangles $ and camelCase)
    store.clj                  model ingestion (put-model!), instance storage, Malli validation, registry rebuild
    execute.clj                trigger! — runs a stored SCI clause, records Request/Response + State bitemporally
    identity.clj                the identity ledger: provision-town!/provision-officeholder!, by-did, by-email
    credential.clj              Verifiable Credential storage: issue!, for-subject, for-contract, revoke!
    certification.clj           grant-office!, certify! (runs execute/trigger! then signs the response as a VC),
                                 attestations-for-contract — the newest and most involved file, read it fully

  template/
    archive.clj                ingest a .cta archive (or unpacked dir) into XTDB
    audit.clj                  staged, human-readable validation of a template's ingestibility
    render.clj                 narrow TemplateMark-subset renderer for Contract View (NOT Accord's full grammar)

model/                     repo-root — cross-cutting Concerto models not owned by one template
  identity.cto               Authority enum, OfficeGrant/Attestation concepts (VC credentialSubject shapes)
  attestation.cto            AttestationRequest/AttestationResponse — the standard pair a contract's own clause
                             adopts for attestation support (see certification.clj)

docs/
  walkthrough.md             narrative tour, Parts 1-5 (Concerto as data -> storing -> executing -> HTTP trigger
                             -> identity, DID/VC and attestation); every form is meant to be pasted and run
  operator-runbook.md         local operations: seed/signin, demo identities, REPL provisioning/revocation
  model-repository.md        municipal model namespace conventions, reuse-by-inheritance rule, parked publishing plan
  concerto-xtdb-mapping.md   original research notes on the Concerto<->XTDB mapping problem xtdb/keys.clj solves
  templatemark-conformance.md  the case for round-tripping templates through Accord's own JS toolchain
  template-playground-webmcp.md  local exploration of Accord's experimental webmcp-poc branch
  examples/
    snow-removal.cto           synthetic, walkthrough-only contract — deliberately zero imports
    manager-employment-contract/  the REAL China Grove contract: model/ (6 namespaces), sample.json, text/grammar.tem.md
    late-delivery.cta          Accord's own reference template — used only as a cross-check oracle

dev/                       runnable scripts; on the classpath for both direct execution and test fixtures
  seed.clj                   boots the real app + seeds both contracts + the Clerk + both certifications —
                             THE way to get a working local instance, see §7
  manager_employment_demo.clj  loads the real employment contract + its clause; exposes approve!
  snow_removal_demo.clj        the synthetic snow-plowing contract + three visit triggers
  certification_demo.clj       provisions Town+Clerk+OfficeGrant, certifies vote then minutes;
                             depends on manager_employment_demo.clj (needs a real, approved contract to attest to)
  late_delivery_demo.clj       cross-checks the Clojure clause runtime against Accord's own known-good answer
  conformance.cljs             what bin/template-conformance runs
  trigger_demo.clj, ingest_demo.clj, contract_queries.clj, audit_library.clj,
  accord_probe.clj, demo.clj   smaller/older exploratory scripts

test/com/trustblocks/      mirrors src/, kaocha-run — see §4 for what's covered and what isn't
```

**Outside this repo, load-bearing:** `io.github.TrustBlocks/concerto-clj`
(pinned by git SHA in `deps.edn`) is where `com.trustblocks.concerto.metamodel`,
`com.trustblocks.concerto.cto`, and `com.trustblocks.concerto.malli` actually
live — the Concerto parser/metamodel/Malli-schema-compiler this whole app is
built on. It is a separate GitHub repo under the same org, not vendored here.
If you need to change how Concerto parsing or schema compilation works, you
are probably in the wrong repo.

## 3. Key decisions & rationale

- **Concerto owns schema/validation; XTDB owns storage, bitemporal history,
  and its own types.** Every write funnels through `xtdb/store.clj`'s
  `put-instance!`/`put-model!`, which resolves a Malli schema from `$class`
  and validates first — writing over the Postgres wire directly bypasses all
  of this, so never do that.
- **`xtdb/keys.clj` exists because XTDB silently mangles keys**: `$class` ->
  `/class` (namespace-separator collision), camelCase -> all-lowercase
  (irreversible), and two different Concerto field names can collide onto
  the same XTDB column. This is why Concerto instances are stored/queried as
  plain string-keyed maps at the model layer and only translated to XTDB's
  keyword-and-kebab-case world at the storage boundary — never invent a
  second translation path.
- **A contract's clause is a pure, sandboxed function of `(data request state
  now)`** (`clause.clj`), interpreted via SCI with an explicit whitelist —
  no clock, no `/` (JVM exact-ratio vs. ClojureScript float divergence), no
  network, no XTDB access, no identity. This is deliberate and load-bearing:
  it is what makes an execution replayable and what makes it safe to store
  and re-run contract-authored logic at all. **Never add a host function to
  the vocabulary without treating it as a security review** — every addition
  widens what any stored clause can do.
- **Officeholder attestation runs as a clause too, but signing never enters
  the sandbox.** `certification.clj/certify!` checks the officeholder's
  authority (host code, against a signed `OfficeGrant`), then calls
  `execute/trigger!` with a standard `AttestationRequest` — the CONTRACT's
  own clause decides whether the claim is true right now (e.g. "only if the
  Board already approved this") — then `certify!` signs *the clause's
  response*, not the caller's raw input, as a Verifiable Credential. The
  clause cannot know who is signing or when they clicked, by design (no
  identity, no clock in the sandbox); those two facts (`did`, `certifiedAt`)
  are added by trusted host code after the clause answers. This was a
  deliberate redesign this session — an earlier version had `certify!`
  bypass `execute/trigger!` entirely, reasoning that a council vote has "no
  parties, no mutual assent" and so isn't a contract execution; that
  reasoning was correct about the Concerto Request/Response *ceremony* but
  wrong to conclude attestation should skip the clause/audit-trail mechanism
  altogether. Do not revert to bypassing `trigger!` without re-deriving why.
- **VC-JWT, not Linked-Data-Proofs.** `com.danubetech/verifiable-credentials-java`
  (the obvious library) was evaluated and rejected: its LD-Proof path
  requires `org.bitcoinj:bitcoinj-core`, `org.web3j:crypto`, a native
  libsodium binding, and a third Maven repo (JitPack) — a full
  Bitcoin/Ethereum stack for a project with no blockchain requirement. VC-JWT
  (a JWT with a `vc` claim) is a complete, spec-recognized alternative built
  entirely on `nimbus-jose-jwt` + `com.google.crypto.tink/tink` (Nimbus's own
  Ed25519 implementation is Tink-backed regardless). `did-common-java` (DID
  *documents* only, Danube Tech's own repo, no blockchain) is kept. Base58 is
  hand-rolled (`did/base58.clj`) rather than pulled from bitcoinj for the same
  reason. See `did/vc.clj`'s namespace docstring for the full trace.
- **Officeholder/Town keys are custodial, stored in plaintext in XTDB**
  (`xtdb/identity.clj`). Deliberate, documented, explicitly scoped to "town
  staff signing routine municipal attestations," not a system meant to
  resist a hostile database operator. If stakes ever rise, the documented
  next step is encrypting `:identity/private-key` at rest with a server-held
  secret — not a rewrite, since nothing above that table ever sees the
  storage format, only a keypair map.
- **An officeholder's identity is found by the email they sign in with**
  (`identity/by-email`) — there is no separate officeholder login/password.
  The signed-in session IS the officeholder, for authorization purposes.
- **Jurisdiction fields are shared by inheritance, not composition**
  (`MunicipalAgreement`, see `docs/model-repository.md`). A nested
  `Jurisdiction` concept was rejected: TemplateMark can only reach a nested
  concept via `{{#with}}`, which is block-level and injects paragraph breaks
  mid-sentence, and the real contract uses e.g. `{{municipalityType}}` inside
  sentences ~20 times. Concerto flattens inheritance into one tagged object,
  so inherited fields stay addressable like local ones. Rule: **if a field
  appears in template prose, it must be on the root concept or inherited
  into it; composition is fine for anything the prose never names.**
- **Template Playground's "only one model" limitation is the external
  tool's, not a defect in our modeling.** Verified by direct comparison
  against Accord's own reference template (`docs/examples/late-delivery.cta`),
  which uses the identical extends-base-Contract-and-imports-a-domain-namespace
  pattern. Left as documented, known behavior (`model/playground.clj`'s
  docstring) rather than flattening our own models to fit an external
  tool's link-size limit — re-check whether Accord's own playground has
  improved before concluding the only fix is self-hosting one.
- **The model registry (`registry-from-xtdb`) is global and flat** — every
  `.cto` ever `put-model!`'d into a node is visible to every query,
  regardless of which contract or feature it was loaded for. This is why
  `model/identity.cto` and `model/attestation.cto` live at the repo root
  rather than inside any one template's directory, and why test fixtures
  must load every model a clause's Request/Response types reference.
- **A model-publishing repo (`models.trustblocks.com`) is a deliberately
  parked, separate decision** — not a folder in this repo, so it can
  contribute back to Accord's own model library independently later. Not
  started. Don't build it without asking; see `docs/model-repository.md`.

## 4. Conventions to follow

**Match existing code. Do not restructure, rename, or reformat files you
aren't otherwise changing — `cljfmt` output is already the enforced style,
not a starting point to improve on.**

- **Docstrings carry the design rationale, not comments.** Nearly every
  namespace and public function has a long-form docstring explaining *why*,
  including dead ends already ruled out. There are essentially no `TODO`/
  `FIXME` comments anywhere in this codebase — read the docstring before
  assuming something is unconsidered.
- **Concerto instances are string-keyed maps** (`{"$class" ... "fieldName"
  ...}`) everywhere above the XTDB storage boundary. Never introduce a
  keyword-keyed representation of Concerto data outside `xtdb/keys.clj`.
- **Errors that a caller should branch on carry a `:trustblocks/error`
  keyword tag in `ex-data`**, established in `execute.clj` and now also used
  by `certification.clj`. Web handlers (`app/trigger.clj`, `app/certify.clj`)
  dispatch on the tag, never on the exception message string. Follow this
  for any new refusal a caller needs to distinguish.
- **Dates are ISO-8601 strings on the wire, `java.time.Instant` in Clojure
  code, and never a raw JVM date inside a sandboxed clause** — clauses
  receive/return DateTime fields as strings (`clause.clj`'s whole design
  point is a clause never holds a platform date object). `execute/->instant`
  is the one sanctioned string->Instant conversion; don't invent another.
- **Bitemporal by construction, not by convention**: use XTDB's own
  `:valid-from`/`:valid-to`/`:for-valid-time` rather than an `effectiveAt`
  application column, and use `:delete-docs` with its own `:valid-from` to
  end a document's validity early rather than re-`:put-docs`-ing an earlier
  `:valid-to` — the latter loses the fact that it was once believed valid
  through system time.
- **XTQL gotchas** (real, previously hit, worth re-stating): projected
  columns must be **symbols**, not keywords; literal-value bindings belong in
  `from`'s binding pattern, not in `:args`, or nothing actually filters; a
  bare `$`-prefixed key (`:$class`) is silently renamed by XTDB — use the
  `:concerto/class` mapping `xtdb/keys.clj`/`xtdb/credential.clj` already
  establish, not a new one.
- **Testing pattern for library code**: `with-open [node (xt.node/start-node)]`
  against a fresh in-memory node per `deftest`, calling functions directly —
  no HTTP layer, no mocking. See `test/com/trustblocks/xtdb/credential_test.clj`
  or `identity_test.clj` for the template.
- **Testing pattern for HTTP handlers**: call the handler function directly
  with a plain ctx map (`{:biff.xtdb/node node :session {...} :params {...}}`),
  no real server — see `test/com/trustblocks/app/certify_test.clj` and
  `auth_test.clj`. This exists because a real bug (`java.time.Instant`
  doesn't support a calendar `Period`) was caught only by a manual curl POST
  and NOT by any earlier unit test — write the handler-level test, don't
  rely on manual verification alone.
- **Coverage is uneven, intentionally not backfilled wholesale**: DID/
  identity/credential/certification/certify (the newest code) have real unit
  tests. `template/render.clj`, `model/graph.clj`, `app/trigger.clj`,
  `app/contracts.clj` do not — they're exercised by the `dev/*_demo.clj`
  scripts and manual curl instead. Don't assume test presence maps to code
  age or importance.
- **One PR per coherent increment**, each independently mergeable, each with
  its own "what/why/verification" description — this repo's entire history
  (14 merged PRs) follows that shape. Don't bundle unrelated changes.
- **`bin/clj -M:run code-quality` only lints/formats `git ls-files`-tracked
  files** (`project-files` in Biff's own `tasks/impl/util.clj` shells out to
  `git ls-files`). A new, not-yet-`git add`-ed file is silently skipped —
  `git add` before running code-quality, or it will pass while your new file
  has real lint errors.

## 5. Current state

**Done and verified working:**
- Concerto model ingestion (`.cto` files and `.cta` archives) into XTDB,
  Malli-validated, with full model round-trip from XTDB alone.
- Contract execution (`execute/trigger!`): Request/Response clauses in
  Clojure/SCI, bitemporal state, at-most-once via request-id, full audit
  trail. Proven on two real contracts (snow-removal's visit-report/payout
  cycle; the employment contract's Board-approval).
- Signed-in operations UI: contract dashboard and show (data/current-state/
  history/certifications), model (fields + native Concerto source), document
  (TemplateMark-subset prose + Word-export stub), stored clause inventory,
  and a signature-authorization ledger that verifies OfficeGrant VCs.
- Passwordless email signin (console-printed codes; no real mail provider).
- DID (`did:key`, minimal `did:web`) + Verifiable Credentials (VC-JWT):
  issue, verify, revoke (bitemporal), evidence-chaining. Tested against real
  published spec test vectors, not just self-consistency.
- The identity ledger and authorization layer: Town/officeholder
  provisioning, `OfficeGrant` issuance, and officeholder attestation
  (`certify!`) running as a standard `execute/trigger!` clause, fully wired
  from model to HTTP form to page.
- The concrete, real worked case: the China Grove Clerk certifying the
  council's vote, then the minutes approving it (naming the vote as
  evidence) — seeded, tested, and reachable through the actual running app.
- Links out to Accord's Concerto Playground (always) and Template Playground
  (when the model fits its one-extra-namespace limit).

**Partially implemented:**
- **Contract execution runtime**: only `:clojure` clauses actually run.
  `:typescript` clauses are stored (ingested `.cta` archives keep their
  `logic/*.ts` as a citable artifact) but `execute.clj`'s `run-clause`
  refuses to execute them (`:runtime-unavailable`) — no TypeScript backend
  exists.
- **Field-level GraphQL-ish querying** (`model/graph.clj`, `model/contract.clj`):
  resolvers are generated correctly from any ingested model, but there is no
  UI or route that actually calls `biff.graph/query` with them today — reachable
  only from a REPL or a new caller you'd have to write.
- **Cross-contract party querying** (`src/com/trustblocks/contract.clj`,
  `signed-by`/`as-of`): implemented, but not registered in `modules.clj` and
  not reachable from the running app — only exercised by
  `dev/contract_queries.clj`. Its `party-roles`/`date-fields` tables are
  hand-keyed to a few Accord *reference* templates, not the municipal ones
  this app actually seeds, so it would need real data to be useful as-is.

**Not started:**
- Any UI for provisioning a Town, an officeholder, or an `OfficeGrant` —
  today this is exclusively `dev/certification_demo.clj`/REPL calls. The
  exact operator procedure is in `docs/operator-runbook.md`, but there is no
  write-capable admin page.
- A bearer-token/API credential for machine callers — `trigger`/`certify`
  both ride the session cookie; a script has to sign in like a person.
- A real mail provider for signin codes.
- Encryption at rest for officeholder/Town private keys.
- A `credentialStatus` endpoint for external verifiers to check revocation —
  the internal bitemporal revocation is real, nothing is exposed over HTTP
  for someone outside this database to check it.
- The general `Proceeding`/`ProceedingStep` model (multi-step municipal
  processes with no contract at all, e.g. a street closing) sketched in the
  sibling research repo's `PROCEEDING-MODEL.md` — deliberately scoped down
  to just the two-step vote-then-minutes case for now; nothing in this repo
  implements the general form.
- A model-publishing repo/site (`models.trustblocks.com`) — parked by
  decision, see `docs/model-repository.md`.

## 6. Open threads & known issues

- **No idempotency semantics were re-examined for `grant-office!`** the way
  `certify!`'s were — granting the same office twice mints two separate,
  independently valid `OfficeGrant` credentials rather than being refused or
  deduplicated. Not exercised by any test; likely fine (a Town re-granting
  isn't harmful) but not a considered decision the way `certify!`'s dedup is.
- **Evidence chains have no depth or cycle check.** `certify!`'s `evidence`
  parameter accepts any JWT string; nothing stops (or currently exercises) a
  credential naming itself, a long chain, or a cycle as its own evidence.
  Display code (`attestations-for-contract`) doesn't walk the chain at all,
  so this is inert today, but a future feature that *does* walk evidence
  chains should treat this as unverified territory.
- **`current-state`'s temporal semantics** (`execute.clj`) were not fully
  traced this session — it's unclear from a quick read alone whether it
  reads state as of real wall-clock "now" or some other point when a clause
  consults `state`. Every real scenario tested so far only needed "state
  after a permanent, never-revoked approval," which is insensitive to this
  distinction; don't assume it's been verified for a case where state
  changes and un-changes over time.
- **`docs/walkthrough.md` promises every output came from a real run.**
  Part 5 (identity, DID/VC, attestation) was checked by executing its
  Clojure blocks verbatim. If you change what `certify!`, `grant-office!`,
  `revoke!` or `signature-authorizations` return or refuse, re-run those
  blocks and update the captured output rather than letting it drift.
- **The Accord Node toolchain currently reports npm audit findings.** After
  the Concerto 5 / Template Engine 5 upgrade, `npm audit` reports 22
  transitive findings (9 moderate, 13 high, 0 critical). Direct Accord
  packages are current; npm's advertised remediations are semver-major
  downgrades to obsolete package lines, so do not run `npm audit fix --force`
  blindly. Re-check upstream releases and the actual dependency paths.
- **No CI.** There's no `.github/workflows` or equivalent — `bin/clj -M:run
  code-quality` and the test suite are run manually, by a human or an agent,
  before merging. Nothing enforces that on GitHub itself.
- **This session's flakiness lesson, worth re-learning if you see it
  again**: a test that intermittently fails with what looks like an extra or
  duplicated row is not necessarily a race or a duplicate write — check
  whether something is being sorted by a random identifier (`:xt/id` is a
  random UUID) before assuming a storage bug. `credential/for-subject`/
  `for-contract` sort by the credential's own `:xt/valid-from` for exactly
  this reason; don't reintroduce an `:xt/id` sort on temporal data.

## 7. How to run & verify

**Environment prerequisites** (already satisfied in this sandbox; check on a
new machine):
- JDK 21-24 — XTDB 2.1.0 will not run on newer or older. `bin/clj` pins this
  via `JAVA_HOME` (default `/opt/homebrew/opt/openjdk@21/...`, override with
  `TRUSTBLOCKS_JAVA_HOME`). **Always invoke `bin/clj`, never bare `clj`** —
  a bare `clj` uses the system JDK and XTDB will fail to start.
- Node.js on `PATH`, with `npm install` run at least once (`node_modules/`
  gitignored). This is a **real runtime dependency**, not just for
  `bin/template-conformance`: `com.trustblocks.concerto.cto/parse` (in the
  external `concerto-clj` dependency) shells out to Accord's own JS tooling
  to parse `.cto` files, and that runs every time `put-model!`/`load-registry`
  is called — including inside most of the test suite. `bin/clj` automatically
  prefers the lockfile-pinned `node_modules/.bin/concerto` (CLI 4.2 backed by
  Concerto Core/CTO 5) over an older global installation. If tests fail with
  something about spawning a process or finding a CLI, check Node/npm first.

**Run the app locally:**
```sh
rm -rf storage log   # clean XTDB state
bin/clj -M:xtdb-jvm-opts -i dev/seed.clj
```
Leaves the app running on `http://localhost:8080`. Sign in at `/signin` with
any email to browse read-only; the six-digit code prints to this same
console (no mail provider). Sign in as `pmills@chinagrovenc.gov` (the seeded
Town Clerk) specifically to see and use the certification form on
`/contracts/china-grove-gover-2025`. Wait for `Seeded.` on the console first:
the server answers requests before the seed has provisioned any officeholder,
and a certify POST made in that window is refused with "No officeholder
identity for this email".

**Run tests:**
```sh
bin/clj -M:run test
```
Currently 45 tests / 151 assertions, 0 failures. Focus a namespace or var
with `--focus <fully.qualified.name>`.

**Lint + format + test together** (what to run before considering any change
done):
```sh
git add -A            # code-quality only sees tracked files — see §4
bin/clj -M:run code-quality
```
This runs `cljfmt` (reformats in place), `clj-kondo`, then the full test
suite. Re-run tests afterward if cljfmt changed anything.

**Verify a web change end-to-end** (no browser available in an agent
context): background the app and curl it **in one terminal invocation** —
a backgrounded process does not survive between separate tool calls in this
kind of environment. Pattern:
```sh
lsof -nP -iTCP:8080 -sTCP:LISTEN -t | xargs -r kill -9   # free the port first
rm -rf storage log
(nohup bin/clj -M:xtdb-jvm-opts -i dev/seed.clj > /tmp/seed.log 2>&1 &)
sleep 32   # real boot time, includes CSS build + XTDB start
curl ...   # sign in (POST /_biff/auth/send-code, read the code from the log,
           # POST /_biff/auth/verify-code), then exercise the actual route
```

**Template conformance** (only needed after touching a `.cto`/`.cta` under
`docs/examples/`):
```sh
npm install   # once
bin/template-conformance
```

## 8. Recommended next steps

1. **Decide whether `com.trustblocks.contract` (party querying) and
   `model/graph.clj`'s field-level resolvers get a real caller, or get
   removed.** Both are complete, working code with zero production callers;
   left alone indefinitely they'll bit-rot against model/schema changes
   nothing exercises them against.
2. **Decide whether provisioning warrants a guarded admin UI.** A concrete
   REPL path now exists in `docs/operator-runbook.md`; onboarding no longer
   requires reverse-engineering the seed script, but it is still manual and
   deliberately has no browser write surface.
3. **Trace `current-state`'s exact temporal read semantics** (see §6) and
   add a test that actually exercises a contract whose state changes more
   than once, so the "as of now vs. as of the clause's own `now`" question
   has a checked answer instead of an assumed one.
4. **If a second contract template is ever added, use it to pressure-test
   `MunicipalAgreement` reuse and the attestation-clause pattern** — both
   are currently proven against exactly one real contract type each. A
   second real template is the cheapest way to find out whether either
   generalizes as intended.
5. Only after the above: revisit the explicitly-parked items (`:typescript`
   clause runtime, model-publishing repo, bearer-token auth, mail provider,
   key encryption at rest) — none of them block anything else on this list,
   and none were asked for yet.