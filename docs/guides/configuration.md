# Configuring an application

This guide is the *how*: declare a schema in code, attach the sources in order, run. The *why* — the
model, the layers and the guarantees — is in the root [`README.md`](../../README.md); the sections
referenced below are its sections. Every snippet here is copied from working code
(`examples/minimal-app`, `integration-tests/native-app`, `config-toolbox`), and every console block is
real output of the `minimal-app` example or of the `uri-app` integration test.

## 1. The application is a declaration plus one lambda

The smallest application on JUCM is one file, and it is the whole application:

```java
import dev.jucm.core.ConfigDefinition;
import dev.jucm.core.ConfigField;
import dev.jucm.core.ConfigInt;
import dev.jucm.core.ConfigString;
import dev.jucm.core.ConfigType;
import dev.jucm.toolbox.ConfigTool;
import module java.base;

void main(String[] args) {
    var app = ConfigTool.of(ConfigDefinition.of(
            ConfigField.required("server.host", ConfigType.STR, ConfigString.of("localhost"))
                .described("Service host."),
            ConfigField.required("server.port", ConfigType.INT, ConfigInt.of(8080))
                .described("Service port.")))
        .program("minimal-app")
        .sources(Path.of("."))
        .env("MINIMAL")
        .help((generated, help) -> {
            help.print(generated);
            help.println();
            help.println("MINIMAL-APP — a JUCM example");
            help.println("  minimal-app        prints host:port (ENV, file, default values)");
            help.println("  minimal-app tui    configurator window (built into the toolbox)");
        })
        .onRun(config -> IO.println("start: " + ConfigTool.display(config, "server.host")
            + ":" + ConfigTool.display(config, "server.port")));

    System.exit(app.run(args));
}
```

Source: `examples/minimal-app/src/main/java/MinimalApp.java` (the whole file; the comment on
`-help` and the working-directory note are in the module's `README.md`).

`ConfigTool` is the facade: it reads the file and its overlay, then ENV, then the command line, builds
the chain, judges it, prints the `layer.*` origin report and returns one exit code. An application that
wants another order composes the chain itself with `StandardPipeline` and does not use the toolbox
(the class comment of `ConfigTool` says exactly that).

## 2. The schema lives in code

A field is declared once, in Java, and that declaration is the single source of truth behind `-help`,
the TUI, `config-validation` and `config-generate`:

```java
ConfigDefinition definition = ConfigDefinition.of(
    // required + a default value: the default answers when no layer states anything
    ConfigField.required("server.host", ConfigType.STR, ConfigString.of("localhost"))
        .described("Service host."),
    // aliases belong to the declaration, not to a separate registry; a collision is a model error
    ConfigField.required("server.port", ConfigType.INT, ConfigInt.of(8080))
        .described("Service port.").aliased("-p"),
    // required WITHOUT a default: no value anywhere is ABSENT — an error
    ConfigField.required("api.key", ConfigType.STR).described("API key — no default value."),
    // optional: ABSENT is a valid state
    ConfigField.optional("debug", ConfigType.BOOL).described("Verbose logging, absent by default."),
    // a rule is a flat chain, evaluated strictly left to right
    ConfigField.required("transport_protocol", ConfigType.STR, ConfigString.of("https"))
        .described("Transport protocol.").constrained(ConfigConstraint.Const("in[http,https]")));
```

Source: `integration-tests/consumer-app/.../ConsumerApp.java` (`applicationModel()`), plus the
optional field from the same model and the rule pattern from `integration-tests/native-app`
(`.constrained(ConfigConstraint.Const("in[8080,443]"))`).

Two further pieces of the declaration, each taken from real code:

```java
// WHAT vs HOW: the transformer name comes from the declaration, its configuration is opaque to the model.
// (This is the shape SourceDeclaration.asField(...) builds for a type stated by a source marker.)
ConfigField.required("started_at", ConfigType.TIMESTAMP, ConfigTimestamp.of(Instant.EPOCH))
    .transformer("date").transformerConfig("yyyy-MM-dd HH:mm:ss");

// a default value that is a template: interpolation belongs to the READ, not to the declaration
ConfigField.required("uri", ConfigType.STR, ConfigString.of("http://${host}:${port}/api"))
    .described("Base address computed from host and port.");
```

The second one is `native-app`'s `uri`. Types carry their own notation (`int`/`float` → `number`,
`timestamp` → `date`, `duration`, `uri`), so a field usually needs no `transformer` at all — see
`config-core/.../ConfigType.defaultTransformer()`.

