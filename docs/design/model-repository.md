# Model repository, default data, and attestation clauses

Design note, 2026-10-23. Status: **step 1 built** (`xtdb/model_repository.clj`); steps 2-5 are proposals. Companion to `clause-tracking.md` (clause versions, template revisions, release) and `matters.md`.

## What exists (checked, not assumed)

In `trustblocks-templates`:

- **`shared/model/`** holds our shared models: `agreement.cto` (`MunicipalAgreement`), `attestation.cto`, `lifecycle.cto`, `deadline.cto`, `manifest.cto`. Each template **vendors a copy** into its own `model/`, and `bb check` fails if any copy differs from the canonical one. That is a model repository in embryo, kept consistent by copying and a check.
- **Namespaces** are `com.trustblocks.*@1.0.0`, one namespace per template, with shared types imported. Jurisdiction is data, not namespace.
- **Reuse is by inheritance.** `MunicipalAgreement` carries `stateCode`, `stateName`, `countyName`, `municipalityName`, `municipalityType`, `governingBodyName`, so "China Grove" is a *value* of those fields on each instance. Nothing supplies those values by default today.
- **Lifecycle** (`lifecycle.json`, per template): states, and events that each *require a certification*, a `Receipt` or `Attestation` under a named authority. `bb check` verifies it is a sound state machine.
- **Attestation** (`attestation.cto`): `AttestationRequest {statement, documentHash, requiredAuthority}`. A contract adopts it by importing the pair and adding one branch to its own clause logic.
- **Clause logic** is per template (`logic/clause.clj`, SCI, no clock), stored in XTDB by contract class and listed on `/clauses`.

In `Trustblocks` (running): `certify!` takes a `documentHash`, which today is a free-text field the clerk types.

My earlier assumption that nothing ties an attestation to a document was too strong: `documentHash` is the hook. It just isn't wired to a release.

## The problem

Clauses are shared across contracts. If clause A says `{{municipalityName}}` and the contract's model calls it `townName`, the draft is wrong. Today each clause would bring its own model fragment (my `compose`), and conflicts surface after the fact. We want: **a placeholder that doesn't resolve against the contract's model cannot be built, and two contracts using the same clause agree on what its placeholders mean.**

## Proposal

### 1. Model repository = versioned, pinned, in XTDB

Promote `shared/model/` from vendored copies to a repository the app reads:

- A **model version** is an immutable row: namespace, version, CTO text, hash. Same pattern as `clause-version`.
- **Pinning:** a template or release pins `com.trustblocks.municipal.agreement@1.0.0` exactly. The release SHA covers the pinned model hashes (it already covers a model hash).
- **Vendoring becomes an export**, not the source of truth. The repo's `shared/model/` stays the authoring home (git, review, `bb check`) and is **imported** into XTDB, so a CTA can still be built for Accord interop if ever wanted.

Not a Clojars-style library, just rows plus an import task.

### 2. Clauses declare a contract, like a stylesheet

A library clause gets a small **requirement list**, instead of a free-standing model fragment:

```
:clause/requires {:extends  "com.trustblocks.municipal.agreement@1.x"   ; the base it reads from
                  :fields   [municipalityName municipalityType stateName]  ; or inferred
                  :adds     "concept ... "}                              ; only clause-specific types, optional
```

- Most of a clause's placeholders live on a **shared base** (`MunicipalAgreement`), so they need no fragment at all, only the declaration "I read these shared fields".
- The **fields can be inferred** from the body (the type checker already walks the placeholders), and *checked* against the declared list so a clause cannot silently depend on more than it says.
- `:adds` shrinks to what is truly clause-specific (e.g. an `Insurance` concept). `compose` keeps its conflict reporting for these.
- **Build-time rule:** adding a clause to a template runs the type check; an unresolved placeholder is an error and the template cannot be saved as releasable. This is what makes inconsistent placeholders impossible within one contract. Across contracts, consistency comes from clauses reading the *same pinned base*.

### 3. Logic and text move together

