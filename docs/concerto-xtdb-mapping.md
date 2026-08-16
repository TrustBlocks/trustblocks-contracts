# Concerto ⇄ XTDB: Mapping Notes & Accord Agenda

*Working notes on storing Concerto-modeled documents in XTDB 2.x with a Malli
enforcement layer, plus the open items to raise with Accord. Findings marked
**measured** were verified against XTDB 2.1.0, Concerto CLI 3.19.0, and the
57-template `cicero-template-library`; items marked **open** are still questions.*

**Thesis:** Concerto owns the schema and validation; XTDB owns storage, history,
and (already-superior) types. The work is all in the transform layer, and in
keeping `$class`.

**Important scope correction (2026-08-16).** Earlier drafts of these notes, and
the first round of implementation, were based on the `cicero-template-library`
and the `models` repo. That is the wrong layer. The **APAP protocol model**
(`org.accordproject.protocol@1.0.0`) already defines `Template`, `Agreement`,
`SharedModel`, `AgreementParty`, `Signature`, and `HistoryEntry`. Several gaps
recorded below as missing from Accord are missing only from the *template
library*, not from the protocol. Storage should be modeled on the protocol.

---

## Part 1 — Accord agenda

### 1.1 Metamodel version drift — **open, new**

`apap/model/protocol.cto` imports `concerto.metamodel@0.4.0`, but Concerto CLI
3.19.0 emits every parsed model tagged `concerto.metamodel@1.0.0`. Since
`JsonModel.model` is typed against the 0.4.0 `Model`, an AST produced by the
current toolchain does not match the type the protocol declares.

**Ask:** is the 0.4.0 pin deliberate, or has the protocol drifted behind the
compiler? What is the intended migration?

### 1.2 Numerical precision — **open; case is now much stronger**

