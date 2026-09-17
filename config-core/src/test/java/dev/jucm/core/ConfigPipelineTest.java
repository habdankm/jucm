package dev.jucm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The steps of the flow and their verdicts: the transformation from the declaration, checking **once**
 * per value, no doubled problems, and the scope of a run.
 */
class ConfigPipelineTest {

    private static final ConfigPath START_DATE = ConfigPath.of("start_date");
    private static final ConfigPath PORT = ConfigPath.of("server.port");
    private static final ConfigSource FROM_ENV = ConfigSource.of(Layer.ENV, "AIMENAGERIE__START_DATE");

    /** A declaration that states its notation, plus a field without a transformation, for contrast. */
    private static ConfigDefinition definition() {
        return ConfigDefinition.of(
            ConfigField.required("start_date", ConfigType.TIMESTAMP)
                .transformer("date").transformerConfig("yyyy-MM-dd HH:mm:ss"),
            ConfigField.required("server.port", ConfigType.INT, ConfigInt.of(8080)),
            ConfigField.optional("debug", ConfigType.BOOL));
    }

    private static Config withStartDate(String raw) {
        return Config.root(definition()).apply(ConfigPatch.empty().with("start_date", ConfigString.of(raw), FROM_ENV));
    }

    private static Config resolved(Config config) {
        return ConfigPipeline.all(definition(), TransformerRegistry.defaults()).apply(config);
    }

    @Nested
    @DisplayName("a collection types its MEMBERS — the element type belongs to the declaration")
    class Members {

        private Config flow(ConfigDefinition definition, ConfigPatch patch) {
            return ConfigPipeline.all(definition, TransformerRegistry.defaults())
                .apply(Config.root(definition).apply(patch));
        }

        private ConfigValue valueOf(Config config, String path) {
            ConfigResult<ConfigValue> found = config.get(ConfigPath.of(path));
            assertThat(found).as("expected a value under `%s`", path)
                .isInstanceOf(ConfigResult.ValueResult.class);
            return ((ConfigResult.ValueResult<ConfigValue>) found).value();
        }

        /** The problems the way the APPLICATION sees them: through the validator, not through `get`. */
        private List<ConfigError> problems(ConfigDefinition definition, Config config) {
            return ConfigValidator.build(definition, config).errors();
        }

        private ConfigPatch fromYaml(String path, ConfigValue value) {
            return ConfigPatch.empty().with(path, value, ConfigSource.of(Layer.YAML, "application.yaml:1"));
        }

        @Test
        @DisplayName("`seq<int>` types the elements by OUR notation: `010` is octal 8, `0x10` is 16, `1_000` is 1000")
        void elementsAreTypedByOurNotation() {
            ConfigDefinition definition = ConfigDefinition.of(ConfigField.seq("ports", ConfigType.INT));
            ConfigPatch patch = fromYaml("ports",
                ConfigSeq.of(List.of(ConfigString.of("010"), ConfigString.of("0x10"), ConfigString.of("1_000"))));

            assertThat(valueOf(flow(definition, patch), "ports"))
                .as("the carrier hands over TEXT; what it means is decided here, not by YAML")
                .isEqualTo(ConfigSeq.of(List.of(ConfigInt.of(8), ConfigInt.of(16), ConfigInt.of(1000))));
        }

        @Test
        @DisplayName("an element that does not fit fails AT ITS OWN ADDRESS — `ports.0`, and `1e3` is a FLOAT")
        void aWrongElementNamesItsAddress() {
            ConfigDefinition definition = ConfigDefinition.of(ConfigField.seq("ports", ConfigType.INT));
            ConfigPatch patch = fromYaml("ports",
                ConfigSeq.of(List.of(ConfigString.of("010"), ConfigString.of("1e3"))));

            List<ConfigError> problems = problems(definition, flow(definition, patch));

            assertThat(problems).hasSize(1);
            assertThat(ConfigError.pathOf(problems.get(0)).map(ConfigPath::canonical))
                .as("the member's own address, so the reader knows WHICH one").contains("ports.1");
            assertThat(problems.get(0).report())
                .as("`1e3` reads as `1000.0` — a FLOAT, and the element type is `int`")
                .contains("floating-point").contains("the field is `int`");
        }

