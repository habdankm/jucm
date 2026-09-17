package dev.jucm.consumer;

import dev.jucm.core.Config;
import dev.jucm.core.ConfigBool;
import dev.jucm.core.ConfigConstraint;
import dev.jucm.core.ConfigDefinition;
import dev.jucm.core.ConfigError;
import dev.jucm.core.ConfigExitCode;
import dev.jucm.core.ConfigField;
import dev.jucm.core.ConfigInt;
import dev.jucm.core.ConfigMap;
import dev.jucm.core.ConfigPatch;
import dev.jucm.core.ConfigResult;
import dev.jucm.core.ConfigPath;
import dev.jucm.core.ConfigSeq;
import dev.jucm.core.ConfigSource;
import dev.jucm.core.ConfigStore;
import dev.jucm.core.ConfigString;
import dev.jucm.core.ConfigTimestamp;
import dev.jucm.core.ConfigType;
import dev.jucm.core.ConfigValidator;
import dev.jucm.core.ConfigValue;
import dev.jucm.core.Layer;
import dev.jucm.core.Version;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A consumer application — an **example of use**, not a test. It walks the whole JUCM structure block
 * by block, in the order a real application does:
 *
 * <ol>
 *   <li><b>Application model</b> — {@link ConfigDefinition} + {@link ConfigField}: type, requiredness,
 *       default values, aliases, descriptions and value rules. One source of metadata.</li>
 *   <li><b>Layers</b> — a {@link ConfigPatch} per layer, every value with its own
 *       {@link ConfigSource} (where it came from: a file, a variable, an argument).</li>
 *   <li><b>Validation at the source</b> — {@link ConfigValidator#validate} turns raw text from
 *       ENV/CLI into typed values, keeping the origin and collecting <em>all</em> problems at once.</li>
 *   <li><b>Tree</b> — {@link Config#root} materialises the root layer from the declaration and
 *       {@link Config#apply} adds layers; {@link Config#entry} and {@link Config#history} show
 *       <em>where</em> a value came from.</li>
 *   <li><b>Report and exit code</b> — {@link ConfigValidator#build} settles requiredness and
 *       {@link ConfigExitCode} turns the problems into one process code.</li>
 *   <li><b>Current configuration</b> — {@link ConfigStore} holds the newest snapshot and replaces it
 *       atomically.</li>
 * </ol>
 *
 * <p>It deliberately imports nothing from {@code io.vavr}: a consumer that had to know Vavr would mean
 * the public API leaks an implementation detail. The missing import is part of the test.
 */
public final class ConsumerApp {

    private ConsumerApp() {
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // 1. Application model: what this application has in its configuration
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Field declarations. Types are declared **in code**, not in data — that is why the application
     * runs without any configuration file, while `-help`, the TUI and the generator read the
     * metadata from one place.
     *
     * @return the model declared by this class
     */
    public static ConfigDefinition applicationModel() {
        return ConfigDefinition.of(
            ConfigField.required("server.port", ConfigType.INT, ConfigInt.of(8080))
                .described("Port the server listens on.").aliased("-p"),
            ConfigField.required("server.host", ConfigType.STR, ConfigString.of("localhost"))
                .described("Address we bind to."),
            ConfigField.required("server.tls", ConfigType.BOOL, ConfigBool.of(false))
                .described("Whether traffic is served over TLS."),
            ConfigField.seq("server.features", ConfigType.STR,
                    ConfigSeq.of(List.of(ConfigString.of("metrics"))))
                .described("Enabled modules, in load order."),
            ConfigField.required("started_at", ConfigType.TIMESTAMP, ConfigTimestamp.of(Instant.EPOCH))
                .described("Start time; default format " + ConfigTimestamp.DEFAULT_FORMAT + "."),
            ConfigField.required("transport_protocol", ConfigType.STR, ConfigString.of("https"))
                .described("Transport protocol.").constrained(ConfigConstraint.Const("in[http,https]")),
            ConfigField.required("api.key", ConfigType.STR).described("API key — no default value."),
            ConfigField.optional("debug", ConfigType.BOOL).described("Verbose logging, absent by default."));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // 2. Patch — values with their origin kept
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * A patch without an explicit origin — the simplest entry, for when the source is application code
     * (or the example does not care about diagnostics).
     *
     * @return the patch in its simplest form
     */
    public static ConfigPatch examplePatch() {
        return ConfigPatch.empty()
            .with("server.port", ConfigInt.of(8080))
            .with("server.host", ConfigString.of("localhost"))
            .with("server.tls", ConfigBool.of(true))
            .with("server.features", ConfigSeq.of(List.of(
                ConfigString.of("metrics"), ConfigString.of("tracing"))));
    }

    /**
     * The YAML layer — as a file adapter would deliver it: value plus address in the file.
     *
     * @return the patch a file adapter would produce
     */
    public static ConfigPatch yamlLayer() {
        return ConfigPatch.empty()
            .with("server.port", ConfigInt.of(8080), ConfigSource.of(Layer.YAML, "application.yaml:12"))
            .with("server.features", ConfigSeq.of(List.of(ConfigString.of("metrics"))),
                ConfigSource.of(Layer.YAML, "application.yaml:15"))
            .with("transport_protocol", ConfigString.of("https"),
                ConfigSource.of(Layer.YAML, "application.yaml:18"));
    }

    /**
     * The ENV layer — **always text**, because that is what the environment holds. Casting onto the
     * declared type is the validator's job, not the adapter's.
     *
     * @return the patch the ENV adapter would produce
     */
    public static ConfigPatch envLayer() {
        return ConfigPatch.empty()
            .with("server.port", ConfigString.of("9001"),
                ConfigSource.of(Layer.ENV, "AIMENAGERIE__SERVER__PORT"))
            .with("started_at", ConfigString.of("2026-01-01T12:00:00Z"),
                ConfigSource.of(Layer.ENV, "AIMENAGERIE__STARTED_AT"))
            .with("api.key", ConfigString.of("[REDACTED]"),
                ConfigSource.of(Layer.ENV, "AIMENAGERIE__API_KEY"));
    }

    /**
     * The CLI layer — the newest one, so it wins; here it overrides one parameter from the command line.
     *
     * @return the patch the CLI adapter would produce
     */
    public static ConfigPatch cliLayer() {
        return ConfigPatch.empty()
            .with("server.tls", ConfigBool.of(true), ConfigSource.of(Layer.CLI, "--server-tls"));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // 3. Validation at the source: raw value → typed, origin kept
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * One pass over the layers. Values go in raw (as the adapter delivered them) and come out typed —
     * and on a failure the path, the raw value and the source are all visible.
     *
     * @param definition the application model to validate against; must not be null
     * @return the report for the layers of this example
     */
    public static ConfigValidator.Report validateLayers(ConfigDefinition definition) {
        return ConfigValidator.validate(definition.schema(), List.of(
            new ConfigValidator.LayerInput(Layer.YAML, yamlLayer()),
            new ConfigValidator.LayerInput(Layer.ENV, envLayer()),
            new ConfigValidator.LayerInput(Layer.CLI, cliLayer())));
    }

    /**
     * The same validator on **deliberately broken** data — it shows two things at once: an empty value
     * is an error without exceptions, and the problems are collected all of them, not the first one.
     *
     * @param definition the application model to validate against; must not be null
     * @return the report for the deliberately broken layers
     */
    public static ConfigValidator.Report validateBrokenLayers(ConfigDefinition definition) {
        ConfigPatch broken = ConfigPatch.empty()
            .with("server.port", ConfigString.of("abba"), ConfigSource.of(Layer.ENV, "AIMENAGERIE__SERVER__PORT"))
            .with("transport_protocol", ConfigString.of("ftp"),
                ConfigSource.of(Layer.ENV, "AIMENAGERIE__TRANSPORT_PROTOCOL"))
            .with("debug", ConfigString.of(""), ConfigSource.of(Layer.ENV, "AIMENAGERIE__DEBUG"))
            .with("default_port", ConfigInt.of(9000), ConfigSource.of(Layer.ENV, "AIMENAGERIE__DEFAULT_PORT"));

        return ConfigValidator.validate(definition.schema(), List.of(
            new ConfigValidator.LayerInput(Layer.ENV, broken)));
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // 4. Tree: the root from the declaration plus the layers, and where the value came from
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The configuration as a chain of immutable snapshots. The root materialises from the declaration
     * (`defaultValue`) and every layer adds only what it brings in itself.
     *
     * @param definition the application model to build from; must not be null
     * @return the chain with every layer of this example applied
     */
    public static Config buildConfig(ConfigDefinition definition) {
        return Config.root(definition)
            .apply(yamlLayer())
            .apply(envLayer())
            .apply(cliLayer());
    }

    /**
     * The origin trace of one value: what each layer contributed, from the newest one down to the root.
     *
     * @param config the chain to walk; must not be null
     * @param canonicalPath the path in canonical notation; must not be null
     * @return one line per layer, newest first
     */
    public static String provenanceOf(Config config, String canonicalPath) {
        ConfigPath path = ConfigPath.of(canonicalPath);
        String steps = config.history(path).stream().map(Config.Step::display)
            .collect(Collectors.joining("  ←  "));
        return canonicalPath + " = " + describeRead(config, path) + "   [" + steps + "]";
    }

    /**
     * A value read as text. `ConfigResult` is **either a value or an error**, so a missing value prints
     * the kind of problem rather than an empty place. `Absent` is not a failure: on an optional field
     * it is a valid state.
     */
    private static String describeRead(Config config, ConfigPath path) {
        return switch (config.get(path)) {
            case ConfigResult.ValueResult<ConfigValue> value -> value.value().display();
            case ConfigResult.ErrorResult<ConfigValue> failure -> "<" + failure.error().kind() + ">";
        };
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // 5. Report of the whole configuration + the process exit code
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The configuration as a whole: what is there, what is missing and whether it may start.
     *
     * @param definition the application model to validate against; must not be null
     * @param config     the chain to validate; must not be null
     * @return the report for the whole configuration
     */
    public static ConfigValidator.Report buildReport(ConfigDefinition definition, Config config) {
        return ConfigValidator.build(definition, config);
    }

    /**
     * One code for the process: the lowest `XY`, and `0` when there are no problems.
     *
     * @param report the report to translate; must not be null
     * @return the exit code of the process
     */
    public static int exitCode(ConfigValidator.Report report) {
        return ConfigExitCode.lowestOf(report.errors(), ConfigExitCode.Pipeline.STANDARD);
    }

    /**
     * The codes of the individual problems — to show why that one won.
     *
     * @param report the report to read; must not be null
     * @return one code per problem, in report order
     */
    public static List<String> problemCodes(ConfigValidator.Report report) {
        return report.errors().stream()
            .map(error -> "  " + error.kind() + " → "
                + ConfigExitCode.of(error, ConfigExitCode.Pipeline.STANDARD)
                + "   [" + error.source().display() + "]")
            .toList();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // 6. Current configuration: atomic replacement of the snapshot
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * A handle to the newest snapshot; a reader holding an old snapshot still sees the old picture.
     *
     * @param initial the snapshot to start from; must not be null
     * @return a handle holding that snapshot
     */
    public static ConfigStore storeWith(Config initial) {
        return ConfigStore.of(initial);
    }

    /**
     * One line of output — stable, because the smoke test compares it.
     *
     * @param patch the patch to describe; must not be null
     * @return one line with the number of entries and their paths
     */
    public static String describe(ConfigPatch patch) {
        String entries = patch.paths().stream()
            .map(path -> path.canonical() + "=" + patch.operation(path)
                .map(operation -> operation instanceof ConfigPatch.SetValue set
                    ? set.value().display()
                    : "reset")
                .orElse("?"))
            .sorted()
            .collect(Collectors.joining(", "));
        return "consumer: JUCM " + Version.get() + " | " + patch.size() + " entries | " + entries;
    }

    /**
     * Runs the whole example: model, layers, validation, provenance and the process code.
     *
     * @param args ignored — the example reads nothing from the command line
     */
    public static void main(String[] args) {
        ConfigDefinition definition = applicationModel();

        System.out.println("== 1. patch without an origin (the simplest entry) ==");
        System.out.println(describe(examplePatch()));

        System.out.println();
        System.out.println("== 2. validation at the source: raw text -> typed values ==");
        System.out.println("  (per layer as it arrived: types and casting; no precedence here — the tree has it)");
        System.out.println(validateLayers(definition).describe().stripTrailing());

        System.out.println();
        System.out.println("== 3. tree: where the value came from (newest layer <- ... <- declaration) ==");
        Config config = buildConfig(definition);
        System.out.println(provenanceOf(config, "server.port"));
        System.out.println(provenanceOf(config, "server.tls"));
        System.out.println(provenanceOf(config, "server.features"));
        System.out.println("  chain depth: " + config.depth()
            + ", own entries of the newest layer: " + config.ownPaths().size());

        System.out.println();
        System.out.println("== 4. report of the whole configuration + exit code ==");
        ConfigValidator.Report report = buildReport(definition, config);
        System.out.println(report.hasErrors()
            ? report.describe().stripTrailing()
            : "  no problems — the application may start");
        System.out.println("  exit code: " + exitCode(report));

        System.out.println();
        System.out.println("== 5. the same on broken data: every problem at once ==");
        ConfigValidator.Report broken = validateBrokenLayers(definition);
        System.out.println(broken.describe().stripTrailing());
        problemCodes(broken).forEach(System.out::println);
        System.out.println("  exit code: " + exitCode(broken) + " (the lowest XY wins)");

        System.out.println();
        System.out.println("== 6. value rules and keys outside the schema are settled by build() ==");
        System.out.println("  (layer validation knows only the TYPES from the schema; rules and auxiliary keys need the declaration)");
        ConfigValidator.Report brokenBuild = buildReport(definition,
            Config.root(definition).apply(ConfigPatch.empty()
                .with("transport_protocol", ConfigString.of("ftp"),
                    ConfigSource.of(Layer.ENV, "AIMENAGERIE__TRANSPORT_PROTOCOL"))
                .with("default_port", ConfigInt.of(9000),
                    ConfigSource.of(Layer.ENV, "AIMENAGERIE__DEFAULT_PORT"))));
        System.out.println(brokenBuild.describe().stripTrailing());
        System.out.println("  exit code: " + exitCode(brokenBuild));

        System.out.println();
        System.out.println("== 7. current configuration: replacing the snapshot ==");
        ConfigStore store = storeWith(config);
        Config heldInHand = store.current();
        store.update(current -> current.with("server.port", ConfigInt.of(9999)));
        System.out.println("  in hand: " + describeRead(heldInHand, ConfigPath.of("server.port"))
            + "   current: " + describeRead(store.current(), ConfigPath.of("server.port")));

        System.out.println();
        System.out.println("== 8. composite values: map and timestamp ==");
        // LinkedHashMap, not Map.of: a map in the model is ordered and `display()` has to show it —
        // the order from `Map.of` would depend on hashes, that is, on nothing.
        Map<String, ConfigValue> retries = new java.util.LinkedHashMap<>();
        retries.put("retries", ConfigInt.of(3));
        retries.put("backoff", ConfigString.of("2s"));
        System.out.println("  " + ConfigMap.of(retries).display());
        System.out.println("  " + ConfigTimestamp.DEFAULT_FORMAT + " → "
            + ConfigTimestamp.parseStrict(ConfigTimestamp.DEFAULT_FORMAT).orElseThrow().display());
    }
}
