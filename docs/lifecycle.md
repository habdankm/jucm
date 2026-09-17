# The life cycle of a configuration

`README.md` is the **model** of JUCM — what it is, how it thinks, what it refuses to do; its section 4 sketches this life
cycle in one diagram, section 7 lists the invariants, section 11 is the glossary whose terms this document uses throughout
(**layer, source, step, flow, verdict, entry, origin, materialisation, patch, statement, dropped, reset, auxiliary**).
This document is the **deeper layer**: it follows one configuration from the moment it is declared to the moment an
application reads it, and for every stage answers the same six questions — *what happens, who triggers it, can it fail, can
it be repeated, is the result immutable, can a human intervene.* Behaviour, not classes: the exact contract of a type or a
method lives in the Javadoc of the module that owns it.

```text
  code ─────────────────────────►  DECLARATION   types, required, defaults, rules, aliases, notation
                                      │  materialised as the ROOT layer (COMPILED): defaults, or nothing
                                      ▼
  file ► overlay ► ENV ► CLI ► TUI  SOURCES      one source at a time, in the order the application gives;
                                      │          each source → a patch: a sparse set of
                                      │          (address, value, origin, statement);
                                      │          a source that cannot be read stops the run here
                                      ▼
                                    CHAIN        one immutable snapshot per layer, linked to its parent;
                                      │          building it cannot fail; the chain IS the history
                                      ▼
                                    FLOW         INTERPOLATION → TRANSFORMATION → CAST → CONSTRAINTS
                                      │          every step returns a NEW variant and marks its verdict
                                      ▼
                                    VERDICT      all problems at once + one exit code; the values are typed
                                      ▼
                                 CONSUMPTION    reads by address, the handle, reports, the window
```

Nothing here is a background process: every arrow is a call the application — or the facade it used — makes, in that order.

## 1. Declaration — the schema, in code

**What happens, and who triggers it.** Application code, before any source is read, states every entry it cares about: an
address, a type, whether a missing value is an error, an optional default, a description, optional short CLI aliases,
optional rules, and — where a written notation needs normalising — the name of a transformation with its configuration. That
declaration is the single source of truth behind `-help`, the window, the validation tool and the sample-file generator.

**Can it fail.** Yes, but only as a **model** error, with no data involved, thrown while the model is built rather than
reported as a configuration problem: a path or an alias declared twice, a blank alias or transformation name, a notation
configuration without a name, an empty collection as a default. A rule that cannot apply to the declared type is a model
error too — a *pathful* one, reported as a problem when the rule meets a value. A transformation name no registry knows is
loud at its first use, in the flow; never silently ignored.

**Repeatable / immutable / intervention.** A declaration is immutable and building it twice yields equivalent
declarations — there is no "reload the model". No human intervenes: the window may display the declaration, it cannot change
a type, a requiredness or a rule. Changing a declaration is changing the program.

**Side effect: the root layer.** The declaration is materialised as the bottom layer of the chain, on `COMPILED`, origin
`COMPILED:declaration <address>`: a field with a default contributes that value, and a field without one gets **no
placeholder** — it is *absent*, valid for an optional field and an error for a required one.

## 2. Sources — one layer after another

**What happens.** A source reads one place — a file, its overlay, the environment, the arguments, the window — and produces
a **patch**: a sparse set of operations (set a value together with its origin and any statement the source made about it;
take off one's own override; state "delivered nothing" — the adapter's own word that it could not obtain a value it knew
about). A patch carries what the source really delivered and nothing else:
an address it never mentions is not in the patch, so a lower layer keeps answering for it. Sources transport — they do not
cast, do not validate, do not decide precedence.

**Who triggers it.** The application, which states which sources take part and in which order; the library never reads
anything on its own initiative. The standard facade reads the file, then its overlay, then the environment, then the command
line; the window's edits sit on top as the `TUI` layer.

**Can it fail.** Yes — a failure is an **outcome, not an exception thrown in the middle of a walk**: a file that cannot be
read, a document that does not parse, a variable name that cannot become an address, a marker without a value or with an
unknown type name. Such an error carries the source it concerns and **no path** — the whole source is unreadable — which is
why it outranks individual fields.

**What it does to the rest.** In the shipped facade an unreadable source **stops the run at intake**: business code does not
run, no verdict report is produced, one error is printed, and the exit code is that single problem's code — `27` for a YAML
file that exists but cannot be read. At the API level a source error is an ordinary result value, so an application
composing its own intake decides. Two adjacent cases are *not* failures: a file that is simply **not there** is skipped by
the stock facade, and a source that contributes nothing (an empty patch) is allowed and changes nothing.

