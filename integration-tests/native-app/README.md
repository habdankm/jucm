# `native-app` — a small application you run and check YOURSELF

What it is for: a configuration with one dependent field (`host`, `port` → `uri`), read from three
layers (file → ENV → command line) and with a **RULE on the port**, so that JUCM can be observed in
practice — on the JVM and as a native binary (GraalVM), on Linux, macOS and Windows.

All commands are run from the repository root.

## 1. The fastest way: the JVM

```bash
./gradlew :integration-tests:native-app:run
```

Output (this is what you actually get — the directory holds `application.yaml`):

```text
host=example.org
port=443
uri=https://example.org:443/kaboom
layer.host=YAML
layer.port=YAML
layer.uri=YAML
```

`layer.*` says **which layer supplied the value**. `uri` is `COMPILED` when nobody supplied a value —
then it comes from the application model and is only **recomputed** from `host` and `port`.

## 2. The RULE on the port: 8080 or 443 only

```java
ConfigField.required("port", ConfigType.INT, ConfigInt.of(8080))
    .constrained(ConfigConstraint.Const("in[8080,443]"))
```

The same chain in every source — one syntax across all media:

```text
Java   ConfigConstraint.Const("in[8080,443]")
ENV    URIAPP__PORT___CONST='in[8080,443]'
CLI    --port--const 'in[8080,443]'
YAML   port: 8080    # CONST(in[8080,443])
```

See how the rule bites (native binary):

```text
$ ./integration-tests/native-app/build/native/nativeCompile/uri-app --port 9999
layer.host=YAML
layer.port=CLI
layer.uri=YAML
error=validation-failed: does not satisfy the rule: in[8080,443] [path `port`] [value `9999`] [source CLI:--port]
$ echo $?
43
```

The message carries the **rule**, the **offending value** and the **source** — exactly what tells you
where to fix it. The exit code depends on the LAYER (`Y=3`, source decade): `43` — CLI, `33` — ENV,
`23` — YAML. The same mechanism with ENV:

```text
$ URIAPP__PORT=8080 URIAPP__PORT___CONST='in[443]' ./build/native/nativeCompile/uri-app
error=validation-failed: does not satisfy the rule: in[443] [path `port`] [value `8080`] [source ENV:URIAPP__PORT]
$ echo $?
33
```

So `___CONST` **replaces** the rule from the code even on a declared field — and that is visible.

## 3. Change something and watch `uri` recompute

The `application.yaml` file in this directory is read from the **working directory** (Gradle starts
the application right here, so it works out of the box). Change `port: 8080` to `443` and run again —
the rule admits no other values (and says so plainly).

The command line beats the file:

```bash
./gradlew :integration-tests:native-app:run --args="--port 443"
```

ENV beats the file but loses to the command line:

```bash
URIAPP__HOST=env.example URIAPP__PORT=443 ./gradlew :integration-tests:native-app:run --args="--port 8080"
# => host=env.example (ENV), port=8080 (CLI)
```

## 4. In the window: the symbolic view, `type`, `CONST` and the configuration outputs

```text
description      type       defaults   field name                value                         CONST
Service host.    | str      | localhost | host = example.org      | example.org
Service port.    | int      | 8080      | port = 443              | 443                           | in[8080,443]
Base address     | str      | [none]    | uri = http://${host}    | http://example.org:443/api
```

The `defaults` column shows the value DECLARED IN THE MODEL — what you get when no source supplies
one (`[none]` = a computed field, such as `uri` from the transformer). The `value` column is the
CURRENT value, and `CONST` is the field's rule.

The window starts MAXIMISED and the columns take as much room as the screen gives them (the
description yields first, then the field name grows up to 30 characters, and `CONST` takes the rest).

The `CONST` column accepts a rule typed by hand — **the same chain** as `___CONST` / `--x--const` /
the `CONST(…)` section in YAML. Confirming it puts the rule into the model: **a rule from a source
replaces the rules from the code**, and an empty field restores them. The `type` column (read-only
for now) shows the type from the dictionary.

```text
RULES travel with EVERY save (the same grammar we read):

      YAML/SAVE  port: "443"   # CONST(in[8080,443])     <- a section in a comment on the SAME LINE
      CLI        --port--const 'in[8080,443]'            <- quoted, because `&` and `|` are shell
      (notation: `# TCONF(…)` and `--x--tconf '…'`)
      Without that the file would reproduce values without the rules that bind them — and that is
      pinned by the closed-loop test: `Save` -> file -> the application reads the rule and ENFORCES
      it (code 23 on a violation).

YAML  writes the WHOLE configuration to `application.resolved.yaml` (resolved values plus an
      `# as written: …` comment next to computed fields)
CLI   writes a SCRIPT `application.sh` with the whole configuration as `--` flags — `sh application.sh`
      starts the application with those values (values with spaces are quoted, `"$@"` passes on
      further arguments)
