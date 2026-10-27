# JUCM — TODO

Open work, one item per section, each with what it changes, why, and how it will be accepted. Anything decided
belongs in `docs/`; anything simply noticed belongs here.

---

## A sensitive ConfigField, so a declared secret cannot be printed

**Status:** proposed 19 X 2026, not started. Nothing depends on it yet.

### The gap, measured

`grep -rn "sensitive" --include=*.java .` finds three lines and all of them say "case sensitive"; `secret`,
`masked`, `redact`, `hidden` and `obfuscat` find nothing. `ConfigField` has `described`, `aliased`,
`constrained`, `transformer`, `withMetadata` — and no way to say "this value must never be shown".

So a password declared as a field is indistinguishable, to every printer in this project, from `service_bind`.

### Why it is not urgent — and why it is still worth writing down

The application that asked the question (maswerk, wiring JIDL's database connections) keeps passwords in
ENVIRONMENT VARIABLES ONLY, deliberately: a secret that is never a `ConfigValue` cannot leak through diagnostics,
`init --interactive` or a provenance dump. The ENV layer supplies it, the file never holds it, and the question of
masking does not arise.

It arises the moment a secret MUST live in a file — a deployment that cannot set variables per process, or a field
that a person types during `init` and expects to be stored. That day the value is in a `ConfigValue`, and every
printer is a leak.

### What to build

1. `ConfigField.sensitive()` — sugar over `withMetadata("sensitive", "true")`, so the field's shape does not change
   and a config that does not use it is byte-identical.
2. ONE place through which every value printed for a human passes. Today that is not one place: `Provenance` and
   `ConfigSource` carry values out to whoever prints them, and `config-toolbox` / `config-picocli` /
   `config-casciian` each render their own view. Finding (or creating) that single door is the real work, and it is
   the reason this item is not a two-line change.
3. Masking is not redaction of the source: the value stays in the snapshot and reaches the application; only the
   PRINTED form becomes `***`, because an application that cannot read its own password is useless.

### Acceptance

- A field marked sensitive never appears in any human-facing rendering: `init --interactive` prompt echo, the
  provenance dump, the CLI's "where did this come from" output, and any exception message that quotes a value.
- A field NOT marked sensitive renders exactly as it does today — the change must be invisible to every existing
  configuration.
- A test that fails when the masking is removed, and a second one that shows the value still arrives intact at the
  application (masking is presentation, never data).
- Documented in `docs/` with the distinction: ENV-only secrets (nothing to mask) versus declared sensitive fields
  (masked at print).

### What a host decides with it (recorded 19 X 2026)

The flag is a DECLARATION BY THE HOST, per field, and JUCM must not decide it: maswerk marks its database
PASSWORDS sensitive and deliberately does NOT mark `user`, because identity in a diagnostic line is worth more
than a name hidden. Any future host may mark whichever field it likes; the library only guarantees that a marked
field is masked wherever a value is printed.

### Not decided here

Whether a sensitive field may also be WRITTEN back by a tool (`init`, an overlay exporter). If it may, the write
side needs the same rule, and that is a second decision rather than a detail of this one.

---

## The vault as a framework layer - DONE 21 X 2026 (JUCM 1.6)

`config-toolbox` now owns the layer: an application declares a field `.sensitive()` and the store is read BETWEEN the file and the environment, with that
position decided here rather than by each application. Opened only when the declaration has a sensitive field, so an application without secrets never
gets a vault directory created under its feet. A vault that cannot be read stops the run with its own sentence. 4 tests: filled from the vault and
reported as CUSTOM; ENV still wins; a password written in the FILE refused (and not printed); no sensitive field means no store touched.

---

## YamlDocument cannot write or read a YAML sequence

**Status:** noticed 27 X 2026 in maswerk, not started, and **nothing in maswerk blocks on it** — a consumer that needed
it was re-shaped instead of waiting: the active set became a FIELD of the workspace register entry (`active: true`, see
`maswerk/docs/refactor_plan.md` §8), so maswerk has no list to write at all. Write it when a caller actually needs a list, not before.

### The gap, measured

- `YamlDocument` exposes `parse`, `read`, `has`, `scalar`, `keys`, `putScalar`, `putMapping`, `remove`, `toYaml`,
  `writeIfChanged`. **There is no way to write a sequence**, and no way to READ one as a list: a flow list
  (`key: [a, b]`) comes back through `scalar(...)` as the string `[a, b]`.
- `putScalar("[" + a + ", " + b + "]", "key")` does not help: it quotes what it is given, so the file gets
  `key: '[a, b]'` — a STRING that looks like a list. Measured in maswerk on 26 X 2026, where `workspaces_active`
  was written by hand around this (a line-level rewrite that keeps comments and takes a backup, `work/Activation.java`).

### A second defect, found the same day

`putMapping` throws `UnsupportedOperationException` on a document obtained from `parse("")` (or from a comment-only
text): the node list it builds is immutable, so a mapping cannot be ADDED to a fresh document. A caller that wants to
write a new file has to write a skeleton with the keys already present and then replace scalars in place — which is
what maswerk's `StorageStateFile` now does, with the reason written next to it.

### What it would take

1. `putSequence(List<String> values, String... path)` — flow list, quoting elements that need it and nothing else.
2. `Optional<List<String>> sequence(String... path)` — so a list reads back as a list.
3. The immutable-node fix, so a document built from nothing can gain structure.
4. Tests beside `YamlDocumentTest`: empty list, one element, an element containing a comma or a quote, a key that is
   a scalar today and becomes a sequence (must be a refusal, not a silent type change).
