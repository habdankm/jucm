# Release 1.1 — decisions that block implementation

Scope: the two problems that made the first consumer (`/home/mecat/Documents/proj/chunked`) stop working, not the
wish list in `release2features.md`. 1.1 is a backward-compatible release on top of 1.0: no existing signature changes,
no existing behaviour changes.

**Status.**
- **Problem A — settled by the owner.** An action is a bare keyword, without a dash; its parameters come from the
  configuration, not from positional arguments. Resolution must read only the paths the model declares; a token JUCM
  does not own is skipped and its value never interpreted. Raw picocli is handed over on request, and the safety net
  covers callers who touch it. Ready to implement.
- **Problem B — awaiting the owner's word on B1, B2, B3, each recommended "yes"**: make the adapters reachable from
  the toolbox (`api`); accept the wider compile classpath (native image unaffected); move consumers to 1.1.

This file is about JUCM only. Rebuilding the consumer's command line around actions is a separate piece of work in the
consumer's own plan and is not part of this release.

Written before any code, because each fix below has a design choice in it and guessing would cost more than asking.

---

## Problem A — a tool's own option with a value rejects the whole command line

Verbatim from the first end-to-end run:

    $ chunked -merge FILE RESPONSE --expect 4266-bca5dd2d
    USAGE: the command line cannot be read: the value is not a readable flow literal:
      [Ljava.lang.String;@7e137f56 ... expected ',' or ']', but got <stream end>

`-merge` takes two arguments, picocli produces a `String[]`, the model does not contain that option — correctly —
and `CliSource` reads the value of an unmatched token as a structure literal. An array is not a literal, so the whole
resolution fails with a message about YAML flow sequences.

### Where the owner wants this to go

Two callers, two needs. Someone writing an application wants to **register a function as an action**: with no action
given the standard main/run happens, with an action given control jumps there from main. Someone who likes poking at
things wants **raw picocli** in hand and the freedom to change whatever the library sets up by default. So JUCM must
not merely tolerate picocli being touched on the side — it must hand it over.

### Proposal

**1. Registering an action — already in 1.0, and the consumer must use it.**

`ConfigTool` already offers exactly this vision: `actions(String... names)` registers the application's own bare
words, `onAction(BiConsumer<Config, String>)` is the business called with the ready configuration and the selected
action name, and `onRun(Consumer<Config>)` is the default path taken when no action is given. The single-dash rule for
engine options is documented there too.

So this is not a 1.1 feature. It is a correction to the consumer: `chunked` hand-rolled picocli options for its modes
instead of registering actions with configuration fields — which is what produced the array value in the first place.
The consumer's rewrite is therefore the *first* thing to do, and it is in the consumer's plan, not here.

What remains for 1.1 in this area is what 1.0 genuinely lacks:

    ConfigTool.of(definition)
        .env("CHUNKED")
        .action("merge", context -> Merge.run(context))
        .action("mark",  context -> Mark.run(context))
        .main(args);                                  // no action given -> the default run

    $ chunked merge --input-file chapter.md --merge-patch answer.txt
    $ chunked mark  --input-file chapter.md --prompt "tighten the second paragraph"

**An action is a bare keyword, without a dash, and its parameters come from the configuration like everything else.**
`--input-file` and `--merge-patch` are ordinary fields of the model: declared in code with defaults, overridable by
ENV and by the command line. The keyword only selects what runs.

This is the correction that matters most in this release, and it is the owner's. The first consumer passed two
positional arguments to an option (`-merge FILE RESPONSE`), which is what produced the array value that brought the
whole command line down. Read the other way round, the defect was a symptom of designing against the grain: the mode
should have been an action, and its parameters should have been configuration. **The library fix below is still
required** — a token JUCM does not own must never be interpreted — but the guidance is now explicit: a tool's
parameters belong in the model, its modes belong in actions.

An action receives a context carrying the resolved configuration, the raw parse result and the arguments; what it
returns becomes the process exit code. Help lists the registered actions, so a user discovers them. Action names are
validated at registration (a bare word, no dash), which is what keeps the dispatch unambiguous.

**2. Handing over picocli (the escape hatch).**

    CliSpec.of(definition).raw(spec -> spec.addOption(...));     // anything picocli can do

The callback receives the `CommandSpec` before the run: subcommands, mixins, custom types, awkward arities — all
allowed, none of it JUCM's business. The single-dash rule stays enforced where JUCM provides the registration
(`engineOption`) and is explicitly not enforced on the raw spec. Once a caller takes picocli in hand, the rule is
theirs.

**3. Engine options without going raw.**

    CliSpec.of(definition).engineOption("-verbose", 0, "…");

For switches that belong to the engine itself — the kind that never appears in the model, like `-help` or `-version`
— with the naming rule checked and the option described in help. These are switches; a tool's *parameters* stay in
the model, as above.

**4. The defect itself, fixed at the root.**

Resolution reads **only the paths the model declares**. A token JUCM does not own is skipped, never interpreted: in
particular its value is never handed to the structure reader. That is what turns today's failure — an array read as a
YAML flow literal, taking the whole command line down — into nothing happening at all.

**5. What an unknown option produces.**

picocli's own refusal, naming the token, with JUCM's usage exit code. Not a warning that is easy to miss and never a
silent skip: in this project a mismatch is an error rather than a repair, and a typo that quietly changes the
configuration is exactly what that rule exists to prevent.

## Problem B — the facade is not usable as a facade

`dev.jucm:config-toolbox` declares `config-yaml`, `config-env` and `config-picocli` as `implementation`. A consumer
that wants `EnvSource`, `CliSource` or `YamlSource` — which is every tool — must declare those artifacts again, at
the same version, or compile against "package dev.jucm.env does not exist".

### Decisions

**B1 — how is reachability fixed?**
  (a) The toolbox declares them `api`.
  (b) A new artifact (`config-facade-cli`) groups them; the toolbox stays minimal.
  (c) Documentation only: "declare these yourself".
  **Recommendation: (a).** One word per dependency, no new artifact, no version skew to keep in step. (c) is what we
  just did and it cost an afternoon.

**B2 — is the added weight acceptable?**
  Making them `api` widens the *compile* classpath of consumers to include picocli and the YAML reader. It does not
  change a native image: the size is set by reachable code, not by the classpath (measured earlier: picocli plus
  SnakeYAML cost 2.06 MiB only when actually reachable).
  **Recommendation: accept.** The binary is unaffected and the compile classpath is where the tool lives anyway.

**B3 — do consumers move to 1.1?**
  (a) `chunked` pins 1.1 and drops its hand-written pre-pass; `integration-tests` follow.
  (b) Publish 1.1 and let consumers stay on 1.0.
  **Recommendation: (a).** `chunked` is the acceptance test for this release; if it stays on 1.0, 1.1 is unproven.

---

## What is deliberately not in 1.1

- **Typed readers on the resolved configuration** (`config.intAt("mlc")`) and the single-call layer composition.
  Both are real (`release2features.md` items 1 and 2). They are held back not because a minor release may not add
  API — 1.1 is a minor, additions are welcome — but because they are a separate design conversation that deserves
  its own decisions.
- **`ConfigResult` and `instanceof`**: documenting the idiom is enough for now.
- **`version.properties` in a native image**: covered by `-H:IncludeResources`, already written down in the
  consumer notes; no library change.

---

## Acceptance for 1.1

1. A tool declares its own options through the library, and a multi-valued one no longer breaks resolution.
2. A tool registers actions: with an action given, control jumps there; with none, the default run happens.
3. A caller can take the `CommandSpec` and add anything picocli allows, and resolution still succeeds.
4. An unknown option produces a clear refusal naming the token, not a message about flow literals.
5. A consumer compiles against `config-toolbox` alone — no second declaration of adapters.
6. `chunked` uses the library for its own options, drops its pre-pass, pins 1.1, and its end-to-end test passes:
   `-merge` applies an answer, a stale snapshot is refused, and the file is untouched on refusal.
7. JUCM's own gate stays green (`clean check --rerun-tasks`) with new tests covering all of the above.
