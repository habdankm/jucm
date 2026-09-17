# Concepts & mental model

This document explains how to **think about** JUCM: what the pieces are, why each of them exists, and
what it is easy to mistake it for. It sits **below the README** — the README is the overview, its
section 3 is the short mental model and its section 11 is the glossary; everything here takes those
words (layer, source, declaration, entry, address, notation, statement, marker, patch, flow, step,
provenance, materialisation, constraint, verdict, absent, empty, dropped, reset, auxiliary, coalesce)
as given and explains the relationships between them. Read the README first; read this when a word
still feels like a class name rather than an idea.

There is no tutorial here and no code: the API contract lives in the Javadoc of the module that owns
it, and the place of each module is described in the root `build.gradle` and the README's section 9.

---

## 1. One picture

```text
  DECLARATION  (code: type, required, default, description, aliases, rules)
        │  the declaration is also the bottom of the chain
        ▼
   ┌────────────┐   a source reads ONE place and delivers a sparse
   │  SOURCES   │   set of (address, value, origin, statement)
   └────────────┘        │          │          │
        YAML file         │   overlay     ENV    │    argv
                         ▼          ▼          ▼
                   ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐
   COMPILED root → │ Layer 1 │→│ Layer 2 │→│ Layer 3 │→│ Layer 4 │   a chain of immutable
                   └─────────┘ └─────────┘ └─────────┘ └─────────┘   snapshots, not documents
                          every lookup starts at the newest node and walks down
                         ▼
                       FLOW  (ordered steps; each returns a new chain and marks verdicts)
                         ▼
                       VERDICT (all problems at once + one exit code)  ·  READS by address
```

Two sentences carry most of the design:

* the configuration is **one tree of values under addresses**, and a source contributes to that tree —
  it does not replace, merge or continue a document;
* everything an application cares about is **declared in code**, and every other place (a file, an
  environment variable, an argument, a window) may only supply values, notation and rules for those
  declarations.

The rest of this document is the vocabulary that makes those two sentences precise.

---

## 2. Layer — a kind of origin, not a document

**What it is.** A layer is a *kind* of origin: `COMPILED`, `YAML`, `ENV`, `CLI`, `TUI`, `CUSTOM`. Every
value remembers the layer it came from together with an address inside that layer (a file and a line, a
variable name, an option name) — that pair is what a diagnostic prints, e.g. `ENV:AIMENAGERIE__SERVER__PORT`.

**Why it exists.** The kind travels with the value, so a message can say *which place is speaking*
without knowing anything about files, shells or terminals. `CLI:--port` and `ENV:AIMENAGERIE__PORT` are
recognisable at a glance, and the exit-code decade numbers the layers the application actually built.

**How it is confused.** With "a document". A layer is not a file: one file may be read as two layers
(the file and its overlay), and one layer kind may appear several times in a chain. A layer is also not
"the whole configuration as of that step": a layer holds **only what it brought in** — sparse storage,
dense lookup.

---

## 3. Address — the identity of an entry

**What it is.** A canonical path: segments joined by dots (`server.port`), a value object in its own
right, and the **only** identity of an entry. Two values in two layers under the same address are the
same entry seen twice; nothing else makes them "the same setting".

**Why it exists.** Because precedence must have a unit smaller than a document (see section 7 below), because
diagnostics must be able to point at a place, and because references (`${…}`) resolve to an address,
never to a copied value.

**How it is confused.** With "a key in a file" (which may be nested, case-insensitive in one source and
case-sensitive in another, and `__`-separated in a third) and with a string. The library normalises
each source's spelling into one address space — see `configuration-model.md`, section 1, for the
per-source rules.

---

## 4. Value, type, notation — three different things

**What it is.**

* the **value** is what a lookup returns under an address, and it always has **exactly one type** from
  a closed dictionary (the eight YAML value types, plus `duration` and `uri` as *targets for declared
  fields*, where the value in the tree stays a string in canonical form);
* the **notation** is how that value was *written* somewhere: `010`, `2026-01-01 12:00:00`, `  x  `.

**Why it exists.** Because the written form is the **source's business** and the meaning is the
**declaration's** business. The same text means the same thing whether it arrived from a file, an
environment variable or an argument, and a file cannot retype a declared field.

