# TrustBlocks municipal models

Our own models, extending Accord Project's registry with types native to
municipal government agreements.

## Namespaces

Reverse domain, from `trustblocks.com`:

| Namespace | Holds |
| --- | --- |
| `com.trustblocks.municipal.agreement@1.0.0` | `MunicipalAgreement`, the abstract base every agreement extends |
| `com.trustblocks.municipal.employment@1.0.0` | `ManagerEmployment`, the manager employment contract's root model |
| `com.trustblocks.municipal.employment.execution@1.0.0` | its board-approval state machine |

**No jurisdiction in the namespace.** The manager employment model was
inherited as `us.nc.municipal.manageremployment@0.1.0`, which bakes North
Carolina into the type name and forces a copy of every model per state.
Jurisdiction is data -- `stateCode` is an `org.accordproject.usa.state.USState`
-- so one set of models serves any state. We only operate in NC today; the
namespace shouldn't decide that we always will.

## Reuse is by inheritance, not composition

The shared jurisdiction fields (`stateCode`, `stateName`, `countyName`,
`municipalityName`, `municipalityType`, `governingBodyName`) live once, on
`MunicipalAgreement`, and every agreement extends it:

```
ManagerEmployment -> MunicipalAgreement -> org.accordproject.contract.Contract
```

The instinct is a nested `o Jurisdiction jurisdiction` concept. That does not
work for anything that appears in prose: TemplateMark can only reach into a
nested concept with `{{#with}}`, which is block-level and injects paragraph
breaks even mid-sentence, and this contract uses `{{municipalityType}}` inside
sentences about twenty times. Concerto flattens a subclass into one tagged
object, so inherited properties stay addressable exactly as if declared
locally. Accord's own registry is built this way too -- `Contract`, `Clause`,
`SignatureClause extends Clause`. See `docs/templatemark-conformance.md`.

The practical rule: **if a field appears in template prose, it must be a
property of the root concept or inherited into it.** Composition is fine for
anything the text never names.

## Where the files live

For now, in the template that uses them
(`docs/examples/manager-employment-contract/model/`), with Accord's imports
vendored alongside as `@models.accordproject.org.*.cto` -- the same layout
Accord's own `.cta` archives use, which keeps an archive self-contained and
loadable with no network.

When a second template needs `MunicipalAgreement`, it moves to a shared
directory here and is vendored into each template, exactly as the Accord
models are. Nothing is published over HTTP yet, so our own models import each
other by namespace with no `from` URL; that works because cicero-core loads
every `model/*.cto` in the archive. Publishing becomes worthwhile when someone
outside this repo needs to import them.

**Decided, not yet built:** publishing is a separate standalone project, not
part of this repo -- its own git history, the way `accordproject/models` is
its own repo rather than a folder inside `accordproject/cicero`. Reasoning:
some of what ends up there may eventually contribute back to Accord's own
model library, which is easier from an independent repo than by extracting it
out of ours later. Planned domains: `models.trustblocks.com` for the Concerto
models (mirroring `models.accordproject.org`'s layout -- raw `.cto` at
stable URLs, `Access-Control-Allow-Origin: *`, skip the multi-language
codegen site until it earns its keep) and `templates.trustblocks.com` for
packaged templates. Parked; revisit before the next template needs a model
this one already declared.

## Checking

`bin/template-conformance` -- every template must load and draft through
Accord's own toolchain. Run it after any model change.
