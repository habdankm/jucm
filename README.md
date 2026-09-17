# JUCM — a configuration engine for Java command-line applications

JUCM reads a configuration from several places — a YAML file, a YAML overlay, environment variables,
command-line arguments and an interactive terminal window — and turns them into **one immutable, typed
snapshot** that an application reads by address.

This document is the **model**: what the library is, how it thinks, what it guarantees, and what it
deliberately refuses to do. It describes behaviour, not classes; the API contract lives in the Javadoc, and a
map of the other documents is at the end.

## 1. What it is, and what it is for

A configuration arrives from several places at once, and those places do not agree on how to write things down.
The same setting arrives
from a file, an environment variable and an argument; each source has its own notation for numbers, dates and
text; values refer to other values (`uri = http://${host}:${port}`); some keys are typos and some are
intentional but unknown; and when something is wrong the user must be told *which* place produced the value
that broke the rule. JUCM answers exactly that: **one model of the configuration, many sources, explicit semantics, diagnostics that
point at an address.**

The model is a **tree of values addressed by path**: a source delivers a **sparse set of values under
addresses**, and the configuration is what a lookup finds along the chain of layers. The application declares
what exists — type, required, default, description, aliases, rules — and that declaration is the single source
of truth behind `-help`, the terminal window, the validation tool and the sample-file generator.

JUCM is deliberately **specific**: it is for **UNIX-minded command-line tools** — hence `--kebab-case` names
derived from the address, values instead of flags (`--server-tls=false`, never `--no-tls`), exit codes in the
spirit of `sysexits`, help built from model metadata, and a terminal interface that is a **configurator**.
Two properties are design criteria from the first commit: **no reflection is needed** — no annotations, no
classpath scanning, no proxies, no runtime code generation, hence a GraalVM Native Image with no user-supplied
metadata for our logic — and **the core knows nothing about formats**: the application picks the adapters.

## 2. What it deliberately is not

The name promises more than the library does, so the boundary comes first.

- **Not an application framework** — no injection, no containers, no lifecycles.
- **Not a POJO binder.** Binding onto application classes might exist one day as a separate optional module.
- **Not live or reactive** — no observers, no change events, no setters. Reconfiguration is a new snapshot in one
  history (section 7).
- **Not an expression language** — references and text concatenation only: no arithmetic, no `+=`.
- **Not self-starting** — the library never reads a file on its own initiative; the application states which
  sources take part, and in which order.
- **No profiles, no includes, no source-driven document switching** — one document per source.
- **No masking** — a layer cannot delete a value below it; it can only take off its own override.
- **No `null` in the model** — absence and emptiness are separate facts (section 5).

## 3. The mental model

### 3.1 The declaration is the schema, and it lives in code

Every entry the application cares about is declared in Java: an address, a type, whether a missing value is an
error, an optional default, a description, optional short CLI aliases, optional rules. The **type belongs to the
model, not to the data**: sources supply values and the declaration decides what they mean, which is why `8080`
from a file, an environment variable and the command line produce *the same* value, and why a file cannot retype a
declared field. The declaration is also the **data source of the root layer**: a default value sits at the bottom
of the chain, and a field without one gets **no placeholder** — no value at all, a valid state for an optional
field and an error for a required one.

### 3.2 A layer is a set of statements, not a document

A layer is a **kind of origin**: `COMPILED`, `YAML`, `ENV`, `CLI`, `TUI`, `CUSTOM`. A file, a variable and an
option each belong to one of these kinds, and the kind travels with every value so diagnostics can say *which*
layer is speaking. The **order of layers is the application's contract**. A layer does not overwrite a document:
it states values under addresses, and **the address is the unit of precedence** — a layer that says nothing about
`server.port` leaves what the layers below said untouched.

```text
  application.yaml  application.applied.yaml      ENV         argv
        ▼                    ▼                     ▼           ▼
   ┌─────────┐         ┌─────────┐          ┌─────────┐ ┌─────────┐  each = one sparse set
   │ Layer 1 │         │ Layer 2 │          │ Layer 3 │ │ Layer 4 │  of (address, value)
   └─────────┘         └─────────┘          └─────────┘ └─────────┘
        └──────────┬─────────┴────────┬─────────┴───────────┘
                   ▼                                        ▼
       chain of immutable snapshots           root layer, materialised FROM the
       (one node per layer, linked to its parent)  DECLARATION (default values)
```