        @Test
        @DisplayName("a map types its VALUES, and it checks its KEYS against the key type (`map<int, …>`)")
        void aMapTypesValuesAndChecksKeys() {
            ConfigDefinition byName = ConfigDefinition.of(
                ConfigField.map("weights", ConfigType.STR, ConfigType.INT));
            ConfigPatch patch = fromYaml("weights", ConfigMap.of(Map.of("a", ConfigString.of("1"))));

            assertThat(valueOf(flow(byName, patch), "weights"))
                .as("the value of the entry is typed, the key stays the address it was")
                .isEqualTo(ConfigMap.of(Map.of("a", ConfigInt.of(1))));

            ConfigDefinition numericKeys = ConfigDefinition.of(
                ConfigField.map("weights", ConfigType.INT, ConfigType.INT));
            ConfigPatch bad = fromYaml("weights", ConfigMap.of(Map.of("abc", ConfigString.of("1"))));

            assertThat(problems(numericKeys, flow(numericKeys, bad))).hasSize(1);
            assertThat(ConfigError.pathOf(problems(numericKeys, flow(numericKeys, bad)).get(0))
                .map(ConfigPath::canonical))
                .as("the key is an address, and the declaration says what that address must BE")
                .contains("weights.abc");
        }
    }

    @Nested
    @DisplayName("transformation: the notation stated by the declaration")
    class Transformation {

        @Test
        @DisplayName("an ENV value in the declared notation lands in the tree as a typed moment, with no error")
        void declaredNotationBecomesTypedValue() {
            ConfigValidator.Report report = ConfigValidator.build(definition(), withStartDate("2026-01-01 12:00:00"));

            assertThat(report.hasErrors()).isFalse();
            assertThat(report.values()).extracting(ConfigValidator.TypedValue::value)
                .contains(ConfigTimestamp.of(Instant.parse("2026-01-01T12:00:00Z")));
        }

        @Test
        @DisplayName("a run means verdicts on the entries, and its scope covers all FOUR steps")
        void runLeavesVerdictsAndScope() {
            Config resolved = resolved(withStartDate("2026-01-01 12:00:00"));

            assertThat(resolved.steps())
                .as("interpolation, transformation, cast and constraints ran over every entry")
                .containsExactly(ConfigStep.INTERPOLATION, ConfigStep.TRANSFORMATION, ConfigStep.CAST,
                    ConfigStep.CONSTRAINTS);

            Config.Entry entry = resolved.entry(START_DATE).orElseThrow();
            assertThat(entry.value()).isEqualTo(ConfigTimestamp.of(Instant.parse("2026-01-01T12:00:00Z")));
            assertThat(entry.verdict(ConfigStep.TRANSFORMATION).orElseThrow().isPassed()).isTrue();
            assertThat(entry.source().display()).as("the origin stays the source's, not the transformation's")
                .isEqualTo("ENV:AIMENAGERIE__START_DATE");
        }

        @Test
        @DisplayName("a field without a stated transformation gets a verdict too — \"no transformation\" is a decision")
        void undeclaredTransformationIsAlsoAResolution() {
            Config resolved = resolved(withStartDate("2026-01-01 12:00:00"));

            assertThat(resolved.entry(PORT).orElseThrow().verdict(ConfigStep.TRANSFORMATION))
                .as("the value goes to the cast as it is — and that is recorded").isPresent();
            assertThat(resolved.entry(PORT).orElseThrow().resolved(ConfigStep.TRANSFORMATION)).isTrue();
        }