**Repeatable / intervention.** Every run reads the sources again; within one run a source is read once. A human intervenes
by fixing the file, the variable or the argument — nothing inside a run can repair a source.

## 3. The chain of snapshots

**What happens.** Each applied patch closes a layer and becomes a node **linked to its parent** — the previous node is the
layer underneath. The chain is the history: no separate change log, no `oldValue`, and a layer whose patch is empty creates
no node. A layer may also be *grown* entry by entry with a value contributed programmatically; that extends the current
layer without creating a link, and such an entry carries **no verdicts** — which is how "has not been through the steps yet"
is visible. Storage is sparse, lookup is dense: a node holds only what it brought in, while a read sees every declared
field, including the ones no source ever mentioned.

**Who triggers it.** The application, or the facade that merged its sources; layer order and the set of layers are an
application input, not a library rule.

**Can it fail.** Not on configuration data — building the chain is not a judgement. The only failures are programmer errors:
a patch mixing two layers, a malformed path, a `null` where a value is required.

**Repeatable / immutable / intervention.** The same patches in the same order produce an equivalent chain, every node is
immutable, and nothing here is a human decision. A node holds a marker where a value was taken off (`reset`) or ceased to
exist (`dropped`), so the history shows *where* something disappeared instead of falling back silently.

## 4. The flow — four steps

The default flow is interpolation → transformation → cast → constraints. The library ships that default **and** a factory
per step, so the order and the set of steps are an explicit input — a different order is a different, legitimate
configuration contract. Every step is a function from a snapshot to a snapshot and marks its **verdict** on the entries it
processed.

| step | what it does | what it can produce |
|---|---|---|
| **interpolation** | turns text into symbols and symbols into values **against this snapshot**: resolves references, walks `a → b → c`, finds cycles, reports broken `${…}` syntax. The only **whole-tree** step: it sees the variant *before* the step, identical for every entry, so the result does not depend on the walk order | a resolved value; a **dropped** entry; `UNRESOLVED_REFERENCE`, `UNRESOLVED_EXPRESSION`, `CYCLE`, `INVALID_SYNTAX` |
| **transformation** | brings the written notation to the canonical form of the declared type, through the transformation named by the declaration or the notation the type itself carries | a normalised value; `TRANSFORM_FAILED`, naming the notation and its configuration |
| **cast** | requires the canonical form of the declared type, **checking emptiness first**, so an empty field is reported as emptiness and not as "expected type bool" | a typed value; `CAST_FAILED`, `NULL_VALUE` |
| **constraints** | applies the rule chain to a value whose type is known; the type's own rule always applies alongside the field's rules | a pass; `VALIDATION_FAILED` with the rule and offending value; `RULE_MISMATCH` |

**Properties that hold for every step.**

- **A step never mutates.** It returns a new variant; an older snapshot keeps its own image, and a step with nothing to do
  returns the *same* node, so an unchanged chain is reused rather than copied.
- **A step processes only entries without its verdict yet.** Re-running is free, and a value contributed after a run is
  checked exactly once.
- **An entry stopped by an earlier step does not go further** — one mistake yields one problem, not four — and the failing
  entry stays in the variant with its problem, so the report can reproduce it.
- **Verdicts are per entry and per step.** A missing verdict is not an error; it means that step has not been here yet. And
  **the entry decides nothing about its type on its own**: types and notations come from the declaration, a statement from a
  source fills in a notation configuration and replaces the field's rules, but never the rule belonging to the type.

**Where the outcome lands.** A step decides the *winning* entry and writes its outcome into the layer of that entry: exactly
one layer claims a path — the first from the top that carries it; a `reset` does not claim a path (it only skips the layer
below it) and a marker of "nothing" decides the path. Lower layers keep their own declarations, which keeps the history
honest, and after a step each layer carries the **state** computed for it, not merely its declaration.

