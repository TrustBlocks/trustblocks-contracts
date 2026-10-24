# UI: gap list, editor decision, and brief for round 2

Round 1 is in `claude-design-handoff/` (README + prototype). It covers the Library track well and the Workshop track barely. This file maps it to the backend, decides the editor question, and is the brief to send back to Claude Design.

## 1. Gap list: handoff element → backend

| Handoff element | Backend | Action |
|---|---|---|
| Shell, tokens, status chips, tables, cards | n/a | **Keep** as the visual base |
| Home, Identities | n/a | Keep; DIDs and the background image are placeholders (decide) |
| Active Contracts list + search | Existing `/contracts` page, `app/contracts` | Restyle; check search exists |
| Statuses Draft / Attorney review / Out for signature / Executed | Lifecycle in `xtdb/advance` + `execute` (template-defined states) | Map the four chips onto template lifecycle states; check how they are named |
| Contract detail: Clause Listing | `release/get-release` manifest (clause id, pinned version, hash), `clauses/audit` for stale pins | Map; add stale-pin badge |
| Per-contract clause "Approve" toggle (attorney) | `signoff/sign!` on a subject (clause version, template revision, release) | **Change:** sign-off is thumbs-up/objection with a note, on the clause *version*, not per contract |
| Signature Authorizations (ordered chain, DIDs) | None yet. Sign-offs are drafting only. | **Already built** (`certification/signature-authorizations`); restyle only |
| Document & Word export | Exists: `template/word`, `/contracts/:id/document.docx` | Wire the button to it |
| Contract Drafting (textarea, clause inserted as pasted `## Title` text) | `template-tree/save!`, `tree/insert`, `:clause` reference nodes | **Redesign:** clauses are pinned references, not pasted text |
| Library of Clauses / Contracts / Matters in drafting | `clause-library/clause-ids`, `latest`, `versions`; contracts via releases; "matters" has no backend | Clauses and contracts map; **Matters = groups of documents sharing a Concerto manifest (§5); new** |
| Templates: three tabs text / Concerto Model / Data | `template-tree/head`, compose's model, `release` | Keep the three tabs, but "Template text" becomes the tree builder (§3) |
| "Validate model + draft" | `compose/compose` (`:ok?`, `:problems`), `draft/draft` | Maps directly. Show problems as a list, not only a green line |
| Enter/edit data modal | `model/describe`, `draft` with data | Maps; generate the fields from the model |
| Template syntax `{{> surety-bond-clauses}}` (partials) | Our syntax: `{{#clause id pin=2}}`, `{{#alternatives}}` | **Reconcile:** the mock-up shows a different include syntax. Ours wins |
| Missing entirely | clause versions + diff, template revisions + tree diff, release, verify, time-travel, clause usage | **Design in round 2** |

## 2. Role model

The prototype has Clerk / Manager / Attorney set at login. Backend has no roles; sign-offs carry a login and an optional role label. Roles in the UI should only gate which buttons show, not authority. Everyone sees everything.

## 3. Editor decision

The thing to edit is a **tree**, not prose, and every structural operation already exists server-side (`insert`, `move`, `remove-node`, `replace-node`, with `tree/diff` to follow). So:

**Recommendation: do not build a home-rolled rich editor, and do not adopt a rich-text editor for the whole template.**

1. **Tree pane = server-rendered Datastar.** Each node is an element with an `id`. Select, insert, delete and move are `@post` calls returning the re-rendered tree fragment. Move is up/down/indent buttons plus keyboard first; drag-and-drop can follow. This is plain hypermedia and is what Datastar is good at.
2. **Node text = a small embedded editor.** Text and directive bodies (`{{ }}`) are short strings. Start with a `<textarea>` and a debounced `@post` that returns the problems list. Upgrade to **CodeMirror 6** if you want highlighting of `{{ }}`, bracket matching and autocomplete for field paths from the model. It is modular (about 100–150 KB with a small setup), framework-free, and works as a web component.
3. **Concerto model tab = CodeMirror 6** with a simple CTO mode, or just a `<pre>`/textarea read-only at first.
4. **Preview = server-rendered fragment**, refreshed after each save.
5. **Rich-text/WYSIWYG (ProseMirror/Tiptap)** only if authors must edit formatted prose in place. ProseMirror's document model is a tree and could map to ours, but it needs a two-way converter and its own state, which fights Datastar. Not for round 1.

Datastar rules for any client-owned widget: mark its container `data-ignore-morph` so fragment patches do not rebuild it; sync with `@post` carrying the value (via a signal) on a debounce; never let the widget be the source of truth, the server tree is.

**Order:** textarea → CodeMirror for node text and model → reconsider ProseMirror only if users ask.

## 4. Brief for Claude Design: round 2

Attach: round 1 handoff, `ui-handoff.md`. Then say:

