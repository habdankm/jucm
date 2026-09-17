package dev.jucm.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jucm.core.Config;
import dev.jucm.core.ConfigConstraint;
import dev.jucm.core.ConfigDefinition;
import dev.jucm.core.ConfigExitCode;
import dev.jucm.core.ConfigField;
import dev.jucm.core.ConfigInt;
import dev.jucm.core.ConfigString;
import dev.jucm.core.ConfigType;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The toolbox checked ON ITS OWN, not only through a consuming application. This is its contract: give
 * it a model and you get the sources, the verdict, the exit codes, the origin report and the actions.
 */
class ConfigToolTest {

    private static ConfigDefinition model() {
        return ConfigDefinition.of(
            ConfigField.required("host", ConfigType.STR, ConfigString.of("localhost")),
            ConfigField.required("port", ConfigType.INT, ConfigInt.of(8080)),
            ConfigField.required("uri", ConfigType.STR, ConfigString.of("http://${host}:${port}/api")));
    }

    /**
     * A CORRECT model: rules, descriptions, a secret and a nested path, with default values that
     * satisfy their rules. For `config-validation`, where the verdict matters, not the examples.
     */
    private static ConfigDefinition guarded() {
        return ConfigDefinition.of(
            ConfigField.required("host", ConfigType.STR, ConfigString.of("localhost"))
                .described("Host name."),
            ConfigField.required("port", ConfigType.INT, ConfigString.of("8080"))
                .constrained(ConfigConstraint.Const("in[8080,443]")).described("Service port."),
            ConfigField.required("mode", ConfigType.STR, ConfigString.of("dev"))
                .constrained(ConfigConstraint.Const("in[dev,test]")).described("Operating mode."),
            ConfigField.required("api_key", ConfigType.STR, ConfigString.of("local"))
                .described("API key."),
            ConfigField.required("server.tls", ConfigType.BOOL, ConfigString.of("false"))
                .described("TLS on the server."));
    }

    /**
     * The model for `config-generate`: the default of `mode` (`prod`) does NOT satisfy its rule, so it
     * is visible that the sample value comes from the RULE, not from the default value.
     */
    private static ConfigDefinition generative() {
        return ConfigDefinition.of(
            ConfigField.required("host", ConfigType.STR, ConfigString.of("localhost"))
                .described("Host name."),
            ConfigField.required("port", ConfigType.INT, ConfigString.of("8080"))
                .constrained(ConfigConstraint.Const("in[8080,443]")).described("Service port."),
            ConfigField.required("mode", ConfigType.STR, ConfigString.of("prod"))
                .constrained(ConfigConstraint.Const("in[dev,test]")).described("Operating mode."),
            ConfigField.required("api_key", ConfigType.STR, ConfigString.of("local"))
                .described("API key."),
            ConfigField.required("server.tls", ConfigType.BOOL, ConfigString.of("false"))
                .described("TLS on the server."));
    }

    /** The result of one run: exit code, output and errors — everything the user sees. */
    private record Ran(int code, String out, String err, Config config) {
    }

