# concerto-edn

Accord Project [Concerto](https://github.com/accordproject/concerto) models as
EDN, and Malli schemas compiled from them at runtime.

No code generation. A new contract type is a data load, not a deploy.

## Why

Concerto's metamodel is self-hosting: it is defined in Concerto itself
(`concerto-metamodel/lib/metamodel.cto`, 277 lines), and every parsed CTO file
is an instance of it serialized as JSON tagged with `$class`. That JSON is a
versioned, normative interchange format — a much firmer thing to code against
than a hand-rolled reading of the CTO grammar.

Every key in the metamodel is a legal Clojure keyword name, so JSON → EDN is
lossless and needs no mapping table. From there a Malli schema is a
straightforward walk of the inheritance chain.

## Layers

Each layer takes data and returns data. Only `concerto.cto` does I/O.

| namespace | does | needs |
|---|---|---|
| `concerto.metamodel` | metamodel JSON/EDN → declaration registry | — |
| `concerto.malli`     | registry → Malli schema, or EDN            | malli |
| `concerto.instance`  | Concerto JSON ⇄ neutral EDN instance       | — |
| `concerto.cto`       | CTO text → metamodel EDN                   | Node CLI |

A storage adapter sits *outside* this library and supplies its own `key-fn`.
That is deliberate: XTDB reads `$` as a namespace separator and lowercases
camelCase, so it needs `:concerto/class` and kebab-case keys — and then needs
the model to recover the original spelling on read. Keeping that in the adapter
means `vechileID` and `INITIAL_DUE` stay spelled correctly everywhere else.

## Use it from a babashka script

`bb.edn`:

```clojure
{:deps {net.trustblocks/concerto-edn
        {:git/url  "https://github.com/TrustBlocks/trustblocks-contracts"
         :git/sha  "PUT-A-SHA-HERE"
         :deps/root "lib/concerto-edn"}}}
```

Then:

```clojure
(require '[concerto.cto :as cto] '[concerto.malli :as cm])

(def reg (cto/load-registry "acceptance-of-delivery/model"))

(cm/->edn reg "org.accordproject.acceptanceofdelivery@0.1.0.TemplateModel")
;; =>
;; [:map
;;  [:$class [:= "org.accordproject.acceptanceofdelivery@0.1.0.TemplateModel"]]
;;  [:$identifier {:optional true} :string]
;;  [:clauseId :string]
;;  [:shipper :string]
;;  [:receiver :string]
;;  [:deliverable :string]
;;  [:businessDays :int]
;;  [:attachment :string]]
```

That is plain EDN — no functions, no `#object[...]` — so it can be written to a
file, checked into git, diffed across model versions, and read back:

```clojure
(m/schema (edn/read-string (slurp "schema.edn")) {:registry cm/registry*})
```

Properties come out in declaration order, so two exports of the same model are
byte-identical and two versions diff cleanly.

## The script

```
bb script/cto2edn.clj MODEL [options]

  --registry            the declaration registry as EDN          [default]
  --malli FQN           the Malli schema for one declaration, as EDN
  --instance FILE.json  a Concerto JSON instance as neutral EDN
  --validate FILE.json  validate an instance against its own $class
  --list                every declaration the model defines
  --cli PATH            path to the concerto CLI
  -o FILE               write to FILE instead of stdout
```

```console
$ bb script/cto2edn.clj promissory-note/model --validate promissory-note/sample.json
VALID   org.accordproject.promissorynote@0.2.0.TemplateModel
```

## Parsing CTO text

`concerto.cto` shells out to Accord's CLI:

```
npm i -g @accordproject/concerto-cli
```

or set `$CONCERTO_CLI` to its path. This is the only part that needs Node, and
it is isolated on purpose — anything that can produce metamodel JSON can feed
the rest. A pure-Clojure parser is possible later: the grammar is a PEG
(`concerto-cto/lib/parser.pegjs`, ~1200 real lines once comments and Unicode
character classes are set aside) and would port to instaparse. It is deferred
because the grammar is normative and drifts, so reimplementing it is a standing
obligation to track that drift, while the metamodel is a stable contract.

## Status

Proof of concept. Verified against all 57 templates in the Accord
[cicero-template-library](https://github.com/accordproject/cicero-template-library).

Known gaps, in the order they matter — the compiled schema is currently weaker
than Concerto's own validator:

- nested concepts compile to `:map`, so their contents are unchecked
- maps are open, so undeclared properties are accepted
- no subtype dispatch on abstract declarations
- `validator` (regex, range) is dropped
- `defaultValue` is ignored
- `ScalarDeclaration` and `MapDeclaration` are not handled
- `$timestamp` never appears in a schema