### 3.3 The address is the identity of an entry

An address is a canonical path: segments joined by dots, so a dot cannot occur inside a segment — a value object,
not a string, and the only identity of an entry. `__` (hierarchy) and `___` (markers) belong to the engine and
may not occur in a key; a single `_` is ordinary; lower-case keys are a *recommendation*, not an enforced rule.

### 3.4 A value carries a type, a notation and an origin

Values are a **closed set**, not an anything-goes map:

```text
value types (the YAML dictionary)   str · int · float · bool · timestamp · seq · map · binary
cast targets identified by JUCM      duration · uri
```

The first eight can live in the tree; the last two are **targets for declared fields** — read through JUCM's own
notation, while the value in the tree stays of the YAML family (a string in canonical form). `map` is one of those
eight, and **the declaration under an address decides how a mapping is read**: under a declared field the
whole mapping in a file is the **value** of that field (`m: {a: 1}` states the value of `m` — one piece, exactly
what `APP__M={a: 1}` states), while under an address outside the schema — a field of another type, a key outside the schema —
a mapping stays a set of **addresses** (`server:` states `server.host`). A field with no entry at all is `ABSENT`;
an **empty** mapping written in the file is an empty value and therefore an error (`m: {}` is the same mistake as
`features: []` or `x: ""`), because "the field may have no elements" is expressed by making the field **optional**.
`int` and `float` are separate types on purpose: an integer literal only, a decimal
point or exponent for `float`, no widening or narrowing. A `timestamp` is an exact moment with a zone; a bare date
is rejected unless the declaration states the pattern that makes it unambiguous. A type **carries its own rules** —
its default notation (a transformer name) and, where it makes sense, a default shape rule (`uri` must have a
scheme) — but `str` gets **no** default trimming.

Every entry also remembers **where it came from** — the layer plus an address inside it (a file and a line, a
variable name, an option name), which is what makes a message like `ENV:AIMENAGERIE__SERVER__PORT` possible. That
origin is **not** part of the identity of a value: two changes setting the same value are equal even when they
came from different layers.

**Notation is not type.** A value is **written** in some notation and **means** something of some type: the written
form is the source's business, the meaning is the declaration's. `010` in a file is the integer 8, and the same text means the same
thing in an environment variable and on a command line, because argument parsers are not allowed to type values on
their own. What a carrier DOES do is read the **shape**: a value that starts with `[` or `{` is a flow literal, read
by the same grammar as in a file, so `--ports [80,443]` fills a declared collection (and on the command line a text
beginning with a bracket has no reading — the same rule as in ENV). `1,000` is one thousand in one locale and one-point-zero in another, so it is rejected unless the
declaration states a locale. The flow therefore has two steps that are easy to conflate — **normalisation** (bring
the written notation to the canonical form of that type) and **casting** (require the canonical form of the
declared type) — and a rule compares either the notation or the typed value: `=010` does not match `8`.

### 3.5 A value may be a symbol

```text
   ${a}                     the value of a;   ${?a}  the same, or nothing at all when a has none
   ${a ?? b}                a, and when a contributes nothing, b (chainable: a ?? b ?? c)
   "http://${host}:${port}" an expression: literals and references concatenated (type: text)
```

An operand of `??` is **what its form says it is**, and every operand must fit the type of the value: `9999` is
an `int`, `"9999"` is a `str`, `true`/`false` are `bool`, and a bare word is a **reference** to another address
(`${a ?? default_port}`). A literal has no declaration behind it, so its form is the only thing that can speak —
exactly as the form of a YAML node speaks for a key outside the schema. A mismatch is an error, and so is a
fallback that is **taken and delivers nothing** — even a `str` one.

An **expression is text**: concatenation renders every part in its canonical form. A **reference is not a copy** —
the symbol stays in the tree and the target is read on every lookup, so a newer snapshot that changes `a` changes
the result of `c = ${a}` without `c` being touched: parsing happens once, resolution belongs to the read. `${?a}`
with no target is where a layer **contributes nothing**: a value below answers, and the layer is
skipped. In a **list** that same `${?a}` makes the ELEMENT vanish instead of the whole value — the list has one
element fewer, and the addresses of its neighbours do not move (`ports.2` stays `ports.2` after `ports.1`
vanished; a reference to a vanished address leads nowhere, `unknown-path`). When **every** element of a list
vanishes, the whole value contributes nothing — the same rule an expression follows; an explicit `[]` is a
different statement, and that one is emptiness (an error). When nothing below has one either, the field is **`ABSENT`** — the same state as if that layer had not
mentioned the path at all — so an optional field stated only by `${?missing}` runs (`0`) while a required one fails
as missing (`14`), never as emptiness (`20`). There is **no escape** for
`${`, one level of substitution only, and braces inside the body are rejected.