        @Test
        @DisplayName("the default notation of a type works where the declaration stated nothing")
        void defaultNotationStillApplies() {
            Config config = Config.root(definition()).apply(ConfigPatch.empty()
                .with("server.port", ConfigString.of(" 9000 "), ConfigSource.of(Layer.YAML, "application.yaml:12")));

            Config resolved = resolved(config);

            assertThat(resolved.get(PORT)).as("` 9000 ` -> 9000, by the YAML notation")
                .isEqualTo(ConfigResult.value(ConfigInt.of(9000)));
        }
    }

    @Nested
    @DisplayName("a FAILED verdict: the message carries everything, and one mistake stays one problem")
    class Failures {

        @Test
        @DisplayName("a failed transformation is TRANSFORM_FAILED, naming the transformer and its configuration")
        void transformFailureCarriesTheNotation() {
            ConfigValidator.Report report = ConfigValidator.build(definition(), withStartDate("12.09.2026"));

            assertThat(report.errors()).extracting(ConfigError::kind)
                .as("the cast adds no second problem — the value was stopped earlier")
                .containsExactly(ConfigError.Kind.TRANSFORM_FAILED);
            ConfigError error = report.errors().get(0);
            assertThat(error.report())
                .contains("transformer `date`")
                .contains("(config `yyyy-MM-dd HH:mm:ss`)")
                .contains("value `12.09.2026`")
                .contains("path `start_date`")
                .contains("ENV:AIMENAGERIE__START_DATE");
            assertThat(ConfigExitCode.of(error, ConfigExitCode.Pipeline.STANDARD)).as("Y = 0 -> 30").isEqualTo(30);
        }

        @Test
        @DisplayName("FAILED does not remove the entry — the culprit value stays in the variant")
        void failedVerdictKeepsTheEntry() {
            Config resolved = resolved(withStartDate("12.09.2026"));

            Config.Entry entry = resolved.entry(START_DATE).orElseThrow();
            assertThat(entry.value()).as("the raw value, not the normalised one")
                .isEqualTo(ConfigString.of("12.09.2026"));
            assertThat(entry.problems()).extracting(ConfigError::kind)
                .containsExactly(ConfigError.Kind.TRANSFORM_FAILED);
            assertThat(resolved.steps()).as("the scope is incomplete: the stopped entry never reached the cast")
                .doesNotContain(ConfigStep.CAST);
        }

        @Test
        @DisplayName("an unknown transformer name is a MODEL error — loud at first use")
        void unknownTransformerNameIsALoudModelError() {
            ConfigDefinition broken = ConfigDefinition.of(
                ConfigField.required("start_date", ConfigType.TIMESTAMP).transformer("formater"));
            Config config = Config.root(broken).apply(ConfigPatch.empty()
                .with("start_date", ConfigString.of("2026-01-01"),
                    ConfigSource.of(Layer.ENV, "AIMENAGERIE__START_DATE")));

            assertThatIllegalArgumentException()
                .as("a declaration does not know the registry, so the name is checked at first use")
                .isThrownBy(() -> ConfigValidator.build(broken, config))
                .withMessageContaining("`formater`")
                .withMessageContaining("number");
        }

        @Test
        @DisplayName("empty means an error: an empty list stopped in the cast step, with a message about a collection")
        void emptinessStopsTheValueBeforeCast() {
            ConfigDefinition withList = ConfigDefinition.of(
                ConfigField.seq("features", ConfigType.STR, ConfigSeq.of(List.of(ConfigString.of("metrics")))));

            Config config = Config.root(withList).apply(ConfigPatch.empty()
                .with("features", ConfigSeq.empty(), ConfigSource.of(Layer.YAML, "application.yaml:7")));

            ConfigValidator.Report report = ConfigValidator.build(withList, config);

            assertThat(report.errors()).extracting(ConfigError::kind).containsExactly(ConfigError.Kind.NULL_VALUE);
            assertThat(report.errors().get(0).report()).contains("empty list");
        }
    }

    @Nested
    @DisplayName("checked once per value: a repeat is free")
    class Idempotence {

