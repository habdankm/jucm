# Release 2 — features that must be solved

Everything below is **evidence, not wishes**. It comes from the first real consumer of JUCM:
`/home/mecat/Documents/proj/chunked` (a command-line tool that marks text chunks with
content-addressed anchors, built as a native image, JUCM 1.0 from mavenLocal).

The point of this document is simple: using JUCM in a CLI application currently costs ~20 lines of
library ceremony before a single line of the application's own logic. That is the library's job, not
the application's, and the application has no way to avoid it today.

---

## The cost, verbatim

This is `Main.run()` from `chunked/src/main/java/dev/chunked/Main.java`, lines 259–290, unchanged.
It wires the layers and reads the configuration. Nothing here is the tool's logic.

```java
    static int run(String[] args) throws IOException, NoSuchAlgorithmException {
        ConfigDefinition definition = definition();
        CommandLine.Model.CommandSpec spec = CliSpec.of(definition);
        // The tool modes are ENGINE options (single dash, like JUCM's `-help`), so we add them to
        // the specification built from the model; the model's own values stay double-dash options.
        spec.addOption(CommandLine.Model.OptionSpec.builder(ADD_CHUNKIDS).arity("1").build());
        spec.addOption(CommandLine.Model.OptionSpec.builder(MERGE).arity("2").build());
        CommandLine line = new CommandLine(spec);
        line.setUnmatchedArgumentsAllowed(true);
        try {
            line.parseArgs(args);
        } catch (CommandLine.ParameterException broken) {
            throw new Usage(broken.getMessage());
        }
        CommandLine.ParseResult parsed = line.getParseResult();

        // Layers: declaration (defaults) → ENV → CLI. JUCM's contract: the later layer wins, so the
        // command line is the strongest one.
        ConfigResult<ConfigPatch> environmentLayer =
            EnvSource.of(System.getenv(), ENV_PREFIX, YamlSource::value).patch(definition);
        if (environmentLayer instanceof ConfigResult.ErrorResult<ConfigPatch> broken) {
            throw new Refusal("the ENV layer cannot be read: " + broken.error().report());
        }
        ConfigPatch environment = ((ConfigResult.ValueResult<ConfigPatch>) environmentLayer).value();

        ConfigResult<ConfigPatch> commandLineLayer = CliSource.read(parsed, YamlSource::value);
        if (commandLineLayer instanceof ConfigResult.ErrorResult<ConfigPatch> broken) {
            throw new Usage("the command line cannot be read: " + broken.error().message());
        }
        ConfigPatch commandLine = ((ConfigResult.ValueResult<ConfigPatch>) commandLineLayer).value();
        Settings settings = settings(StandardPipeline.of(definition, TransformerRegistry.defaults(),
            environment, commandLine));
```

And this is how a resolved value is read today (`Main.java:245–255`, the tail of the only reader;
the typed readers above it delegate here):

```java
    static ConfigValue at(Config config, String path) {
        ConfigResult<ConfigValue> found = config.get(ConfigPath.of(path));
        if (found instanceof ConfigResult.ValueResult<ConfigValue> ok) {
            return ok.value();
        }
        throw new Refusal("missing value for `" + path + "`: "
            + ((ConfigResult.ErrorResult<ConfigValue>) found).error().message());
    }
```

Around it sit four one-line casts — `(ConfigInt) value()`, `(ConfigString) value()`,
`(ConfigBool) value()` — and a loop that walks `ConfigSeq.elements()` to rebuild a `List<String>`.

---

## 1. One call that composes the layers

**Problem.** Every layer costs the same six lines: build the source, look at `ConfigResult`,
unwrap the patch through an unchecked cast. Two layers, two blocks; a third layer (a YAML file)
would be a third block. The order of layers is a JUCM contract (declaration → ENV → CLI, later
wins), so the application should not be re-stating it either.

**Today:** the 12 lines above, twice, growing linearly with layers.
**Should be:**

```java
        ConfigResult<Config> resolved = ConfigLayers.of(definition)
            .environment(ENV_PREFIX)          // reads System.getenv(), injected reader
            .commandLine(args)                // parses and builds the CLI patch
            .resolve();                       // one result; one place to handle the failure
```

**Gain:** 12 lines → 2, and the failure of any layer is handled once instead of once per layer.

---

## 2. Typed readers on the resolved configuration

**Problem.** JUCM *knows* the declared type — it validates it and hands back a value that already
carries it — and then asks the application to know it a second time and cast. `ConfigValue` is the
right return type for the pipeline; it is the wrong one for a consumer that declared `int` and
`str` and `seq<str>`.

**Today:** the `at()` helper above, plus four casts and a `ConfigSeq.elements()` loop.
**Should be:**

```java
        int mlc           = config.intAt("mlc");
        String magic      = config.textAt("magic");
        boolean backup    = config.boolAt("backup");
        List<String> cascade = config.stringsAt("cascade");
```

with a single failure type (`ConfigError`) instead of a per-call `ConfigResult` dance, or a
`ConfigResult`-returning variant if the project wants to keep the no-exceptions contract.

**Gain:** ~15 lines → 4, and no unchecked casts at the consumer.

---

## 3. Engine options without touching picocli