### 3.6 Statements about an entry travel with the value

A source may need to say more than "this value": how to read it, or which rule is meant. It says so with a
**marker**, and each kind of source spells the same three facts its own way:

```text
                 type                      notation                    rule
YAML        from the form of the node      # TCONF(…)   (same line)     # CONST(…)
ENV         NAME___TYPE=str               NAME___TCONF=…                NAME___CONST='…'
CLI         --name--type str              --name--tconf …               --name--const '…'
```

In YAML a marker is a **section in a comment on the same line as the value**, and it counts only when the comment
**starts** with it — after prose, a section is prose. A comment on its own line, above a key, or on a block-form
collection is misplaced and **rejected**: a marker where it cannot be read would be a rule that silently does not
work. At most one notation and one rule section per comment; the payload ends at the first unescaped `)` outside
backticks, so regex groups work.

A rule needs something to compare against, and that something is a type. The schema is **CODE** and it is
complete: every key it declares has its type from the declaration. A key it does **not** declare is an
**auxiliary**, and there the type is the one the statement brings (`___TYPE`, and in YAML the form of the node)
— and **`str` when it brings none**, because a value from a source arrives as text. There is therefore no state
"a statement without a type": a rule has something to compare against, and a notation has a type whose notation
is looked up (a notation on a type that has none — `str` — is an error, not a silent skip). A statement that
says nothing at all declares nothing and leaves the value exactly as the source delivered it. On a declared
field the type comes from the declaration, so a rule from a source always applies.

A statement from a source **replaces** rules declared in code for that field — neither added to them nor ignored;
the rule belonging to the **type** stays, because a type must mean the same thing in every field. A source never
expresses a transformer name: a name points at an implementation in an application's registry, and the registry is
an application concept — the source says *how to read*, the declaration says *with what*.

**Every layer has a computed state "as of that moment".** A snapshot stores **only what that layer brought in** — sparse storage, dense lookup — and the library can compute
"what the configuration would have been after this layer" by walking the chain, layer by layer: that is what the
layer-printing diagnostic action uses, and the only way to answer *at which layer a value appeared, changed or
disappeared*. The chain itself is the ledger — no change log, no `oldValue` field — and a layer's node holds a
marker where a value was taken off (`reset`) or ceased to exist, so the history shows *where* something
disappeared instead of silently falling back.

### 3.7 Precedence is per address, not per document

The last layer that states a value wins. Nothing merges as an object: a lower layer's `server.host` survives a
higher layer's `server.port` — deep merging by construction, at leaf addresses. A **sequence is replaced whole**
(no element-wise merge, no `+=`), and a whole object stored under one key turns that key into a leaf.

**The verdict is not a value.** Reading a value and judging a configuration are two operations. Reading a path gives either a value or an error
explaining why there is none; it never returns `null` and never throws for a missing value. Judging gives the typed
values **and every problem at once**, each with the failing kind, the path, the offending value and the origin.

## 4. The life cycle of a configuration

```text
  DECLARATION  (code)  types, required, defaults, rules, aliases, descriptions
     ├─ triggered by the application; cannot fail except on a broken rule
     ├─ root layer materialised FROM the declaration: default values, or nothing
     ▼
  SOURCES      one layer at a time, in the order the application gives (the last one wins);
     │           each source → a sparse set of (address, value, origin, statement);
     │           a source that cannot be read stops the run here, as a source error
     ▼
  CHAIN        immutable snapshots: one node per layer, linked to its parent;
     │           building it cannot fail; every value and its origin is now fixed
     ▼
  FLOW         INTERPOLATION → TRANSFORMATION → CAST → CONSTRAINTS, one step at a time,
     │           each returning a NEW variant and marking its verdict
     ▼
  VERDICT      all problems at once + one exit code; the values are typed
     ▼
  CONSUMPTION  reads by address, the current-snapshot handle, reports, the window
```