## 3. Attach the sources, in order

```java
ConfigTool.of(model())
    .program("uri-app")                    // the name in -help, in the printed command line, in the window title
    .sources(Path.of("."))                 // the directory the files are read from AND written to
    .env("URIAPP")                         // URIAPP__PORT=443; without this call ENV takes no part
    .actions("export")                     // application actions (bare words), next to the built-in `tui`
    .onRun(config -> business(config))     // called only when the configuration has no errors
    .onAction((config, action) -> handle(action, config))
    .run(args);
```

Source: `integration-tests/native-app/.../NativeApp.java` (`run(...)`) and the class comment of
`ConfigTool`. The resulting order is the standard one, and it is a contract:

```text
COMPILED (materialised from the declarations)
   └─► application.yaml          the file
        └─► application.applied.yaml   the overlay, read AFTER the file, so it wins
             └─► ENV          the variables carrying the prefix
                  └─► CLI     `--field value`
                       └─► TUI  edits made in the configurator window
```

- `file("config.yml")` changes the name — and the overlay follows it: `config.applied.yml`
  (`ConfigTool.overlay(...)`).
- `env(prefix, environment)` is the variant for tests: `System.getenv()` cannot be injected, so the
  map is given directly.
- A missing file is **not** an error: the values come from the declarations and the exit code is `0`.

## 4. The file, and the markers

`application.yaml` in the working directory — the file `minimal-app` reads:

```yaml
# What the app reads, from the WORKING DIRECTORY of the process.
server:
  host: from-file.example
  port: 9000
```

Real run (`minimal-app` with the file above in its working directory):

```text
$ java -cp … MinimalApp
start: from-file.example:9000
layer.server.host=YAML
layer.server.port=YAML
```

`layer.*` is diagnostics, not decoration: it says **which layer supplied the value**. The same setting
arrives from three media, and each medium spells a statement its own way — in YAML a **marker in a
comment on the same line as the value**, in ENV a suffix of the variable's name, on the command line an
option of its own. One syntax, one meaning:

```text
YAML     port: 8080            # CONST(in[8080,443])        (a comment on the SAME LINE, starting with the section)
ENV      URIAPP__PORT___CONST='in[8080,443]'
CLI      --port 8080 --port--const 'in[8080,443]'
```

Verified, all three, against `uri-app` — the rule from a source **replaces** the rule from the code:

```text
$ java -cp … dev.jucm.nativeapp.NativeApp                       # file: port: 8080   # CONST(in[443])
error=validation-failed: does not satisfy the rule: in[443] [path `port`] [value `8080`] [source YAML:application.yaml:2]
$ echo $?
23

$ URIAPP__PORT=8080 URIAPP__PORT___CONST='in[443]' java -cp … dev.jucm.nativeapp.NativeApp
error=validation-failed: does not satisfy the rule: in[443] [path `port`] [value `8080`] [source ENV:URIAPP__PORT]
$ echo $?
33

$ java -cp … dev.jucm.nativeapp.NativeApp --port 8080 --port--const 'in[443]'
error=validation-failed: does not satisfy the rule: in[443] [path `port`] [value `8080`] [source CLI:--port]
$ echo $?
43
```

Notation travels the same way (`# TCONF(…)`, `___TCONF`, `--x--tconf`). A nonsense notation is a loud
model error, not a silent degradation:

```text
$ # file: port: 8080   # TCONF(not-a-locale)
error=transform-failed: transformer `number` (config `not-a-locale`) did not let the value through:
the `number` configuration is a locale of the form `language_REGION` … [path `port`] [value `8080`] [source YAML:application.yaml:2]
$ echo $?
20
```

Two rules about markers worth keeping in mind (`README.md` section 3.6, and the `Markers` comment in
`config-yaml/.../YamlSource.java`): a section counts only when the comment **starts** with it, and a
marker anywhere else (its own line, a list element, a block collection) is **rejected** rather than
silently ignored.

## 5. What the user gets

**`-help`, generated from the model** — descriptions, types, aliases and the actions, plus whatever the
application adds. Two facts in it come from the facade rather than the model: the **program name**
(`program("uri-app")` — without it picocli prints its `<main class>` placeholder) and the **environment
prefix** (`env("URIAPP")` gives `URIAPP__HOST`; with no `env(...)` the line says plainly that ENV is not
read, instead of showing a prefix nobody reads). The engine's options take ONE dash, the model's fields
TWO, so `-help` is the help while a field named `help` would keep `--help`:

