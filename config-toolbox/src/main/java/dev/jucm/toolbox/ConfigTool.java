package dev.jucm.toolbox;

import dev.jucm.casciian.Configurator;
import dev.jucm.casciian.TuiApplication;
import dev.jucm.core.Config;
import dev.jucm.core.ConfigConstraint;
import dev.jucm.core.ConfigDefinition;
import dev.jucm.core.ConfigDraft;
import dev.jucm.core.ConfigError;
import dev.jucm.core.ConfigExitCode;
import dev.jucm.core.ConfigField;
import dev.jucm.core.ConfigPatch;
import dev.jucm.core.ConfigPath;
import dev.jucm.core.ConfigResult;
import dev.jucm.core.ConfigValue;
import dev.jucm.core.ConfigValidator;
import dev.jucm.core.Interpolation;
import dev.jucm.core.Layer;
import dev.jucm.core.StandardPipeline;
import dev.jucm.core.TransformerRegistry;
import dev.jucm.env.EnvSource;

import java.util.LinkedHashMap;
import dev.jucm.picocli.CliSource;
import dev.jucm.picocli.CliSpec;
import dev.jucm.yaml.YamlSource;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;

/**
 * An application facade: **declare the model and write one lambda, and everything else comes for free**.
 *
 * <pre>{@code
 * public static void main(String[] args) {
 *     ConfigTool.of(model)                       // the only thing an application writes beyond business logic
 *         .program("uri-app")
 *         .sources(Path.of("."))                 // application.yaml + the overlay
 *         .env("URIAPP")                         // environment variable prefix
 *         .onRun(config -> business(config))
 *         .run(args);
 * }
 * }</pre>
 *
 * <p>What this class takes on — the boilerplate every application would otherwise write for itself:
 *
 * <ol>
 *   <li>file sources in order: the file, then the overlay (`application.yaml`, `application.applied.yaml`);</li>
 *   <li>the ENV adapter with a prefix, with the structure reader injected from the YAML adapter (source
 *       modules do not know one another);</li>
 *   <li>`CliSpec` from the declaration, reading `argv` and recognising the selected action;</li>
 *   <li><b>`-help`</b> — the help generated from the model, which NAMES the program and the environment
 *       prefix (or says plainly that ENV takes no part); the engine's options take one dash, the model's
 *       fields two, so a field named `help` is reachable as `--help`;</li>
 *   <li>the pipeline ({@link StandardPipeline}) and the verdict (`ConfigValidator.build`) — the same as
 *       at startup;</li>
 *   <li>exit codes and error messages on the error stream;</li>
 *   <li><b>the built-in `tui` action</b> — the whole configurator window together with its file outputs;</li>
 *   <li>the origin report (`layer.*`) for declared fields — diagnostics without writing any in the app.</li>
 * </ol>
 *
 * <p>What stays in the application: the <b>model</b> (that is the configuration) and the <b>business</b>
 * — one lambda. Nothing more, deliberately: this is a composition of existing adapters, not a framework.
 *
 * <p>The layer order is the standard one (file, then ENV, then CLI) — an application that wants another
 * order uses {@link StandardPipeline} directly and does not need this toolbox.
 *
 * @since 1.0
 */
public final class ConfigTool {

    /** The built-in action: the configurator window (`./app tui --port 8080`). */
    public static final String TUI = "tui";

    /** Actions reserved by the engine; `CliSpec.ACTIONS` carries the same names. */
    static final String VALIDATION = "config-validation";
    /**
     * The layer-printing action — a SEPARATE ACTION, not a `--layers` option. The reason: the name
     * `--layers` belongs to the model — a field named `layers` has every right to exist in the
     * configuration, so the engine must not take that name. The action name carries hyphens, while the
     * canonical form of a path does not allow them, so a collision is impossible.
     */
    static final String VALIDATION_WITH_LAYERS = "config-validation-with-layers";
    static final String GENERATE = "config-generate";

    private final ConfigDefinition definition;
    private final String program;
    private final Path sources;
    private final String baseFile;
    private final String envPrefix;
    private final Map<String, String> env;
    private final List<String> actions;
    private final boolean reportOrigin;
    private final Consumer<Config> onRun;
    private final BiConsumer<Config, String> onAction;
    /** An extension point for help: the application writes its own, given the help generated from the model. */
    private final java.util.function.BiConsumer<String, PrintStream> helpView;
    private final Consumer<Configurator> tuiExtension;

