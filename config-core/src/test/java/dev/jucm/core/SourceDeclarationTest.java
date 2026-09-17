package dev.jucm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What a source **stated** about an entry: the type (`___TYPE`, `--x--type`) and the transformation
 * configuration (`___TCONF`, `--x--tconf`). A source says **how to read**, never **with what** — the name
 * of a notation comes from the declaration in code or from the type. That is how an auxiliary key
 * carrying a statement gets a verdict, while one without a statement still has nothing to decide.
 */
class SourceDeclarationTest {

    private static final ConfigSource FROM_ENV = ConfigSource.of(Layer.ENV, "AIMENAGERIE__CUSTOM___TYPE");
    private static final ConfigDefinition NO_SCHEMA = ConfigDefinition.of();

    private static ConfigValue valueOf(Config config, String path) {
        ConfigResult<ConfigValue> found = config.get(path);
        return found.isValue() ? ((ConfigResult.ValueResult<ConfigValue>) found).value() : null;
    }

    private static Config flowed(ConfigDefinition definition, ConfigPatch patch) {
        return ConfigPipeline.all(definition, TransformerRegistry.defaults())
            .apply(Config.root(definition).apply(patch));
    }

    private static ConfigValidator.Report build(ConfigDefinition definition, ConfigPatch patch) {
        return ConfigValidator.build(definition, Config.root(definition).apply(patch));
    }

    @Nested
    @DisplayName("an entry carries the statement of the source")
    class Carried {

        @Test
        @DisplayName("a patch and an entry keep the type and the notation configuration from the marker")
        void patchAndEntryKeepIt() {
            SourceDeclaration declared = SourceDeclaration.of(ConfigType.TIMESTAMP, "yyyy-MM-dd HH:mm:ss");
            ConfigPatch patch = ConfigPatch.empty()
                .with("start_date", ConfigString.of("2026-03-07 08:00:00"), FROM_ENV, declared);

            Config config = Config.root(NO_SCHEMA).apply(patch);

            assertThat(config.entry(ConfigPath.of("start_date")).orElseThrow().declaration()).isEqualTo(declared);
            assertThat(patch.operation("start_date").orElseThrow()).isInstanceOf(ConfigPatch.SetValue.class);
        }

        @Test
        @DisplayName("a source does NOT express the name of a transformer — the record carries a configuration "
            + "and a rule, never a name")
        void sourceNeverNamesTheTransformer() {
            assertThat(SourceDeclaration.class.getRecordComponents())
                .as("type + configuration + rule + what a collection HOLDS — never a NAME: the name belongs to "
                    + "the registry, and the registry to the application")
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("type", "transformerConfig", "constraints", "elementType", "keyType");
        }

        @Test
        @DisplayName("equality of patches includes the statement — a marker changes the EFFECT, so the patch "
            + "is not the same")
        void equalityIncludesDeclaration() {
            ConfigPatch plain = ConfigPatch.empty().with("x", ConfigString.of("1"));
            ConfigPatch declared = ConfigPatch.empty().with("x", ConfigString.of("1"), FROM_ENV,
                SourceDeclaration.type(ConfigType.INT));

            assertThat(declared).as("a different effect -> a different patch").isNotEqualTo(plain);
            assertThat(declared).isEqualTo(ConfigPatch.empty().with("x", ConfigString.of("1"),
                ConfigSource.application(), SourceDeclaration.type(ConfigType.INT)));
        }

        @Test
        @DisplayName("an entry without a statement carries `none()`, and a configuration without content "
            + "is rejected")
        void noneAndEmptyConfig() {
            Config config = Config.root(NO_SCHEMA).apply(ConfigPatch.empty().with("x", ConfigString.of("1")));

            assertThat(config.entry(ConfigPath.of("x")).orElseThrow().declaration().isNone()).isTrue();
            assertThatIllegalArgumentException()
                .isThrownBy(() -> SourceDeclaration.config(""))
                .withMessageContaining("without content means nothing");
        }
    }

    @Nested
    @DisplayName("the steps of the flow see the statement of the source (a key outside the schema)")
    class Flow {

        @Test
        @DisplayName("`___TYPE=int` types a key outside the schema: `010` is octal 8")
        void typeFromSourceTypesTheKey() {
            Config result = flowed(NO_SCHEMA, ConfigPatch.empty().with("custom_port", ConfigString.of("010"),
                FROM_ENV, SourceDeclaration.type(ConfigType.INT)));

            assertThat(valueOf(result, "custom_port")).isEqualTo(ConfigInt.of(8));
            assertThat(result.entry(ConfigPath.of("custom_port")).orElseThrow().verdict(ConfigStep.CAST))
                .as("the verdict of the cast step — an auxiliary key carrying a type gets one").isPresent();
        }

