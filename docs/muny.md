# Muny: the Town's AI agent, over MCP

Muny reads Trustblocks' record and advises on it. It holds no office and
certifies nothing: it is an AI agent identity (its own DID, and the Finance
Director accountable for it) designated only to **ADVISE** on a matter, and
everything it writes is a signed Advisory -- advice, on the record, under its
own name, that moves nothing (see `com.trustblocks.xtdb.advisory`).

Its thinking happens in whatever **MCP client** runs the model -- Claude Code
or Claude Desktop today, a self-hosted model later with an MCP-capable local
client. Trustblocks holds no model and no API key; it is an MCP server, at
`POST /mcp` in the running app (`com.trustblocks.app.mcp`), and the bearer
token a client sends *is* Muny.

## What Muny can do

| Tool | Reads or writes | What it does |
|---|---|---|
| `list_matters` | reads | Every open matter |
| `matter_overview` | reads | A matter as of a day: its documents and status, the package checklist (what is required but not on record), its deadlines |
| `contract` | reads | A document as of a day: its data, state, next steps and who must certify them, deadlines, timeline, advice already given |
| `read_document` | reads | A stored file's text (a received PDF) |
| `template_schema` | reads | A document type's JSON Schema and Concerto source |
| `sql` | reads | A read-only SQL query (XTDB SQL, bitemporal), echoed back with its rows; private keys and token hashes are withheld |
| `advise` | writes advice | A signed note on a document -- on its timeline as AI advice |
| `propose_vendor_form` | writes advice | A received vendor form's values, read from its PDF, checked against the vendor form's model -- errors come back so Muny can correct them -- then signed as an Advisory carrying the proposal |

A proposal is shown on the vendor form's page, field by field beside what
is on record, differences marked. Nothing is applied: confirming it into the
record is the Finance Director's act, and the next step to build.

Muny cannot certify, trigger a lifecycle step, approve, pay, or change a
document: every one of those needs an authority an agent cannot be
designated.

## Connecting

Start the app as usual (`bin/clj -M:xtdb-jvm-opts -i dev/seed.clj`). Seeding
provisions Muny, designates it to advise on STR-27, and -- the first time,
or when the token no longer matches -- issues it a token, written to
`storage/muny.token` (readable only by you; only its hash is in the
database, and issuing a new token revokes the old). Then, from the repo:

```sh
claude mcp add --transport http trustblocks-muny http://localhost:8080/mcp \
  --header "Authorization: Bearer $(cat storage/muny.token)"
```

and in Claude Code, for example:

> Read the vendor form received on STR-27 and propose its values.

Muny will find the form (`matter_overview`), read its PDF (`read_document`),
look up the form's fields (`template_schema`) and propose them
(`propose_vendor_form`); the proposal appears on
`/contracts/str-27-vendor-form-example-paving`.

## Limits of this first version

- One agent, Muny, designated on STR-27; one form it proposes, the vendor
  form. Other forms need only their own `propose_*` tool, or a general one.
- Reading is text only: a PDF with a text layer. A scanned image needs OCR
  first.
- Confirming a proposal into the record is not built yet.
- The endpoint is on the running app, on localhost; hosting it anywhere
  else needs TLS and thought about who may hold a token.