A clause version holds its text body and optional logic as **one** pinned unit, so text and logic cannot drift. Both read the same model.

Two kinds of clause logic exist, and they stay distinct:

- **Template-time / drafting logic** (alternatives, includes, optional blocks): part of the clause tree. Done.
- **Run-time clause logic** (`logic/clause.clj`, the state transitions): per template. Reusable *branches* (such as the attestation branch) can live in the library as a clause's `:clause/logic`, and a template's `clause.clj` is assembled from the branches of the clauses it uses plus its own. **Open:** is that assembly worth it now, or do we only pin and hash the logic a contract has?

### 4. Default data

A separate, versioned record from the schema:

- `defaults/town` v3 = `{stateCode "NC" stateName "North Carolina" countyName "Rowan" municipalityName "China Grove" municipalityType "Town" governingBodyName "Town Council"}`, applied to any model that extends `MunicipalAgreement`.
- Drafting **prefills** from it; the user may override a field. Fields stay optional, so a blank still renders as a blank.
- A release **snapshots the values and hashes them**. Changing the Town's data later never alters a released contract. Schema and data are versioned separately: a data change is not a model change.
- Multiple layers are possible later (town → department → matter), merged in order. Start with one.

### 5. Attestation clause tied to the lifecycle

Contracts are not always explicit about who attests or approves. So:

- A **standard attestation clause** (library clause, text and model) says who certifies, under which authority, for which event, e.g. *"This Agreement shall not take effect until certified by the {{authority}} under the authority of {{governingBodyName}}."* Its model adopts `AttestationRequest`.
- **Release check:** for every lifecycle transition that requires an `Attestation` (or `Receipt`), the template must contain a clause that states the requirement. Otherwise the release reports `:attestation-unstated` with the event and authority. This turns "not all contracts are clear" into a build-time finding rather than a legal discovery.
- **Wire `documentHash` to the release:** the Attestation's `documentHash` becomes the **release SHA** (and a matter's, when signing a matter), chosen from the contract's release instead of typed. `release/verify` then answers "what the officeholder attested to is unchanged."

## Boundaries

- The two repos stay separate. `trustblocks-templates` is authoring, history and `bb check`; `Trustblocks` reads the imported rows. `templatemark-clj` remains a library; nothing here is Concerto-conformance work.
- Accord compatibility of the *templates* is a non-goal (decided); Concerto conformance of the *models* is exact, unchanged.

## Sequence (each step small, tested)

1. **Done.** `xtdb/model_repository`: immutable model versions keyed by `namespace@version`, `import-dir!` from `shared/model/` or a template's vendored `model/`, `closure`, `registry`, `pins`, `verify-pins`. Same content laid out differently is not a change; different content under an existing version is refused. Reads re-hash.
2. `:clause/requires` + build-time resolution against the pinned base, replacing per-clause fragments where a shared base covers them.
3. `xtdb/defaults`: versioned defaults, prefill, snapshot in a release.
4. Release checks: attestation-unstated; `documentHash` = release SHA.
5. Standard attestation clause in the library.

## Questions for review

1. Is one defaults layer (the Town) enough to start?
2. Should the attestation clause check cover `Receipt` requirements too (e.g. a clerk's receipt of a pay request), or only `Attestation`?
3. Do we assemble a template's run-time `clause.clj` from library clause logic, or leave `clause.clj` per template and just pin and hash it?
4. Should the Workshop UI show a clause's required fields as chips ("reads: municipalityName, stateName") so authors see the contract it imposes?

## Decisions (2026-10-23)

- One defaults layer (the Town) to start; a dialog to add defaults comes later.
- The Receipt/Attestation check covers both: every lifecycle event that requires a certification must have its requirement stated in the text.
- `clause.clj` stays per template and is pinned and hashed; assembling it from library clause logic waits for a second contract that needs the same branch.
- Consistency between layers is checked mechanically (logic vs model, logic vs lifecycle, text vs lifecycle). Nothing is stated twice: a value the text and the logic both use lives in the model.