**Problem.** A tool has its own modes (`-add-chunkids FILE`, `-merge FILE RESPONSE`) next to the
model's options. JUCM builds the specification from the model, so the application has to reach into
picocli's `CommandSpec`, construct `OptionSpec.Builder`, know about `arity("1")` being a `String`,
and construct the `CommandLine` itself. That is JUCM's adapter leaking through its own abstraction.

**Today:** 6 lines of picocli, including one API detail that is easy to get wrong.
**Should be:**

```java
        CommandLine line = CliSpec.of(definition)
            .engineOption("-add-chunkids", 1)     // single dash: the engine's namespace
            .engineOption("-merge", 2)
            .toCommandLine();
```

**Gain:** 6 lines → 3, and the engine/namespace rule (single-dash engine options, double-dash model
options) is enforced by the library instead of by the application's memory.

---

## 4. Smaller item: `char` in the configuration types

From the same consumer: the field separator is one character (`0x1F`). The configuration carries a
`str` (correct — every carrier speaks strings), and the application casts it to `char` in one place,
with a length validation. That is acceptable once. If a second consumer needs the same, add a
`char` type to JUCM's types rather than let each application hand-roll the validation.

---

## Acceptance for release 2

The same `run()` — layers, typed reads, engine options — should fit in about eight lines, with all
failure handling in one place and no unchecked casts at the consumer. `chunked` is the acceptance
test: it is small, it is real, it exercises ENV, CLI, typed collections and a native image.

## Status of earlier items

- Collection element types (`ConfigField.seq(path, ConfigType.STR, …)`) — **done in 1.0**; used by
  `chunked` for the separator cascade, works in a native image.
- Version 1.0 remains published to mavenLocal; release 2 starts from the evidence above, not from a
  wish list.


---

## Found while building the first consumer (`chunked`)

Everything below was hit in real code, not imagined. The consumer is a command-line tool built on this library,
with picocli as its engine, Gradle 9.7, Java 25, a native image, and no Guava.

### 1. A tool's own option with a value breaks the whole command line

Verbatim from the first end-to-end run:

    $ chunked -merge FILE RESPONSE --expect 4266-bca5dd2d
    USAGE: the command line cannot be read: the value is not a readable flow literal:
      [Ljava.lang.String;@7e137f56 ... expected ',' or ']', but got <stream end>

`-merge` takes two arguments, so picocli hands the application a `String[]`. The model does not know that option —
correctly, because it belongs to the engine, not to the configuration — and `CliSource` treats it as an unmatched
token whose value it then tries to read as a structure literal. An array is not a literal, so the entire command
line is rejected with a message about YAML flow sequences, which describes nothing that actually went wrong.

What the consumer had to do: strip its own options out of the argument array before handing anything to JUCM and
parse them by hand. That is exactly the picocli hydraulics item 3 above asks to remove.

**Acceptance.** A tool declares options of its own and JUCM either ignores them or reports them as unknown
**without** interpreting their values. A multi-valued engine option must not be able to break configuration
resolution.

### 2. The adapters are not transitive from the toolbox

`dev.jucm:config-toolbox` declares `config-yaml`, `config-env` and `config-picocli` as `implementation`, so a
consumer that wants `EnvSource` or `CliSource` directly must declare those artifacts itself, at the same version, or
compile against "package dev.jucm.env does not exist". The facade is the advertised entry point, so the adapters it
is built from should be reachable from it (`api`) — or the toolbox should be documented as not being a facade.

### 3. Reading a value back means knowing its type a second time

Item 2 above, now with measurements: four readers (int, text, bool, strings) plus a helper unwrapping
`ConfigResult` came to about fifteen lines, all of it re-deriving types the declaration already states. The
consumer's own summary: JUCM already knows `mlc` is an int and `magic` is a string, and then hands back a
`ConfigValue` so the application can know it again.

### 4. `ConfigResult` does not switch

`switch (result) { case ConfigResult.ValueResult<ConfigPatch> ok -> …; case ConfigResult.ErrorResult<…> bad -> …; }`
is rejected by javac with *duplicate unconditional pattern*, while the same two branches written as `instanceof`
compile fine. It is why JUCM's own sources use `instanceof`. Either document that idiom or give the result a single
unwrapping method, so consumers stop inventing one.

### 5. Native image drops `version.properties` unless told otherwise

A consumer built to a native image reports its version incorrectly unless the build adds
`-H:IncludeResources=.*\.properties$`. Without it nothing fails — the binary simply reports a wrong version,
which is the kind of wrongness that reaches users. Either ship the resource configuration with the artifacts, or
say it loudly in the documentation.

**Acceptance for the whole section.** A new consumer project can be started from a README, declare options of its
own, read typed values, and build a native image that reports its version — with no workarounds copied out of
JUCM's integration tests.

### 6. Field names reach the command line verbatim

A model field named `inputFile` becomes `--inputFile`; the command-line convention is `--input-file`. The consumer
wanted the latter, found the former, and would have to rename the field to something it would not otherwise choose.
Either render camelCase paths as kebab-case options, or let the declaration carry an explicit command-line name.

**Acceptance.** `ConfigField.required("inputFile", …)` can be given as `--input-file` without renaming the field.