Save  writes the CHANGES of the session to `application.applied.yaml` — the source file stays
      untouched, and the application reads it and then OVERLAYS it, so after a restart you see the
      same thing

WHICH FILES ARE READ: only `application.yaml` and `application.applied.yaml` (in that order — the
overlay wins). **`application.resolved.yaml` is NOT read**: it is a resolved view, for pasting or
comparing. A rule that is to be in force therefore belongs in `application.yaml` (or in the TUI and
`Save`), not in the `resolved` file — there it lands only as a record of what was.

WHERE THE FILES LAND: in the working directory of the process — the same one `application.yaml` is
read from (under `./gradlew run` that is the `integration-tests/native-app` module directory, not the
one you typed the command in). The status line shows the FULL PATH after every save.

Reset undoes the changes (and removes the rules entered in the session) · Cancel and ESC exit · the
window close button too
```

## 5. What may legitimately surprise you (and what is deliberate)

```text
port: 0673       -> port=443, but uri=http://localhost:0673/api
                    interpolation of a VALUE sees the NOTATION from before the cast — that is the
                    contract (`0673` is 443 in octal, which is why the rule `in[8080,443]` lets it through)
--port           -> exit code 125 (a picocli usage error: an option without its parameter)
--prot           -> exit code 125 (the SAME usage error: a bare token sets nothing, whatever the name —
                    the boundary is the VALUE, not the name, so an unknown option does not get a pass)
--prot 9000      -> exit code 0 (a typo WITH a value is an "auxiliary" key, not a usage error)
--port 9999      -> exit code 43 (the rule from the model, a message with the chain)
```

Exit codes: `0` success · `125` usage error · the rest are problem codes from `ConfigExitCode`.

## 6. The native binary (GraalVM)

```bash
./gradlew :integration-tests:native-app:nativeCompile \
    -Porg.gradle.java.installations.paths=/path/to/graalvm      # or GRAALVM_HOME

cd integration-tests/native-app && ./build/native/nativeCompile/uri-app
```

**Run it from the module directory** — the binary reads `application.yaml` from the working directory.

The same set of checks passes in it exactly as on the JVM — so any differences you see will concern
new things (platform, time zone, console), not the model.

`native-image` **does not cross-compile**: a binary for macOS/Windows has to be built ON that platform.

GraalVM is indicated **at native build time** (`-P…installations.paths=…` or `GRAALVM_HOME`). When it
is not indicated, `run` and the tests work normally, and `nativeCompile` says plainly that
`native-image` is missing — instead of a mysterious toolchain selection error.

The binary is **not in the repository** (and should not be) — it is built locally, and `./gradlew
clean` removes it. If the file is missing after a `clean`, that is not a failure: run `nativeCompile`
again.

## 7. Tests and checks in this module

```bash
./gradlew :integration-tests:native-app:test          # behaviour through the public API
./gradlew clean check                                 # the whole project gate
```

The `org.graalvm.buildtools.native` plugin is **only here** — because this is the consumer's
application project; the JUCM libraries keep producing JARs. The native recipe
(`toolchainDetection = false` + `javaLauncher` on the **binary**) is described in
`integration-tests/native-app/build.gradle`.

## JVM ↔ binary parity

The same checks, two ways of running — and a **hard comparison**, because what matters is whether the
result is identical (stdout, stderr, exit code). A difference means one side behaves differently: it
reads ENV differently, handles the help (`-help`) differently or computes the exit code differently.

The comparison is a case-by-case run of the two launchers — the JVM one through `java -cp` (the
classpath from the `printRuntimeClasspath` task) and the binary through
`./build/native/nativeCompile/uri-app` — followed by a comparison of the three result dimensions
(exit code, stdout, stderr). The harness that drove those cases during development is **not part of
this repository**, so there is no script to call here: run both sides yourself and compare.

Cases the comparison covers:

```text
no arguments (file + ENV)
-help
config-validation -help
config-validation
config-validation-with-layers
ENV overrides the port
ENV breaks the rule `in[8080,443]`
an option outside the schema WITH a value is auxiliary (`--prot 9000`), a bare one is a usage error (`125`)
```

Two things this comparison forced, worth knowing:

- The application has a **public no-argument constructor**, although it never calls it: that is what
  the JVM launcher needs to run an instance `main`. Without it `./gradlew run` fails with "no
  non-private zero argument constructor found". Native Image builds it the same way.
- The JVM side is started through `java -cp` (the classpath from the `printRuntimeClasspath` task),
  **not** `./gradlew run`: Gradle starts the process from the daemon, so environment variables set for
  the invocation do not reach the application — and without ENV the ENV layer cannot be compared.

Scope: **Linux**. macOS/Windows are explicitly OPEN — we do not pretend they are verified.