> Round 1 matched the Library track. Keep its shell, tokens, chips and tables exactly. Now design the Workshop screens in the same style. Use the screen list in `ui-handoff.md` section 4 (W1–W8), P1 first: **W4 template builder, W3 clause version diff, W5 template revision diff, W2 clause editor, W6 release**.
>
> Changes from round 1:
> - Clauses in a template are **pinned references** (`Indemnity v3`, with a "v4 available" badge), not pasted text.
> - The builder is a three-pane workspace: a node **tree** at left (text, field, if/else, optional, with, list, join, clause, alternatives), the selected node's editor in the centre, live preview and a problems list at right. Show insert, move up/down/indent, and delete on the selected node. Moves must be keyboard-operable.
> - "Alternatives" lets the author pick one of several clauses when building; show it as a node with options, one marked chosen.
> - Approval is a **sign-off** (thumbs-up or objection with a note) by a named person, shown as a small panel. It is not an ordered signature chain.
> - Diff views: show added, removed, moved and changed nodes in a tree; moves read "Indemnity moved from §3 to §5".
> - Release screen: checklist, resulting `sha256:…` with copy, clause manifest table, Verify button, and a clear **tampered** state listing what differs.
> - Node text areas are plain textareas for now. Leave a slot where a code editor can be dropped in.
>
> Output: the same format as round 1 (HTML prototype + README with fragment ids and signals per screen).

## 5. Decisions (from Tom)

1. **Two kinds of authority, kept separate.**
   - *Drafting:* the author of the change, plus lightweight sign-offs (thumbs-up/objection). Built (`signoff`).
   - *Signing:* authorized signatures and certifications, based on **original or delegated authority**, proven with a **DID credential**. The ordered chain in the prototype (authorize, attest, counterparty, notary) stays, but it belongs to this second track and is not a drafting sign-off. Not built.
2. **DIDs are real.** The Identities screen is live data, not a placeholder.
3. **Matters** are groups of documents that share a **Concerto manifest** (e.g. street paving: contract, bid form, payment request). They are tracked together through execution. Not built.
4. **Contract workflow status** (draft, review, signing, executed) is needed. Bitemporal history on it is nice-to-have, not required.

### What the running app already has (checked 2026-10-23)

The signing track, matters and workflow exist in Trustblocks `main`; my first list of "new work" was wrong.

| Need | Already there |
|---|---|
| DIDs, keys | `did/key`, `did/vc` (VC-JWT, Ed25519), `xtdb/identity` (custodial keys, found by login email) |
| Original and delegated authority | `xtdb/certification`: `grant-office!` (OfficeGrant), `designate!` (Designation for a matter), `authority-for`, `certify!` (checks authority, signs Receipt/Attestation) |
| Credentials in valid time | `xtdb/credential` (validFrom/validUntil become XTDB valid time) |
| Signature authorizations list | `certification/signature-authorizations`, `attestations-for-contract`, route `/authorizations` |
| Matters, with the manifest | `xtdb/matter`, `model/manifest` (checklist by role), routes `/matters`, `/matters/:id`, design note `matters.md` |
| Lifecycle / workflow | `xtdb/advance` + `xtdb/execute` (state advances by certified events, `trigger!`) |
| Word export | `template/word`, route `/contracts/:id/document.docx` |
| Contract, templates, clauses pages | `app/contracts`, `app/templates`, `app/clauses` |

### What is genuinely not there

This session's work on the `clause-tracking` branch (clause versions, template tree and revisions, release/verify, drafting sign-offs) is the Workshop side, and it is not yet connected to the above:

1. **Release is not yet what is signed.** `certify!` attests a contract's state; nothing ties a signature or attestation to a release SHA. Decide: the contract's template release SHA is recorded on the contract (`release-of` exists), and the Attestation names it, so `verify` can say "signed content unchanged".
2. **Matter release.** A matter's package/manifest is not fingerprinted together with its documents' releases.
3. **Clause references** in the running contracts and templates are not yet clause-library pins.
4. **Workshop UI.** No routes for clauses-versions, tree builder, diffs, release. The existing `/clauses` and `/templates` pages need reading before round 2 so the new screens extend them rather than duplicate.

### Open questions (revised)

- Should the Attestation name the release SHA (and a matter's SHA)? I think yes.
- How does the existing `/clauses` page (SCI clause *logic*) relate to library clause *text*? Same screen with two parts, or separate?
- Is the China Grove console in the handoff meant to replace the current Biff pages, or restyle them?

## 6. Decisions, round 2 (from Tom)

- The console **replaces** the old pages (they were a showcase of Concerto/Clojure/XTDB). The drafting side is entirely new.
- The four statuses (Draft, Attorney review, Out for signature, Executed) are right. Drafting statuses are tracked lightly; executed contracts rigorously.
- Clauses are mostly text, some logic, and are shared across contracts, so their placeholders and schemas must be consistent. Model: clauses declare what they need from a **model repository** (versioned, pinned, like Accord's models but our own), and a contract's placeholders must all resolve against its composed model. The repository may also carry **default data** (e.g. the Town is always China Grove).
- Contracts are not always explicit about attestation or approval. Add an explicit **attestation clause** to the library, tied to the lifecycle event it satisfies.