**Three ways an entry can carry no value**, and the difference is visible in the read: **dropped** — an expression
yielded nothing (`${?x}` with no target), so the layer contributes
nothing and the lookup descends, a lower layer's value answering (and after the flow, being materialised into the
winning entry); when nothing below answers either, the field is **`ABSENT`** — the same state as if that layer had
not mentioned the address, so an optional field runs (`0`) and a required one fails as missing (`14`), never as
emptiness (`20`). **Nothing delivered** — the *adapter's own statement* that it knew of the value and could not
obtain it: the read stops there with an emptiness problem and does **not** descend, and this is the only route to
`null-value` that carries no value (the shipped adapters never take it — see section 2). **Empty** — an empty
string, list or map **written on purpose**, an error in every source, caught before the cast; an empty environment
variable is not this case, because it brings in nothing at all.

## 5. The verdict

**What happens, and who triggers it.** Judging a configuration is a separate, explicit operation, not a side effect of
building one; the application, the facade at startup or one of the built-in report actions — which do not run the business
code — triggers it. It runs the default flow when the chain has not been through it and then **reads the verdicts** instead
of repeating the work, returning the typed values *and every problem at once*, because stopping at the first problem would
make the user fix the configuration one error at a time.

**Can it fail.** It cannot fail as an operation; it *reports*. Both halves are facts of equal standing: the values that
passed and the problems that stopped others. **Idempotence:** a second call adds nothing (the entries already carry their
verdicts), so judging is repeatable *and* free — as is re-running the flow, which gives back the same snapshot.

**Order and determinism.** Problems are ordered by address, then kind, then origin; problems that concern no path — an
unreadable source — come first, because they outrank fields. The same configuration yields the same list with the same first
problem on any machine and in any run. A cycle is one problem even though every participant fails, and no problem appears
twice when it arrives from two places. Keys outside the schema stay auxiliary: reachability is computed statically, so an
unknown option **with a value** neither fails the run nor hides in silence (`UNUSED_AUXILIARY`, when the verdict is asked
for a chain that has not been resolved yet). A **bare** option token is not in that category at all — an option left
without a parameter is a usage error (`125`) for every option name, declared or not.

**One code when there are many errors.** The exit code is `XY`: the **decade** is the position of the layer in the pipeline
the application built (the first position is the materialised declaration, saturating at nine) and the **unit** is the
category of the cause (section 8). **The lowest code wins**, so the result does not depend on the order of detection, and `0`
means exactly "zero problems". The zero decade belongs to the application: single-digit codes are never ours. `125` is a
command-line **usage** error and sits outside the family — a configuration never produces it. The report on the output
stream is the product; the exit code carries the verdict.

## 6. Consumption

- **A read** gives either a value or an error saying why there is none; it never returns `null` and never throws for a
  missing value. **Two views of one entry:** the *symbolic* view is the entry as the winning layer holds it — value, origin,
  verdicts, the statement the source made — while the *resolved* read resolves `${…}` lazily, against one snapshot.
- **The history of a value** is what each layer contributed, newest first; it is never stored, only computed by walking the
  chain. *Boundary:* on a chain that has been through the resolution step each layer carries its current, materialised state,
  so the history then shows a layer's state, not its declaration — "what this layer said" needs a snapshot taken **before**
  the flow. **The state as of a layer** is the chain cut off at that layer, computed on demand, nothing cached; *boundary:*
  step-computed values were computed once, with the widest view, so a state computed *in the context* of a layer means
  running the flow over the prefix of the layers, which is what the layer-printing report does.
- **The handle to the current configuration** is the one mutable thing in the model (section 7).
- **Reports** built from the same machinery: the verdict report for a human; the layer-printing report (what each layer
  contributes / what comes out of it); the sample-file generator, whose source is the **model** and therefore works even when
  the configuration does not; the origin report `layer.<address>=<layer>` per declared field, ordered by address.
- **The window** is a projection of the model: one row per declared field, captions from the declaration, no text of its
  own. Every edit goes through the model, and the status line shows exactly what validation at startup would say, so window
  and run cannot disagree; saving writes into the source directory — the same place the file is read from.

Reads are side-effect free and consult nothing outside the layers of the snapshot they read.

## 7. When the configuration changes

**A change is never in place.** Reconfiguration means a new layer on top of the chain (a new patch) or a new position in the
chain — never an edit of an existing node.

- **The handle swaps atomically.** A reader sees either the whole old model or the whole new one, never a mix, and no read
  locks are needed for that; **a snapshot held in hand does not see later changes** — an advantage inside one operation and a
  trap for long-lived objects, which must hold the **handle**, not a copy of the value.
- **No hidden propagation.** Nothing pushes a change into dependent values; there are no observers, no change events, no
  setters. "The dependent field changed" always means a new resolution of a **new snapshot**, computed on demand; a
  reference keeps pointing at an address, so a newer layer changes what existing references resolve to, nothing is rewritten.
