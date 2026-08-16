# Concerto ⇄ XTDB: Mapping Notes & Accord Agenda

*Working notes on storing Concerto-modeled documents in XTDB 2.x, with a Malli enforcement layer. Covers open Accord discussion items (dates, numeric precision, inheritance) and reusable storage/prompt guidance.*

**One-line thesis:** Concerto owns the schema and validation; XTDB owns storage, history, and (already-superior) types. Wherever Concerto's date and precision debates land, XTDB already has the target type — the work is all in the transform layer, and in keeping `$class`.

---

## Part 1 — Items to raise with Accord

### Dates
- **Status: mostly resolved; moved toward XTDB's model.** The old timezone-loss bug (dates silently shifted to `Z` on serialize/deserialize — [concerto #27](https://github.com/accordproject/concerto/issues/27)) is fixed.
- In [#845](https://github.com/accordproject/concerto/issues/845), `strictQualifiedDateTimes` became the **default in v4.0** — a deliberate breaking change — so qualified UTC offsets now survive the round-trip.
- **Ask Accord:**
  - Does the v4.0 default preserve offsets and emit `$class` on **nested / inherited** concept instances (deep hierarchies), not just the root?
  - Any plan to support named zones (e.g. `[Europe/London]`), which XTDB already handles natively?

### Numerical precision
- **Status: unresolved — the weakest spot.** Canonical thread [#189 "Numeric type Improvements"](https://github.com/accordproject/concerto/issues/189) was **closed as "not planned" (Dec 2024)**, marked Stale.
- Spec says `Integer` (32-bit) / `Long` (64-bit), but the JS implementation treats both as `Number` — safe only to 2^53−1, not 2^63−1. No decimal type exists; #189 explicitly flagged **monetary amounts** as the missing use case.
- **Ask Accord:** Is there appetite to revive #189 — a real decimal / `BigDecimal` type and a safe 64-bit `Long`? This is the one place the type system can silently corrupt financial data today.

### Inheritance
- **Status: works via flattening, but relies entirely on `$class`.** Concerto uses a **nominal** type system: a subclass instance serializes to one **flattened** JSON object (parent + child fields) tagged with `$class` (e.g. `org.acme@1.0.0.Person`). The hierarchy is only recoverable through that tag plus the model. `abstract` concepts never serialize directly.
- **Ask Accord:**
  - Does the metamodel reliably expose the full super-type chain (`getSuperType` / `getAllSuperTypes`) for a given `$class`, so external validators can reconstruct the hierarchy?
  - Confirm nested instances always carry their own `$class`.

---

## Part 2 — Storage & prompt guidance (Concerto ⇄ XTDB)

Reusable framing to give any coding assistant. Corrects two things an assistant was unsure about.

### Correction 1: XTDB **is** schemaless — state it as fact
XTDB 2.x is **schema-on-write**: no `CREATE TABLE`, no column declarations, no constraints. It stores flat or arbitrarily nested maps (`OBJECT`/`RECORD`, `ARRAY`) as first-class values. **Don't** ask an assistant to "define the XTDB schema" — there isn't one. The schema lives in Concerto; XTDB stores the data and provides bitemporal history for free.

### Correction 2: This is two problems, not one
1. **Data mapping** (easy, structural): Concerto JSON instance ⇄ XTDB record. Both are nested maps.
2. **Schema mapping** (semantic): Concerto's declarations have no home in XTDB's engine — so **store the schema as data** and **enforce it in the Clojure layer** (Malli).

### Core storage patterns

**Identity → `_id`.** Map the Concerto identity field to XTDB `_id`:
- `identified by email` → `_id` = email value
- `identified` (implicit `$identifier`) → `_id` = that value

**Always persist `$class` as a column.** This is the linchpin. Because both Concerto and Malli **flatten** inheritance, the flattening is lossless *only if* `$class` is retained. On read, `$class` tells Concerto's deserializer the exact leaf type and lets it re-expand the hierarchy.

### Type mapping table

| Concerto | XTDB | Note |
|---|---|---|
| String / Boolean | VARCHAR / BOOLEAN | direct |
| Integer | INT | |
| Long | **BIGINT** | avoids JS 2^53 bug — do not store as double |
| Double | DOUBLE | |
| Money / high precision | **DECIMAL(p,s)** | your layer's decision; Concerto has no decimal yet |
| DateTime (qualified) | **TIMESTAMP WITH TIMEZONE** | preserves offset; needs Concerto v4.0+ |
| enum | VARCHAR | validate membership in your layer |
| `[]` array | ARRAY | first-class |
| nested concept (value) | OBJECT / RECORD (embed) | |
| relationship (`-->`) | store referenced `_id` | rehydrate via join / pull |

**Nested vs. normalized:** embed a value-type sub-concept as a nested `OBJECT`; normalize an `asset`/`participant` with its own identity into a separate record referenced by `_id`.

**Schema-as-data:** serialize the Concerto model to **Metamodel JSON**, store one record per declaration keyed by fully-qualified name (e.g. a `_concerto_schema` table). Schemas then become versioned and bitemporal alongside their data. This is how you "import both schema and data into XTDB."

### Inheritance pattern (the flattening bridge)
- Concerto flattens a subclass → tagged flat object.
- **Malli solved the matching side:** `mu/merge` and the declarative `[:merge Parent [:map ...]]` (shipped via [malli #217](https://github.com/metosin/malli/issues/217)) flatten parent + child into one `:map`.
- **Gotcha:** `:merge` / `:union` / `:select-keys` are **not in Malli's default registry** — you must add `malli.util/schemas`, or validation throws "unknown type." (Most common reason people think it doesn't work.)
- **Resolver pattern:** read `$class` → walk the Concerto super-type chain → compile a Malli schema with `mu/merge` over the parents → validate the flat record. The two flattenings line up field-for-field.
- Malli merge is **structural** (drops the tag); Concerto is **nominal** (keeps `$class`). Never ask Malli to *infer* the subtype — always drive it from `$class`, using `:multi {:dispatch :type}` for open hierarchies.

### Enforcement caveat (include in every prompt)
XTDB enforces **nothing** — no ranges, regex, required fields, or enum membership. All validation is the Malli layer. Anyone writing via raw SQL / Postgres wire bypasses every Concerto constraint, so **funnel all writes through one guarded ingestion function** that validates against the `$class`-resolved Malli schema before `INSERT`.

---

## Reference links
- XTDB Data Types: https://docs.xtdb.com/reference/main/data-types.html
- Concerto docs: https://concerto.accordproject.org/docs/intro
- Concerto #27 (timezone loss): https://github.com/accordproject/concerto/issues/27
- Concerto #845 (strictQualifiedDateTimes default, v4.0): https://github.com/accordproject/concerto/issues/845
- Concerto #189 (numeric improvements — closed, not planned): https://github.com/accordproject/concerto/issues/189
- Malli #217 (declarative `:merge`): https://github.com/metosin/malli/issues/217
- Malli #493 (merging `:map` and `:multi`): https://github.com/metosin/malli/issues/493