**Interpolation** turns text into symbols against this snapshot and resolves references, including cycles;
**transformation** normalises the written notation through the transformer named by the declaration (or the
notation carried by a type the library recognises); **cast** requires the declared type in canonical form,
checking emptiness first, so an empty field is reported as emptiness and not as "expected type bool";
**constraints** apply the rules after a successful cast. Four properties hold for every step:

- **A step never mutates** — it returns a new variant; an older snapshot keeps its own image.
- **A step processes only entries without its verdict yet** — re-running is free, and a value contributed
  programmatically is checked exactly once.
- **An entry stopped by an earlier step does not go further** — one mistake yields one problem, not two.
- **The order and the set of steps are an explicit input**: the library ships a well-set default and a factory
  per step, so an application may compose its own order or add a step of its own.

A value a step decided to drop (`${?x}` with no target) disappears from that layer and the lookup continues
below: a layer whose expression produced nothing does not block a lower fallback. A reference to something absent
is an error, not a silent absence; a value a layer stated as empty is an error that **stops the read**.

Validation is a separate, explicit call: it runs the default flow when the configuration has not been through it
and reads the verdicts instead of repeating the work, so it is **idempotent** and repeatable — and it does **not**
stop at the first problem, because fixing a configuration one error at a time is the worst possible mode.

## 5. Layers, precedence and absence

1. **A later layer wins** for the address it states. The order is whatever the application composed; the
   toolbox reads the file, then its overlay, then ENV, then the command line; window edits sit on top (TUI).
2. **Saying nothing is not a value.** A layer that does not mention an address contributes nothing; absence
   travels as absence, never as a placeholder such as `NotProvided`.
3. **Absent ≠ empty, and neither is the same as "no value at all".** A missing value is a fact (`ABSENT`); an
   **empty** scalar, list or map is an **error** (`20` in the file, `40` from the command line, `30` from an adapter) —
   but only where the emptiness was **written**: a `x: ""` node, a key written with nothing after it (`host:`), an
   explicit `--host ""` argument, an empty collection — `m: {}` in a file (`20`) and `APP__M={}` in the environment
   (`30`) state the same empty map, and a list is the same case again. "I want no elements" is expressed by omitting
   the key, or by making the field **optional** — a field with no entry at all is `ABSENT`; the message names what
   was empty, because the fix differs.
4. **`null` does not exist in the model.** An absent variable is not in the layer, and neither is one present without
   a value: an **empty environment variable** (`X=`) states nothing about the address, so it brings in nothing at all
   and a lower layer, or the declaration, answers. Telling the two states apart is the application's business
   (`EnvVars` exposes both).
5. **`reset` takes off a layer's own override** and the read continues below, skipping only that declaration —
   it cannot mask a lower layer's value.
6. **A gap is created by an expression, not by a state**: an entry that produced nothing does not stop a lower
   fallback, while an explicitly empty value does — an empty environment variable is neither an expression nor a
   value, so it does not stop anything either.
7. **Keys outside the schema are legal and need no declaration.** They are *auxiliary*: the engine keeps the form
   the source gave them, values may refer to them (`${default_port}`, also through `??`), and a typo'd *option*
   **with a value** (`--prot 9000`) is exactly such a key rather than a usage error. A **bare** option token
   (`--prot` with nothing after it) is not that case: it is a usage error (`125`) for **every** option name,
   declared or not, because someone meant to set a parameter and set nothing. The boundary is the **value**, not
   the name. Reachability is computed **statically**, from the fields of the
   schema over references, transitively — never from what happened to be read at runtime. A reference's **type**
   comes from the declaration in code while its **value** may arrive from any medium (YAML, ENV, a CLI argument);
   a key outside the schema has no declaration, and a YAML scalar is text, so an `int` rule parameter wants a
   declared field — or a carrier that states the type (`___TYPE=int`). A reference whose target
   exists nowhere is `UNRESOLVED_REFERENCE`; an auxiliary key nothing leads to is reported as `UNUSED_AUXILIARY`
   (a typo detector) **when the verdict is asked for the chain of layers**, before the symbols have been resolved
   and the raw notation is gone. In the standard flow the validity report is asked for a chain that has already
   been through the flow, so the check does not fire and a typo'd key does not fail the run: an unknown option
   **with a value** stays an auxiliary key and exits `0`. Auxiliary entries are not for the application to read — declare what you
   intend to read.
