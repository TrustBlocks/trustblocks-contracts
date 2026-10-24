# Municipal Contracts: UI design brief

For a design tool (Claude Design or similar). Ask for **HTML + Tailwind prototypes**, one per screen, not Figma frames. The app is server-rendered, so what you design is what we ship.

## 1. What the app is

One application and one XTDB database, with two tracks:

| Track | Job | Main users |
|---|---|---|
| **Library** | Executed contracts: browse, search, see status, update status, answer "which contracts contain clause X?" | Clerks, department staff, attorneys |
| **Workshop** | Contract development: write and revise clauses, build templates from clauses, release a template for use | Contract authors, managers, attorneys |

The two tracks share one nav and one visual language. A user can move from a contract in the Library to the clause in the Workshop that it was made from, and back.

## 2. Constraints

- **Stack:** Biff (Clojure), hiccup, Tailwind, **Datastar**. The server renders HTML. Datastar swaps fragments by element `id` and holds small client state in *signals*. There is no SPA and no client-side router.
- **So design in fragments.** For each screen, mark which regions update on their own (a row, a panel, a diff pane) and give each a stable `id`. Prefer a few large fragments to many small ones.
- **Signals are for UI state only:** filter text, selected tab, which node is selected in the tree, open or closed panels. Data lives on the server.
- **Prototype output:** plain HTML with Tailwind classes. Add Datastar attributes where behaviour matters: `data-signals`, `data-bind`, `data-on-click="@get('/…')"`, `data-show`, `data-text`. Confirm the Datastar version with us before relying on exact attribute syntax.
- **Desktop first** (office use, wide screens). It should degrade sensibly to a tablet.
- **Accessibility:** government software. Keyboard-operable everything (the template tree especially), visible focus, AA contrast, status never conveyed by colour alone.
- **Tone:** calm, dense, legible. Closer to a ledger or a code review tool than a marketing site. No decoration.

## 3. Vocabulary (use these words in the UI)

- **Clause:** a reusable piece of contract text with its own versions. Versions are numbered 1, 2, 3 and never change once saved.
- **Clause status:** `draft` → `approved` → `retired`. Only approved clauses go into released templates.
- **Template:** a document built from text and clauses, with logic (conditionals, repeated lists, alternatives). Edited as a **tree**. Every save is an immutable **revision**.
- **Alternatives:** the template author chooses one of several clauses when building. This is *template-time* choice, not run-time logic.
- **Pin:** a reference fixed to an exact clause version. `latest` is a floating reference, allowed while drafting only.
- **Release:** a frozen template. Every clause is pinned and the whole thing is fingerprinted as `sha256:…`. A released contract can be **verified** at any time. If any clause, the template or the data model changed, verification says exactly what.
- **Sign-off:** a thumbs-up or a objection from a person (manager, attorney) with an optional note. It is not a legal certification. The author's own sign-off does not count. An objection blocks release.
- **Deployed:** a contract made from a release, in force from a date.

## 4. Screens

Priority marks: **P1** design first, **P2** next, **P3** later.

### Library track

**L1. Contract list (P2).** Table of executed contracts: title, counterparty, department, status, effective dates, release SHA (short). Filter by status and department; text search; filter by "contains clause X". Row click opens L2.

**L2. Contract detail (P2).** Header with status and a status control. Tabs: *Text* (rendered agreement), *Data* (the field values), *Clauses* (each clause used, its pinned version, and a badge if a newer version exists), *History* (what changed and when). A **verification banner**: green "Verified: unchanged since release", or a red list of what differs.

**L3. Clause usage (P3).** Pick a clause: which templates and contracts use it, which version each is pinned to, flagging stale pins ("pinned to v2, latest is v4").

### Workshop track

**W1. Clause library (P2).** List of clauses with latest version, status, and number of templates and contracts using it. Search and status filter.

**W2. Clause editor (P1).** Edit the clause body (template text with `{{ }}` directives) beside its data model fragment. Shows version number, status control, sign-off panel. Saving creates a new version, never edits one. Show the problems found on save (unknown field, unpinned nested clause) inline.

**W3. Clause version diff (P1).** Compare two versions of a clause: side by side or unified, with changed nodes highlighted. A header says what changed: added, removed, changed. Beside it, "N templates and M contracts use the older version" with a link to each.

**W4. Template builder (P1, the hardest screen).** A three-pane workspace:
- *Left:* the **tree**. Nodes: text, field, if/else, optional, with, list, join, clause reference, alternatives with options. Select, insert, **move** (drag or keyboard), delete. A clause node shows its id, pin and a stale badge.
- *Centre:* the selected node's editor.
- *Right:* a **live preview** of the drafted text using sample data, plus a **problems** list (type errors and warnings from checking the template against the data model).

Needs a clause picker (search the library, choose a version or `latest`) and an "add alternatives" action. Show revision number and an unsaved indicator. Save creates a revision.

**W5. Template revision history and diff (P1).** Revision list. Pick two to see a *tree* diff: nodes added, removed, moved, reordered, changed. Moves must read clearly ("Indemnity moved from section 3 to section 5"). Plain-text diff is secondary.

**W6. Release (P1).** The action that freezes a template. A checklist: every clause approved and pinned, composed model has no errors, required sign-offs present, no objections. Shows the resulting SHA and the clause manifest (id, version, hash). Confirm button is disabled with reasons if anything fails. After release: a deploy action and a verify action.

**W7. Sign-offs (P2).** A panel reused on W2, W4 and W6. Each person's current verdict (thumbs-up or objection), role, note, time, and whether it is still current (the subject changed after they signed). Add or change your own. History expands.

**W8. Time travel (P3).** "Show me this clause or template as it was on date D." A date picker that re-renders the current view as of that date. Mark clearly when viewing the past, and make it read-only.

## 5. States to design for every screen

Empty, loading, error, "nothing changed", "read-only because released or viewing the past", and **tampered** (verification failed). The tampered state is the most important error: it must be unmistakable and say which clause or file differs.

## 6. Things the UI must make visible

- The SHA of a release, short form with copy-to-clipboard.
- Stale pins ("newer version available") wherever a clause reference appears.
- Who and when for every change.
- That versions and releases are immutable. Show "new version" not "edit".

## 7. Out of scope

Login and user management, formal authority or credential flows, document-signing, notifications, mobile.

## 8. What we want back

1. A shared layout (nav with Library and Workshop, page header, status badges, the diff styling) as a Tailwind kit.
2. P1 screens as separate HTML files, with fragment `id`s marked in comments and Datastar attributes on interactive parts.
3. A short note per screen: which regions are fragments, what signals it needs, and any interaction that cannot be done in server-rendered HTML.
