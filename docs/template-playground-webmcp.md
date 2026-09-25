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

## Run the branch locally

Use a sibling checkout so generated Node files never enter this repository:

```sh
cd ~/Code
git clone https://github.com/accordproject/template-playground.git
cd template-playground
git switch webmcp-poc
npm install
npm run dev
```

It serves at `http://localhost:5173`. Start Trustblocks in another terminal,
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
2. Use its development relay with an MCP-capable agent:

   ```sh
   claude mcp add webmcp-local-relay -- \
     npx -y @mcp-b/webmcp-local-relay@latest --host :: --port 9333
   ```

   Hard-reload the playground tab, then use `webmcp_list_sources` and
   `webmcp_list_tools` from the agent. The IPv6 `--host ::` value matters on
   macOS because the page connects through `[::1]`.

The relay/polyfill is development scaffolding loaded only by the playground's
Vite development build. Do not add it to Trustblocks or treat it as a
production browser integration.

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