8. **Required and default are independent.** Required with a default: the default answers. Required without one:
   no value anywhere is `ABSENT` — an error. Optional without one: `ABSENT` is a valid state.
9. **An empty contribution is not a layer** — it is allowed and changes nothing.

## 6. When something is wrong

A configuration problem is **one of a closed set of kinds**, and each answers five questions: what failed, where,
under which path, with what value, and what a human should read. Kinds are never signalled by a `null` or a blank
value — that is exactly what keeps absence and emptiness apart.

```text
  source / input   PARSE_FAILED   NULL_VALUE   INVALID_SYNTAX   TRANSFORM_FAILED
  conversion       CAST_FAILED
  resolution       UNRESOLVED_REFERENCE   UNRESOLVED_EXPRESSION   CYCLE
  validation       VALIDATION_FAILED   UNUSED_AUXILIARY
  missing          ABSENT          path / model   UNKNOWN_PATH   RULE_MISMATCH
  I/O              FILE_NOT_EXIST
```

The kind is not the Java class that threw; it is the **cause**, and the set is closed: a new kind lands in the
nearest category. A source that cannot be read is a **result**, not an exception thrown in the middle of a walk;
such an error has no path — the whole source is unreadable — and for that reason it outranks individual fields. In
the delivered facade the run ends there: the error goes to the error stream, the application is not started and
**no verdict report is produced**, so a broken source is never collected next to the problems of the other layers.

**All problems at once, in a stable order** — by address, then kind, then origin, so the same configuration
produces the same first problem on any machine and in any run.

**One code when there are many errors.** The **decade** is the layer's position in the pipeline the application
actually built (the first position is the materialised declaration; the number saturates at the ninth and later
layer), and the **unit** is the category from the table above. The **lowest code wins**, so the result does not
depend on the order of detection, and `0` means exactly "zero problems". The zero decade is not ours: single-digit
codes `1..9` are left to the application, so our numbering starts at `10`.

```text
  2 3  ← 23: category 3 (validation) in the layer at position 2 — so inserting a source
  │ └─ unit:  the cause                                  shifts the numbers
  └─── decade: which layer, BY POSITION
```

The decade is **positional**: it is the position of the layer **kind** in the pipeline the application built, taken
at its first occurrence — which is why messages carry the layer *and* the origin. A stage of a kind that is already
there moves nothing: an overlay file is `YAML` like the file below it, so a rule violation gives `23` from either of
them, and ENV stays `33` and the command line `43`. Only a **kind** inserted before it shifts the numbers: with
`CUSTOM` placed before `YAML`, the same file violation becomes `33` and ENV `43`. Verified examples: a rule
violation gives `23` from the file layer, `33` from ENV and `43` from the command line; an absent required value
gives `14` (`ABSENT` belongs to the declaration, the lowest position, and wins over a higher-layer problem); a value
an adapter stated it could not obtain gives `30`, a failed conversion there `31` — while an empty environment
variable contributes nothing at all (rule 4) and a marker with an empty payload is a loud source error, `30`.

`125` sits **outside** both families: a command-line **usage** error (an option left without its argument, whatever
the option is called, and an unknown action), which a configuration never produces. A typo'd *option* is not a
usage error **as long as it carries a value**: the adapter accepts unmatched options on purpose so they can be
reported as auxiliary keys, and the run succeeds. A bare token is a different case — nothing was set, so it is a
usage error like any other option without its parameter.

## 7. Invariants you may rely on

1. **Nothing in the model changes in place.** Every operation produces a new snapshot; an older snapshot keeps its
   own image and its own view of the history.
2. **A snapshot held in hand does not see later changes** — a consistent picture during an operation, and a trap
   for long-lived objects: the current configuration is reached through a **handle** that swaps the snapshot
   atomically, not through a stored copy. That handle is the only mutable thing in the model.
3. **A reference points at an address, never at a copied value**: a newer layer changes what existing references
   resolve to, without anything being rewritten.
4. **Resolution is against one particular snapshot**, lazily, at the moment of reading.
5. **No hidden propagation.** Nothing pushes a change into dependent values: "dependent fields updated" means a new
   resolution of a new snapshot, computed on demand.