    private static Ran run(ConfigTool tool, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        List<Config> seen = new ArrayList<>();
        int code = tool
            .onRun(seen::add)
            .run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Ran(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8),
            seen.isEmpty() ? null : seen.get(0));
    }

    private static void write(Path file, String text) throws Exception {
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("file and overlay: the value from the file, then overridden by `application.applied.yaml`")
    void fileThenOverlay(@TempDir Path dir) throws Exception {
        write(dir.resolve("application.yaml"), "host: example.org\nport: 9000\n");
        write(dir.resolve("application.applied.yaml"), "port: 4242\n");

        Ran ran = run(ConfigTool.of(model()).sources(dir));

        assertThat(ConfigTool.display(ran.config(), "host")).isEqualTo("example.org");
        assertThat(ConfigTool.display(ran.config(), "port"))
            .as("the overlay is read AFTER the source file, so it wins").isEqualTo("4242");
        assertThat(ran.out()).contains("layer.host=YAML").contains("layer.port=YAML");
    }

    @Test
    @DisplayName("ENV comes after the file and CLI after ENV — the standard order, without writing it in the app")
    void envThenCli(@TempDir Path dir) throws Exception {
        write(dir.resolve("application.yaml"), "host: from-file\nport: 1000\n");

        Ran fromEnv = run(ConfigTool.of(model()).sources(dir).env("APP", Map.of("APP__PORT", "7000")));
        assertThat(ConfigTool.display(fromEnv.config(), "port")).isEqualTo("7000");
        assertThat(fromEnv.out()).contains("layer.port=ENV").contains("layer.host=YAML");

        Ran fromCli = run(ConfigTool.of(model()).sources(dir)
            .env("APP", Map.of("APP__PORT", "7000")), "--port", "8080");
        assertThat(ConfigTool.display(fromCli.config(), "port")).isEqualTo("8080");
        assertThat(fromCli.out()).contains("layer.port=CLI");
    }

    @Test
    @DisplayName("the origin report shows the `??` DECISION, not only the value — which side was chosen and why")
    void theOriginReportShowsTheCoalesceDecision(@TempDir Path dir) throws Exception {
        ConfigDefinition definition = ConfigDefinition.of(
            ConfigField.optional("new_port", ConfigType.INT),
            ConfigField.required("port", ConfigType.INT, ConfigInt.of(80)));
        write(dir.resolve("application.yaml"), "port: ${new_port ?? 8080}\n");

        Ran ran = run(ConfigTool.of(definition).sources(dir));

        assertThat(ran.code()).isZero();
        assertThat(ran.out().lines().filter(line -> line.startsWith("coalesce.")))
            .as("`new_port` is absent, so the fallback decided — the line names the side and the value")
            .anyMatch(line -> line.startsWith("coalesce.port=RIGHT") && line.contains("8080"));
        assertThat(ran.out()).contains("layer.port=YAML");
    }

    @Test
    @DisplayName("interpolation works without the application: `uri` computed from `host` and `port`")
    void interpolationComesForFree(@TempDir Path dir) throws Exception {
        write(dir.resolve("application.yaml"), "host: example.org\n");
        Ran ran = run(ConfigTool.of(model()).sources(dir), "--port", "7000");

        assertThat(ConfigTool.display(ran.config(), "uri")).isEqualTo("http://example.org:7000/api");
    }

    @Test
    @DisplayName("a missing required value = a verdict + an exit code, the message on the error stream")
    void verdictAndExitCode(@TempDir Path dir) {
        ConfigDefinition required = ConfigDefinition.of(
            ConfigField.required("token", ConfigType.STR),
            ConfigField.required("port", ConfigType.INT, ConfigInt.of(8080)));

        Ran ran = run(ConfigTool.of(required).sources(dir));

        assertThat(ran.code()).as("a failure has a code of its own, not zero").isNotEqualTo(ConfigExitCode.OK);
        assertThat(ran.err())
            .as("the diagnostics says WHY and WHICH field — the report carries the path and the source")
            .startsWith("error=").contains("token").contains("[path `token`]").contains("[source ");
        assertThat(ran.config()).as("the business is not called on a broken configuration").isNull();
    }

    @Test
    @DisplayName("an origin report for EVERY declared field — without a list written in the app")
    void originForEveryDeclaredField(@TempDir Path dir) throws Exception {
        write(dir.resolve("application.yaml"), "host: example.org\n");
        Ran ran = run(ConfigTool.of(model()).sources(dir));

        assertThat(ran.out().lines().filter(line -> line.startsWith("layer.")))
            .containsExactly("layer.host=YAML", "layer.port=COMPILED", "layer.uri=COMPILED");
    }

    @Test
    @DisplayName("application actions: `export` reaches `onAction`, a typo says what is known")
    void appActions(@TempDir Path dir) {
        List<String> handled = new ArrayList<>();

        int code = ConfigTool.of(model()).sources(dir)
            .actions("export")
            .onRun(config -> handled.add("run"))
            .onAction((config, action) -> handled.add(action))
            .run(new String[] {"export"}, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));

        assertThat(code).isEqualTo(ConfigExitCode.OK);
        assertThat(handled).as("an application action does not call the default `onRun`").containsExactly("export");

        Ran typo = run(ConfigTool.of(model()).sources(dir).actions("export"), "exprot");
        assertThat(typo.code()).as("an unknown action is a parsing error").isNotEqualTo(ConfigExitCode.OK);
        assertThat(typo.err())
            .as("the message lists the known actions: the built-in `tui` and the application's own")
            .contains("tui").contains("export");
    }

    @Test
    @DisplayName("the overlay name follows the source file name (`config.yml` -> `config.applied.yml`)")
    void overlayName() {
        assertThat(ConfigTool.overlay("application.yaml")).isEqualTo("application.applied.yaml");
        assertThat(ConfigTool.overlay("config.yml")).isEqualTo("config.applied.yml");
        assertThat(ConfigTool.overlay("no-dot")).isEqualTo("no-dot.applied");
    }

    @Test
    @DisplayName("`config-validation`: a report WITHOUT running the application, all problems, exit code XY")
    void validationReportsWithoutRunning(@TempDir Path dir) throws Exception {
        write(dir.resolve("application.yaml"), "host: example.org\nport: 9999\nserver:\n  tls: false\n");

        Ran broken = run(ConfigTool.of(guarded()).sources(dir), "config-validation");
        assertThat(broken.config()).as("the application does NOT start — that is the whole point of this action").isNull();
        assertThat(broken.code()).as("23 = the YAML decade (2) + validation (3)").isEqualTo(23);
        assertThat(broken.out())
            .contains("Invalid configuration")
            .contains("path:              port")
            .contains("error:             ValidationFailed")
            .contains("expected:          in[8080,443]")
            .contains("actual:            9999")
            .contains("source:            YAML:application.yaml:")
            .contains("compiled fallback: 8080");

        // A correct configuration: the verdict and code 0, and `layer.*` is NOT printed (the report is the tool's product).
        write(dir.resolve("application.yaml"), "host: example.org\nport: 443\n");
        Ran fine = run(ConfigTool.of(guarded()).sources(dir), "config-validation");
        assertThat(fine.code()).as("443 satisfies `in[8080,443]`").isZero();
        assertThat(fine.out()).startsWith("OK: ").contains("field(s) match the model").doesNotContain("layer.");
    }

    @Test
    @DisplayName("`previous:` in the report shows what the configuration said EARLIER (history)")
    void validationShowsThePreviousLayers(@TempDir Path dir) throws Exception {
        write(dir.resolve("application.yaml"), "host: example.org\nport: 8080\n");
        Ran ran = run(ConfigTool.of(guarded()).sources(dir).env("APP", Map.of("APP__PORT", "1111")),
            "config-validation");

        assertThat(ran.code()).as("33 = the ENV decade (3) + validation (3)").isEqualTo(33);
        assertThat(ran.out())
            .contains("source:            ENV:APP__PORT")
            .contains("previous:          YAML:application.yaml:");
        assertThat(ran.out()).as("the indicated value is the one from the file, not the offender").contains("-> 8080")
            .doesNotContain("-> 1111");
    }

    @Test
    @DisplayName("`config-validation-with-layers`: what each layer contributes and what comes out of it")
    void layersViewShowsContributionAndState(@TempDir Path dir) throws Exception {
        write(dir.resolve("application.yaml"), "host: from-file\nport: 8080\n");
        Ran ran = run(ConfigTool.of(model()).sources(dir).env("APP", Map.of("APP__PORT", "9999")),
            "config-validation-with-layers");

        assertThat(ran.code()).as("the configuration is correct").isZero();
        assertThat(ran.out()).contains("layers: what each one contributes / what comes out of it")
            .contains("COMPILED").contains("YAML").contains("ENV")
            .as("the YAML contribution is its own declaration, the ENV contribution is its own value — not one shared")
            .contains("contributes: 8080").contains("contributes: 9999");
    }

    @Test
    @DisplayName("a field named `layers` does NOT collide with the engine — `--layers` belongs to the MODEL")
    void aFieldNamedLayersBelongsToTheModel(@TempDir Path dir) throws Exception {
        // While the layer printout was a `--layers` option, the ENGINE occupied a name a model field has
        // every right to own. It is a separate action now, so `--layers` is free.
        ConfigDefinition withLayers = ConfigDefinition.of(
            ConfigField.required("layers", ConfigType.INT, ConfigInt.of(3)),
            ConfigField.required("host", ConfigType.STR, ConfigString.of("localhost")));

        Ran ran = run(ConfigTool.of(withLayers).sources(dir), "--layers", "7");

        assertThat(ran.code()).as("`--layers` is a FIELD option, so the application receives the value").isZero();
        assertThat(ran.out()).as("and the origin shows the CLI layer")
            .contains("layer.layers=CLI").contains("layer.host=COMPILED");
    }

    @Test
    @DisplayName("`config-generate`: the rule before the default, the secret as a placeholder, the description after the marker")
    void generateIsDeterministicAndRuleAware(@TempDir Path dir) {
        String text = run(ConfigTool.of(generative()).sources(dir), "config-generate").out();

        assertThat(text).contains("mode: \"dev\"")
            .as("the value comes from the RULE `in[dev,test]`, not from the default `prod`");
        assertThat(text).contains("# CONST(in[dev,test]) Operating mode.")
            .as("the marker BEFORE the description, in the comment on the SAME line");
        assertThat(text).contains("\"<provide-api_key>\"")
            .as("a secret becomes a placeholder").doesNotContain("\"local\"");
        assertThat(text).contains("server:\n  tls: false")
            .as("nested paths keep their structure");

        assertThat(run(ConfigTool.of(generative()).sources(dir), "config-generate").out())
            .as("identical byte for byte").isEqualTo(text);
    }

    @Test
    @DisplayName("`config-generate`: the generated file IS correct — we read it back and validate it")
    void generatedFilePassesValidation(@TempDir Path dir) throws Exception {
        String text = run(ConfigTool.of(generative()).sources(dir), "config-generate").out();
        write(dir.resolve("application.yaml"), text);

        Ran check = run(ConfigTool.of(generative()).sources(dir), "config-validation");
        assertThat(check.code()).as("a starting file must validate, otherwise the tool lies").isZero();
        assertThat(check.out()).startsWith("OK: ");
    }

    @Test
    @DisplayName("`-help`: help from the MODEL (description, type, aliases), for the root and for an action")
    void helpComesFromTheModel(@TempDir Path dir) {
        Ran root = run(ConfigTool.of(guarded()).sources(dir), "-help");

        assertThat(root.code()).isZero();
        assertThat(root.config()).as("the application does NOT start").isNull();
        assertThat(root.out())
            .as("a field description from the declaration — one source of metadata").contains("Service port.")
            .as("the type in the parameter label").contains("<int>")
            .as("actions visible in the help TOGETHER with a description").contains("config-validation")
            .contains("tui").contains("opens the configurator window");
        assertThat(root.out()).as("help is not a run").doesNotContain("layer.");

        Ran action = run(ConfigTool.of(guarded()).sources(dir), "config-validation", "-help");
        assertThat(action.code()).as("an action's help also ends with zero").isZero();
        assertThat(action.out()).as("and it does NOT validate on the way").doesNotContain("OK: ");
    }

    @Test
    @DisplayName("`-help` NAMES the program and the ENV prefix — or says plainly that ENV takes no part")
    void helpNamesTheProgramAndTheEnvironmentPrefix(@TempDir Path dir) {
        Ran withEnv = run(ConfigTool.of(model()).sources(dir).program("uri-app").env("URIAPP", Map.of()),
            "-help");

        assertThat(withEnv.code()).isZero();
        assertThat(withEnv.out())
            .as("the usage line is what a user copies into a script — no `<main class>` placeholder")
            .contains("Usage: uri-app ").doesNotContain("<main class>")
            .as("the prefix of the source plus the variable of a DECLARED field, so the separator is known")
            .contains("`URIAPP`").contains("URIAPP__HOST");

        Ran without = run(ConfigTool.of(model()).sources(dir).program("uri-app"), "-help");
        assertThat(without.out())
            .as("no prefix declared: the help says so instead of inventing one")
            .contains("Environment variables: not read")
            .doesNotContain("URIAPP");

        Ran action = run(ConfigTool.of(model()).sources(dir).program("uri-app").env("URIAPP", Map.of()),
            "config-validation", "-help");
        assertThat(action.out())
            .as("an action's help carries the same facts, and the action in its own usage line")
            .contains("Usage: uri-app config-validation ").contains("URIAPP__HOST");
    }

    @Test
    @DisplayName("a field named `help` is LEGAL: `--help` sets it, while `-help` still asks for the help")
    void aFieldNamedHelpIsSetByTheDoubleDash(@TempDir Path dir) {
        ConfigDefinition withHelp = ConfigDefinition.of(
            ConfigField.required("help", ConfigType.STR, ConfigString.of("none")),
            ConfigField.required("port", ConfigType.INT, ConfigInt.of(8080)));

        Ran ran = run(ConfigTool.of(withHelp).sources(dir), "--help", "1");

        assertThat(ran.code()).as("the application runs: `--help` is a field here, not a request for help")
            .isZero();
        assertThat(ran.out()).contains("layer.help=CLI");
        assertThat(ConfigTool.display(ran.config(), "help")).isEqualTo("1");

        Ran help = run(ConfigTool.of(withHelp).sources(dir), "-help");

        assertThat(help.code()).isZero();
        assertThat(help.config()).as("the engine's help is untouched by the field").isNull();
        assertThat(help.out())
            .as("both spellings stand in the spec, each meaning what it says")
            .contains("--help=<str>").contains("-help").doesNotContain("layer.");
    }

    @Test
    @DisplayName("a bare `--help` without a field `help` = a usage error 125 that POINTS AT `-help`")
    void bareHelpWithoutTheFieldIsAUsageErrorWithAHint(@TempDir Path dir) {
        Ran ran = run(ConfigTool.of(model()).sources(dir), "--help");

        assertThat(ran.code())
            .as("a bare option sets nothing — the same code as `--port` without its value")
            .isEqualTo(125);
        assertThat(ran.err())
            .as("`-help` is the form the engine has, and the message names it")
            .contains("`--help`").contains("did you mean `-help`");
        assertThat(ran.config()).as("the application does not start").isNull();
    }

    @Test
    @DisplayName("with a field `help` declared, a bare `--help` is a missing VALUE — and nothing is suggested")
    void bareHelpWithTheFieldIsAboutTheValue(@TempDir Path dir) {
        ConfigDefinition withHelp = ConfigDefinition.of(
            ConfigField.required("help", ConfigType.STR, ConfigString.of("none")));

        Ran ran = run(ConfigTool.of(withHelp).sources(dir), "--help");

        assertThat(ran.code()).isEqualTo(125);
        assertThat(ran.err())
            .as("the user forgot a value, they did not mistake the help — so no hint")
            .doesNotContain("did you mean")
            .as("picocli reports the missing parameter of the declared option")
            .contains("--help");
    }

    @Test
    @DisplayName("there is no `-h`: a short name exists only when it is DECLARED")
    void thereIsNoShortHelp(@TempDir Path dir) {
        Ran ran = run(ConfigTool.of(model()).sources(dir), "-h");

        assertThat(ran.code()).as("an undeclared short name is an error, not a guess")
            .isNotEqualTo(ConfigExitCode.OK);
        assertThat(ran.err()).contains("`-h`").contains("DECLARED");
    }

    @Test
    @DisplayName("EXTENSION POINT: the application injects its OWN help (it receives the one generated from the model)")
    void applicationInjectsItsOwnHelp(@TempDir Path dir) {
        ConfigTool withOwn = ConfigTool.of(guarded()).sources(dir)
            .help((generated, out) -> {
                out.print(generated);
                out.println();
                out.println("MY APPLICATION");
                out.println("  app ping — checks whether the URI is reachable");
            });

        Ran ran = run(withOwn, "-help");
        assertThat(ran.code()).isZero();
        assertThat(ran.out())
            .as("the application's own section").contains("MY APPLICATION").contains("app ping")
            .as("and below it what the model gives").contains("--port=<int>").contains("Service port.");
        assertThat(ran.config()).as("help does NOT run the application").isNull();

        // An action has a help of its own: the extension receives the ACTION text, not the root one.
        ConfigTool action = ConfigTool.of(guarded()).sources(dir)
            .help((generated, out) -> {
                out.print(generated);
                out.println("MY APPLICATION");
            });
        assertThat(run(action, "config-validation", "-help").out())
            .as("the action help plus the application's own section").contains("config-validation")
            .contains("MY APPLICATION")
            .as("and it does not validate on the way").doesNotContain("OK: ");
    }

    @Test
    @DisplayName("a source that is not a mapping is REPORTED, not a crash — the problem has an origin of its own")
    void aSourceThatIsNotAMappingIsReported(@TempDir Path dir) throws Exception {
        // The document is a sequence, so the offending entry comes from the container, not from a layer
        // the file names. Such an origin must still be numberable, otherwise the whole verdict throws
        // instead of reaching the user — on a plain run AND on `config-validation`, which is worse.
        write(dir.resolve("application.yaml"), "- \"${?maybe}\"\n");

        Ran plain = run(ConfigTool.of(model()).sources(dir));
        assertThat(plain.code()).as("the user gets a code; there is no crash").isNotZero();
        assertThat(plain.err()).as("and the diagnostics reaches the error stream").isNotEmpty();

        Ran validation = run(ConfigTool.of(model()).sources(dir), "config-validation");
        assertThat(validation.code()).as("the tool for checking configurations must not be the one that dies")
            .isNotZero();
        assertThat(validation.config()).as("a broken configuration does not reach the application").isNull();
    }

    @Test
    @DisplayName("a facade is IMMUTABLE: setting something up does not leak into the tool it was set up from")
    void settingUpAFacadeDoesNotChangeTheReceiver(@TempDir Path dir) throws Exception {
        // A tool built once is meant to be run as many times as needed; if a builder method altered its
        // receiver, the second run would see the first one's environment and the same tool would answer
        // differently twice — the kind of surprise that takes an afternoon to find in an application.
        write(dir.resolve("application.yaml"), "host: from-file\nport: 1000\n");
        ConfigTool base = ConfigTool.of(model()).sources(dir);

        Ran withEnv = run(base.env("APP", Map.of("APP__PORT", "7000")));
        assertThat(ConfigTool.display(withEnv.config(), "port"))
            .as("the NEW facade carries the environment").isEqualTo("7000");

        Ran again = run(base);
        assertThat(ConfigTool.display(again.config(), "port"))
            .as("and the receiver never saw it: no call changes an existing facade")
            .isEqualTo("1000");
        assertThat(again.out()).as("the origin report does not mention ENV either").doesNotContain("layer.port=ENV");

        Ran third = run(base);
        assertThat(ConfigTool.display(third.config(), "port")).as("still the same answer").isEqualTo("1000");
    }
}
