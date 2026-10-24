# Handoff: Trustblocks — China Grove Contract Management Console

## Overview
Internal console for the Town of China Grove, NC to manage municipal contracts end to end: monitor active contracts, draft new ones, author templates (Concerto models + data), review clauses, and collect signature authorizations. Backed by the `tbrooke/nc-municipal-agreements` repo (templates under `content/municipal/…`, e.g. `surety-bond-scm`). Target stack uses **Datastar** (server-driven hypermedia + signals).

## About the Design Files
`Trustblocks.dc.html` is a **design reference built in HTML** — a clickable prototype of intended look and behavior, not production code. Recreate it in the target codebase (server-rendered templates + Datastar) using its existing patterns. Do not ship the HTML. All data in it is placeholder (contracts, matters, DIDs, template text).

## Fidelity
**High-fidelity** for layout, type, color, spacing. Neutral UI is deliberate; the logo is the only brand element. Copy and sample data are placeholders.

## Global Layout
- Viewport-height flex column, min-width 760px, page bg `#f6f6f4`, font Public Sans (400/500/600/700), mono IBM Plex Mono; base 14px, color `#1c1c1a`.
- **Header** 64px, white, 1px bottom border `#e2e2de`, padding 0 24px: logo (`logo.png`, 38px high, clickable → Home), 1×24px divider, "Trustblocks" (15px/600) + "· Municipal Contracts" (400, `#6b6b66`).
- **Sidebar** 208px, white, right border, padding 16/12, items stacked 2px: Home, Active Contracts, Contract Drafting, Templates, Identities. Item: 9×12 padding, radius 6, 14px; active bg `#eeeeea`, 600, ink; inactive `#55554f`/500. Bottom: dev toggle "data-signals" (mono 12px) showing a fixed-bottom-right dark panel (`#1c1c1a`, 340px) with current state JSON — maps to Datastar signals.
- **Main** padding 28/32/48, scrolls.
- No role switcher: **role is set at login** (Town Clerk, Town Manager, Attorney). Prototype hard-codes Clerk.

## Screens

### 1. Home
Full-bleed (negative margins to fill main), min-height `100vh-64px`, bg `#f6f6f4`, with an `<image-slot>` drop target behind content (user can drop an aerial photo later; leave as optional background image setting). Content padding 32/48/48, column, space-between, gap 32.
- Top row: logo 96px high; right caption "TOWN OF CHINA GROVE, NORTH CAROLINA" 12px, letter-spacing .14em, uppercase, `#6b6b66`.
- Bottom: "Welcome to the" 26px/600; h1 "Contract Management Console" 64px/700, line-height 1.02, letter-spacing -.02em, max-width 900, margin-bottom 36.
- Two buttons (min-width 280, radius 10, gap 16, wrap): **Active Contracts →** (bg `#1c1c1a`, white, padding 20/28, subtitle "Status, next steps and search" 14px `#dcdcd6`; hover `#2c2c29`) and **Draft Contracts →** (2px `#1c1c1a` border, transparent, padding 18/28, subtitle "Editor, clauses, contracts and matters" `#55554f`; hover `#eeeeea`). Titles 22px/600.

### 2. Active Contracts (full width)
1. **Current Status** panel (white, 1px `#e2e2de`, radius 8, margin-bottom 24): header bar (`#fafaf8`, padding 12/16) "Current Status" 600 + right summary "N drafts · N attorney review · N out for signature · N executed" (12px muted). Up to **5 rows** (grid `2.4fr 1.4fr 2fr 24px`, gap 12, padding 12/16, hover `#fafaf8`): name (500), status chip, next-step text (13px `#55554f`), arrow. Click → Contract detail. (Selection rule is a placeholder: first five; real rule should be "needs attention / most recent".)
2. Row: h2 "Active Contracts" (15px/600) left, **query box** right (360px, border `#d4d4cf`, radius 6, padding 9/12) — filters name, counterparty, type, status live. Datastar: `data-bind` + debounced `data-on-input` hitting a search endpoint.
3. **Table** (white card, radius 8): columns `2.4fr 1.4fr 1fr 1.2fr` — Contract (name + type subline), Counterparty, Amount (tabular-nums), Status chip. Header 12px uppercase `.04em` muted. Row padding 13/16, border `#eeeeea`, hover `#fafaf8`.

**Status chips** (pill, 12px/500, padding 3/9): Draft `#eeeeea`/`#4a4a45`; Attorney review `#f6ead0`/`#6b4a0a`; Out for signature `#dde8f5`/`#1f3a5f`; Executed `#dcefe0`/`#1f5a31`.
**Next step text**: Draft → "Clerk: complete draft data"; Review → "Attorney: review clauses"; Signing → "Signatories: authorize & attest"; Executed → "Complete — export copy".