6. **No value is ever `null`, and absence is never an empty value** — two kinds of fact, two errors.
7. **The verdict is deterministic and complete:** all problems of the whole configuration at once, in a defined
   order; one mistake yields one problem; a repeated call adds nothing, because a step skips entries that already
   carry its verdict.
8. **The kind of error is a closed set**, so the ways a configuration can fail can be enumerated, and a new kind
   cannot be added without deciding what it says about path and value.
9. **A rule from a source replaces rules from code**; it does not accumulate. The rule belonging to the **type** is
   applied alongside field rules and cannot be replaced from a source.
10. **The default flow is a default, not a law.** Layer order and the set of steps are inputs; a different order is
    a different, legitimate configuration contract.
11. **No reflection anywhere:** declarations are ordinary Java, nothing has to be registered for a native image, and
    no class of the user's application is inspected at runtime.
12. **The library's own dependencies stay out of its API.** Objects of a third-party collection library are used
    internally and never appear in a public signature, field type or generic parameter, so a consumer compiles
    against plain Java types only; a build check enforces this on the consumer's compile classpath.


## 8. Extension points

Each exists because the library cannot know something the application does; each is a seam, not a plugin system.

**Your own source — an adapter.** *Why:* the set of places a configuration comes from is open (a remote store, a
database, a secrets manager). *When:* whenever the four shipped sources are not where your values live. *What you
must provide:* entries — address, value, origin, and optionally a statement about type, notation and rule — with
**no configuration semantics of your own**: adapters transport, they do not cast, do not validate and do not decide
precedence. Your I/O is yours: a file that cannot be read or a service that does not answer becomes a **source
error carried in the result**, not an exception thrown in the middle of a walk. Such an error has no path — it
concerns the whole source — and the delivered facade ends the run there: no verdict report is produced and the
problems of the other layers are not collected (section 6). A value your source could not obtain is stated as
"delivered nothing", which is also the seam where the reason travels. *What the library guarantees:* the value is
typed, checked against the rules and reported against your origin, without your adapter knowing anything about
types or rules.

**Your own transformer — by name.** *Why:* input notations are domain-specific (`2 hours`, `10MB`). *When:* a
written form needs normalising and neither the type's default notation nor a configuration of a shipped transformer
fits. *What you must provide:* a result that is either a value in canonical form or a **rejection with a reason** —
never an exception, because without the reason the message says "bad value" and not which notation was expected; a
check of your own configuration, so a nonsense configuration is a model error instead of a false blame on the data;
and the contract that a transformation is **deterministic and stateless**, because one instance serves many fields
at once. It receives no field, no path and no source — the caller adds those — so it is testable in isolation, and
it must not do I/O or depend on time. Transformers live in an **explicit registry**, immutable once built: no
global mutable singleton, no behaviour depending on registration order; registering a taken name is an error, and
replacing a shipped transformer has its own explicit route. An unknown name is a **loud model error** at first use,
never a silent degradation.

**Your own actions, and your own help.** *Why:* a tool has verbs (`export`, `ping`) that are not configuration.
*When:* as soon as your application does more than one thing. *What you must provide:* a bare word (no dash) and its
handling; actions reserved by the engine and repeated names are model errors. *What the library guarantees:* each
action is a subcommand carrying a **copy of the same options**, so `--port 8080 export` and `export --port 8080`
mean the same thing. For help there is one route, not two: your extension receives the help generated from the model
— exactly what would be printed without it — plus an output stream, and may precede, extend or replace it. That
generated text is `-help` (the engine's options take **one** dash, the model's fields **two**), and it names the
**program** (`program("uri-app")` — never a `<main class>` placeholder) and the **environment prefix** of your
sources (`env("URIAPP")` gives `URIAPP__HOST`, and with no `env(...)` it says plainly that ENV is not read).

**Your own widgets in the window.** *Why:* a real configurator sometimes needs its own button (a connection test, a
picker). *When:* when the generated view is not enough. *What you must provide:* correctness about the widget
library; you receive a view of the **model**, not a bag of controls — the resolved configuration to read, the
working copy and the edit operation to change it, a redraw call, the first free row to build in, the application
object for background work, and the status line for reporting. The deliberate cost is yours: writing such a widget
means knowing the widget library, which is why the application declares that dependency itself. *What the library
guarantees:* the state you read is the state the application will receive, and the status line shows exactly what
validation at startup would say, so window and run cannot disagree.