**How it is confused.** With each other, in both directions. Reading a notation as a type (`010` is
eight, not ten; `"11" < "2"` is true for text and false for numbers) and treating a type as a notation
(assuming a declared type trims, normalises or guesses: `1,000` is rejected unless the declaration
states a locale, and a bare date is rejected unless the declaration states the pattern). The two steps
that separate them — normalisation and casting — are described in `configuration-model.md`, section 3.

---

## 5. Declaration — a schema that lives in code

**What it is.** The code-side statement about a field: address, type, whether a missing value is an
error, an optional default, a description, optional short aliases, optional rules.

**Why it exists.** Because it is the single source of truth behind `-help`, the configurator window,
the validation tool, the sample-file generator and the exit-code categories — and because it is the
**data source of the root layer**: defaults sit at the bottom of the chain, so "where did this value
come from" is answered in exactly one place.

**How it is confused.** With a value store (it never holds "the current value"), with a validator
(judging is a separate operation, see section 14 below), and with something a source can overrule. A source may
state a rule that *replaces* the field's rules and may supply a notation configuration the declaration
omitted — but the declaration always wins on **type** and on the fact that the field exists at all.

---

## 6. Source and adapter — transportation, not decision

**What it is.** A source is a reader that turns one place (a file, the environment, arguments, a
window, an external store) into entries of one layer. In the code that reader is an *adapter*.

**Why it exists.** Because the set of places a configuration comes from is open, and because a value
must be typed, checked and reported identically regardless of where it was found.

**How it is confused.** With a component that may have opinions. An adapter does **not** cast, does not
validate, does not decide precedence and does not merge: it delivers values as text (or as a structure
when the source is structurally rich), states what the source said about them, and reports its own
failures as a **source error carried in the result**, never as an exception thrown mid-walk.

---

## 7. Precedence is per address, not per document

**What it is.** The last layer that states a value under an address wins for that address. Nothing
merges as an object; a lower layer's `server.host` survives a higher layer's `server.port`.

**Why it exists.** Because that is what makes layers independent: an overlay, an environment variable
and an option can each change one thing without having to restate a document. Deep merging happens
"by construction", at leaf addresses.

**How it is confused.** With document merging. There is no object merge: a sequence is replaced whole,
and a whole object stored under one key turns that key into a leaf (see `configuration-model.md`,
section 8) — in a file this happens under a declared field, while under an address outside the schema a
mapping stays a set of **addresses**: a path, not a value. It is also confused with a fixed order: the
order of layers is the **application's contract**, not a property of the library.

---

## 8. Absent is not empty

**What it is.** *Absent* is a fact: no layer has a value for a declared field. *Empty* is a value that
exists and is empty — an empty string, an empty list, an empty map — and it is an error in every
source **where someone wrote it on purpose** (`x: ""`, `host:` with nothing after it, `--host ""`,
`features: []`, `m: {}`), with no exception for the notation format. Which of those spellings is a
VALUE at all is the declaration's business: a mapping in a file is the value of a declared field,
and a set of addresses under an address outside the schema (see `configuration-model.md`, section 1).

**Why it exists.** Because the two facts need different fixes. "I want no elements" is expressed by
omitting the key — or by making the field **optional**, which is the same thing in the model; an empty
value is a mistake and the message says what was empty, so the user does not
hunt for a missing key that is not there. `null` does not exist in the model at all.

**How it is confused.** Constantly. `X=` in the environment is not an empty value either: nobody wrote a
value there, so the layer brings in nothing and a lower layer, or the declaration, answers. A missing
variable is not in the layer at all, and a required field with no value anywhere is `ABSENT` (a missing
value), not an empty one. An expression that yielded nothing (`${?x}` with no target) is the third case:
the layer contributes nothing and the read descends. See the resolving table in
`configuration-model.md`, section 6.

---

## 9. Auxiliary keys — legal, composable, not an API

**What it is.** A key outside the schema. It is kept in the form the source gave it, values may refer
to it (`${default_port}`, also through `??`), and a typo'd *option* **with a value** is exactly such a
key rather than a usage error. The boundary is the value, not the name: a **bare** option token
(`--prot` with nothing after it) is a usage error (`125`) for **every** option name, declared or not —
someone meant to set a parameter and set nothing.

**Why it exists.** Because users need scratch values that refer to each other, and because refusing
unknown input would make a typo fatal instead of reportable. Reachability is computed statically from
the fields of the schema over references, which gives the library a typo detector:
`UNUSED_AUXILIARY`.

