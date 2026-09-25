# Template Playground WebMCP PoC

Trustblocks defaults to Accord Project's deployed Template Playground at
<https://playground.accordproject.org>. Accord's `webmcp-poc` branch is a
working proof of concept, not a separately hosted deployment; to explore it,
run that branch locally and point Trustblocks share links at its origin.

This was checked against branch commit `8206dc182337ee402dd3a7c52d40a02c0c234f0d`:
its 42 test files / 296 tests passed and its production build completed (with
existing CSS/chunk-size warnings). Re-check the branch before relying on these
details: WebMCP and its browser API are explicitly experimental.

## What the branch adds

When the playground's Design v2 layout mounts, it registers six guardrailed
in-page WebMCP tools:

- `get_template_text`, `get_model`, `get_data`
- `set_template_text`, `set_model`, `set_data`

An external agent supplies the reasoning. The page exposes thin read/write
primitives over its panels; model writes are parsed by Concerto and must carry
an `@template` root, and data writes must be valid JSON.

It **does not** add multiple model files. The store still has one `modelCto`
string, and `rebuild()` still calls `ModelManager.addCTOModel` once. Therefore
the manager-employment contract remains unavailable in Template Playground
because its correct model closure needs three non-bundled namespaces. Concerto
Viewer remains the complete multi-model viewer.

At the checked commit the branch's direct Concerto dependencies were still
4.1.x. That is independent from Trustblocks' own toolchain, which is pinned to
Concerto Core 5 through `@accordproject/concerto-cli` 4.2 and Template Engine
5.1. Watch the branch for its own dependency upgrade rather than assuming the
PoC already tests v5.

## Status: parked, run locally

Decided 2026-09-24: this is Trustblocks' playground, run as a **local service**
rather than hosted, and **parked** until the single-model limit is addressed.
Nothing is forked or contributed upstream; `bin/playground` only clones
Accord's public branch read-only.

Why local: the agent relay that lets Claude Code drive the page's WebMCP tools
exists only in the branch's Vite development build (`import.meta.env.DEV`),
and a production build exposes WebMCP only to Chrome with an experimental
flag. Why parked: the playground runs Concerto offline with a fixed set of
eight preloaded Accord models, and rejects any other import -- Accord's own
published `usa.state` included. Each of our templates now has a single
namespace of its own, as Accord's do, but still imports `usa.state` and our
shared models (`municipal.agreement`, and `attestation` for the manager
contract), so neither opens in it. What it would buy once that changes is a way in that is not a URL:
pushing a template through `set_template_text` / `set_model` / `set_data`
instead of a share link that carries the whole template -- measured at about
16,500 characters for the manager employment contract and **72,000** for the
Street Resurfacing contract (STR-27), well past what mail, chat and link
previews reliably preserve.

The WebMCP tools are read/write handles on the playground's three panels; they
do not author anything. Our own authoring loop -- trustblocks-templates'
`bb check` / `bb conformance` on Concerto 5 / Template Engine 5.1, plus the
model tests -- is stricter than
the branch, which is still on Concerto 4.1 / Template Engine 4.0.

## Run the branch locally

```sh
bin/playground
```

keeps a sibling checkout at `~/Code/template-playground` (override with
`TRUSTBLOCKS_PLAYGROUND_DIR`) on the verified commit below (override with
`TRUSTBLOCKS_PLAYGROUND_REF`), runs `npm ci` when its dependencies are missing
or stale, and serves it at `http://localhost:5173`. The checkout stays outside
this repository so its generated Node files never enter git. The first page
load compiles the branch's large dependencies and can keep the tab busy for a
minute or more. Start Trustblocks in another terminal,
setting the compatible playground origin before the JVM starts:

```sh
cd ~/Code/Trustblocks
rm -rf storage log
TRUSTBLOCKS_TEMPLATE_PLAYGROUND_ORIGIN=http://localhost:5173 \
  bin/clj -M:xtdb-jvm-opts -i dev/seed.clj
```

Template Playground actions for eligible, single-model templates now open the
local WebMCP checkout with its current `/#data=<lz-string>` share payload. The
environment value only changes the Template Playground origin; Concerto Viewer
continues to use Accord's deployed Concerto Playground.

## Exercise WebMCP

The branch documents two experimental host paths:

1. Enable `chrome://flags#enable-webmcp-testing`, relaunch Chrome, open the
   playground, and enable **Design v2** in Settings; or
2. Use its development relay with an MCP-capable agent. This repository's
   `.mcp.json` declares it for Claude Code, pinned to
   `@mcp-b/webmcp-local-relay@5.1.0` rather than `@latest`; approve it when
   Claude Code asks. For other clients:

   ```sh
   npx -y @mcp-b/webmcp-local-relay@5.1.0 --host :: --port 9333
   ```

   The page side of the relay is the branch's own code and still loads its
   embed script from a CDN at `@latest` -- acceptable for a local
   development tool, and one more reason this is not deployed.

   Hard-reload the playground tab, then use `webmcp_list_sources` and
   `webmcp_list_tools` from the agent. The IPv6 `--host ::` value matters on
   macOS because the page connects through `[::1]`.

The relay/polyfill is development scaffolding loaded only by the playground's
Vite development build. `.mcp.json` only tells a local Claude Code session how
to start the relay; never bundle the relay or polyfill into Trustblocks' own
web app, or treat it as a production browser integration.

## Concerto 5 verification in this repository

`npm install` now supplies the parser and reference toolchain locally:

```sh
node_modules/.bin/concerto --version     # 4.2.0; depends on concerto-core 5
npm ls @accordproject/concerto-core @accordproject/concerto-cto
bin/clj -M:run test
```

Template conformance (`bb conformance`) now runs in the
TrustBlocks/trustblocks-templates repository, with the templates.

`bin/clj` automatically sets `CONCERTO_CLI` to
`node_modules/.bin/concerto`, avoiding accidental use of an older globally
installed CLI. An explicitly supplied `CONCERTO_CLI` still wins for deliberate
compatibility testing.