        @Test
        @DisplayName("`___TYPE=timestamp` + `___TCONF=format` reads a date written in the stated notation")
        void typeAndConfigFromSource() {
            Config result = flowed(NO_SCHEMA, ConfigPatch.empty().with("start_date",
                ConfigString.of("07.03.2026"), FROM_ENV,
                SourceDeclaration.of(ConfigType.TIMESTAMP, "dd.MM.yyyy")));

            assertThat(valueOf(result, "start_date")).isEqualTo(ConfigTimestamp.of(Instant.parse("2026-03-07T00:00:00Z")));
        }

        @Test
        @DisplayName("`___TCONF` supplies the format itself when the declaration has none")
        void configFromSourceFillsTheDeclaration() {
            ConfigDefinition definition = ConfigDefinition.of(
                ConfigField.required("start_date", ConfigType.TIMESTAMP));
            Config result = flowed(definition, ConfigPatch.empty().with("start_date",
                ConfigString.of("07.03.2026"), FROM_ENV, SourceDeclaration.config("dd.MM.yyyy")));

            assertThat(valueOf(result, "start_date")).isEqualTo(ConfigTimestamp.of(Instant.parse("2026-03-07T00:00:00Z")));
        }

        @Test
        @DisplayName("a statement without a type is read as `str` — the type is never missing, so the error is "
            + "about the NOTATION, not about the type")
        void statementWithoutTypeIsReadAsString() {
            ConfigValidator.Report report = build(NO_SCHEMA, ConfigPatch.empty()
                .with("orphan", ConfigString.of("x"), FROM_ENV, SourceDeclaration.config("dd.MM.yyyy")));

            assertThat(report.errors()).extracting(ConfigError::kind)
                .contains(ConfigError.Kind.INVALID_SYNTAX);
            assertThat(report.errors().get(0).report())
                .contains("___TCONF")
                .contains("`STR` has no notation")
                .as("`str` by default — NOT \"a key outside the schema without `___TYPE`\"")
                .doesNotContain("without `___TYPE`");
        }

        @Test
        @DisplayName("a configuration on a type WITHOUT a notation (`str`) is loud too, not silently skipped")
        void configOnTypeWithoutNotationIsLoud() {
            ConfigValidator.Report report = build(NO_SCHEMA, ConfigPatch.empty()
                .with("x", ConfigString.of("1"), FROM_ENV,
                    SourceDeclaration.of(ConfigType.STR, "dd.MM.yyyy")));

            assertThat(report.errors()).extracting(ConfigError::kind)
                .contains(ConfigError.Kind.INVALID_SYNTAX);
            assertThat(report.errors().get(0).report()).contains("has no notation");
        }

        @Test
        @DisplayName("a configuration the notation does not know is a MODEL error — loud at first use")
        void configUnknownToTheNotationIsAModelError() {
            assertThatIllegalArgumentException()
                .as("`number` takes a locale, not a date format — checked at first use")
                .isThrownBy(() -> flowed(NO_SCHEMA, ConfigPatch.empty()
                    .with("x", ConfigString.of("1"), FROM_ENV,
                        SourceDeclaration.of(ConfigType.INT, "dd.MM.yyyy"))))
                .withMessageContaining("number");
        }

        @Test
        @DisplayName("`___CONST` gives a rule to a key outside the schema — with a type from `___TYPE` there "
            + "is something to compare")
        void constraintsFromSourceAreEnforced() {
            SourceDeclaration spoken = SourceDeclaration.type(ConfigType.INT)
                .mergedWith(SourceDeclaration.constraints(">=1&<=10"));

            Config ok = flowed(NO_SCHEMA, ConfigPatch.empty()
                .with("retries", ConfigString.of("5"), FROM_ENV, spoken));
            assertThat(valueOf(ok, "retries")).isEqualTo(ConfigInt.of(5));

            ConfigValidator.Report bad = build(NO_SCHEMA, ConfigPatch.empty()
                .with("retries", ConfigString.of("12"), FROM_ENV, spoken));

            assertThat(bad.errors()).extracting(ConfigError::kind)
                .as("the type matches (12 is a number), but the rule from the source does not")
                .contains(ConfigError.Kind.VALIDATION_FAILED);
            assertThat(bad.errors().get(0).report()).contains(">=1&<=10").contains("retries");
        }