        @Test
        @DisplayName("a second run returns the SAME variant — there is nothing left to process")
        void secondRunIsFree() {
            Config once = resolved(withStartDate("2026-01-01 12:00:00"));
            Config twice = resolved(once);

            assertThat(twice).isSameAs(once);
        }

        @Test
        @DisplayName("an entry contributed programmatically after a run is processed exactly once")
        void programmaticEntryIsProcessedOnce() {
            Config once = resolved(withStartDate("2026-01-01 12:00:00"));
            Config grown = once.with("debug", ConfigString.of("true"));

            assertThat(grown.steps()).as("a fresh entry carries no verdicts yet").isEmpty();
            assertThat(resolved(grown).get("debug")).isEqualTo(ConfigResult.value(ConfigBool.of(true)));

            Config again = resolved(resolved(grown));
            assertThat(again.steps()).containsExactly(ConfigStep.INTERPOLATION, ConfigStep.TRANSFORMATION,
                ConfigStep.CAST, ConfigStep.CONSTRAINTS);
        }

        @Test
        @DisplayName("build() does not repeat validation — two calls give an identical report")
        void buildIsRepeatable() {
            Config config = withStartDate("12.09.2026");

            ConfigValidator.Report first = ConfigValidator.build(definition(), config);
            ConfigValidator.Report second = ConfigValidator.build(definition(), config);

            assertThat(second.errors()).isEqualTo(first.errors());
            assertThat(second.values()).isEqualTo(first.values());
        }
    }

    @Nested
    @DisplayName("scope: auxiliary keys and source markers")
    class Scope {

        @Test
        @DisplayName("a key outside the schema gets no verdict — the source marker states its notation")
        void auxiliaryHasNoVerdict() {
            Config config = Config.root(definition()).apply(ConfigPatch.empty()
                .with("default_port", ConfigInt.of(9000), ConfigSource.of(Layer.ENV, "AIMENAGERIE__DEFAULT_PORT")));

            Config resolved = resolved(config);

            assertThat(resolved.entry(ConfigPath.of("default_port")).orElseThrow().verdicts())
                .as("interpolation touches EVERY value, but with no declaration there is nothing to normalise")
                .containsOnlyKeys(ConfigStep.INTERPOLATION);
            assertThat(resolved.steps())
                .as("the scope is incomplete for three steps -> only interpolation is recorded")
                .containsExactly(ConfigStep.INTERPOLATION);
        }
    }

    @Nested
    @DisplayName("the types WE identify (`duration`, `uri`) carry their own notation and rule")
    class OurOwnTypes {

        @Test
        @DisplayName("`duration` without a stated notation: the text is normalised, because the notation is part of the type")
        void durationCarriesItsNotation() {
            ConfigDefinition definition = ConfigDefinition.of(
                ConfigField.required("timeout", ConfigType.DURATION, ConfigString.of("30s")));
            Config config = Config.root(definition).apply(ConfigPatch.empty()
                .with("timeout", ConfigString.of("1h30m"), ConfigSource.of(Layer.ENV, "AIMENAGERIE__TIMEOUT")));

            ConfigValidator.Report report = ConfigValidator.build(definition, config);

            assertThat(report.hasErrors()).as(report.describe()).isFalse();
            assertThat(report.values()).extracting(ConfigValidator.TypedValue::value)
                .as("canonical form, a single unit")
                .contains(ConfigString.of("90m"));
        }

        @Test
        @DisplayName("`duration` with something that is not a duration: TRANSFORM_FAILED, not a misleading \"expected str\"")
        void durationRejectsGarbage() {
            ConfigDefinition definition = ConfigDefinition.of(
                ConfigField.required("timeout", ConfigType.DURATION, ConfigString.of("30s")));
            Config config = Config.root(definition).apply(ConfigPatch.empty()
                .with("timeout", ConfigString.of("soon"), ConfigSource.of(Layer.ENV, "AIMENAGERIE__TIMEOUT")));

            ConfigValidator.Report report = ConfigValidator.build(definition, config);

            assertThat(report.errors()).extracting(ConfigError::kind)
                .containsExactly(ConfigError.Kind.TRANSFORM_FAILED);
            assertThat(report.errors().get(0).report())
                .contains("transformer `duration`")
                .contains("expected a number with a unit")
                .contains("ENV:AIMENAGERIE__TIMEOUT");
        }