Canonical thread [#189](https://github.com/accordproject/concerto/issues/189)
was closed as *not planned* (Dec 2024). The concrete evidence:

The standard money concept, used by essentially every commercial template, is

```
concept MonetaryAmount {
  o Double doubleValue // convert to fixed-point?
  o CurrencyCode currencyCode
}
```

The field is *named* `doubleValue`, and the source comment shows the authors
already doubted it. **Measured:**

```
8250000.35 stored as a Double is really 8250000.34999999962747097015380859375
12 x 1234.567   Double: 14814.803999999996    Decimal: 14814.804
```

A contract award is wrong the instant it is stored, before any arithmetic.

**Long is a separate problem with a different remedy.** **Measured:** `2^53+1`
round-trips exactly as `java.lang.Long` through XTDB. So Long is purely a
*JavaScript-runtime* defect, fixable in the JS implementation without touching
the spec; money genuinely needs a new type. The two should be asked separately.

**Ask:** appetite to revive #189 for a real decimal/`BigDecimal` type? And,
separately, to fix `Long` in the JS runtime?

### 1.3 Inheritance and `superType` — **measured; reframed**

Concerto uses a nominal type system: a subclass serializes to one *flattened*
object tagged with `$class`. The hierarchy is recoverable only through that tag
plus the model.

**Measured:** the metamodel emits `superType` as a `TypeIdentifier` carrying
**only a `name`, with no namespace**:

```json
"superType": {"$class": "concerto.metamodel@1.0.0.TypeIdentifier", "name": "Clause"}
```

So a declaration alone does *not* let an external validator reconstruct the
chain — the bare name must be resolved through the model's `imports` table, one
link at a time. This is workable (we implement it, and it resolves
`TemplateModel → Clause` across namespaces) but it is a papercut.

**Ask:** why is `superType` unqualified when other references in the metamodel
are fully qualified? Is qualifying it on the table?

### 1.4 Nested `$class` — **measured; spec confirmation still wanted**

**Measured:** nested concepts *do* carry their own `$class`. `interest-rate-swap`
carries 4 tags and `fragile-goods` 5; all survive a full round trip, and roughly
a dozen other templates nest tagged concepts.

That is evidence from the corpus, not a guarantee. **Ask:** confirm at spec
level that nested instances always carry `$class`, since every external
validator depends on it.

### 1.5 Dates and offsets — **partially measured; the real risk is untested**

`strictQualifiedDateTimes` became the default in v4.0
([#845](https://github.com/accordproject/concerto/issues/845)), a deliberate
breaking change, so qualified UTC offsets should survive the round trip.

**Measured, with a caveat:** the `+01:00` offsets in `interest-rate-swap`
round-trip byte-identically through our pipeline — **but that proves less than
it appears**. We currently store `DateTime` as an opaque *string*, not as
`TIMESTAMP WITH TIMEZONE`. It is lossless precisely because we never interpret
it. Converting to a real timestamp type is exactly where offset loss would
occur, and that path is untested.

**Ask:** does the v4.0 default preserve offsets on nested/inherited instances,
not just the root? Any plan for named zones (`Europe/London`), which XTDB
handles natively?

### 1.6 The `$` sigil is not portable — **measured, new**

**Measured:** XTDB 2 parses `$` as a namespace separator, silently rewriting
`:$class` to `:/class` and destroying the tag the entire model depends on. We
work around it by canonicalizing to `:concerto/class` (§2.2).

Since `$class` is load-bearing for portability, **ask:** should the reserved-name
convention avoid a character that is structural in other systems?

### 1.7 Template library defects — **measured, new**

Sweeping all 57 templates found two that fail against their own models,
independently confirmed with `concerto validate`:

| template | defect |
|---|---|
| `bill-of-lading` | model declares `o Double declaredValue`; sample supplies a `MonetaryAmount` object |
| `supply-agreement-loc` | nine field mismatches; Concerto stops at `termRenewal`, our validator reports all nine |

Also worth reporting: **the template library does not use Accord's own identity
models.** Not one of the 57 templates imports `Party`, `Person`, or
`Organization` — signatories are bare `String` fields under inconsistent names
(`licensee`, `licensor`, `maker`, `lender`, `shipper`, `receiver`). The protocol
models this properly with `AgreementParty`; the template library has not caught
up.

**Ergo is gone.** All 57 templates declare the `typescript` runtime, there are
zero `.ergo` files, and only three prose mentions remain in READMEs.

---

## Part 2 — Storage guidance (Concerto ⇄ XTDB)

### 2.1 Two problems, not one

1. **Data mapping** (structural, easy): Concerto JSON instance ⇄ XTDB record.
   Both are nested maps.
2. **Schema mapping** (semantic): Concerto's declarations have no home in
   XTDB's engine — so **store the schema as data** and **enforce in Clojure**
   (Malli).

XTDB 2.x is schema-on-write: no `CREATE TABLE`, no column declarations, no
constraints. Don't ask an assistant to "define the XTDB schema" — there isn't
one.

### 2.2 XTDB rewrites your keys — **measured**

This is the single most important implementation fact, and it is not documented
anywhere obvious:

| written | read back | note |
|---|---|---|
| `:$class` | `:/class` | **`$class` is destroyed** |
| `:businessDays` | `:businessdays` | camelCase lowercased — **not reversible** |
| `:business_days` | `:business-days` | underscore → hyphen |
| `:business-days` | `:business-days` | kebab-case is stable |
| `:concerto/class` | `:concerto/class` | **namespaced keywords are stable**, including nested and inside arrays |

`business_days` and `business-days` **collide silently**, last write winning.

**Canonicalize on write:** `$class` → `:concerto/class`, `$identifier` →
`:concerto/identifier`, camelCase → kebab-case — with a pre-write collision
check that refuses ambiguous instances rather than letting XTDB drop a field.

**Reserve namespaced keys for your own metadata.** Derived indexes must not
reappear as Concerto properties on read, or the instance stops validating.

### 2.3 Restoring names requires the model — **measured**

Deriving the original name back from the canonical key with `->camelCase` is
**lossy** for anything that is not plain camelCase:

```
vechileID   -> vechileId     (car-rental-tr)
softwareID  -> softwareId    (online-payment-contract-tr)
INITIAL_DUE -> initialDue    (installment-sale)
```

The model is the only authority on the original spelling. Resolve the reverse
map from the declared property names, **per level** from each level's own
`$class`, so nested concepts get their own names rather than their parent's.
Do not make this opt-in — silent field renaming on read is a defect that
surfaces much later as a mysterious data problem.

### 2.4 Core patterns

**Identity → `_id`.** Map the Concerto identity field to XTDB's id. Note
`identified by` may be **inherited** (`TemplateModel → Clause → clauseId`), so
resolve it along the whole super-type chain.

**Always persist `$class`.** Both Concerto and Malli flatten inheritance; the
flattening is lossless *only* because `$class` names the leaf type.

**Schema-as-data.** Store the Metamodel JSON, one record per declaration keyed
by fully qualified name. Schemas then get bitemporal history alongside their
data — and reads become self-sufficient: the schema needed to interpret a record
lives beside the record, with no filesystem dependency. **Strip `location`
nodes**; they are byte offsets into the source, not schema semantics, and they
dominate the payload.

### 2.5 Type mapping

| Concerto | XTDB | Note |
|---|---|---|
| String / Boolean | VARCHAR / BOOLEAN | direct |
| Integer | INT | |
| Long | **BIGINT** | JVM-safe; the 2^53 bug is a JS-boundary problem only |
| Double | DOUBLE | see below |
| Money / high precision | **DECIMAL(p,s)** | your layer's decision; Concerto has no decimal |
| DateTime (qualified) | **TIMESTAMP WITH TIMEZONE** | *not yet implemented — we store ISO strings* |
| enum | VARCHAR | validate membership in your layer |
| `[]` array | ARRAY | first-class |
| nested concept (value) | OBJECT / RECORD (embed) | |
| relationship (`-->`) | store referenced `_id` | rehydrate via join |

**`Double` must accept integers.** JSON has no int/double distinction for whole
numbers, so a `Double` field commonly arrives as `85` rather than `85.0`.
Concerto's own validator accepts this (verified against `demandforecast` and
`volumediscount`), so a strict `:double` is stricter than the spec — it produced
16 false failures across the library.

### 2.6 Malli specifics

- `:merge` / `:union` / `:select-keys` are **not in Malli's default registry**.
  Add `malli.util/schemas` or validation throws "unknown type." This is the most
  common reason people think it doesn't work.
- **Resolver pattern:** read `$class` → walk the super-type chain → compile with
  `mu/merge` over the parents → validate the flat record.
- Malli merge is **structural** (drops the tag); Concerto is **nominal** (keeps
  `$class`). Never ask Malli to *infer* the subtype — always drive from `$class`.

### 2.7 Enforcement caveat

XTDB enforces **nothing** — no ranges, regex, required fields, or enum
membership. Anyone writing over the Postgres wire bypasses every Concerto
constraint. **Funnel all writes through one guarded ingestion function** that
validates against the `$class`-resolved schema before insert.

### 2.8 Query gotcha — **measured**

In a `from` binding, `{:xt/id $id}` with an `:args` param **does not filter**.
XTDB treats `$id` as another column to project and returns **the entire table** —
silently the wrong row. Match the id as a literal instead:

```clojure
(from :contract [{:xt/id "the-id"} *])   ; correct
```

---

## Part 3 — History and bitemporality

XTDB has two independent time axes, and the distinction decides how Accord's
`HistoryEntry` should be handled:

| axis | who sets it | answers |
|---|---|---|
| **valid time** | the application | *what was true in the world at time T* |
| **system time** | XTDB alone | *what the database believed at time T* |

**Measured**, using a back-dated correction (amount discovered to be $265k,
effective from the original signing date):

```
VALID TIME    at 2025-05-01 (pre-signing): []
              at 2025-07-01              : 265000   <- corrected value
SYSTEM TIME   as believed before the fix : 250000   <- the old belief, still recoverable
              as believed now            : 265000
```

That second row is the property that matters in a dispute: *"we approved payment
against $250k, because that is what the record said at the time"* stays
answerable even though the corrected record now reads $265k.

**Decision: keep both, and map them deliberately.**

- Accord `HistoryEntry` ↔ **valid time**. Application-controlled, world
  semantics, and it travels with the agreement to APAP or a counterparty.
- **System time** ↔ nothing in Accord. It stays purely database-side.

The key property: **system time cannot be set by the application.** Valid time
can be back-dated freely — that is the point of it — but you cannot forge when
the database learned something. That is what makes it credible as an audit
trail.

**Design rule: derive `HistoryEntry` from XTDB on export; never maintain the two
independently.** If history is appended by application code at one moment and
valid time set at another, the two will eventually disagree — and a legal record
with two contradictory histories is worse than one. Deriving it keeps XTDB the
single source of truth while the exported agreement stays self-contained.

Caveat: XTDB's history is per-record, so an agreement's history must be
assembled across the agreement plus anything modeled separately. That argues for
storing `Agreement` close to the protocol's shape rather than decomposing it.

---

## Part 4 — Implementation status

Working end to end (`src/com/trustblocks/`):

| namespace | role |
|---|---|
| `concerto.clj` | bijective instance ⇄ XTDB record transform |
| `concerto/model.clj` | CTO → Metamodel JSON, registry, super-type walk, `$class` → Malli |
| `store.clj` | guarded ingestion; schema resolvable from XTDB with no filesystem access |
| `template/audit.clj` | per-stage template diagnosis |

**Verified:** 55 of 57 templates ingest and round-trip byte-identically; the 2
failures are the genuine template defects in §1.7. Round-tripped output passes
Concerto's own `concerto validate`. A new namespace with `-->` relationships,
arrays of nested concepts, and enums works with no pipeline changes — extending
the model is a modeling exercise, not a re-architecture.

**Known divergences from this document:**

- `DateTime` is stored as an ISO string, not `TIMESTAMP WITH TIMEZONE` (§1.5).
- Money is stored as the `Double` Concerto gives us; no decimal layer yet (§1.2).
- Storage is modeled on template-library shapes, not on the protocol's
  `Template`/`Agreement`/`SharedModel`. The derived party index in
  `contract.clj` exists only because the template library lacks `AgreementParty`;
  it should be dropped when storage moves to the protocol model.

---

## Reference

- XTDB data types — https://docs.xtdb.com/reference/main/data-types.html
- Concerto docs — https://concerto.accordproject.org/docs/intro
- Concerto #27 (timezone loss, fixed) — https://github.com/accordproject/concerto/issues/27
- Concerto #845 (strictQualifiedDateTimes default in v4.0) — https://github.com/accordproject/concerto/issues/845
- Concerto #189 (numeric improvements — closed, not planned) — https://github.com/accordproject/concerto/issues/189
- Malli #217 (declarative `:merge`) — https://github.com/metosin/malli/issues/217
- Malli #493 (merging `:map` and `:multi`) — https://github.com/metosin/malli/issues/493
