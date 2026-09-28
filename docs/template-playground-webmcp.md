# Trustblocks' Template Playground

A contract's **Template Playground** action (the contract page and the
document view; `com.trustblocks.model.playground/template-status`) opens it
in Trustblocks' own Template Playground: TrustBlocks' fork of Accord
Project's playground,
[TrustBlocks/template-playground](https://github.com/TrustBlocks/template-playground),
hosted at <https://trustblocks.github.io/template-playground/>.

## What the fork is

- **Branch `trustblocks`**: Accord's `webmcp-poc` branch (Design v2 and the
  WebMCP proof of concept) with Accord's `main` merged in. `upstream` is
  accordproject/template-playground, for pulling their updates.
- **Logic in Clojure.** The TypeScript logic runtime is replaced by our own
  clause runtime -- `trustblocks-logic.js`, built from trustblocks-templates'
  [`runtime/`](https://github.com/TrustBlocks/trustblocks-templates/blob/main/docs/runtime.md),
  the same code the app runs on every request -- so a request gets there the
  answer it gets here, to the character, lifecycle and all. The Logic step's
  "compile" is the runtime's vocabulary check; Simulate takes each step as
  `trigger!` does, lifecycle first, with the certification simulated (the
  first the transition accepts; each run says which). Accord's own
  playground remains the place for TypeScript logic.
- **Our templates as samples**, and our shared models (`municipal.agreement`,
  `attestation`) and Accord's `usa.state` bundled beside Accord's standard
  ones -- from the trustblocks-templates commit its `trustblocks.json` pins
  (`scripts/sync-trustblocks.mjs`). Every template of ours, the manager's
  contract included, therefore opens: each has one namespace of its own.
- **The share link carries the clause and the lifecycle** (`logicTs`,
  `lifecycleJson`, beside Accord's `templateMarkdown`, `modelCto`, `data`),
  so a contract opens ready to simulate.
- **Deployed by GitHub Actions** (`.github/workflows/pages.yml`): the runtime
  and samples are built from trustblocks-templates' source at the pinned
  commit, the unit tests run, and every push to `trustblocks` deploys.

Links are still whole-template URLs -- about 20,000 characters for the
manager's contract, more for STR-27 -- so they suit a browser, not mail or
chat.

## Run it locally

```sh
bin/playground
```

keeps a sibling checkout of the fork at `~/Code/trustblocks-playground`
(override with `TRUSTBLOCKS_PLAYGROUND_DIR`) on a verified commit (override
with `TRUSTBLOCKS_PLAYGROUND_REF`), runs `npm ci` when its dependencies are
missing or stale, the first time builds the logic runtime and samples from
the sibling trustblocks-templates checkout (`TRUSTBLOCKS_TEMPLATES_DIR`,
default `~/Code/trustblocks-templates`: `bb logic-bundle` there, then the
fork's sync script), and serves it at `http://localhost:5173`. Point
Trustblocks at it before the JVM starts:

```sh
TRUSTBLOCKS_TEMPLATE_PLAYGROUND_ORIGIN=http://localhost:5173 \
  bin/clj -M:xtdb-jvm-opts -i dev/seed.clj
```

The origin only changes the Template Playground; Concerto Viewer continues
to use Accord's deployed Concerto Playground. Run it locally to try a
template change before it is published, or for WebMCP: the agent relay below
exists only in the Vite development build.

The fork's own browser tests drive a pay request, the vendor form and a
share link through their lifecycles:
`PW_CHANNEL=chrome npx playwright test e2e/trustblocks.spec.ts` in the
checkout.

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
   development tool; the deployed build does not include it.

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