    /**
     * The only constructor: every setting is given at once, and no call ever changes an existing
     * facade. A tool therefore survives a run unchanged and can be run again with the same result.
     */
    private ConfigTool(ConfigDefinition definition, String program, Path sources, String baseFile,
                       String envPrefix, Map<String, String> env, List<String> actions,
                       boolean reportOrigin, Consumer<Config> onRun, BiConsumer<Config, String> onAction,
                       BiConsumer<String, PrintStream> helpView, Consumer<Configurator> tuiExtension) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.program = program;
        this.sources = sources;
        this.baseFile = baseFile;
        this.envPrefix = envPrefix;
        this.env = env;
        this.actions = actions;
        this.reportOrigin = reportOrigin;
        this.onRun = onRun;
        this.onAction = onAction;
        this.helpView = helpView;
        this.tuiExtension = tuiExtension;
    }

    /**
     * Starts from the model — the one thing an application must declare.
     *
     * @param definition the model declaration; must not be {@code null}
     * @return a facade with the defaults (`app`, `.`, `application.yaml`) and no sources configured yet
     */
    public static ConfigTool of(ConfigDefinition definition) {
        return new ConfigTool(definition, "app", Path.of("."), "application.yaml", null, Map.of(),
            List.of(), true, config -> { }, null, null, view -> { });
    }

    /**
     * The program name in `-help`, in the `application.sh` script and in the printed command line.
     *
     * <p>It reaches the help as the name of the command line spec, so the usage line reads
     * {@code Usage: uri-app …} instead of picocli's placeholder {@code <main class>} — the program name is
     * the first thing a user copies out of the help.
     *
     * @param program the name the user sees, such as `uri-app`; must not be {@code null}
     * @return a NEW facade with this one setting applied; the receiver is left untouched
     */
    public ConfigTool program(String program) {
        return new ConfigTool(definition, Objects.requireNonNull(program, "program"), sources, baseFile,
            envPrefix, env, actions, reportOrigin,
            onRun, onAction, helpView, tuiExtension);

    }

    /**
     * The source directory: the file and the overlay are read here, and the configurator writes here
     * (`Save`/`YAML`/`CLI`).
     *
     * @param directory the directory the file names are resolved against; must not be {@code null}
     * @return a NEW facade with this one setting applied; the receiver is left untouched
     */
    public ConfigTool sources(Path directory) {
        return new ConfigTool(definition, program, Objects.requireNonNull(directory, "directory"), baseFile,
            envPrefix, env, actions, reportOrigin,
            onRun, onAction, helpView, tuiExtension);

    }

    /**
     * The source file name; the overlay is {@code <name>.applied.yaml} (for example
     * `application.applied.yaml`).
     *
     * @param baseFile the file name looked up in the source directory; must not be {@code null}
     * @return a NEW facade with this one setting applied; the receiver is left untouched
     */
    public ConfigTool file(String baseFile) {
        return new ConfigTool(definition, program, sources, Objects.requireNonNull(baseFile, "baseFile"),
            envPrefix, env, actions, reportOrigin,
            onRun, onAction, helpView, tuiExtension);

    }

    /**
     * The environment variable prefix (`URIAPP` becomes `URIAPP__HOST`); without a call ENV takes no
     * part, and the help says exactly that instead of showing a prefix that was never declared.
     *
     * @param prefix the prefix of the variables read from the real environment
     * @return a NEW facade with this one setting applied; the receiver is left untouched
     */
    public ConfigTool env(String prefix) {
        return env(prefix, System.getenv());
    }

    /**
     * The variant for tests: the environment is given directly, because `System.getenv()` cannot be
     * injected.
     *
     * @param prefix      the prefix of the variables to read
     * @param environment the environment to read from, or {@code null} for an empty one
     * @return a NEW facade with this one setting applied; the receiver is left untouched
     */
    public ConfigTool env(String prefix, Map<String, String> environment) {
        return new ConfigTool(definition, program, sources, baseFile,
            prefix, environment == null ? Map.of() : environment, actions, reportOrigin,
            onRun, onAction, helpView, tuiExtension);

    }

    /**
     * Extra application actions (bare words) — alongside the built-in `tui`. The names are registered in
     * the CLI (so a typo says what is known) and reach {@link #onAction}.
     *
     * @param actions the action names; an empty list means the application adds none
     * @return a NEW facade with this one setting applied; the receiver is left untouched
     */
    public ConfigTool actions(String... actions) {
        return new ConfigTool(definition, program, sources, baseFile,
            envPrefix, env, List.of(actions), reportOrigin,
            onRun, onAction, helpView, tuiExtension);

    }

    /**
     * Whether to print the origin (`layer.*`). Yes by default — this is diagnostics, not decoration.
     *
     * @param reportOrigin whether the `layer.*` lines are printed to the output stream
     * @return a NEW facade with this one setting applied; the receiver is left untouched
     */
    public ConfigTool reportingOrigin(boolean reportOrigin) {
        return new ConfigTool(definition, program, sources, baseFile,
            envPrefix, env, actions, reportOrigin,
            onRun, onAction, helpView, tuiExtension);

    }

    /**
     * The application business: it receives a ready configuration and returns nothing (the toolbox decides the exit code).
     *
     * @param business the business code, called only when the configuration has no errors and no
     *                 action was selected; must not be {@code null}
     * @return a NEW facade with this one setting applied; the receiver is left untouched
     */
    public ConfigTool onRun(Consumer<Config> business) {
        return new ConfigTool(definition, program, sources, baseFile,
            envPrefix, env, actions, reportOrigin,
            Objects.requireNonNull(business, "business"), onAction, helpView, tuiExtension);

    }

    /**
     * An extension point for the configurator window: the developer receives the view and adds widgets of
     * their own.
     *
     * <p>The view gives everything that is needed, and only that: `window()` (where to add a widget),
     * `freeRow()` (where there is room), `config()` (the resolved state — read from the model, not from a
     * widget), `draft()` + `edit(...)` (when a widget changes the configuration), `application()`
     * (`invokeLater`, `addTimer`, dialogs for widgets doing something in the background) and
     * `statusMessage(...)` (reporting).
     *
     * <p>The cost is on the application side: writing such a widget requires knowing the widget
     * library — and that is why the application declares it itself. This is deliberate: the toolbox does
     * not pretend that widgets can be added without a widget library.
     *
     * @param extension the code adding widgets to the configurator window, called once with the built
     *                  view before the first repaint; must not be {@code null}
     * @return a NEW facade with this one setting applied; the receiver is left untouched
     */
    public ConfigTool tui(Consumer<Configurator> extension) {
        return new ConfigTool(definition, program, sources, baseFile,
            envPrefix, env, actions, reportOrigin,
            onRun, onAction, helpView, Objects.requireNonNull(extension, "extension"));

    }

    /**
     * An extension point for HELP: the application receives the help generated from the model (exactly
     * what we would print without an extension) and writes its own — it may precede it, add to it or
     * replace it entirely.
     *
     * <p>ONE route, not two: there is no separate "append a section" option, because that is the same
     * mechanism — the application has full control over what the user sees, and we do not guess what
     * belongs there.
     *
     * <pre>{@code
     * ConfigTool.of(model)
     *     .help((generated, out) -> {
     *         out.print(generated);
     *         out.println();
     *         out.println("MY APPLICATION");
     *         out.println("  app ping — checks whether the URI is reachable");
     *     })
     *     .run(args, out, err);
     * }</pre>
     *
     * @param view the code writing the help, called with the text generated from the model and with
     *             the output stream; must not be {@code null}
     * @return a NEW facade with this one setting applied; the receiver is left untouched
     */
    public ConfigTool help(java.util.function.BiConsumer<String, PrintStream> view) {
        return new ConfigTool(definition, program, sources, baseFile,
            envPrefix, env, actions, reportOrigin,
            onRun, onAction, Objects.requireNonNull(view, "view"), tuiExtension);

    }

    /**
     * The business for an application action: the configuration plus the name of the selected action.
     *
     * @param business the code called with the ready configuration and the selected action name; must
     *                 not be {@code null}
     * @return a NEW facade with this one setting applied; the receiver is left untouched
     */
    public ConfigTool onAction(BiConsumer<Config, String> business) {
        return new ConfigTool(definition, program, sources, baseFile,
            envPrefix, env, actions, reportOrigin,
            onRun, Objects.requireNonNull(business, "business"), helpView, tuiExtension);

    }

    /**
     * Runs with the default streams.
     *
     * @param args the command line: `--field value` for configuration, a bare word for an action
     * @return the exit code: {@code 0} on success, otherwise the code of the problem kind that decides
     *         the run
     */
    public int run(String[] args) {
        return run(args, System.out, System.err);
    }

    /**
     * All of the work. The streams are arguments so that this can be checked without starting a process
     * (that is how `native-app` tests it, and how this module tests it).
     *
     * @param args the command line: `--field value` for configuration, a bare word for an action
     * @param out  the stream the results go to — the generated help, the `config-*` reports and the
     *             origin report; anything the application does not print itself
     * @param err  the stream errors and the help of a failed parse go to
     * @return the exit code: {@code 0} on success, otherwise the code of the problem kind that decides
     *         the run
     */
    public int run(String[] args, PrintStream out, PrintStream err) {
        TransformerRegistry registry = TransformerRegistry.defaults();
        List<ConfigPatch> layers = new ArrayList<>();
        List<Layer> names = new ArrayList<>();

        // 1) the source file and the overlay — in this order, because the overlay must win
        for (String name : List.of(baseFile, overlay(baseFile))) {
            Path file = sources.resolve(name);
            if (!Files.exists(file)) {
                continue;
            }
            ConfigResult<YamlSource> source = YamlSource.of(file);
            if (source instanceof ConfigResult.ErrorResult<YamlSource> broken) {
                return fail(err, broken.error());
            }
            ConfigResult<ConfigPatch> patch = ((ConfigResult.ValueResult<YamlSource>) source).value()
                .patch(Config.root(definition), definition, registry);
            if (patch instanceof ConfigResult.ErrorResult<ConfigPatch> broken) {
                return fail(err, broken.error());
            }
            layers.add(((ConfigResult.ValueResult<ConfigPatch>) patch).value());
            names.add(Layer.YAML);
        }

        // 1b) THE SEPARATE VAULT IS GONE (owner, 1 XI 2026: „wywal stare śmieci”): a secret now travels INSIDE the configuration as `!decrypt` with its key, so there
        // is no second store to open here, no key file beside it, and no layer that could disagree with the file. `secrets.*` and `SecretsLayer` were removed with
        // it - a second mechanism beside the file is what the tag replaced.

        // 2) ENV — the structure reader is injected from the YAML adapter; source modules do not know each other
        if (envPrefix != null) {
            ConfigResult<ConfigPatch> environmentLayer = EnvSource.of(env, envPrefix, YamlSource::value)
                .patch(definition);
            if (environmentLayer instanceof ConfigResult.ErrorResult<ConfigPatch> broken) {
                return fail(err, broken.error());
            }
            layers.add(((ConfigResult.ValueResult<ConfigPatch>) environmentLayer).value());
            names.add(Layer.ENV);
        }

        // 3) command line: `--x` is configuration, `x` is an action, and application actions sit next
        // to the built-in `tui`.
        // Actions WITH DESCRIPTIONS: `tui` is ours, the rest belong to the application — it knows their meaning.
        java.util.Map<String, String> described = new java.util.LinkedHashMap<>();
        described.put(TUI, "opens the configurator window (TUI)");
        for (String action : actions) {
            described.putIfAbsent(action, "");
        }
        CommandLine commandLine = new CommandLine(spec(described));
        commandLine.setUnmatchedArgumentsAllowed(true);
        try {
            commandLine.parseArgs(args);
        } catch (ParameterException usage) {
            err.println("error=USAGE message=" + oneLine(usage.getMessage()));
            return ConfigExitCode.USAGE_ERROR;
        }
        // HELP COMES FIRST (a measurement: earlier `--help` was quietly swallowed and the application
        // started). It does not depend on the configuration, so it cannot blow up on a broken file.
        // The help request sits in the parse result of THE level it was written at: `-help` before an
        // action is at the root, while `config-validation -help` is in the subcommand result.
        boolean help = commandLine.getParseResult().isUsageHelpRequested()
            || (commandLine.getParseResult().subcommand() != null
                && commandLine.getParseResult().subcommand().isUsageHelpRequested());
        if (help) {
            String generated = usageText(commandLine);
            if (helpView == null) {
                out.print(generated);
            } else {
                helpView.accept(generated, out);
            }
            return ConfigExitCode.OK;
        }
        ConfigResult<ConfigPatch> arguments;
        try {
            arguments = CliSource.read(commandLine.getParseResult(), dev.jucm.yaml.YamlSource::value);
        } catch (ParameterException usage) {
            // A BARE option token (`--prot` with nothing after it) is a USAGE error raised while argv is
            // read — the same answer, and the same code, as a declared option without its parameter.
            err.println("error=USAGE message=" + oneLine(usage.getMessage()));
            return ConfigExitCode.USAGE_ERROR;
        }
        if (arguments instanceof ConfigResult.ErrorResult<ConfigPatch> broken) {
            return fail(err, broken.error());
        }
        layers.add(((ConfigResult.ValueResult<ConfigPatch>) arguments).value());
        names.add(Layer.CLI);

        ConfigPatch[] collected = layers.toArray(ConfigPatch[]::new);
        String selected = CliSource.selectedAction(commandLine.getParseResult()).orElse(null);
        if (TUI.equals(selected)) {
            return tui(registry, collected);
        }
        // Built-in tools. `config-generate` does not look at the configuration (its source is the
        // MODEL), so it comes first — otherwise an invalid file would block generating a starting point.
        if (GENERATE.equals(selected)) {
            return generate(out);
        }
        // Printing layers is a SEPARATE ACTION, not a `--layers` option: a model field has the right to
        // take that name, while an action name carries hyphens that a path does not allow.
        if (VALIDATION_WITH_LAYERS.equals(selected)) {
            return validate(registry, collected, out, true);
        }
        if (VALIDATION.equals(selected)) {
            return validate(registry, collected, out, false);
        }

        Config config = StandardPipeline.of(definition, registry, collected);
        ConfigValidator.Report report = ConfigValidator.build(definition, config);
        if (report.errors().isEmpty() && selected != null && onAction != null) {
            onAction.accept(config, selected);
        }
        if (report.errors().isEmpty() && selected == null) {
            onRun.accept(config);
        }
        if (reportOrigin) {
            // The `??` trace lives in the SYMBOLIC tree: once the flow has run the values are resolved and the
            // decisions are gone (that is why reachability, too, is counted before the steps). The snapshot
            // built from the layers WITHOUT the steps is the one that still carries them.
            Config symbolic = Config.root(definition);
            for (ConfigPatch layer : collected) {
                symbolic = symbolic.apply(layer);
            }
            // ORDER BY ADDRESS, not by `fields()`: the model does not guarantee an order (the collection
            // is immutable, so iteration is random per JVM run — measured three times), while a
            // diagnostic report is sometimes COMPARED between platforms. The same rule as in the view
            // and in `diff`.
            for (ConfigField field : sortedFields()) {
                out.println("layer." + field.path().canonical() + "="
                    + winner(layers, names, field.path()));
                // A fallback that did its job is otherwise INVISIBLE: the report shows the value, not the
                // decision that produced it. These lines are the `debug` view the documentation promises.
                for (String decision : coalesceLines(symbolic, field.path())) {
                    out.println(decision);
                }
            }
        }
        for (ConfigError problem : report.errors()) {
            err.println("error=" + oneLine(problem.report()));
        }
        return report.errors().isEmpty() ? ConfigExitCode.OK
            : ConfigExitCode.lowestOf(report.errors(), ConfigExitCode.Pipeline.STANDARD);
    }


    /**
     * The `??` decisions of one address, as `coalesce.<path>=<side>: <reason>` lines.
     *
     * <p>Which side was chosen and why — the record the interpolation keeps for exactly this purpose. An address
     * whose value involves no `??`, or whose value cannot be resolved, yields no line: the report says nothing
     * about a decision that was never made.
     */
    private static List<String> coalesceLines(Config symbolic, ConfigPath path) {
        return switch (Interpolation.resolve(symbolic, path)) {
            case ConfigResult.ValueResult<Interpolation.Resolved> resolved -> resolved.value().trace().stream()
                .map(decision -> "coalesce." + path.canonical() + "=" + decision.chosen() + ": "
                    + decision.reason())
                .toList();
            case ConfigResult.ErrorResult<Interpolation.Resolved> ignored -> List.of();
        };
    }

    /** Declaration fields ordered by address — a repeatable output order (see the comment at the report). */
    private List<ConfigField> sortedFields() {
        List<ConfigField> fields = new ArrayList<>(definition.fields());
        fields.sort((one, other) -> ConfigPath.BY_VALUE.compare(one.path(), other.path()));
        return fields;
    }

    /**
     * The built-in `config-validation` action: a report about the configuration **without running the
     * application**. The configuration is built EXACTLY as at a normal startup (the same layer order),
     * and the report carries ALL problems at once — it does not stop at the first.
     *
     * <p>The report goes to **stdout** — that is the product of the tool (just like `config-generate`),
     * while the verdict is carried by the exit code. The tool stays **silent** about the layer order in
     * the chain: the order is the application's contract.
     */
    private int validate(TransformerRegistry registry, ConfigPatch[] layerPatches, PrintStream out,
                         boolean showLayers) {
        Config config = StandardPipeline.of(definition, registry, layerPatches);
        ConfigValidator.Report report = ConfigValidator.build(definition, config);
        if (report.errors().isEmpty()) {
            out.println("OK: " + sortedFields().size() + " field(s) match the model");
            if (showLayers) {
                printLayerStates(layerPatches, registry, out);
            }
            return ConfigExitCode.OK;
        }
        out.println("Invalid configuration");
        for (ConfigError problem : report.errors()) {
            out.println();
            ConfigPath path = ConfigError.pathOf(problem).orElse(null);
            ConfigField field = path == null ? null : definition.field(path).orElse(null);
            out.println("path:              " + (path == null ? "(whole source)" : path.canonical()));
            out.println("error:             " + problem.getClass().getSimpleName());
            out.println("message:           " + problem.message());
            expected(problem, field).ifPresent(text -> out.println("expected:          " + text));
            ConfigError.valueOf(problem).ifPresent(value -> out.println("actual:            " + value.display()));
            out.println("source:            " + problem.source().display());
            if (path != null && field != null) {
                previous(config, path, problem).ifPresent(text -> out.println("previous:          " + text));
                field.defaultValue().ifPresent(value ->
                    out.println("compiled fallback: " + value.display()));
            }
        }
        if (showLayers) {
            printLayerStates(layerPatches, registry, out);
        }
        return ConfigExitCode.lowestOf(report.errors(), ConfigExitCode.Pipeline.STANDARD);
    }

    /**
     * PRINTING LAYERS (`config-validation-with-layers`): for every path — what each layer contributed
     * and what the state was AFTER it. "Contributes" is the declaration from the chain, "resolved" is
     * `Config.stateAsOf(layer)`, that is the configuration trimmed to that layer. The pair answers the
     * question "at which layer did the value change or disappear".
     */
    private void printLayerStates(ConfigPatch[] layerPatches, TransformerRegistry registry,
                                  PrintStream out) {
        out.println();
        out.println("layers: what each one contributes / what comes out of it");
        for (ConfigField field : sortedFields()) {
            ConfigPath path = field.path();
            List<String> rows = new java.util.ArrayList<>();
            // The root is not a patch: it materialises from the FIELD DECLARATIONS, so its row is
            // computed from the model — otherwise the table would start at the second layer.
            ConfigResult<ConfigValue> root = Config.root(definition).get(path);
            if (root instanceof ConfigResult.ValueResult<ConfigValue> value) {
                rows.add(row("COMPILED:declaration", value.value(), value.value()));
            }
            for (int upTo = 0; upTo < layerPatches.length; upTo++) {
                if (!(layerPatches[upTo].operation(path).orElse(null)
                        instanceof ConfigPatch.SetValue set)) {
                    continue;   // this layer contributes no value here (`reset`/`null` carry none either)
                }
                // STATE COMPUTED FOR THE PREFIX: the pipeline over the layers up to `upTo` inclusive —
                // "as if nothing above existed". That is why interpolation counts in THIS context: a
                // `uri` from a file layer shows the port from the file, not the one overridden by ENV.
                Config prefix = StandardPipeline.of(definition, registry,
                    java.util.Arrays.copyOfRange(layerPatches, 0, upTo + 1));
                ConfigResult<ConfigValue> state = prefix.get(path);
                rows.add(row(set.source().display(), set.value(),
                    state instanceof ConfigResult.ValueResult<ConfigValue> resolved
                        ? resolved.value() : null));
            }
            if (rows.isEmpty()) {
                continue;
            }
            out.println();
            out.println("  " + path.canonical());
            rows.forEach(out::println);
        }
    }

    /** A printed row: the source, this layer's contribution and the state computed for the prefix ending at it. */
    private static String row(String source, ConfigValue contributes, ConfigValue state) {
        return String.format("    %-34s contributes: %-16s resolved: %s", source, contributes.display(),
            state == null ? "(none)" : state.display());
    }

    /** The chain over the WHOLE pipeline — for `history(path)`, that is the contribution of the layers. */
    private Config resolvedChainEnd(ConfigPatch[] layerPatches, TransformerRegistry registry) {
        return StandardPipeline.of(definition, registry, layerPatches);
    }

    /**
     * What was expected: for a rule problem the rule from the declaration, for a type problem the type
     * from the declaration. We take it from the MODEL, because only the model is the source of truth
     * about expectations.
     */
    private static java.util.Optional<String> expected(ConfigError problem, ConfigField field) {
        if (field == null) {
            return java.util.Optional.empty();
        }
        return switch (problem.kind()) {
            case VALIDATION_FAILED, RULE_MISMATCH -> field.constraints().stream()
                .map(constraint -> constraint instanceof ConfigConstraint.Const constant
                    ? constant.chain() : constraint.toString())
                .reduce((first, second) -> first + " | " + second);
            case CAST_FAILED, INVALID_SYNTAX, NULL_VALUE, ABSENT, UNRESOLVED_REFERENCE ->
                java.util.Optional.of(field.type().name());
            default -> java.util.Optional.empty();
        };
    }

    /**
     * `previous:` — the trace of the value in the chain OUTSIDE the entry that failed
     * (`Config.history(path)`): it shows what the configuration said EARLIER, so it is clear where to
     * put the fix.
     */
    private static java.util.Optional<String> previous(Config config, ConfigPath path, ConfigError problem) {
        List<String> lines = new java.util.ArrayList<>();
        java.util.Set<dev.jucm.core.ConfigSource> already = new java.util.LinkedHashSet<>();
        for (Config.Step sighting : config.history(path)) {
            if (sighting.source().equals(problem.source()) || !already.add(sighting.source())) {
                // the offending entry is shown above as `source:`, and the pipeline steps repeat the same
                // source — what interests us is what each LAYER said
                continue;
            }
            lines.add(sighting.source().display() + " -> "
                + sighting.value().map(ConfigValue::display).orElse("(none)"));
        }
        return lines.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(String.join(" | ", lines));
    }

    /**
     * The built-in `config-generate` action: a sample YAML file from the model, **always on stdout**
     * (the file itself is the shell's business; there is no write option, because that would be a second
     * output path, a second way of handling a write failure and a second set of exit codes).
     */
    private int generate(PrintStream out) {
        out.print(Generator.yaml(definition));
        return ConfigExitCode.OK;
    }

    /**
     * The picocli help as TEXT: for a selected action its own help, and without an action the root one.
     * Text (rather than a stream) is here so that the extension point can precede, extend or skip it.
     */
    static String usageText(CommandLine commandLine) {
        CommandLine.ParseResult parsed = commandLine.getParseResult();
        CommandLine selected = parsed.subcommand() == null
            ? commandLine
            : commandLine.getSubcommands().get(parsed.subcommand().commandSpec().name());
        return selected.getUsageMessage();
    }

    /**
     * The spec of the command line: the options derived from the model, plus the two things only the
     * FACADE knows — the program name and the prefix of the environment variables.
     *
     * <p>The name is set on the spec itself, so <b>every</b> view of the help carries it: the root usage
     * line, the usage line of an action (`uri-app config-validation`), the text handed to the
     * application's own help extension, and the usage picocli would print on a parse error. Without that
     * the first line of the help read {@code Usage: <main class> …} — the placeholder picocli gives a fresh
     * spec — and that line is exactly what the user copies into a script.
     *
     * <p>The environment line goes to the FOOTER of every level, because it concerns the sources of the
     * whole run and not one option: a user reads the help to learn where a value may come from.
     */
    private CommandSpec spec(Map<String, String> describedActions) {
        CommandSpec spec = CliSpec.of(definition, describedActions);
        spec.name(program);
        String sources = environmentHelp();
        spec.usageMessage().footer(sources);
        for (CommandLine action : spec.subcommands().values()) {
            action.getCommandSpec().usageMessage().footer(sources);
        }
        return spec;
    }

    /**
     * The line about the ENV source in the help. A prefix declared — the help names it and shows the
     * variable of one DECLARED field, so the separator does not have to be guessed (`URIAPP` plus
     * `server.port` gives `URIAPP__SERVER__PORT`: a double underscore between segments, a single one
     * inside a segment). No prefix declared — the help says that plainly, because a help that invented a
     * prefix would send the user to variables nobody reads.
     *
     * @return the footer line for the help of every level
     */
    String environmentHelp() {
        if (envPrefix == null) {
            return "Environment variables: not read — this application declares no prefix.";
        }
        List<ConfigField> fields = sortedFields();
        if (fields.isEmpty()) {
            return "Environment variables: prefix `" + envPrefix
                + "` (a double underscore separates the segments of a path).";
        }
        ConfigPath path = fields.get(0).path();
        return "Environment variables: prefix `" + envPrefix + "` — the field `" + path.canonical()
            + "` is `" + environmentName(path) + "` (a double underscore separates the segments).";
    }

    /**
     * The environment variable of a path: lowercase segments become uppercase, and the dots become the
     * double underscore (the separator `EnvSource` splits on). A prefix already ending with the separator
     * is not doubled.
     *
     * @param path the canonical path of a declared field, such as `server.port`
     * @return the variable name, such as `URIAPP__SERVER__PORT`
     */
    String environmentName(ConfigPath path) {
        String prefix = envPrefix.endsWith("__") ? envPrefix : envPrefix + "__";
        return prefix + path.canonical().replace(".", "__").toUpperCase(java.util.Locale.ROOT);
    }

    /** `application.yaml` becomes `application.applied.yaml`: the overlay sits next to the source, same name family. */
    static String overlay(String baseFile) {
        int dot = baseFile.lastIndexOf('.');
        return dot < 0 ? baseFile + ".applied" : baseFile.substring(0, dot) + ".applied" + baseFile.substring(dot);
    }

    /** Actions declared by the application plus the built-in `tui` (the engine-reserved ones are added by `CliSpec`). */
    private List<String> registeredActions() {
        List<String> all = new ArrayList<>();
        all.add(TUI);
        for (String action : actions) {
            if (!all.contains(action)) {
                all.add(action);
            }
        }
        return all;
    }

    /**
     * The built-in `tui` action: the whole configurator window together with its file outputs.
     *
     * <p>The TUI life cycle (the window, the loop, restoring the terminal) lives in the TUI module —
     * here there is only the call. Otherwise the toolbox would have to know the widget library: in Java,
     * using a subclass requires the supertype on the classpath, so it cannot be hidden behind a method of
     * our own.
     *
     * <p>The write targets are computed from the source directory: reading and writing in one place (a
     * report once said "I do not know where that file was saved, but not in the path I ran the app from").
     */
    private int tui(TransformerRegistry registry, ConfigPatch... layers) {
        return TuiApplication.runConfigurator(definition, registry, layers,
            Configurator.Targets.in(sources), program, tuiExtension);
    }

    /** Which layer won for a field: the last one that set it, and without one the value from the code. */
    private static String winner(List<ConfigPatch> layers, List<Layer> names, ConfigPath path) {
        for (int index = layers.size() - 1; index >= 0; index--) {
            if (layers.get(index).operation(path).isPresent()) {
                return names.get(index).name();
            }
        }
        return Layer.COMPILED.name();
    }

    /**
     * A message about a problem: `error=` plus the **library report** — kind, reason, path, value and
     * source (`ConfigError.report()`). Gluing that together by hand in an application was worse: the
     * information ABOUT WHICH field the problem concerns was lost, and diagnostics without an address
     * does not say where to look.
     */
    private static int fail(PrintStream err, ConfigError problem) {
        err.println("error=" + oneLine(problem.report()));
        return ConfigExitCode.lowestOf(List.of(problem), ConfigExitCode.Pipeline.STANDARD);
    }

    /** An error on one line and without non-ASCII characters — the same comparison on every platform. */
    static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    /**
     * The value of a field as seen by the application; `[none]` instead of an exception, so that the
     * output is comparable.
     *
     * @param config the resolved configuration to read from; must not be {@code null}
     * @param field  the canonical path of the field, such as `server.port`
     * @return the display form of the value, or `[none]` when the field has no value
     */
    public static String display(Config config, String field) {
        return config.get(ConfigPath.of(field)) instanceof ConfigResult.ValueResult<ConfigValue> value
            ? value.value().display()
            : "[none]";
    }
}
