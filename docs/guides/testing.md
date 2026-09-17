# Testing a configuration

This guide is the *how* for tests. A configuration is testable at every seam the model exposes, and none
of those seams needs a process, a terminal or a real service: the model is immutable and the verdict is
repeatable (`README.md` section 7). Every snippet below is copied from working code, and the numbers
quoted are the real result of that set: **19 tests, 5 containers, 0 failures, 439 ms**.

## 1. Five questions a test can ask

| the question | the seam | the pattern |
|---|---|---|
| does my adapter deliver the entries I mean? | `patch(definition)` in, a `ConfigPatch` out | section 3 |
| do the file, ENV and the command line behave in order? | the whole toolbox in a temporary directory | section 2 |
| which code does this configuration give? | `ConfigValidator.build(...)` + `ConfigExitCode.lowestOf(...)` | sections 3 and 5 |
| does my transformer read this notation? | `normalize(value, config)` — a value in, an outcome out | section 8 |
| does the window recompute a field and save the change? | `HeadlessBackend` + `TuiDriver` | section 6 |

## 2. A temporary directory and the whole facade, in-process

`@TempDir` plus one call runs the toolbox — file, overlay, ENV and the command line — inside the test
JVM, with both output streams captured and the configuration the business received kept for inspection.
There is no file in the repository, no process to start, and the working directory is whatever the test
was given:

```java
/** One run: the code, both streams and the configuration the business received. */
private record Ran(int code, String out, String err, Config config) {
}

private static Ran run(ConfigTool tool, String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    List<Config> seen = new ArrayList<>();
    int code = tool.onRun(seen::add)
        .run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    return new Ran(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8),
        seen.isEmpty() ? null : seen.get(0));
}

@Test
void fileThenOverlay(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("application.yaml"), "host: example.org\nport: 8080\n");
    Files.writeString(dir.resolve("application.applied.yaml"), "port: 443\n");

    Ran ran = run(ConfigTool.of(model()).sources(dir));

    assertThat(ran.code()).isZero();
    assertThat(ConfigTool.display(ran.config(), "port")).isEqualTo("443");
    assertThat(ConfigTool.display(ran.config(), "uri"))     // computed, never written down
        .isEqualTo("http://example.org:443/api");
    assertThat(ran.out()).contains("layer.host=YAML").contains("layer.port=YAML");
}
```

`ran.out()` is the `layer.*` origin report, so an assertion on it is an assertion about **which layer
answered** — the shortest way to pin precedence. ENV comes after the file and the command line after ENV
in the same style: `.env("APP", Map.of("APP__PORT", "443"))` instead of a process environment, because
`System.getenv()` cannot be injected, and the extra `"--port", "8080"` arguments afterwards.

## 3. The verdict, without a run of anything

A patch built in the test and a report over it — no file, no process, no toolbox:

```java
private static ConfigPatch layerOf(Map<String, String> values) {
    ConfigResult<ConfigPatch> read = RemoteStoreSource.of(values, "store.json").patch(DEFINITION);

    assertThat(read).as("the source could not be read: %s", read)
        .isInstanceOf(ConfigResult.ValueResult.class);
    return ((ConfigResult.ValueResult<ConfigPatch>) read).value();
}

@Test
void theAdapterDoesNotCast() {
    ConfigPatch layer = layerOf(Map.of("server.port", "later"));

    assertThat(layer.operation(ConfigPath.of("server.port")))
        .as("the value went in as text, exactly as the store gave it")
        .isEqualTo(Optional.of(new ConfigPatch.SetValue(
            ConfigString.of("later"), ConfigSource.of(Layer.CUSTOM, "store.json:server.port"))));

    ConfigValidator.Report report = ConfigValidator.build(DEFINITION,
        StandardPipeline.of(DEFINITION, TransformerRegistry.defaults(), layer));

    assertThat(report.errors()).extracting(error -> error.kind().name()).containsExactly("CAST_FAILED");
}
```

That pair of assertions is the whole contract of an adapter in two lines: the patch carries **text** and
the origin, the problem is the field's, not the adapter's. The kinds are a closed set (`README.md`
section 6), so `containsExactly` on `kind().name()` is a stable assertion, not a message match.