### 3. Contract detail
Back link "← All contracts". Title 24/600 + status chip right. Meta line: type · counterparty · amount · statute (muted).
- **Action cards** (3-col grid, gap 12): Town Clerk "Prepare draft", Attorney "Review clauses" (shows pending count of 6), Town Manager "Authorize signature". Card: white, radius 8, padding 16; owner label 12px uppercase muted; title 15/600; body 13px; button full-width. Enabled only for the matching role and state (enabled = dark `#1c1c1a`; disabled = `#f1f1ee`, text `#9a9a94`, card opacity .75).
- **Tabs**: Clause Listing | Signature Authorizations | Document & Word export (active: ink + 2px bottom border).
- **Clause Listing**: rows with mono number (§1), title 600, text 13px, mono variable list (`{{principal_name}}` …), right pill button: Attorney sees "Approve" toggle → "✓ Approved" (`#dcefe0`/`#1f5a31`); others see "Pending".
- **Signature Authorizations**: ordered signers — Town Manager (authorize), Town Clerk (attest), Counterparty signer (record), Notary (record). Each shows role, DID (mono), note "Waiting on prior step" until predecessor done; button enabled only for right role and when predecessor complete; done → "✓ Authorized".
- **Document & Word export**: two columns (1fr / 280px). Left: serif (Times) document preview, white card, padding 28/36, 14px/1.6, centered bold title. Right: "Export to Word" card, dark button; on click shows "Queued: <file>.docx" (stub — real implementation renders template+data to .docx).
- **Workflow**: Draft → (Clerk marks ready) Attorney review → (all clauses approved) Out for signature → (all signers done) Executed.

### 4. Contract Drafting
Title + subtitle. **Editor** card (white, radius 8): toolbar (`#fafaf8`) with "Untitled draft", char count, Clear, Save draft (→ "✓ Saved"); monospace textarea 13px/1.7, min-height 300, padding 20/24, resizable. Below: **segmented switch** Clauses | Contracts | Matters (active dark) + library search (300px). Library list rows: title 500, sub 13px muted, mono meta right, action button: Clauses → "Insert" (appends `## Title` + text to editor); Contracts → "Use as base" (replaces editor); Matters → "Attach" (appends `<!-- Matter ID: title -->`).
Implementation note: the editor is a plain textarea placeholder; a real markdown/template editor (Accord template syntax with `{{variables}}`) is expected.

### 5. Templates
Header with title, subtitle "Surety Bond — SCM · one self-contained Concerto model per template", and **Validate model + draft** button (dark; shows green "✓ Model valid · 9 fields bound · draft renders"). Tabs: **Template text** | **Concerto Model** | **Data**, each pane **full width**: filename bar (mono 12px) + `<pre>` mono 13px/1.7, padding 24/28, min-height 60vh. Data tab adds **Enter / edit data** button → modal (560px, radius 10, overlay `rgba(28,28,26,.45)`): one labeled input per model field (mono key label), Cancel / Save data. Saving updates the JSON view (bond_amount coerced to number).
Fields: principal_name, principal_entity_type, surety_name, obligee_name, project_address, bond_amount, completion_period, governing_law. Real model/template text lives in the repo (`content/municipal/surety-bond-scm/`).

### 6. Identities
2-col grid of cards: role, "● Active" (`#2f6b3f`), description, mono DID block (bg `#f6f6f4`). Roles: Town Manager, Town Clerk, Town Attorney, Mayor. DIDs are placeholders.

## Interactions & State
Prototype state (map to Datastar signals / server state): `view`, `contractId`, `tab`, `query`, `draftText`, `libTab`, `libQuery`, `tplTab`, `tplData`, `dialogOpen`, per-contract `status`, `approvedClauses{}`, `signatures{}`, `exported`. Role comes from session. Suggested Datastar mapping: nav = `data-on-click` → `@get` swapping main fragment; search/query via `data-bind` + `@get` with debounce; approve/authorize/attest = `@post` returning updated fragment; dialog = signal-driven. No animations beyond hover color changes. Layout is fluid down to ~760px.

## Design Tokens
Colors: ink `#1c1c1a`; ink-hover `#2c2c29`; body-secondary `#55554f`; muted `#6b6b66`; faint `#8a8a84`/`#9a9a94`; page `#f6f6f4`; panel-header `#fafaf8`; hairline `#eeeeea`; border `#e2e2de`; input border `#d4d4cf`; button border `#c4c4be`; link `#1f3a5f` (hover `#0e2240`); success `#2f6b3f`/`#1f5a31` on `#dcefe0`; warn `#6b4a0a` on `#f6ead0`; info `#1f3a5f` on `#dde8f5`.
Type: Public Sans 12/13/14/15/22/24/26/64; IBM Plex Mono 11–13. Radius: 4, 6, 8, 10, pill 99. Spacing: 4/8/10/12/16/20/24/28/32. Shadows: modal `0 16px 48px rgba(0,0,0,.25)`; signals panel `0 8px 24px rgba(0,0,0,.2)`. Cards are flat (1px border, no shadow).

## Assets
- `logo.png` — China Grove municipal logo (supplied by the user). Use original brand asset in production.
- `image-slot.js` — prototype-only drop target for an optional Home background; replace with a configurable background image.
- Fonts: Public Sans and IBM Plex Mono from Google Fonts.

## Files
- `Trustblocks.dc.html` — the full prototype (all screens, logic class at the bottom)
- `support.js`, `image-slot.js` — prototype runtime helpers (not for production)
- `logo.png`
- Source repo: `tbrooke/nc-municipal-agreements` (see `github.md`)
