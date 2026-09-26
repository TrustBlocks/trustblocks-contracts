# Matters: sets of related documents, one state, signed transitions

Design note, 2026-09-25. Status: **agreed direction, first slice not yet built.**
Worked example: the Town of China Grove Street Resurfacing contract, STR-27,
and its monthly pay applications.

Companion to `~/Code/Accord/PROCEEDING-MODEL.md` (the Hopedale Drive street
closing), which reached the same gap from the other side, and informed by
Flood & Goodenough, *Contract as Automaton* (OFR Working Paper 15-04, rev.
2017).

## The gap

A template is one document. Municipal work is rarely one document. STR-27 is a
contract *plus* a pay application every month, change orders, notices of
award and to proceed, an inspection before anything is paid, and the Finance
Officer's record that payment went out. A street closing is a petition,
resolutions, notices published for four successive weeks, a hearing, and a
recorded instrument. Each document has its own data and its own lifecycle,
but they share terms, advance one shared state, and are authorized by
different officeholders.

Accord's proposed 1.0 models (accordproject/models PR #200, open) reach
partway: an `Agreement` is a list of `AgreementDocument`s, with one revisioned
`AgreementState`, and obligations move through an `ObligationTransition` that
records the actor and the evidence. Workflow, authorization, and
non-contractual proceedings are left out. That is the part to build, shaped
so that it maps onto those types.

## The construct

One idea, in two places:

| | Repository: **package** | Running app: **matter** |
|---|---|---|
| is | a family of templates that share one model | one live instance of a package — "STR-27 with Example Paving" |
| holds | one namespace (shared types: `BidItem`, `StreetSegment`, the state); one template per document type, each with its own `@template` root | the terms, once; its documents, each carrying only its own data; one state |
| Accord | each template still builds to a standard `.cta`, vendoring the shared model | a matter corresponds to `Agreement`, its documents to `AgreementDocument` |

A proceeding with no contract in it — the street closing — is a matter too.

## Three mechanisms

### 1. Shared terms, per-document data

A pay application stores its application number, period, and quantities per
pay item, and a reference to its matter. It never copies the contractor, the
streets or the unit prices: rendering it reads those from the matter **as of
the application's own date**, so a change order that altered a unit price in
April does not rewrite March's application.

### 2. One state, advanced by documents

The matter holds the contract-to-date position: quantity paid to date per pay
item, gross earned, retainage held, payments to date, percent complete (which
under N.C. Gen. Stat. § 143-134.1 ends retainage at 50%), contract days used,
liquidated damages accrued. Documents advance it through `execute/trigger!`,
keyed by matter, so what #19 established holds unchanged: a pay application
dated before one already approved is refused as out of order, concurrent
submissions cannot lose an update, and "what had been paid as of May 31, and
what did we believe on June 15" is a query.

### 3. Signed transitions

Each document type — and the matter itself — has a lifecycle written as
**data**: a state machine in the *Contract as Automaton* sense.

- **States and events are finite and declared.** Every (state, event) pair
  is either a transition or explicitly ignored; some events are
  *omnipresent* (reachable from every state: for STR-27, Sec. 17 mediation
  and Sec. 18 breach). `bb check` verifies completeness, that every state is
  reachable, and that an accept state is reachable from every state.
- **Each transition names the authority it requires.** The host checks the
  actor's `OfficeGrant` as of the event's own time, as `certify!` does today,
  and signs the transition as a Verifiable Credential whose `evidence` is the
  credential it rests on. The chain for one pay application reads
  *payment certified ← approved ← certified ← inspected ← received*.
- **The machine controls; the clause computes.** A pure DFA cannot hold
  amounts, and giving it free memory makes it Turing-complete (the paper's
  own caveat). So the lifecycle is a checkable control machine, and the
  numbers — quantities, retainage, liquidated damages — and the guards on
  them are computed by the contract's sandboxed Clojure clause: an
  extended state machine.
- **Time is an event.** Clauses have no clock, by design. Deadlines — "30
  days after approval", "day 270 passes", "10-day cure period expires" — are
  events a host scheduler records in XTDB at their valid time, so "which
  deadlines are running" is a query.