## 4. A patch built from the outside

A consumer test does not use the toolbox at all: it builds a patch with the public API and asks the
engine what it makes of it — the way to test *your* values without a file:

```java
ConfigPatch patch = ConfigPatch.empty()
    .with("server.port", ConfigInt.of(8080))        // the canonical path as a String is a convenience
    .with("server.tls", ConfigBool.of(true));

assertThat(patch.operation(ConfigPath.of("server.port")))
    .contains(new ConfigPatch.SetValue(ConfigInt.of(8080)));
```

The same pattern answers "does the source change the verdict?" — one identical value arriving from three
origins has to give **one** code and **one** set of kinds:

```java
var codes = List.of(fromFile, fromEnv, fromCli).stream()
    .map(config -> ConsumerApp.buildReport(definition, config))
    .map(ConsumerApp::exitCode)
    .distinct()
    .toList();
assertThat(codes).as("three sources, one verdict").hasSize(1);
```

## 5. An exit code is an assertion

The code is a number with a reason, so a test asserts it together with the message that names the rule,
the value and the source — and with the fact that the business was **not** called:

```java
Ran broken = run(ConfigTool.of(model()).sources(dir));            // file: port: 9999

assertThat(broken.code()).as("23 = the YAML decade (2) + validation (3)").isEqualTo(23);
assertThat(broken.err()).startsWith("error=").contains("in[8080,443]").contains("9999")
    .contains("YAML:application.yaml:");
assertThat(broken.config()).as("the business is not called on a broken configuration").isNull();
```

Two neighbours worth pinning in the same class, because they are the mistakes users make: a **usage**
error (`--port` without its argument) is `125` — and so is a **bare** token, whether the option is
declared (`--port`) or a typo (`--prot`), because an option left without its parameter is a usage error
for every name. An option **with** a value is the other case: a typo'd **option** (`--prot 9000`)
becomes an auxiliary key and exits `0` with an empty error stream (`README.md` section 10, items 10–11).
All of them are asserted against a directory that does not exist — a missing file is a legitimate
fixture, because the values then come from the declaration and the run is a real `0`.

## 6. The configurator window, without a terminal

`HeadlessBackend` supplies a screen and `TuiDriver` supplies input, so a window test needs no terminal
and prints nothing. The driver owns the application thread it started and joins it in `close()`, so the
window is opened inside a record that closes with the test — a failing assertion leaves no thread behind:

```java
private record Open(HeadlessBackend backend, TuiApplication application, TuiDriver ui,
                    Configurator view, Configurator.Targets targets) implements AutoCloseable {

    static Open of() {
        HeadlessBackend backend = new HeadlessBackend();
        backend.setBackend(backend);        // as in casciian's own tests: the backend points at itself
        TuiApplication application = new TuiApplication(backend);
        Configurator.Targets targets = Configurator.Targets.in(Path.of("build"));
        Configurator view = Configurator.open(application, model(), draft, targets, "uri-app", extension -> { });
        return new Open(backend, application, TuiDriver.start(application, backend), view, targets);
    }

    @Override
    public void close() {
        ui.close();
    }
}
```

Three properties are worth asserting, all without a screen:

```java
// a field edit RECOMPUTES a dependent field
open.ui().fill(open.view().field(ConfigPath.of("port")), "7000");
open.view().edit(ConfigPath.of("port"), "7000");
assertThat(open.view().field(ConfigPath.of("uri")).getText()).isEqualTo("http://example.org:7000/api");

// `Save` writes the CHANGES only: a resolved `uri` in the file would freeze the interpolation
open.ui().click(open.ui().findButton("Save"));
assertThat(Files.readString(open.targets().applied()))
    .contains("port: \"7000\"").doesNotContain("uri:");

// ESC ends the loop, so a test never has to kill a process
open.ui().sendKey(Tkeypress.kbEsc);
open.ui().await(() -> !open.ui().isRunning(), "the TUI loop", "finished (ESC)");
```