- **Window edits** are a layer like any other: they sit on top, beat the file, the environment and the command line, and stop
  doing so when the edit is taken off (`reset`, which removes that layer's own override only — it cannot mask a lower layer's
  value). After every edit the window recomputes a fresh snapshot and redraws the fields whose resolved value changed,
  including fields distant from the cause: the working copy is the model's own computation, not a second opinion about it.
- **Retention.** Compaction is in the API but has no implementation in this version, and it fails loudly on purpose; the
  chain grows with the number of layers and nothing is discarded, so plan long-lived handles accordingly.

## 8. When something is wrong

A problem is one of a **closed set of kinds**, each answering five questions: what failed, where, under which path, with what
value, and what a human should read. A problem is never signalled by a `null` or a blank value — precisely what keeps
absence and emptiness apart.

| stage that produced it | kinds | unit |
|---|---|---|
| source / input | `PARSE_FAILED`, `NULL_VALUE`, `INVALID_SYNTAX`, `TRANSFORM_FAILED` | `0` |
| conversion | `CAST_FAILED` | `1` |
| resolution | `UNRESOLVED_REFERENCE`, `UNRESOLVED_EXPRESSION`, `CYCLE` | `2` |
| validation | `VALIDATION_FAILED`, `UNUSED_AUXILIARY` | `3` |
| missing | `ABSENT` — counts in the declaration layer, the lowest position | `4` |
| path / model | `UNKNOWN_PATH`, `RULE_MISMATCH` | `5` |
| I/O | `FILE_NOT_EXIST` | `7` |

- **A source error is a result, not an exception in the middle of a transition.** A source that cannot be read is never read
  as a whole, which is why such a problem has no path and outranks fields; what it does to the rest of the run is in
  section 2 (`27` for a YAML file that cannot be read).
- **One failing entry does not hide the others.** Every other entry is still reported — as a value or as its own problem —
  and the report lists all of them at once. **A failure stops that entry only:** later steps skip an entry an earlier step
  stopped, so the number of problems matches the number of mistakes, not the number of steps.
- **The exit code is one number for all of them** (section 5). Verified examples: a rule violation in the file gives `23`,
  in the environment `33`, on the command line `43`; a required value no layer supplies gives `14`, because `ABSENT` belongs
  to the declaration at the lowest position and wins over a higher-layer problem.
- **A model error with a path is reported, not thrown** (`RULE_MISMATCH`); what is thrown are the failures about the
  *declaration itself* (section 1), because no configuration value could fix them.

## 9. Guarantees

1. **Nothing changes in place.** Every operation produces a new snapshot; an older snapshot keeps its own image and its view.
2. **A snapshot in hand does not see later changes**; the current configuration is reached through a handle that swaps it
   atomically — the only mutable thing in the model.
3. **A reference points at an address, never at a copy of a value**, and resolution is lazy, against one snapshot, at the
   moment of reading.
4. **No hidden propagation** — no observers, no events, no dependent value updated behind anyone's back.
5. **No value is ever `null`, and absence is never an empty value** — two kinds of fact, two problems.
6. **The verdict is deterministic and complete:** all problems at once, in a defined order; one mistake, one problem; a
   repeated call adds nothing.
7. **Every step is safe to repeat**, a repeat over an unchanged chain returns the same snapshot, and a value contributed
   later is processed exactly once.
8. **The kind of a problem is a closed set**, so failures can be enumerated; a new kind must state what it says about path
   and value.
9. **The layer order and the set of steps are inputs, not laws** — a different order is a different, legitimate contract.
10. **The declaration is ordinary Java:** no annotations, no scanning, no proxies, no runtime code generation, nothing of the
    application inspected at runtime.

## 10. Where to read more

| you want | go to |
|---|---|
| the behaviour of the library as a model, and the invariants | `README.md`, sections 3–7, glossary in 11 |
| the sketch of this life cycle in one diagram | `README.md` section 4 |
| the exact contract of a type or a method | the Javadoc of the module that owns it (`config-core` for the model; each adapter for its own intake rules) |
| a running application, layered output, real exit codes, rules biting | `integration-tests/native-app/README.md` |
| the smallest possible application | `examples/minimal-app/README.md` and its single source file |
| the role and boundary of every module | the root `build.gradle` and each module's `build.gradle` |