**How it is confused.** With configuration the application may read. Auxiliary entries are deliberately
**not** an API: a field you read must be declared, and the typo detector only fires on the **chain of
layers** — asked for after the flow, the symbols are already resolved and the check is silent by
design, so an unknown option **with a value** still exits `0` (see `configuration-model.md`, section 8).

---

## 10. Coalesce `??` — a fallback, not a default value

**What it is.** `${a ?? b}`: the left side when it contributes a value, otherwise the right side
(chainable: `a ?? b ?? c`). On the LEFT, "nothing" is a normal state — that is what the operator is for.

An operand is **what its form says it is**: a number is a literal `int`, a quoted text a literal `str`,
`true`/`false` a literal `bool`, and a bare word a **reference** to another address. A literal has no declaration
behind it, so its form is the only thing that can speak — exactly as the form of a YAML node speaks for a key
outside the schema.

**Why it exists.** Because a reference to something absent is an error, so there must be a way to say
"use this other one if there is nothing". The decision is recorded as its own object per value, so the
origin report can say which side was chosen and why — one `coalesce.<path>=<side>: <reason>` line per decision,
next to the `layer.<path>` line. The trace lives in the SYMBOLIC tree: once the flow has run the values are
resolved and the decisions are gone, so a report that wants them builds the snapshot before the steps.

**What is an error.** Operands are judged against the DECLARATION: every operand must fit the declared type of
the value (`name: str` with `?? 9999` is a mismatch — the form of the literal is `int`; `port: int` with
`?? fallback` where `fallback` is declared `str` is the same mistake), and the right side, **once taken**, must
deliver a value: a fallback that leads nowhere is an error even where the types fit, because `??` must never hand
over the layer below in silence. Both cases share one kind (`RULE_MISMATCH`) — the statement and the declaration
contradict each other.

**How it is confused.** With a default value (a default belongs to the declaration and sits at the
bottom of the chain; `??` lives inside one value). `??` descends **only** on absence: an error on the left
side propagates, because swallowing it would turn a typo into a silent default.

---

## 11. Interpolation `${…}` — text becomes a symbol, the read becomes a value

**What it is.** `${a}` — the value of `a`; `${?a}` — the same, or nothing at all when `a` has none;
`"http://${host}:${port}"` — an expression: literals and references concatenated, with type text.

**Why it exists.** Because configurations refer to each other, and because a reference must follow the
configuration rather than freeze it: parsing happens once, **resolution belongs to the read**.

**How it is confused.** With textual substitution. A reference is not a copy: the symbol stays in the
tree and the target is read on every lookup, so a newer snapshot that changes `a` changes the result of
`c = ${a}` without `c` being touched. Nor is it an expression language: no arithmetic, no `+=`, no
escape for `${`, one level of substitution, braces inside the body rejected. `${?a}` with no target is
where a layer **contributes nothing**: the read descends to a lower layer, and with nothing
below either the field is `ABSENT` — a state, not a value, which is why it is not an error state. In a
**list** the same symbol makes the ELEMENT vanish instead: the list has one element fewer, the addresses of
its neighbours do not move (`ports.2` stays `ports.2`), and a reference to a vanished address leads nowhere.
Only when every element vanishes does the whole value contribute nothing; an explicit `[]` is emptiness, and
emptiness is an error.

---

## 12. Marker — the spelling of what a source states

**What it is.** A source may need to say more than "this value": how to read it, or which rule is meant.
It says so with a marker, and each kind of source spells the same three facts its own way — a section
in a same-line comment in YAML, `___TYPE`/`___TCONF`/`___CONST` suffixes in the environment,
`--x--type`/`--x--tconf`/`--x--const` on the command line. What travels with the value is the
**statement**; the marker is only its notation.

**Why it exists.** Because an entry outside the schema has nothing to type itself with, and because a
user occasionally knows better than the code what a value means. It is also how a rule reaches the
library from a file without recompiling.

**How it is confused.** With a second declaration, an addition, and documentation. It is not a second
declaration (it speaks for keys outside the schema and fills in a missing notation configuration); it
is not an addition (a rule from a source **replaces** the field's rules, while the rule belonging to the
**type** stays); and it is not prose — only a comment **on the same line as the value**, and only when
the comment **starts** with the section, while a marker anywhere else is a loud error rather than a
rule that silently does not work. A source can never express a transformer name: the source says *how
to read*, the declaration says *with what*.

