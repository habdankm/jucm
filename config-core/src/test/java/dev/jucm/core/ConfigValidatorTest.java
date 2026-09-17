package dev.jucm.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The validator: a single pass over the layers, keeping the {@link ConfigSource} of every value, and
 * **all** problems at once.
 */
class ConfigValidatorTest {

    private static final ConfigSchema SCHEMA = ConfigSchema.empty()
        .with("server.port", ConfigType.INT)
        .with("server.tls", ConfigType.BOOL)
        .with("server.max_connections", ConfigType.INT);

    private static ConfigPatch fromYaml(String canonical, ConfigValue value) {
        return ConfigPatch.empty().with(canonical, value, ConfigSource.of(Layer.YAML, "application.yaml:12"));
    }

    private static ConfigPatch fromEnv(String canonical, ConfigValue value, String variable) {
        return ConfigPatch.empty().with(canonical, value, ConfigSource.of(Layer.ENV, variable));
    }

    private static ConfigPatch fromCli(String canonical, ConfigValue value, String option) {
        return ConfigPatch.empty().with(canonical, value, ConfigSource.of(Layer.CLI, option));
    }

    @Test
    @DisplayName("walks the layers in order, keeping the source of every value")
    void walksLayersInOrderAndKeepsSource() {
        ConfigValidator.Report report = ConfigValidator.validate(SCHEMA, List.of(
            new ConfigValidator.LayerInput(Layer.YAML,
                fromYaml("server.port", ConfigString.of("8080"))),
            new ConfigValidator.LayerInput(Layer.ENV,
                fromEnv("server.max_connections", ConfigString.of("16"), "AIMENAGERIE__SERVER__MAX_CONNECTIONS")),
            new ConfigValidator.LayerInput(Layer.CLI,
                fromCli("server.tls", ConfigString.of("true"), "--server-tls"))));

        assertThat(report.hasErrors()).isFalse();
        assertThat(report.values()).extracting(ConfigValidator.TypedValue::path)
            .containsExactly(ConfigPath.of("server.port"), ConfigPath.of("server.max_connections"),
                ConfigPath.of("server.tls"));
        assertThat(report.values()).extracting(entry -> entry.source().display())
            .containsExactly("YAML:application.yaml:12", "ENV:AIMENAGERIE__SERVER__MAX_CONNECTIONS", "CLI:--server-tls");
        assertThat(report.values()).extracting(entry -> entry.layer())
            .containsExactly(Layer.YAML, Layer.ENV, Layer.CLI);
        assertThat(report.values()).extracting(ConfigValidator.TypedValue::value)
            .containsExactly(ConfigInt.of(8080), ConfigInt.of(16), ConfigBool.of(true));
    }

    @Test
    @DisplayName("collects ALL problems at once, from every layer")
    void collectsAllErrorsAtOnce() {
        ConfigValidator.Report report = ConfigValidator.validate(SCHEMA, List.of(
            new ConfigValidator.LayerInput(Layer.YAML,
                fromYaml("server.port", ConfigString.of("8080"))
                    .with("server.tls", ConfigString.of("yes"), ConfigSource.of(Layer.YAML, "application.yaml:7"))),
            new ConfigValidator.LayerInput(Layer.ENV,
                fromEnv("server.max_connections", ConfigString.of("sixteen"), "AIMENAGERIE__SERVER__MAX_CONNECTIONS")),
            new ConfigValidator.LayerInput(Layer.CLI,
                fromCli("server.tls", ConfigString.of("1"), "--server-tls"))));

        assertThat(report.values()).extracting(ConfigValidator.TypedValue::path)
            .as("a good value passes despite the errors next to it").containsExactly(ConfigPath.of("server.port"));
        assertThat(report.errors()).hasSize(3);
        assertThat(report.errors()).extracting(ConfigError::kind)
            .containsOnly(ConfigError.Kind.CAST_FAILED);
        assertThat(report.errors()).extracting(error -> error.source().display())
            .containsExactlyInAnyOrder("YAML:application.yaml:7",
                "ENV:AIMENAGERIE__SERVER__MAX_CONNECTIONS", "CLI:--server-tls");
    }

