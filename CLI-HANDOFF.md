# CLI agent handoff — Trustblocks

Read `AGENTS.md` first; it is the durable project handoff and contains the
architecture, non-obvious decisions, conventions, current state, known issues,
and exact baseline commands. This file is the shorter bridge from the Delta
thread to a native-terminal agent.

## Current git state

The work is split into two coherent, stacked branches:

1. `feat/operations-dashboard` at `0d88083`
   - PR: <https://github.com/TrustBlocks/trustblocks-contracts/pull/16>
   - Base: `main`
   - Adds the Trustblocks/Municipal Contracts workspace, contract action cards,
     Clause Listing, Signature Authorizations, Word-export stub, four seeded
     China Grove DID identities, `docs/operator-runbook.md`, and UI/domain
     tests.
2. `chore/concerto5-webmcp` (current branch)
   - Based on `feat/operations-dashboard`; create/review it as a stacked PR
     until PR #16 merges.
   - Pins the local Concerto CLI / Accord toolchain to Concerto Core/CTO 5,
     updates Template Engine to 5.1, updates Template Playground links to the
     current `/#data=` share format, and documents/configures local exploration
     of Accord's `webmcp-poc` branch.

To continue with every current change from the native checkout:

```sh
cd ~/Code/Trustblocks
git fetch origin
git switch --track origin/chore/concerto5-webmcp
npm install
bin/clj -M:run test
```

If the local branch already exists, use `git switch chore/concerto5-webmcp`
instead. Do not work from `main` if you expect the new dashboard — it is not
merged there yet.

## What was verified

Trustblocks:

- `node_modules/.bin/concerto --version` => `4.2.0`; that CLI resolves
  `@accordproject/concerto-core` and `concerto-cto` 5.0.0.
- `bin/clj` now sets `CONCERTO_CLI` to the package-lock-pinned local binary,
  avoiding the machine's old global Concerto CLI 3.16.7.
- `bin/clj -M:run code-quality` => 41 tests / 143 assertions / 0 failures.
- `bin/template-conformance` drafts the real manager-employment template under
  cicero-core 2.2.0 + Template Engine 5.1.0 + Concerto 5.

Accord's external `template-playground` `webmcp-poc` branch, checked at
`8206dc182337ee402dd3a7c52d40a02c0c234f0d`:

- 42 test files / 296 tests passed.
- Production build completed with existing CSS/chunk-size warnings.
- It adds six WebMCP tools: get/set template text, model, and data.
- It remains a one-`modelCto` playground and still calls `addCTOModel` once;
  it does **not** solve the multi-namespace manager-employment limitation.
- It is source code, not a separately hosted deployment. See
  `docs/template-playground-webmcp.md` for the local checkout, Chrome flag,
  relay, and Trustblocks origin override.

The updated Node dependency graph reports 22 transitive `npm audit` findings
(9 moderate, 13 high, 0 critical). Direct Accord packages are current; npm's
suggested fixes are semver-major downgrades to obsolete package lines. Do not
run `npm audit fix --force` without tracing those paths.

## Delta checkout and transcript

The Delta-managed checkout used for this work is:

```text
~/Code/Trustblocks/.delta/worktrees/4w7rq4w037rr/trustblocks-contracts
```

That directory is a normal materialized checkout of the Delta worktree. It is
**not** the transcript archive. The sibling `.delta/clones/` and
`.delta/worktrees/` directories are machine-local Delta bookkeeping/checkouts
and are hidden from git.

The current thread is:

```text
Title: Review Trustblocks application status
Thread ID: ksQQDqRA7EhoSoqO97C2V7FChpLMkpIBxBDkaC1z-NNLsY4hRvOeZfgt
```

The local DeltaDB file observed on this machine is:

```text
~/Library/Application Support/delta/user_01hpytdvwxprpmek5ejsdgbgg2/data.sqlite
```

DeltaDB is Delta's replicated internal database: it stores messages, comments,
file-tree history, and edits as deltas, while checkouts materialize those files
for git, editors, and terminal tools. The transcript is stored in that database,
not as JSON/Markdown inside the repository.

Use the supported CLI to inspect/export it from a normal native terminal:

```sh
DELTA=/Applications/Delta.app/Contents/MacOS/delta
THREAD=ksQQDqRA7EhoSoqO97C2V7FChpLMkpIBxBDkaC1z-NNLsY4hRvOeZfgt

"$DELTA" cli thread show --format summarize "$THREAD"
"$DELTA" cli thread show --format full "$THREAD" > /tmp/trustblocks-thread.json
"$DELTA" cli thread request "$THREAD" > /tmp/trustblocks-model-request.json
"$DELTA" cli thread data "$THREAD" > /tmp/trustblocks-delta-tree.json
```

Those commands may exclude the currently open thread when launched from an
agent turn carrying `DELTA_CURRENT_THREAD_ID`; run them from the independent
native terminal after leaving/closing this Delta turn. Use `delta cli thread
search`, `list`, and `find-relevant` to locate other persisted threads.

Do not edit `data.sqlite` directly. `delta cli db` exposes read-only storage
inspection (`stats`, `attribute`, `worktree-files`, `node`, `root`) when raw
DeltaDB investigation is useful.

## Can DeltaDB be used without the desktop interface?

Partly:

- Yes: the installed `delta` binary can inspect persisted threads and raw
  DeltaDB from a native terminal, and `delta cli run --model <MODEL> --repo .`
  can launch one new headless agent turn and output its shared thread URL.
- No: DeltaDB is not a general application database or a supported standalone
  replacement for git/SQLite APIs. It is Delta's synchronization/history
  layer. Existing-thread CLI commands are primarily inspection/export; the
  documented `run` command starts a new headless turn rather than continuing
  this exact conversation interactively.
- A different CLI agent should consume `AGENTS.md`, this file, normal git
  branches, and (if needed) an exported transcript. It should not depend on
  DeltaDB internals to work on the codebase.

## Immediate continuation

1. Commit and push the Concerto 5/WebMCP increment on
   `chore/concerto5-webmcp`.
2. Open it as a stacked PR against `feat/operations-dashboard`; after PR #16
   merges, change/rebase its base to `main` before merging.
3. Preserve the finding that WebMCP is valuable for agent-driven authoring but
   does not yet change the playground's single-model architecture.
4. When changing any `.cto` or Accord package, run both:

   ```sh
   bin/clj -M:run code-quality
   bin/template-conformance
   ```