---

## 13. Step and flow — judgement as a pipeline

**What it is.** A **step** is one stage that takes a chain and returns a **new** chain, marking on each
entry it processed a **verdict**: interpolation → transformation → cast → constraints. The **flow** is
the ordered set of steps — an explicit input, not a law.

**Why it exists.** Because "what does this text mean" and "is this value allowed" are different
questions with different failures, and because a mistake should be reported with the stage that found
it. Verdicts are what make re-running free.

**How it is confused.** With a mutable pass (a step never mutates: an older snapshot keeps its own
image), with a fixed order, and with validation as a whole. A step processes only the entries without
its own verdict yet; an entry stopped by an earlier step does not go further, so one mistake yields one
problem; and a step runs once even if the chain is judged repeatedly.

---

## 14. Verdict — reading and judging are two operations

**What it is.** Reading a path gives either a value or an error explaining why there is none; it never
returns `null` and never throws for a missing value. Judging gives the typed values **and every problem
at once**, each with the failing kind, the path, the offending value and the origin.

**Why it exists.** Because fixing a configuration one error at a time is the worst possible mode, and
because a problem must be attributable: a value that broke a rule and the place that supplied it are
one fact.

**How it is confused.** With reading (a read of a path whose value failed a rule still returns the
value — the verdict lives in the entry, not in the value), with exceptions (a problem is a **result**,
and the kind is a cause from a closed set, never a `null` or a blank value), and with a per-value error
list (a cycle is one mistake however many participants it has).

---

## 15. Snapshot and handle — a photograph, and the one mutable thing

**What it is.** A snapshot is an immutable node of the chain: it holds what one layer brought in and a
link to its parent. A **handle** is a small object that holds *the current* snapshot and swaps it
atomically.

**Why it exists.** Because nothing in the model may change in place: an operation that reads several
fields must see one consistent picture, and the history must be a chain rather than a change log.
Long-lived application objects therefore need a place to ask for "the current one".

**How it is confused.** With a live configuration. A snapshot does not see later layers — holding one
and expecting it to follow is the classic bug — and the handle is not a cache of values: it is a
pointer, and it is the only mutable thing in the model. "Nothing propagates" is the other half:
a dependent field is recomputed by resolving a new snapshot, on demand, never pushed.

---

## 16. Materialisation — "the state as of that layer"

**What it is.** Because a snapshot stores only its own contribution, the library can compute what the
configuration would have been **after** a given layer by walking the chain up to it. That is what the
layer-printing action shows, and the only way to answer *at which layer a value appeared, changed or
disappeared*.

**Why it exists.** Because a chain of immutable snapshots is a history by construction: no change log,
no `oldValue` field, and nothing to keep in sync. A `reset` or a dropped value leaves a marker in the
layer where it happened, so the history shows *where* something disappeared instead of silently falling
back.

**How it is confused.** With "what this layer said". On a chain **after** the resolution step each
layer carries its current, materialised state — the ledger is truthful about states, not about
declarations. When a declaration-level view is needed, it has to be asked for before the flow (that is
what the validation report's `previous:` row does), and a state computed *in the context* of one layer
comes from running the flow over the prefix of the layers, not from reading a cut-off chain.

---

## 17. What is deliberately missing

Profiles, includes, source-driven document switching, masking, live values with observers, injection,
POJO binding, an expression language, anchors as interpolation, and `null` as a value: all absent on
purpose, with the reasoning in the README's section 2 and the mistakes that follow from them in
section 10. Two absences deserve a second mention here because they shape the mental model:

* **no masking** — a layer cannot delete a value below it; the strongest thing it can do is take off
  its **own** override (`reset`);
* **no reflection** — declarations are ordinary code, nothing is scanned or registered, which is why a
  native image needs no metadata for the library's own logic.

---

## 18. Where to go next

| you want | go to |
|---|---|
| the model in one pass, with guarantees and invariants | `README.md`, sections 3, 5 and 7 |
| the precise semantics of values, rules, markers, absence | `configuration-model.md` |
| the exact contract of a type or a method | the Javadoc of the module that owns it |
| a running application, layered output and real exit codes | `integration-tests/native-app/README.md` |
| the smallest possible application | `examples/minimal-app/README.md` |