    @Test
    @DisplayName("the report shows the path, the raw value and the source — everything needed to fix it")
    void reportCarriesEverythingNeededToFixIt() {
        ConfigValidator.Report report = ConfigValidator.validate(SCHEMA, List.of(
            new ConfigValidator.LayerInput(Layer.CLI,
                fromCli("server.tls", ConfigString.of("yes"), "--server-tls"))));

        assertThat(report.describe())
            .contains("ERROR cast-failed")
            .contains("path `server.tls`")
            .contains("value `yes`")
            .contains("CLI:--server-tls");
    }

    @Test
    @DisplayName("empty means an error in every source — ENV, CLI and YAML alike")
    void emptyIsAnErrorInEverySource() {
        ConfigValidator.Report report = ConfigValidator.validate(SCHEMA, List.of(
            new ConfigValidator.LayerInput(Layer.ENV,
                fromEnv("server.max_connections", ConfigString.of(""), "AIMENAGERIE__SERVER__MAX_CONNECTIONS")),
            new ConfigValidator.LayerInput(Layer.CLI,
                fromCli("server.port", ConfigString.of(""), "--server-port")),
            // YAML allows such a notation; this configuration does not
            new ConfigValidator.LayerInput(Layer.YAML,
                fromYaml("server.tls", ConfigString.of("")))));

        assertThat(report.values()).as("nothing got through").isEmpty();
        assertThat(report.errors()).hasSize(3);
        assertThat(report.errors()).extracting(ConfigError::kind)
            .as("one rule, one kind of error — the origin makes no difference")
            .containsOnly(ConfigError.Kind.NULL_VALUE);
        assertThat(report.errors()).extracting(error -> error.source().display())
            .containsExactlyInAnyOrder("ENV:AIMENAGERIE__SERVER__MAX_CONNECTIONS", "CLI:--server-port",
                "YAML:application.yaml:12");
    }

    @Test
    @DisplayName("empty gives NullValue, not a misleading \"expected type bool\" (checked before the cast)")
    void emptyIsCheckedBeforeCast() {
        ConfigValidator.Report report = ConfigValidator.validate(SCHEMA, List.of(
            new ConfigValidator.LayerInput(Layer.CLI,
                fromCli("server.tls", ConfigString.of(""), "--server-tls"))));

        assertThat(report.errors()).extracting(ConfigError::kind).containsExactly(ConfigError.Kind.NULL_VALUE);
        assertThat(report.errors().get(0).report()).contains("empty value").contains("CLI:--server-tls");
    }

    @Test
    @DisplayName("\"no value delivered\" gives NullValue — just like an empty string")
    void nullDeliveredIsAlsoNullValue() {
        ConfigValidator.Report report = ConfigValidator.validate(SCHEMA, List.of(
            new ConfigValidator.LayerInput(Layer.ENV,
                ConfigPatch.nullDelivered("server.port", ConfigSource.of(Layer.ENV, "AIMENAGERIE__SERVER__PORT")))));

        assertThat(report.errors()).extracting(ConfigError::kind).containsExactly(ConfigError.Kind.NULL_VALUE);
        assertThat(report.errors().get(0).report()).contains("ENV:AIMENAGERIE__SERVER__PORT");
    }

    @Test
    @DisplayName("a missing entry is NOT an error — an absent variable is a different matter than an empty one")
    void missingEntryIsNotAnError() {
        ConfigValidator.Report report = ConfigValidator.validate(SCHEMA, List.of(
            new ConfigValidator.LayerInput(Layer.ENV,
                fromEnv("server.port", ConfigInt.of(8080), "AIMENAGERIE__SERVER__PORT"))));

        assertThat(report.hasErrors()).isFalse();
        assertThat(report.values()).hasSize(1);
    }

