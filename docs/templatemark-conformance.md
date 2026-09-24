# TemplateMark conformance: what Accord's engine actually requires

Findings from running our manager-employment template against Accord's own
toolchain. The current lockfile resolves `@accordproject/cicero-core` 2.2.0,
`@accordproject/template-engine` 5.1.0, and Concerto Core/CTO 5.0.0.
Everything below was established by probing the engine, not by reading
the spec, because several of these behaviours are not what the syntax
suggests.

## Running the check

```sh
npm install              # once; node_modules is gitignored
bin/template-conformance
```

```
Accord conformance: 1 template(s) under docs/examples
  ok    manager-employment-contract@0.1.0  (18126 chars drafted)
```

Every template under `docs/examples` (any directory with a `package.json`)
must load through cicero-core and draft through template-engine from its own
`sample.json`. Non-zero exit on failure.

The checker is `dev/conformance.cljs` -- nbb, i.e. Clojure on Node. The
reference implementation is JavaScript with no JVM port, so the check has to
run where it lives; writing it in Clojure keeps it readable alongside the rest
of the repo. It is deliberately **not** part of `bin/clj -M:run code-quality`:
a Clojure-only change shouldn't require an `npm install`.

This exists because `com.trustblocks.template.render` implements a *subset* of
TemplateMark, and a subset written to the wrong semantics still renders
plausibly on the page. Everything in this document was found by probing the
engine rather than reading the spec, because several behaviours are not what
the syntax suggests.

## Load-time requirements

| Requirement | Symptom if missing |
| --- | --- |
| Exactly one concept carries `@template` | `Failed to find a concept with the @template decorator` |
| `package.json` exists, with `accordproject.cicero` satisfied by the installed cicero | `The template targets Cicero version ^0.25.0 but the current Cicero version is 2.2.0` |
| Optional properties are guarded by **their own name** | `Optional properties used without guards: retroactiveDate` |

The cicero range is a real gate: the vendored `late-delivery.cta` declares
`^0.25.0` and is refused outright by cicero-core 2.x. Ours declares `^2.0.0`,
matching APAP's current test fixture.

A guard must name the optional itself. Guarding with a companion boolean
(`{{#if retroactiveSalary}}` around a use of `retroactiveDate`) does not
count.

## Scope rebinding -- the one that changes how you model

**Both** block forms rebind scope to the value they test. Inside a block you
can use `{{this}}` and plain text; naming a sibling property is an error.

| Grammar | Result |
| --- | --- |
| `{{#optional x}}{{this}}{{/optional}}` | works |
| `{{#optional x}}{{x}}{{/optional}}` | `Unknown property: x` |
| `{{#optional x}}{{this}} and {{sibling}}{{/optional}}` | `Unknown property: sibling` |
| `{{#optional x}}…{{else}}{{sibling}}{{/optional}}` | `Unknown property: sibling` -- an `{{else}}` branch can reference **nothing** |
| `{{#if bool}}plain text{{/if}}` | works |
| `{{#if bool}}{{sibling}}{{/if}}` | `Unknown property: sibling` |
| `{{#if dateTimeOptional}}…{{/if}}` | fails -- `{{#if}}` wants a boolean |

Accord's own reference template is consistent with this: its only conditional
is a plain-text toggle, `{{#if forceMajeure}} except for Force Majeure
cases,{{/if}}`.

Consequences:

- A conditional section can only reference fields of the concept it binds.
  To put several values in one conditional, they must live together in a
  nested concept -- `{{#optional retroactive}}{{retroactiveDate}} by the
  {{governingBodyName}}{{/optional}}` works when both are properties of the
  `retroactive` concept. **TemplateMark's scoping is what makes concept
  extraction structural rather than stylistic.**
- An `{{else}}` branch must be plain text.

## Addressing nested values

| Grammar | Result |
| --- | --- |
| `{{term}}` where `term` is a `Period` | renders `4 years` -- registry types format themselves inline |
| `{{term.amount}}` | **not supported**; passes through as literal text |
| `{{#with term}}{{amount}}{{/with}}` | works, but is block-level -- injects paragraph breaks, so unusable inline |
| `{{term as "0"}}` | format strings are ignored for `Period`/`Duration` |

This is why `org.accordproject.time.Period`/`Duration` were **not** adopted
for this contract's six numeral/written pairs. They render as one atomic
phrase (`4 years`), which suits the reference template's `for every
{{penaltyDuration}} of delay` but cannot produce this document's drafting
convention, `four (4) years`, where the written form, the numeral in
parentheses, and the unit are three separate pieces of prose. Adopting them
would either mangle the text or add a third representation beside the two
that already exist.

## What we changed, and why

Sections 4B (retroactive salary) and 14 (automobile) each wrapped several
variables in `{{#if}}`, so neither could draft. They were written to mustache
semantics. Both were reworded to keep variables out of conditional scope,
using terms the contract already defines:

- `{{governingBodyName}}` / `{{councilActionDate}}` inside 4B became "the
  governing body recited above" and "that date", referring back to recitals
  that state both.
- `{{municipalityType}}` became **Employer**, which the opening paragraph
  already defines as a synonym (`hereinafter called the "{{municipalityType}}"
  or "Employer"`).
- `{{pronounPossessive}}` became "Employee's", avoiding the pronoun.
- Both conditionals became `{{#optional}}` on the optional value itself.

That removed the two companion booleans, `retroactiveSalary` and
`automobileStipendProvided`: the presence of `retroactiveDate` /
`automobileStipend` is the same fact, and two sources for one fact is the
drift risk this model already argues against elsewhere.

The template now loads **and drafts**, in both the present and absent cases
for each optional.

## Known divergence: date formatting

Accord renders `DateTime` as `07/01/2025`. We render the wire form,
`2025-07-01T00:00:00.000Z`, per `render-value`'s rule that a DateTime stays a
string until something has a reason to parse it. Not yet reconciled.
