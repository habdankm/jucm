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
