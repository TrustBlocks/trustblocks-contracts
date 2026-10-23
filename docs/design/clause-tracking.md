# Clause tracking, template revisions and pinned releases

Backend only so far (no UI). Three layers, each an immutable record of what was
true, with XTDB's bitemporality answering "as of when":

| Namespace | Holds | Mutable? |
|---|---|---|
| `xtdb.clause-library` | `:clause-version` (the words), `:clause-state` (draft / approved / retired) | version: never. state: yes, with full history |
| `xtdb.template-tree` | `:template-revision` (every save), `:template` (the head), `:clause-use` (what the head uses) | revisions: never. head and uses: move together, in one transaction |
| `xtdb.release` | `:release` (a pinned bundle, keyed by its SHA), `:contract-release` (which release a contract is deployed against) | release: never. deployment: yes, with history |

The tree, clause and release logic itself (parsing, hashing, diffing, pinning,
composing a Concerto model from clauses) is in `templatemark-clj`; these three
namespaces only persist it.

## The two sides

**Builder side: everything changes by adding.** A clause is edited by saving a
new version; a template by saving a new revision. Neither has an update. Each
carries the SHA-256 of its content and the hash of what came before it, so the
history is a chain. A diff between any two versions or revisions is a function
of the two stored trees (`diff-versions`, `diff-revisions`; node ids are stable
across edits, so the diff reports edits, moves and reorders rather than
guessing). A save names the revision it started from; if the head has moved it
is refused (`:stale-template`) and the caller diffs and merges, and the
transaction asserts it, so two saves racing cannot both land.

**Deployed side: pinned, with a SHA.** `release!` takes a template revision and
resolves every clause reference (in the template and in the library clauses
those pull in) to an exact version; it refuses anything not `:approved`. The
release stores the pinned tree, the manifest (every clause, version, content
hash), the composed Concerto model if there is one, and a SHA over all of them.
It is keyed by `sha256:<hex>`, so a release cannot be changed -- changing
anything changes its key. `deploy!` ties a contract to a release (and verifies
it first); amending a contract moves it to a newer release, and the old one
stays in the history.

`verify` re-reads every pinned clause and recomputes every hash. `check-active`
runs it over every release a contract is deployed against and says which
contracts rest on a release that no longer holds.

## What it answers

| Question | Function |
|---|---|
| Which templates use clause X (at version N)? Now, or on a past date? | `template-tree/clause-users` (`:version`, `:at`, `:as-of`) |
| Which **deployed contracts** contain clause X version N? | `release/deployed-with` |
| Which version of clause X applied on 1 March? | `clause-library/in-force` (`:at`) |
| Who approved this version, and when did that take effect vs when did we record it? | `clause-library/state-history`, `state-audit` |
| What did the template look like on a date, as we then understood it? | `template-tree/head`, `uses` (`:at`, `:as-of`) |
| What changed between clause versions / template revisions? | `diff-versions`, `diff-revisions` |
| Which contract was on which release on a date? | `release/release-of` (`:at`) |
| Have the clauses behind every contract in force stayed unchanged? | `release/check-active` |
| Which pinned uses are behind the library's latest? | `templatemark.clauses/audit` over `clause-users` |

## Tamper evidence

The library has no function that alters a stored version, and writes are
guarded inside their transactions. But XTDB itself accepts a direct write, so
every read re-hashes what it returns: a clause version, template revision or
release whose content no longer matches its recorded hash is refused
(`:clause-corrupt`, `:template-corrupt`) and reported by `verify`. A release
whose manifest was edited to hide a change fails the SHA; a pinned tree edited
after release fails the template hash; a stored model edited after release
fails the model hash.

## Rules worth knowing

- A library clause must pin the clauses it depends on (`v=N`, never `latest`):
  it is immutable, so what it depends on must be too. `put-version!` refuses
  otherwise.
- A template that floats (`v=latest`) picks up **approved** clauses only. A
  draft is never picked up by something that floats.
- A release can legitimately hold two versions of one clause: the template pins
  or floats to one, a library clause it uses pins another. `deployed-with`
  reports each.
- Approval cannot be undone, and a retired version cannot be revived: the words
  someone approved are replaced by a new version, not edited back to draft.
- Saving content identical to the latest version or head writes nothing.

## Not done

- No UI, no HTTP routes.
- Approval is a status change recorded with `:by` and `:note`. Tying it to a
  signed credential (as officeholder certification does) is the natural next
  step: the council's approval of a clause version as a VC naming its hash.
- `templatemark-clj` is a sibling checkout (`:local/root`) until it has a
  remote to pin by SHA.
- Importing existing markdown re-parses with fresh node ids, so a diff against
  an earlier revision shows everything replaced. The builder edits the loaded
  tree, which keeps ids; an `align-ids` step for imports would fix it.