        @Test
        @DisplayName("`uri` without a scheme: stopped already in the transformation, with a reason from the type's transformer")
        void uriWithoutSchemeIsStoppedInTransformation() {
            ConfigValidator.Report report = endpoint("api.openai.com");

            assertThat(report.errors()).extracting(ConfigError::kind)
                .containsExactly(ConfigError.Kind.TRANSFORM_FAILED);
            assertThat(report.errors().get(0).report()).contains("missing scheme");
        }

        @Test
        @DisplayName("`uri` with its default shape rule: `mailto:` passes the notation, but not the `://` scheme")
        void uriCarriesItsDefaultShapeRule() {
            ConfigValidator.Report report = endpoint("mailto:jan@example.com");

            assertThat(report.errors()).extracting(ConfigError::kind)
                .as("the notation passed (there is a scheme), so the type stops it — and says what it expects")
                .containsExactly(ConfigError.Kind.VALIDATION_FAILED);
            assertThat(report.errors().get(0).report())
                .contains("~`^[a-z][a-z0-9+.\\-]*://.*`")
                .contains("value `mailto:jan@example.com`");
        }

        private ConfigValidator.Report endpoint(String raw) {
            ConfigDefinition definition = ConfigDefinition.of(
                ConfigField.required("endpoint", ConfigType.URI, ConfigString.of("https://api.openai.com")));
            Config config = Config.root(definition).apply(ConfigPatch.empty()
                .with("endpoint", ConfigString.of(raw), ConfigSource.of(Layer.ENV, "AIMENAGERIE__ENDPOINT")));

            return ConfigValidator.build(definition, config);
        }
    }

    @Nested
    @DisplayName("the registry of the application enters the flow")
    class CustomRegistry {

        @Test
        @DisplayName("a custom transformer from the registry works through a declaration")
        void customTransformerFromRegistry() {
            TransformerRegistry registry = TransformerRegistry.defaults()
                .register("shout", () -> (raw, config) -> raw instanceof ConfigString text
                    ? Transformer.normalized(ConfigString.of(text.value().toUpperCase(java.util.Locale.ROOT)))
                    : Transformer.rejected("only text is accepted"));
            ConfigDefinition definition = ConfigDefinition.of(
                ConfigField.required("greeting", ConfigType.STR).transformer("shout"));
            Config config = Config.root(definition).apply(ConfigPatch.empty()
                .with("greeting", ConfigString.of("hello"), ConfigSource.of(Layer.ENV, "AIMENAGERIE__GREETING")));

            Config resolved = ConfigPipeline.all(definition, registry).apply(config);

            assertThat(resolved.get("greeting")).isEqualTo(ConfigResult.value(ConfigString.of("HELLO")));
            assertThat(ConfigValidator.build(definition, config, registry).hasErrors()).isFalse();
        }

        @Test
        @DisplayName("the constraints step stops a value after the type, with a verdict and the culprit value")
        void constraintsLeaveTheirVerdict() {
            ConfigDefinition definition = ConfigDefinition.of(
                ConfigField.required("transport_protocol", ConfigType.STR, ConfigString.of("https"))
                    .constrained(ConfigConstraint.Const("in[http,https]")));
            Config config = Config.root(definition).apply(ConfigPatch.empty()
                .with("transport_protocol", ConfigString.of("ftp"),
                    ConfigSource.of(Layer.ENV, "AIMENAGERIE__TRANSPORT_PROTOCOL")));

            Config resolved = ConfigPipeline.all(definition, TransformerRegistry.defaults()).apply(config);

            assertThat(resolved.entry(ConfigPath.of("transport_protocol")).orElseThrow().problems())
                .extracting(ConfigError::kind).containsExactly(ConfigError.Kind.VALIDATION_FAILED);
            assertThat(ConfigValidator.build(definition, config).errors())
                .extracting(ConfigError::kind).containsExactly(ConfigError.Kind.VALIDATION_FAILED);
        }
    }