- **Events are measurements, and signed ones.** The paper observes that a
  contract's discretion lives in its event definitions — *has* the work been
  done acceptably? A signed officeholder certification is that measurement,
  made accountable.

### Receipt attestation

Parties outside the Town — the contractor, a consulting engineer, a surety —
do not need identities of their own. A Town officeholder certifies **receipt**:
"I received document X (by its hash) from party Y on date Z", with her own DID
and under her own authority. Trust is anchored in the Town's officeholders,
which is how the practice already works. A Town Engineer who is an employee
with an `OfficeGrant` certifies an inspection directly; an outside engineer's
report is received and certified by the Director. The machine is the same.

## First slice: the STR-27 pay application

**Lifecycle** (happy path in order; authority names are proposals):

| From | Event | To | Who | Authority |
|---|---|---|---|---|
| — | pay application received from the Contractor | `RECEIVED` | the Director, or the Director's designee | `RECEIVE_PAY_APPLICATIONS` |
| `RECEIVED` | inspection certified (or an engineer's inspection report received) | `INSPECTED` | Town Engineer, or the Director | `CERTIFY_INSPECTIONS` / `RECEIVE_INSPECTION_REPORTS` |
| `INSPECTED` | quantities and amounts verified (Sec. 6) | `CERTIFIED` | the Director | `CERTIFY_PAY_APPLICATIONS` |
| `CERTIFIED` | approved and payment recommended (Exhibit E) | `APPROVED` | Town Manager | `APPROVE_PAYMENTS` |
| `APPROVED` | payment certified made | `PAID` (accept) | Finance Officer | `CERTIFY_PAYMENTS` |
| `RECEIVED`…`CERTIFIED` | returned for correction | `RETURNED` (accept; a corrected application is a new document) | the Director | `CERTIFY_PAY_APPLICATIONS` |
| `APPROVED` | 30 days pass (Sec. 6) | `OVERDUE` | scheduler | — |
| any | mediation requested (Sec. 17) | `DISPUTED` | — | — |

**Into the matter:** `APPROVED` emits the certified quantities and amounts
(gross, retainage withheld per § 143-134.1, liquidated damages deducted per GC
22); `PAID` emits the payment. The clause checks that no quantity to date
exceeds what inspection approved, and computes retainage and liquidated
damages.

**Templates** (a `street-resurfacing` package):

- the contract, as now;
- a **pay application** drawn from Exhibit E's Contractor Pay Request:
  application number, period, notice-to-proceed and completion dates, days
  remaining, percent of work, time and payment complete; per pay item the
  quantity this period and to date, amount this period and to date, and
  **Completed [ ] / Approved by Engineer [ ]**; gross, retainage, previous
  payments, liquidated damages, other deductions, net due; the Contractor's
  certificate; approval lines for the Director and the Town Manager; and a
  **payment certificate** for the Finance Officer — every blank a field.

**Inspection before payment.** The Bid Form's pay items and General
Conditions 16–18 say work is measured "as accepted", but nothing ties a
quantity on a pay request to an inspection. This was first proposed as a new
Special Provision (SC-16). The Town Attorney's memo instead revises **General
Conditions 18**, so that no quantity is paid until it has been inspected and
approved, and defines who verifies an inspection by type of work and contract
size. The contract text will change when the Town adopts that revision; the
pay application's **Approved by Engineer** box already records it.

## Decisions

1. **Who receives and verifies.** The Director of Public Works, as the STR-27
   documents say. The Zoning Administrator acts on a matter as the Director's
   designated representative (Sec. 6), under a **Designation** credential for
   that matter. She receives the Contractor's pay applications and the
   engineer's inspection certificate by signing a **Receipt** for each stored
   file. Neither the Contractor nor an outside engineer needs a DID.
   Implemented in #32.
2. **Inspection language.** A revision of General Conditions 18, per the Town
   Attorney's memo, not SC-16.
3. **Repository layout.** `packages/<package>/templates/<name>/` in
   trustblocks-templates. Each document is still its own template, archive and
   namespace, because Accord allows exactly one `@template` per archive. Types
   the documents share go in `packages/<package>/model/` once there are any.
   Implemented in TrustBlocks/trustblocks-templates#3, with the pay
   application.