```text
$ java -cp … dev.jucm.nativeapp.NativeApp -help
Usage: uri-app [-help] [--host=<str>] [--port=<int>]
               [-strict-config-parser=<true|false>] [--uri=<str>] [COMMAND]
      -help          prints this help and exits (does not run the application)
      --host=<str>   Service host.
      --port=<int>   Service port.
      -strict-config-parser=<true|false>
                     Lenient mode of the configuration parser: warnings instead
                       of an error.
      --uri=<str>    Base address computed from host and port.
Commands:
  config-validation              checks the configuration and prints a report —
                                   without running the application
  config-generate                prints a sample YAML file matching the model
                                   (to stdout)
  config-validation-with-layers  checks the configuration and prints the state
                                   AFTER EACH layer: what the layer brings in
                                   and what comes out of it
  tui                            opens the configurator window (TUI)
Environment variables: prefix `URIAPP` — the field `host` is `URIAPP__HOST` (a
double underscore separates the segments).

URI-APP
  uri-app                     prints the configuration and the origin (layer.*)
  uri-app tui                 configurator window (saves to application.applied.yaml)
  uri-app config-validation   checks the configuration without running the application
  uri-app config-generate     a sample YAML file from the model
```

**`config-generate`** — a sample file built from the model alone, byte-for-byte repeatable, values
taken from the rule before the default, secrets never looking like secrets:

```yaml
# Generated by `config-generate` (JUCM) — a starting point that matches the model.
# Values: first from the `in[…]` rule, then from the default value, and finally an example.
api_key: "<provide-api_key>"  # API key.
host: "localhost"         # Host name.
mode: "dev"               # CONST(in[dev,test]) Operating mode.
port: 8080                # CONST(in[8080,443]) Service port.
server:
  tls: false              # TLS on the server.
```

**`config-validation`** — the verdict without running the application; all problems at once, each with
the path, the offending value, the source and what was expected:

```text
$ java -cp … dev.jucm.nativeapp.NativeApp config-validation      # file: port: 9999
Invalid configuration

path:              port
error:             ValidationFailed
message:           does not satisfy the rule: in[8080,443]
expected:          in[8080,443]
actual:            9999
source:            YAML:application.yaml:2
previous:          COMPILED:declaration port -> 8080
compiled fallback: 8080
$ echo $?
23
```

**`config-validation-with-layers`** — what each layer contributed and what came out of it, so the
question "at which layer did this change" has an answer:

```text
  port
    COMPILED:declaration               contributes: 8080             resolved: 8080
    YAML:application.yaml:2            contributes: 8080             resolved: 8080
    ENV:URIAPP__PORT                   contributes: 443              resolved: 443
```

**`tui`** — the configurator window. It is built into the toolbox: the application writes not one line
for it.

## 6. Exit codes

One code for the process: `X` is the layer's **position** in the pipeline the application built, `Y`
the category of the cause (`0` input, `1` conversion, `2` resolution, `3` validation, `4` missing,
`5` path/model, `7` I/O). The lowest code wins, and `0` means exactly "zero problems". Codes `1..9`
belong to the application, never to the library.

```text
23   the rule from the file        (YAML = position 2, validation = 3)
33   the rule from ENV             (ENV  = position 3)
43   the rule from the command line (CLI = position 4)
31   a value ENV could not cast
20   a notation the transformer refused, in the file
125  a command-line usage error — not a configuration error at all
```

A typo'd **option** is not in that list as long as it carries a value: `--prot 9000` becomes an auxiliary
key, the run continues and the exit code is `0` (README section 10, point 11). A **bare** option token is
the other case — `--prot` with nothing after it sets nothing, so it is a usage error (`125`) like any
option left without its parameter, whatever the name.

## 7. Where the files are read from

`sources(Path)` is the **working directory of the process**, not the directory you typed the command
in. Under `./gradlew run` that is the module directory, so `application.yaml` has to be put there — the
same trap is described in `examples/minimal-app/README.md` and
`integration-tests/native-app/README.md`.

## Where to go next

| you want | go to |
|---|---|
| the model behind all of this | [`README.md`](../../README.md) |
| the smallest possible application, file by file | `examples/minimal-app/README.md` |
| a running application with real exit codes | `integration-tests/native-app/README.md` |
| your own source, your own transformer | [`custom-source.md`](custom-source.md) |
| testing a configuration | [`testing.md`](testing.md) |