    @Nested
    @DisplayName("layers: with() grows the current layer, apply() closes it")
    class Layers {

        @Test
        @DisplayName("two with() calls are ONE layer — the parent is untouched and the chain does not grow")
        void withGrowsTheCurrentLayer() {
            Config config = Config.root(definition())
                .with("debug", ConfigBool.of(true))
                .with("server.port", ConfigInt.of(9000));

            assertThat(config.layer()).isEqualTo(Layer.COMPILED);
            assertThat(config.ownPaths()).containsExactlyInAnyOrder(PORT, ConfigPath.of("debug"));
            assertThat(config.depth()).as("the root and nothing more — `with` creates no link").isEqualTo(1);
            assertThat(config.parent()).isEmpty();
        }

        @Test
        @DisplayName("apply() closes a layer: the previous variant becomes the layer underneath")
        void applyClosesTheLayer() {
            Config built = Config.root(definition())
                .with("debug", ConfigBool.of(true))
                .apply(ConfigPatch.empty().with("server.port", ConfigInt.of(9000), ConfigSource.of(Layer.YAML, "application.yaml:3")));

            assertThat(built.depth()).isEqualTo(2);
            assertThat(built.layer()).isEqualTo(Layer.YAML);
            assertThat(built.parent().orElseThrow().ownPaths()).contains(PORT, ConfigPath.of("debug"));
            assertThat(built.get(PORT)).as("the value from the default beats the entry in the layer below")
                .isEqualTo(ConfigResult.value(ConfigInt.of(9000)));
        }

        @Test
        @DisplayName("a snapshot in hand keeps its own picture — growing a layer does not change it")
        void earlierVariantKeepsItsOwnPicture() {
            Config open = Config.root(definition()).with("debug", ConfigBool.of(true));
            Config grown = open.with("debug", ConfigBool.of(false));

            assertThat(open.get("debug")).isEqualTo(ConfigResult.value(ConfigBool.of(true)));
            assertThat(grown.get("debug")).isEqualTo(ConfigResult.value(ConfigBool.of(false)));
            assertThat(open.ownPaths()).hasSize(2);
        }

        @Test
        @DisplayName("steps are plain Config -> Config functions — an application may compose a flow of its own")
        void stepsArePlainFunctions() {
            Function<Config, Config> transformation =
                ConfigPipeline.transformation(definition(), TransformerRegistry.defaults());
            Function<Config, Config> cast = ConfigPipeline.cast(definition(), TransformerRegistry.defaults());

            Config config = withStartDate("2026-01-01 12:00:00");
            Config afterTransformation = transformation.apply(config);

            assertThat(afterTransformation.get(START_DATE)).as("the transformation gives a value, not a type")
                .isEqualTo(ConfigResult.value(ConfigTimestamp.of(Instant.parse("2026-01-01T12:00:00Z"))));
            assertThat(afterTransformation.steps()).containsExactly(ConfigStep.TRANSFORMATION);
            assertThat(cast.apply(afterTransformation).steps())
                .containsExactly(ConfigStep.TRANSFORMATION, ConfigStep.CAST);
        }
    }

    @Test
    @DisplayName("normalisation instead of guessing — no more \"a different format\"")
    void endToEndExample() {
        ConfigValidator.Report report = ConfigValidator.build(definition(), withStartDate("2026-01-01 12:00:00"));

        assertThat(report.hasErrors()).isFalse();
        assertThat(report.values()).extracting(ConfigValidator.TypedValue::value)
            .contains(ConfigTimestamp.of(Instant.parse("2026-01-01T12:00:00Z")));
        assertThat(report.describe()).contains("start_date = 2026-01-01T12:00:00Z");
    }
}