## 7. The writer has to be the one the button uses

A test that hand-writes an overlay proves nothing about a session, so it writes it with the very method
`Save` calls — `DraftWriter.write(patch, file)`. Then the application reads the overlay back and enforces
the rule it carries: the model's rule says `in[8080,443]`, the session's rule says `in[8080]`, the file
wins, and the message names the file.

```java
DraftWriter.write(ConfigPatch.set(ConfigPath.of("port"), ConfigString.of("443"),
    ConfigSource.of(Layer.TUI, "port"), SourceDeclaration.constraints("in[8080]")),
    dir.resolve("application.applied.yaml"));

Ran broken = run(ConfigTool.of(model()).sources(dir));

assertThat(broken.code()).isEqualTo(23);
assertThat(broken.err()).as("the message carries the rule FROM THE FILE")
    .contains("in[8080]").contains("application.applied.yaml");
```

## 8. A transformer is testable in isolation

The SPI is shaped for it: a value and a configuration in, an outcome out — no field, no path, no source,
no registry. Because a transformer must be stateless and deterministic, one instance per test class is
enough, and the whole class is plain assertions:

```java
private final Transformer bytes = new BytesTransformer();      // stateless: one for every test

private static ConfigInt normalized(Transformer transformer, String text) {
    Transformer.Outcome outcome = transformer.normalize(ConfigString.of(text), Optional.empty());

    assertThat(outcome).as("`%s` was rejected", text).isInstanceOf(Transformer.Outcome.Normalized.class);
    return (ConfigInt) ((Transformer.Outcome.Normalized) outcome).value();
}

assertThat(normalized(bytes, "10MB")).isEqualTo(ConfigInt.of(10 * 1024 * 1024));
assertThat(normalized(bytes, "2 gb")).isEqualTo(ConfigInt.of(2L * 1024 * 1024 * 1024));
assertThat(rejection(bytes, "soon")).contains("not a size");   // a reason, never an exception
```

Keep one test for the **registration** as well: the name the declaration writes is the name the
application registers, and an unknown name is a loud model error rather than a silent degradation.

```java
TransformerRegistry registry = TransformerRegistry.defaults().register("bytes", BytesTransformer::new);

assertThat(registry.require("bytes")).isInstanceOf(BytesTransformer.class);
```

## 9. Rules of thumb for a set of your own

- **ASCII and no clock.** Everything asserted should be deterministic on any machine: a temporary
  directory, a text block, a map.
- **Nothing reads the user's terminal** and nothing talks to a real service; a headless backend and a
  fake store keep a test of a window or an adapter honest.
- **Close what you open** — the driver behind an `AutoCloseable` record stops the loop thread even when
  an assertion fails.
- **Assert the code and the origin, not the whole message**: the kind is a closed set, the text is not.

## 10. Running such a set outside a build

The JUnit Platform launcher discovers a package in a few lines, which is how the set quoted in the
introduction was run against the compiled classes:

```java
LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
    .selectors(DiscoverySelectors.selectPackage("com.example"))
    .build();
SummaryGeneratingListener listener = new SummaryGeneratingListener();
LauncherFactory.create().execute(request, listener);

TestExecutionSummary summary = listener.getSummary();
summary.printTo(out);
summary.printFailuresTo(out);
System.exit(summary.getTotalFailureCount() == 0 ? 0 : 1);
```

## Where to go next

| you want | go to |
|---|---|
| the invariants a test may rely on | [`README.md`](../../README.md) section 7, and section 6 for the codes |
| how the sources are attached and what the user sees | [`configuration.md`](configuration.md) |
| an adapter of your own, and a transformer of your own | [`custom-source.md`](custom-source.md) |
| the shipped pattern for the file layers | `integration-tests/native-app/src/test/java/dev/jucm/nativeapp/NativeAppTest.java` |
| the shipped pattern for a patch built from the outside | `integration-tests/consumer-app/src/test/java/dev/jucm/consumer/ConsumerSmokeTest.java` |
| the driver used to test the window | `config-casciian/src/main/java/dev/jucm/casciian/TuiDriver.java` |