    @Test
    @DisplayName("a path with no declaration (an auxiliary key) passes without a cast, keeping the form "
        + "from the source")
    void undeclaredPathKeepsSourceForm() {
        ConfigValidator.Report report = ConfigValidator.validate(SCHEMA, List.of(
            new ConfigValidator.LayerInput(Layer.ENV,
                fromEnv("default_port", ConfigString.of("9000"), "AIMENAGERIE__DEFAULT_PORT"))));

        assertThat(report.hasErrors()).isFalse();
        assertThat(report.values()).extracting(ConfigValidator.TypedValue::value)
            .containsExactly(ConfigString.of("9000"));
    }

    @Test
    @DisplayName("an empty list of layers is not an error — there is nothing to validate")
    void noLayersIsNotAnError() {
        ConfigValidator.Report report = ConfigValidator.validate(SCHEMA, List.of());

        assertThat(report.hasErrors()).isFalse();
        assertThat(report.values()).isEmpty();
    }

    @Test
    @DisplayName("build(): required with a default is fine, required without one is Absent -> an error")
    void buildChecksRequiredness() {
        ConfigDefinition definition = ConfigDefinition.of(
            ConfigField.required("server.port", ConfigType.INT, ConfigInt.of(8080)),
            ConfigField.required("api.key", ConfigType.STR),
            ConfigField.optional("debug", ConfigType.BOOL));

        ConfigValidator.Report report = ConfigValidator.build(definition, Config.root(definition));

        assertThat(report.errors()).hasSize(1);
        assertThat(report.errors().get(0).kind()).isEqualTo(ConfigError.Kind.ABSENT);
        assertThat(report.errors().get(0).report())
            .contains("no value in any layer")
            .contains("path `api.key`");
        assertThat(report.values()).extracting(ConfigValidator.TypedValue::path)
            .as("an optional key without a value is neither an error nor a value")
            .containsExactly(ConfigPath.of("server.port"));
    }

    @Test
    @DisplayName("build(): a CLI value beats YAML, and that source is visible in the result")
    void buildUsesTheWinningLayer() {
        ConfigDefinition definition = ConfigDefinition.of(
            ConfigField.required("server.port", ConfigType.INT, ConfigInt.of(80)));

        Config config = Config.root(definition)
            .apply(ConfigPatch.empty().with("server.port", ConfigString.of("8080"),
                ConfigSource.of(Layer.YAML, "application.yaml:12")))
            .apply(ConfigPatch.empty().with("server.port", ConfigString.of("9001"),
                ConfigSource.of(Layer.CLI, "--server-port")));

        ConfigValidator.Report report = ConfigValidator.build(definition, config);

        assertThat(report.hasErrors()).isFalse();
        assertThat(report.values()).hasSize(1);
        assertThat(report.values().get(0).value()).isEqualTo(ConfigInt.of(9001));
        assertThat(report.values().get(0).source().display()).isEqualTo("CLI:--server-port");
        assertThat(report.values().get(0).layer()).isEqualTo(Layer.CLI);
    }

    @Test
    @DisplayName("build(): \"not delivered\" inside the chain is NullValue, not Absent")
    void buildReportsNullValueFromTheChain() {
        ConfigDefinition definition = ConfigDefinition.of(
            ConfigField.required("server.port", ConfigType.INT, ConfigInt.of(80)));

        Config config = Config.root(definition)
            .apply(ConfigPatch.nullDelivered("server.port", ConfigSource.of(Layer.ENV, "AIMENAGERIE__SERVER__PORT")));

        ConfigValidator.Report report = ConfigValidator.build(definition, config);

        assertThat(report.errors()).extracting(ConfigError::kind).containsExactly(ConfigError.Kind.NULL_VALUE);
        assertThat(report.errors().get(0).report()).contains("ENV:AIMENAGERIE__SERVER__PORT");
    }