## 9. How the project is put together

```text
dev.jucm:config-core        the model and the engine: values, paths, declarations, changes (patches), the chain,
                            the flow, the verdict, exit codes, the transformer registry. MUST NOT depend on a
                            YAML parser, an argument parser or any UI; its one implementation dependency never
                            reaches its public API.
dev.jucm:config-yaml    ──► core    YAML source (snakeyaml-engine): raw scalars, comments kept (the markers
                                    live in them), `${…}` in KEYS resolved before parsing, and the single
                                    text→structure reader that the ENV adapter is given.
dev.jucm:config-env     ──► core    Environment variables: prefix, `__` hierarchy, markers, values as text.
                                    No external dependencies.
dev.jucm:config-picocli ──► core    CLI: the option spec is built from the model, no annotations.
                            ──► yaml/env   the module-composition smoke test
dev.jucm:config-casciian ──► core   The terminal configurator and its test driver.
dev.jucm:config-toolbox ──► all of the above
                                    The facade: layer assembly, flow, validation, exit codes, the built-in
                                    actions. An application sees the model and the result — not picocli,
                                    not casciian.

examples/minimal-app            ──► toolbox as an EXTERNAL artifact (mavenLocal)
integration-tests/consumer-app  ──► core as an EXTERNAL artifact — behaves like a normal user
integration-tests/native-app    ──► toolbox as an EXTERNAL artifact; the ONLY module allowed to apply the
                                    native-image plugin — it is the consumer's application
```

Dependencies point one way: every adapter depends on the core, the core depends on nothing of ours, and source
modules do not know one another — which is why the ENV adapter is *given* the YAML reader instead of importing it.
The libraries produce **JARs and nothing more**; the native plugin is applied in the consumer's application
project, so a plain `check` works without GraalVM. The modules are published to `mavenLocal` as
`dev.jucm:<module>:1.0` and consumed as artifacts — never as project dependencies — so the published
contract is what gets tested.

## 10. Conceptual mistakes to avoid

1. **Treating absence and emptiness as one thing.** Absence is a state; emptiness someone wrote down is an error in
   every source. `X=` in the environment is neither: nobody wrote a value there, so the layer brings in nothing and a
   lower layer, or the declaration, answers.
2. **Reading a notation as a type.** `010` is eight, not ten — and interpolation happens before the cast, so a
   dependent expression may see the notation as written while the field itself shows the cast value.
3. **Believing values are live.** A snapshot is a photograph; holding one and expecting it to follow later layers
   is the classic bug — the handle exists for that reason.
4. **Expecting objects to merge.** Layers state addresses, they do not merge documents; a sequence is replaced
   wholesale, and a whole object under one key turns that key into a leaf (in a file, when the field standing there
   is declared `map`).
5. **Reading a marker from anywhere in a file.** Only a comment **on the same line as the value**, and only when it
   **starts** with the section, counts; elsewhere it is prose — or an error.
6. **Writing a marker as an addition.** A source rule *replaces* declared rules; if you wanted both, the replacing
   rule must state the whole chain.
7. **Looking for an escape for `${`.** There is none; the construct is rejected. Likewise: no anchors or aliases as
   interpolation, no `+=`, no self-reference.
8. **Expecting a rule language.** A rule is a flat chain evaluated strictly left to right — no precedence, no
   brackets, no arithmetic — and every condition after the first must start with `&` or `|`. Order on text is a
   model error, not a text comparison.
9. **Assuming an exit code names a layer.** The decade is a **position** in the pipeline the application built;
   inserting a source shifts the numbers.
10. **Mistaking the engine's options for configuration options — or the other way round.** The engine's options
    take ONE dash (`-help`, `-strict-config-parser`), the model's fields take TWO (`--server-port`), and a declared
    alias takes one (`-p`). So `-help` is the help, while `--help` belongs to the model: a field named `help` is
    legal and is set exactly like any other field. The one place a mistake is likely rather than accidental is a
    bare `--help` on a model without such a field — there the usage error names the form the user probably meant
    (the message carries `did you mean -help`) instead of only reporting the missing parameter. There is no `-h`:
    a short name exists only when it is declared.