        @Test
        @DisplayName("a rule from the source is PARSED while the statement is built — a broken chain does not "
            + "wait for validation")
        void brokenConstraintChainIsRejectedAtTheSource() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> SourceDeclaration.constraints("<2>0"))
                .withMessageContaining("constraint chain `<2>0`");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> SourceDeclaration.constraints("   "))
                .withMessageContaining("without conditions means nothing");
        }

        @Test
        @DisplayName("a rule without a type says WHAT is missing — `needsType()` is the one place for adapters")
        void constraintWithoutTypeNeedsAType() {
            assertThat(SourceDeclaration.constraints(">0").needsType())
                .as("with no type there is nothing to compare — an adapter reports it as an entry error")
                .contains(">0");
            assertThat(SourceDeclaration.type(ConfigType.INT)
                .mergedWith(SourceDeclaration.constraints(">0")).needsType()).isEmpty();
        }
    }

    @Nested
    @DisplayName("a declaration from CODE wins over a statement in the source")
    class Precedence {

        @Test
        @DisplayName("the notation configuration from code cannot be overridden by `___TCONF`")
        void codeConfigWins() {
            ConfigDefinition definition = ConfigDefinition.of(
                ConfigField.required("start_date", ConfigType.TIMESTAMP)
                    .transformer("date").transformerConfig("dd.MM.yyyy"));
            Config result = flowed(definition, ConfigPatch.empty().with("start_date",
                ConfigString.of("07.03.2026"), FROM_ENV, SourceDeclaration.config("MM.dd.yyyy")));

            assertThat(valueOf(result, "start_date"))
                .as("code says day.month; the source wanted month.day — code wins")
                .isEqualTo(ConfigTimestamp.of(Instant.parse("2026-03-07T00:00:00Z")));
        }

        @Test
        @DisplayName("the type of a field from code cannot be overridden by `___TYPE` from the source")
        void codeTypeWins() {
            ConfigDefinition definition = ConfigDefinition.of(ConfigField.required("port", ConfigType.INT));
            Config result = flowed(definition, ConfigPatch.empty().with("port", ConfigString.of("8080"),
                FROM_ENV, SourceDeclaration.type(ConfigType.URI)));

            assertThat(valueOf(result, "port")).as("the type comes from code, not from ENV").isEqualTo(ConfigInt.of(8080));
        }

        @Test
        @DisplayName("`___CONST` REPLACES the rules from code — the rule from the source is the one in force")
        void sourceRulesReplaceTheCode() {
            ConfigDefinition definition = ConfigDefinition.of(
                ConfigField.required("retries", ConfigType.INT).constrained(ConfigConstraint.Const(">=100")));

            ConfigValidator.Report report = build(definition, ConfigPatch.empty()
                .with("retries", ConfigString.of("5"), FROM_ENV, SourceDeclaration.constraints(">=1")));
            assertThat(report.errors()).as("the source replaced `>=100` with `>=1`, so 5 passes").isEmpty();

            ConfigValidator.Report again = build(definition, ConfigPatch.empty()
                .with("retries", ConfigString.of("0"), FROM_ENV, SourceDeclaration.constraints(">=1")));
            assertThat(again.errors()).extracting(ConfigError::kind)
                .as("and the rule from the source really holds").contains(ConfigError.Kind.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("the TYPE's default rule stays — a marker replaces the rules of the FIELD, not of the type")
        void typeDefaultRuleSurvives() {
            ConfigDefinition definition = ConfigDefinition.of(
                ConfigField.required("endpoint", ConfigType.URI).constrained(ConfigConstraint.Const("<20")));

            ConfigValidator.Report report = build(definition, ConfigPatch.empty()
                .with("endpoint", ConfigString.of("no-scheme"), FROM_ENV,
                    SourceDeclaration.constraints("in[no-scheme]")));

            assertThat(report.errors()).extracting(ConfigError::kind)
                .as("the rule of the `uri` type (a scheme and `://`) still holds — the type's step stops the value")
                .containsAnyOf(ConfigError.Kind.VALIDATION_FAILED, ConfigError.Kind.TRANSFORM_FAILED);
        }

        @Test
        @DisplayName("with no statement from the source nothing changed: an auxiliary key is still `unused-auxiliary`")
        void withoutDeclarationNothingChanges() {
            ConfigValidator.Report report = build(NO_SCHEMA,
                ConfigPatch.empty().with("orphan", ConfigString.of("x"), FROM_ENV));

            assertThat(report.errors()).extracting(ConfigError::kind)
                .contains(ConfigError.Kind.UNUSED_AUXILIARY);
        }
    }

    @Test
    @DisplayName("`holding(value)`: a carrier that READ the value states what its members are")
    void holdingStatesTheMemberType() {
        SourceDeclaration seq = SourceDeclaration.type(ConfigType.SEQ);

        assertThat(seq.holding(ConfigSeq.of(List.of(ConfigString.of("a"), ConfigString.of("b")))).orElseThrow()
            .elementType()).as("text members").contains(ConfigType.STR);
        assertThat(seq.holding(ConfigSeq.of(List.of(ConfigSeq.empty()))).orElseThrow().elementType())
            .as("list members — without this a list of lists would be cast as text and refused")
            .contains(ConfigType.SEQ);
        assertThat(seq.holding(ConfigSeq.of(List.of(ConfigString.of("a"), ConfigSeq.empty()))))
            .as("mixed members: no type to state, so the carrier reports it").isEmpty();
        assertThat(seq.holding(ConfigString.of("abc")).orElseThrow().elementType())
            .as("the value is not a collection (a `seq` marker over text): the cast refuses it anyway")
            .contains(ConfigType.STR);
    }
}