    @Test
    @DisplayName("a key that a RULE parameter leads to is USED — the rule reads it on every run")
    void aRuleParameterLeadsToTheKey() {
        ConfigDefinition definition = ConfigDefinition.of(
            ConfigField.required("server.port", ConfigType.INT, ConfigInt.of(80))
                .constrained(ConfigConstraint.Const(">=${min_port}")));

        Config config = Config.root(definition).apply(ConfigPatch.empty()
            .with("server.port", ConfigInt.of(9000), ConfigSource.of(Layer.ENV, "AIMENAGERIE__PORT"))
            .with("min_port", ConfigInt.of(9000), ConfigSource.of(Layer.ENV, "AIMENAGERIE__MIN_PORT"))
            .with("nobody_reads_me", ConfigInt.of(1), ConfigSource.of(Layer.ENV, "AIMENAGERIE__NOBODY")));

        ConfigValidator.Report report = ConfigValidator.build(definition, config);

        assertThat(report.errors()).extracting(ConfigError::kind)
            .as("`min_port` is read by the rule (`>=${min_port}`), so it is not an unused auxiliary — "
                + "`nobody_reads_me` stays one")
            .containsExactly(ConfigError.Kind.UNUSED_AUXILIARY);
        assertThat(report.errors().get(0).report())
            .contains("nobody_reads_me")
            .doesNotContain("min_port");
    }

    @Test
    @DisplayName("build(): a key outside the schema is an UnusedAuxiliary — today nothing can lead to it")
    void undeclaredKeyIsAnUnusedAuxiliary() {
        ConfigDefinition definition = ConfigDefinition.of(
            ConfigField.required("server.port", ConfigType.INT, ConfigInt.of(80)));

        Config config = Config.root(definition).apply(ConfigPatch.empty()
            .with("default_port", ConfigInt.of(9000), ConfigSource.of(Layer.ENV, "AIMENAGERIE__DEFAULT_PORT")));

        ConfigValidator.Report report = ConfigValidator.build(definition, config);

        assertThat(report.errors()).extracting(ConfigError::kind)
            .containsExactly(ConfigError.Kind.UNUSED_AUXILIARY);
        assertThat(report.errors().get(0).report())
            .contains("path `default_port`")
            .contains("ENV:AIMENAGERIE__DEFAULT_PORT");
        assertThat(ConfigExitCode.of(report.errors().get(0), ConfigExitCode.Pipeline.STANDARD))
            .as("Y=3, ENV decade 3 -> 33").isEqualTo(33);
    }