11. **Expecting an unknown option to fail — or an option without its parameter to be forgiven.** A token outside
    the schema **with a value** becomes an auxiliary key and the run continues, the point of accepting it being to be
    able to report it (`UNUSED_AUXILIARY` fires on the chain of layers, not on the output of the flow — section 5,
    rule 7). A bare token is the opposite case: an option left without its parameter is a usage error (`125`) for
    **every** name, declared or unknown, and the application never starts. The boundary is the value, not the name —
    and it holds on **both** sides of an action word, because an action carries a copy of the options: `tui --prot`
    is an error, not a token dropped in silence.
12. **Reading the history as a declaration.** On a chain that has been through the resolution step every layer
    carries its **current, materialised** state; "what this layer said" is visible before the flow.
13. **Exercising validation by hand and calling it a verdict.** Judging needs the declaration and the full flow; a
    preview that knows only types cannot know a notation stated in a declaration, and reports values written in it
    as unreadable.


## 11. Glossary

| term | meaning |
|---|---|
| **layer** | a kind of origin of values (`COMPILED`, `YAML`, `ENV`, `CLI`, `TUI`, `CUSTOM`); one node of the chain |
| **source** | a reader that turns one place into entries of a layer: a file, the environment, arguments, a window |
| **declaration** | the code-side statement about a field: address, type, required, default, description, aliases, rules |
| **entry** | a value under an address, with its origin, its source statement and the step verdicts |
| **address (path)** | the canonical, dotted identity of an entry; the unit of precedence |
| **notation** | the written form of a value (`010`, `2026-01-01 12:00:00`), as opposed to its type |
| **statement** | what a source says about an entry: type, notation configuration, rule |
| **marker** | the spelling of a statement: `CONST(…)`/`TCONF(…)` in a same-line comment, `___TYPE`/`___TCONF`/`___CONST` in ENV, `--x--type`/`--x--tconf`/`--x--const` on the command line |
| **patch** | a sparse set of operations — set a value, reset an override, "delivered nothing" (the adapter's own statement that it could not obtain a value) — contributed by one source |
| **overlay** | an additional file read *after* the source file, so that its entries win |
| **flow (pipeline)** | the ordered steps applied to a chain: interpolation → transformation → cast → constraints |
| **step** | one stage of the flow; returns a new variant and marks its verdict |
| **provenance (origin)** | the layer plus the address inside it where a value came from, e.g. `YAML:application.yaml:12` |
| **materialisation** | computing the state of the chain as of a given layer |
| **constraint (rule)** | a chain of conditions imposed on an already typed value |
| **verdict** | the result of a step for one entry: passed, or failed with a problem |
| **absent** | no layer has a value for a declared field |
| **empty** | a value that exists and is empty — scalar, list or map — **written on purpose** (`x: ""`, `--host ""`, `host:`); always an error, wherever it was written. An empty environment variable is not this case: it brings in nothing |
| **dropped** | an expression yielded nothing, so the layer contributes no value and the lookup continues below; with nothing below, the field is `ABSENT` |
| **reset** | a layer takes off its own override, so the layer below answers again |
| **auxiliary** | a key outside the schema; legal, composable, and not an API |
| **coalesce** | the fallback `??`: the left side when it contributes a value, otherwise the right. The origin report prints one `coalesce.<path>=<side>: <reason>` line per decision, next to `layer.<path>` — it needs the snapshot BEFORE the steps, because the flow resolves the symbols away. An operand has the type its **form** states (a number is `int`, quotes make `str`, `true`/`false` are `bool`) or the type of the address it names; it must fit the declared type of the value, and the right side, once taken, must **deliver** — otherwise `RULE_MISMATCH` |

## 12. Where to read what

| you want | go to |
|---|---|
| the behaviour of the library as a model | **this document** |
| the exact contract of a type or a method | the **Javadoc** of the module that owns it (`config-core` for the model; each adapter for its own intake rules) |
| the smallest possible application | `examples/minimal-app/README.md` and its single source file |
| a running application, its layered output, real exit codes and rules biting | `integration-tests/native-app/README.md`, including what legitimately surprises |
| how a native binary is built for the consumer, and why the plugin is only there | `integration-tests/native-app/build.gradle` and the comment in `settings.gradle` |
| the role and boundary of every module | the root `build.gradle` and each module's `build.gradle` |
