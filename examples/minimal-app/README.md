# `minimal-app` — how little you have to write to have an application

**This is an example, not a test.** There is not a single test here, and that is deliberate: examples
are meant to show the *shape*, not to pin behaviour. It compiles in `./gradlew check`, so that it does
not rot.

```text
examples/minimal-app/src/main/java/MinimalApp.java    <- THE WHOLE application, one file
```

## What is NOT in this file

```text
package · class declaration · `public` · `static` · `String[] args` · JDK imports
```

All of that is gone thanks to `JEP 512` (compact source file + instance `main`, standard in JDK 25)
and `JEP 511` (module import). `Path` works from the implicit `java.base`, and `IO.println` from
`java.lang.IO` (note: an unqualified `println` does **not** work — use `IO.println`).

Configuration (file, ENV, command line, TUI window), validation and diagnostics live in the library,
so an application is a **declaration plus one lambda**.

## Running it

```bash
./gradlew :examples:minimal-app:run                      # simplest: Gradle assembles the classpath
./gradlew :examples:minimal-app:run --args="tui"         # configurator window: BUILT IN, zero code
./gradlew :examples:minimal-app:run --args="-help"       # help from the model + the application's section
./gradlew :examples:minimal-app:run --args="--server-port 9090"
MINIMAL__SERVER__PORT=7777 ./gradlew :examples:minimal-app:run
```

The engine's own options take **one dash** (`-help`, `-strict-config-parser`), the model's fields take
**two** (`--server-port`). So `-help` is the help, and a field named `help` — should the model ever
declare one — keeps `--help` for itself.

The "compact source file, run straight from the source" form works too — but it **must have JUCM on
the classpath**, because the application uses the library:

```bash
java -cp "config-toolbox/build/libs/*:config-casciian/build/libs/*:…" MinimalApp.java
```

So what is compact is the **application file**, not its classpath: one *source* file, not one
dependency. Zero runtime dependencies come only with the native binary (see
`integration-tests/native-app`).

## Custom help: one extension point

`-help` is generated from the MODEL (field descriptions, types, aliases, actions) and it also names the
program and the environment prefix. If the application wants to add its own, one line in the chain is
enough — the hook receives the ready help and a stream:

```java
.help((generated, help) -> {
    help.print(generated);
    help.println();
    help.println("MINIMAL-APP — a JUCM example");
    help.println("  minimal-app        prints host:port (ENV, file, default values)");
    help.println("  minimal-app tui    configurator window (built into the toolbox)");
})
```

Output (`./gradlew :examples:minimal-app:run --args="-help"`), measured, not retyped:

```text
Usage: minimal-app [-help] [--server-host=<str>] [--server-port=<int>]
                   [-strict-config-parser=<true|false>] [COMMAND]
      -help                 prints this help and exits (does not run the
                              application)
      --server-host=<str>   Service host.
      --server-port=<int>   Service port.
      -strict-config-parser=<true|false>
                            Lenient mode of the configuration parser: warnings
                              instead of an error.
Commands:
  config-validation              checks the configuration and prints a report —
                                   without running the application
  config-generate                prints a sample YAML file matching the model
                                   (to stdout)
  config-validation-with-layers  checks the configuration and prints the state
                                   AFTER EACH layer: what the layer brings in
                                   and what comes out of it
  tui                            opens the configurator window (TUI)
Environment variables: prefix `MINIMAL` — the field `server.host` is
`MINIMAL__SERVER__HOST` (a double underscore separates the segments).

MINIMAL-APP — a JUCM example
  minimal-app        prints host:port (ENV, file, default values)
  minimal-app tui    configurator window (built into the toolbox)
```

Three things that line carries on purpose: the first line says `minimal-app` and not picocli's
`<main class>` placeholder (that is what `.program("minimal-app")` is for), the last line names the
environment prefix with the variable of a **declared** field (with no `.env(...)` it says plainly that
ENV is not read, rather than showing a prefix nobody reads), and `-help` itself has one dash — the
engine's options are single-dash, while the model's fields are double-dash.

The point's contract: `generated` is **exactly** what we would print without the hook (so it can be
preceded, extended or skipped), and `help` is the application's output stream. One route, not two —
there is no separate "append a section" option, because it is the same mechanism (`tui(…)`,
`onRun(…)`, `onAction(…)` are the same family).

## Working directory (the same trap as in `native-app`)

`sources(Path.of("."))` means the **working directory of the process**. Under `./gradlew run` that is
the module directory (`examples/minimal-app`), not the one you typed the command in — so
`application.yaml` has to be placed there (or the application has to be started from a chosen
directory with the classpath assembled by hand).

## The dependency on JUCM: one line

```groovy
implementation 'dev.jucm:config-toolbox:1.0'
```

The application does not know picocli or casciian — it knows the model and the toolbox. The libraries
come from `mavenLocal` (as in `native-app`), because the example is meant to show the use of an
**external artifact**.

## Where to look for tests

In `integration-tests/` — that is where the applications have packages and classes, because a class
from a package can be imported by a test, while a default class **cannot**. The integration tests
therefore stay with the packaged form (easier to test), and this module shows the minimal form.