    @Test
    @DisplayName("a rule on a key outside the schema is COMPARED — the type is `str` when nothing states one")
    void aRuleOnAnAuxiliaryIsCompared() {
        ConfigDefinition definition = ConfigDefinition.of(
            ConfigField.required("server.port", ConfigType.INT, ConfigInt.of(80)));
        ConfigPatch patch = ConfigPatch.empty().with("default_port", ConfigString.of("9000"),
            ConfigSource.of(Layer.ENV, "AIMENAGERIE__DEFAULT_PORT"), SourceDeclaration.constraints("in[80,443]"));

        assertThat(ConfigValidator.build(definition, Config.root(definition).apply(patch)).errors())
            .extracting(ConfigError::kind)
            .as("the RAW route: `9000` does not satisfy `in[80,443]`")
            .containsExactly(ConfigError.Kind.VALIDATION_FAILED);
        assertThat(ConfigValidator.build(definition,
            StandardPipeline.of(definition, TransformerRegistry.defaults(), patch)).errors())
            .extracting(ConfigError::kind)
            .as("the route every application builds (pipeline first) must show the same verdict")
            .containsExactly(ConfigError.Kind.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("a value the stated type refuses is visible in BOTH routes — a verdict nobody sees is a silent marker")
    void aRefusedValueIsVisibleInBothRoutes() {
        ConfigDefinition definition = ConfigDefinition.of(
            ConfigField.required("server.port", ConfigType.INT, ConfigInt.of(80)));
        ConfigPatch patch = ConfigPatch.empty().with("default_port", ConfigString.of("abc"),
            ConfigSource.of(Layer.ENV, "AIMENAGERIE__DEFAULT_PORT"), SourceDeclaration.type(ConfigType.INT));

        assertThat(ConfigValidator.build(definition, Config.root(definition).apply(patch)).errors())
            .extracting(ConfigError::kind).containsExactly(ConfigError.Kind.TRANSFORM_FAILED);
        assertThat(ConfigValidator.build(definition,
            StandardPipeline.of(definition, TransformerRegistry.defaults(), patch)).errors())
            .extracting(ConfigError::kind)
            .as("the application route showed NOTHING before — the guard on reachability took the problems with it")
            .containsExactly(ConfigError.Kind.TRANSFORM_FAILED);
    }

    @Test
    @DisplayName("a notation on a key outside the schema is visible in BOTH routes — `str` has no notation")
    void aNotationOnAnAuxiliaryIsVisibleInBothRoutes() {
        ConfigDefinition definition = ConfigDefinition.of(
            ConfigField.required("server.port", ConfigType.INT, ConfigInt.of(80)));
        ConfigPatch patch = ConfigPatch.empty().with("default_port", ConfigString.of("9000"),
            ConfigSource.of(Layer.ENV, "AIMENAGERIE__DEFAULT_PORT"), SourceDeclaration.config("upper"));

        assertThat(ConfigValidator.build(definition, Config.root(definition).apply(patch)).errors())
            .extracting(ConfigError::kind).containsExactly(ConfigError.Kind.INVALID_SYNTAX);
        assertThat(ConfigValidator.build(definition,
            StandardPipeline.of(definition, TransformerRegistry.defaults(), patch)).errors())
            .extracting(ConfigError::kind).containsExactly(ConfigError.Kind.INVALID_SYNTAX);
    }

    @Test
    @DisplayName("a key outside the schema with NO statement is not typed at all — the value stays as delivered")
    void withoutAStatementNothingIsTyped() {
        ConfigDefinition definition = ConfigDefinition.of(
            ConfigField.required("server.port", ConfigType.INT, ConfigInt.of(80)));
        ConfigPatch patch = ConfigPatch.empty().with("payload",
            ConfigMap.of(java.util.Map.of("a", ConfigInt.of(1))),
            ConfigSource.of(Layer.ENV, "AIMENAGERIE__PAYLOAD"));

        assertThat(ConfigValidator.build(definition, Config.root(definition).apply(patch)).errors())
            .extracting(ConfigError::kind)
            .as("the RAW route counts reachability — nothing leads to `payload`, but nothing is refused either")
            .containsExactly(ConfigError.Kind.UNUSED_AUXILIARY);
        assertThat(ConfigValidator.build(definition,
            StandardPipeline.of(definition, TransformerRegistry.defaults(), patch)).errors())
            .as("no statement means no cast — the map value is not refused as if it had to be a string")
            .isEmpty();
    }

    @Test
    @DisplayName("a problem INSIDE a value names the layer that stated the value — not `application code`")
    void aProblemInsideAValueNamesItsLayer() {
        ConfigDefinition definition = ConfigDefinition.of(ConfigField.seq("ports", ConfigType.INT));
        ConfigPatch yaml = ConfigPatch.empty().with("ports",
            ConfigSeq.of(List.of(ConfigString.of("80"), ConfigString.of("${missing}"))),
            ConfigSource.of(Layer.YAML, "application.yaml:4"));

        ConfigValidator.Report report =
            ConfigValidator.build(definition, StandardPipeline.of(definition, TransformerRegistry.defaults(), yaml));

        ConfigError problem = report.errors().get(0);
        assertThat(ConfigError.pathOf(problem).map(ConfigPath::canonical))
            .as("the element's own address").contains("ports.1");
        assertThat(problem.source().display())
            .as("an element has no entry of its own, so the nearest address that has one answers — "
                + "the file that stated `ports`, not `application code`")
            .isEqualTo("YAML:application.yaml:4");
        assertThat(ConfigExitCode.of(problem, ConfigExitCode.Pipeline.STANDARD))
            .as("and the exit code lands in the layer's decade (YAML: 2x), not in the application's (5x)")
            .isEqualTo(22);
    }

    @Test
    @DisplayName("emptiness inside a collection is an error too: `[{}, …]`, `[[], …]`, `[\"\", …]` — each names the member")
    void emptinessInsideACollectionIsAnError() {
        // A collection is HOMOGENEOUS and says what it holds, so each shape gets its own declaration.
        ConfigDefinition listOfLists = ConfigDefinition.of(ConfigField.seq("ports", ConfigType.SEQ));
        assertThat(errorsOf(listOfLists,
            ConfigSeq.of(List.of(ConfigSeq.empty(), ConfigSeq.of(List.of(ConfigInt.of(2)))))))
            .extracting(ConfigError::kind).as("an empty list as a member")
            .containsExactly(ConfigError.Kind.NULL_VALUE);

        ConfigDefinition listOfMaps = ConfigDefinition.of(ConfigField.seq("ports", ConfigType.MAP));
        assertThat(errorsOf(listOfMaps,
            ConfigSeq.of(List.of(ConfigMap.empty(), ConfigMap.of(Map.of("a", ConfigInt.of(1)))))))
            .extracting(ConfigError::kind).as("an empty map as a member")
            .containsExactly(ConfigError.Kind.NULL_VALUE);

        ConfigDefinition listOfTexts = ConfigDefinition.of(ConfigField.seq("ports", ConfigType.STR));
        assertThat(errorsOf(listOfTexts, ConfigSeq.of(List.of(ConfigString.of(""), ConfigString.of("x")))))
            .extracting(ConfigError::kind).as("an empty text as a member")
            .containsExactly(ConfigError.Kind.NULL_VALUE);
    }

    @Test
    @DisplayName("the NESTED address is the one reported: `[[{}]]` points at `ports.0.0`, not at `ports`")
    void theNestedAddressIsReported() {
        ConfigDefinition definition = ConfigDefinition.of(ConfigField.seq("ports", ConfigType.SEQ));

        ConfigError problem = errorsOf(definition,
            ConfigSeq.of(List.of(ConfigSeq.of(List.of(ConfigMap.empty()))))).get(0);

        assertThat(ConfigError.pathOf(problem).map(ConfigPath::canonical)).contains("ports.0.0");
    }

    @Test
    @DisplayName("an optional collection nobody stated is CORRECT — `additional_ports` with no value at all")
    void anUnstatedOptionalCollectionIsFine() {
        ConfigDefinition definition = ConfigDefinition.of(
            ConfigField.optionalSeq("additional_ports", ConfigType.STR));

        assertThat(ConfigValidator.build(definition, Config.root(definition)).errors())
            .as("optional and never set: there is nothing to complain about — that is what optional means")
            .isEmpty();
    }

    /** Runs the flow for one value under `ports` and returns the problems, in order. */
    private static List<ConfigError> errorsOf(ConfigDefinition definition, ConfigValue value) {
        ConfigPatch patch = ConfigPatch.empty()
            .with("ports", value, ConfigSource.of(Layer.YAML, "application.yaml:1"));
        return ConfigValidator.build(definition,
            StandardPipeline.of(definition, TransformerRegistry.defaults(), patch)).errors();
    }

    @Test
    @DisplayName("reset is not a value to validate — it belongs to lookup, not to value validation")
    void resetIsNotAValueToValidate() {
        ConfigValidator.Report report = ConfigValidator.validate(SCHEMA, List.of(
            new ConfigValidator.LayerInput(Layer.TUI, ConfigPatch.reset("server.port", ConfigSource.of(Layer.TUI, "reset")))));

        assertThat(report.hasErrors()).isFalse();
        assertThat(report.values()).isEmpty();
    }
}
