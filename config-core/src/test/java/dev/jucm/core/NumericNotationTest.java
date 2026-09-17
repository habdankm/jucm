package dev.jucm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The numeric family: `number` (notation + locale), `size` and `percentage`.
 *
 * <p>The rule tying them together: **a conversion works where a numeric type is expected** — on a `str`
 * field text stays text, and a numeric transformer on a non-numeric field is a model error.
 */
class NumericNotationTest {

    private static final ConfigSource FROM_ENV = ConfigSource.of(Layer.ENV, "AIMENAGERIE__V");

    /** The flow that matters here: the steps of the pipeline — the transformation from the declaration, then the cast. */
    private static Config resolved(ConfigField field, String raw) {
        ConfigDefinition definition = ConfigDefinition.of(field);
        Config config = Config.root(definition).apply(ConfigPatch.empty()
            .with(field.path(), ConfigString.of(raw), FROM_ENV));
        return ConfigPipeline.all(definition, TransformerRegistry.defaults()).apply(config);
    }

    private static ConfigValidator.Report report(ConfigField field, String raw) {
        ConfigDefinition definition = ConfigDefinition.of(field);
        Config config = Config.root(definition).apply(ConfigPatch.empty()
            .with(field.path(), ConfigString.of(raw), FROM_ENV));
        return ConfigValidator.build(definition, config);
    }

    private static ConfigValue value(ConfigField field, String raw) {
        ConfigResult<ConfigValue> got = resolved(field, raw).get(field.path());
        assertThat(got).as("expected a value for `%s`", raw).isInstanceOf(ConfigResult.ValueResult.class);
        return ((ConfigResult.ValueResult<ConfigValue>) got).value();
    }

    private static String reason(ConfigField field, String raw) {
        ConfigValidator.Report report = report(field, raw);
        assertThat(report.errors()).as("expected a problem for `%s`", raw).hasSize(1);
        return report.errors().get(0).report();
    }

    private static ConfigError.Kind kind(ConfigField field, String raw) {
        ConfigValidator.Report report = report(field, raw);
        assertThat(report.errors()).as("expected a problem for `%s`", raw).hasSize(1);
        return report.errors().get(0).kind();
    }

    @Nested
    @DisplayName("`number`: the notation of texts agrees with what YAML decides by itself")
    class Notation {

        @Test
        @DisplayName("an exponent is a float — `x: 1e6` and ENV `1e6` mean the same")
        void exponentIsAFloat() {
            assertThat(value(ConfigField.required("v", ConfigType.FLOAT), "1e6"))
                .isEqualTo(ConfigFloat.of(1_000_000.0));
            assertThat(value(ConfigField.required("v", ConfigType.FLOAT), "1e-3"))
                .isEqualTo(ConfigFloat.of(0.001));
            assertThat(kind(ConfigField.required("v", ConfigType.INT), "1e6"))
                .as("on an `int` field an exponent bounces off — exactly as in YAML").isEqualTo(ConfigError.Kind.CAST_FAILED);
        }

        @Test
        @DisplayName("the YAML notations stay: `010` = 8, `0xFF`, `0b1010`, `1_000`, `+100`")
        void yamlNotationsStay() {
            ConfigField integral = ConfigField.required("v", ConfigType.INT);

            assertThat(value(integral, "010")).isEqualTo(ConfigInt.of(8));
            assertThat(value(integral, "0xFF")).isEqualTo(ConfigInt.of(255));
            assertThat(value(integral, "0b1010")).isEqualTo(ConfigInt.of(10));
            assertThat(value(integral, "1_000")).isEqualTo(ConfigInt.of(1000));
            assertThat(value(integral, "+100")).isEqualTo(ConfigInt.of(100));
            assertThat(value(integral, "-100")).isEqualTo(ConfigInt.of(-100));
        }

        @Test
        @DisplayName("`0o755` is NOT accepted — YAML reads it as a string, and we do not invent a dialect")
        void octalWithLetterIsNotOurs() {
            assertThat(reason(ConfigField.required("v", ConfigType.INT), "0o755"))
                .contains("not a number in YAML notation");
        }

        @Test
        @DisplayName("`1,000` with no configuration: rejected WITH a reason that says what is missing")
        void ambiguousSeparatorPointsAtLocale() {
            assertThat(reason(ConfigField.required("v", ConfigType.INT), "1,000"))
                .as("the user has to know that it is a matter of locale — not that this is not a number")
                .contains("the separator is ambiguous")
                .contains("transformerConfig(\"pl_PL\")");
        }
    }

    @Nested
    @DisplayName("`number` + a locale: the custom of the country decides the separator")
    class Locale {

        private ConfigField asInt(String locale) {
            return ConfigField.required("v", ConfigType.INT).transformer("number").transformerConfig(locale);
        }

        private ConfigField asFloat(String locale) {
            return ConfigField.required("v", ConfigType.FLOAT).transformer("number").transformerConfig(locale);
        }

        @Test
        @DisplayName("`en_US`: `1,000` is one thousand while `1,5` is an error (a separator in the wrong place)")
        void enUs() {
            assertThat(value(asInt("en_US"), "1,000")).isEqualTo(ConfigInt.of(1000));
            assertThat(value(asFloat("en_US"), "1,000.50")).isEqualTo(ConfigFloat.of(1000.5));
            assertThat(reason(asInt("en_US"), "1,5"))
                .contains("grouping separator in the wrong place");
        }

        @Test
        @DisplayName("`da_DK`: `1.000,00` is a thousand with change (the dot groups, the comma divides)")
        void daDk() {
            assertThat(value(asFloat("da_DK"), "1.000,00")).isEqualTo(ConfigFloat.of(1000.0));
            assertThat(value(asFloat("da_DK"), "1,5")).isEqualTo(ConfigFloat.of(1.5));
            assertThat(reason(asInt("da_DK"), "1.5")).contains("grouping separator in the wrong place");
        }

        @Test
        @DisplayName("`pl_PL`: grouping uses a non-breaking space — we accept the whole FAMILY of spaces")
        void plPlAcceptsTheSpaceFamily() {
            assertThat(value(asInt("pl_PL"), "1 000")).as("a plain space").isEqualTo(ConfigInt.of(1000));
            assertThat(value(asInt("pl_PL"), "1\u00A0000")).as("U+00A0 from CLDR").isEqualTo(ConfigInt.of(1000));
            assertThat(value(asFloat("pl_PL"), "1,5")).as("the comma is the decimal separator there")
                .isEqualTo(ConfigFloat.of(1.5));
            assertThat(value(asFloat("pl_PL"), "1 000,25")).isEqualTo(ConfigFloat.of(1000.25));
        }

        @Test
        @DisplayName("`pl_PL`: `1,000` is ONE with zeros — and on an `int` field it bounces off as a fractional part")
        void plPlCommaIsNotGrouping() {
            assertThat(kind(asInt("pl_PL"), "1,000"))
                .as("grouping is a space, so the comma means the fractional part — an `int` field catches that")
                .isEqualTo(ConfigError.Kind.CAST_FAILED);
            assertThat(value(asFloat("pl_PL"), "1,000")).isEqualTo(ConfigFloat.of(1.0));
        }

        @Test
        @DisplayName("the identifier MUST be a `language_REGION` locale — otherwise it is a model error, not a value")
        void identifierMustBeALocale() {
            assertThatIllegalArgumentException()
                .as("a bad configuration is the declaration's fault, not the data's")
                .isThrownBy(() -> value(asInt("not-a-locale"), "1000"))
                .withMessageContaining("language_REGION")
                .withMessageContaining("not-a-locale");

            assertThatIllegalArgumentException()
                .as("with no region there are no separators, so there is no locale")
                .isThrownBy(() -> value(asInt("pl"), "1000"))
                .withMessageContaining("language_REGION");
        }
    }

    @Nested
    @DisplayName("`size`: a size becomes a number of bytes")
    class Size {

        private ConfigField size() {
            return ConfigField.required("max_payload", ConfigType.INT).transformer("size");
        }

        @Test
        @DisplayName("`10MB` is decimal, `2GiB` is binary — one number, with no guessing of the base")
        void decimalAndBinary() {
            assertThat(value(size(), "10MB")).isEqualTo(ConfigInt.of(10_000_000L));
            assertThat(value(size(), "2GiB")).isEqualTo(ConfigInt.of(2_147_483_648L));
            assertThat(value(size(), "1.5MB")).as("a decimal point, with no rounding").isEqualTo(ConfigInt.of(1_500_000L));
            assertThat(value(size(), "512B")).isEqualTo(ConfigInt.of(512));
        }

        @Test
        @DisplayName("a unit outside the dictionary is an error with the list — `10M` does not say which base")
        void unknownUnitIsAnError() {
            assertThat(reason(size(), "10M"))
                .contains("with the `B` suffix, because decimal and binary are not the same")
                .contains("MiB");
            assertThat(reason(size(), "10mb")).as("case matters").contains("`mb`");
            assertThat(reason(size(), "lots")).contains("expected a number with a unit");
        }

        @Test
        @DisplayName("fractional bytes are an error, and a locale does not enter `size` (a decimal point)")
        void fractionalBytesAndLocale() {
            assertThat(reason(size(), "0.5B")).contains("is a fraction of a byte");
            assertThat(reason(size(), "1,5MB")).contains("is not a number");
        }

        @Test
        @DisplayName("`size` on a `str` field is a MODEL error — the conversion only makes sense for a numeric type")
        void sizeNeedsANumericField() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> value(ConfigField.required("v", ConfigType.STR).transformer("size"), "10MB"))
                .withMessageContaining("this transformer works on types")
                .withMessageContaining("[int]");
        }
    }

    @Nested
    @DisplayName("`percentage`: a percentage becomes a number")
    class Percentage {

        private ConfigField percentage() {
            return ConfigField.required("cache_ratio", ConfigType.FLOAT).transformer("percentage");
        }

        @Test
        @DisplayName("`10%` becomes a fraction of the whole")
        void percentBecomesAFraction() {
            assertThat(value(percentage(), "10%")).isEqualTo(ConfigFloat.of(0.1));
            assertThat(value(percentage(), "100%")).isEqualTo(ConfigFloat.of(1.0));
            assertThat(value(percentage(), "0.5%")).isEqualTo(ConfigFloat.of(0.005));
        }

        @Test
        @DisplayName("`%` is mandatory and without spaces — a space means something, so we do not guess it")
        void percentSignIsRequired() {
            assertThat(reason(percentage(), "10")).contains("expected a number with `%`");
            assertThat(reason(percentage(), "10 %")).as("a space inside is not our notation")
                .contains("no spaces");
        }

        @Test
        @DisplayName("`percentage` on an `int` field is a MODEL error (the result has a fractional part)")
        void percentageNeedsFloat() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> value(ConfigField.required("v", ConfigType.INT).transformer("percentage"), "10%"))
                .withMessageContaining("this transformer works on types")
                .withMessageContaining("[float]");
        }
    }

    @Nested
    @DisplayName("a configuration the transformer does not know is rejected LOUDLY")
    class UnknownConfiguration {

        @Test
        @DisplayName("`trim`, `uri`, `size` and `percentage` have no configuration — providing one is a model error")
        void transformersWithoutConfigurationRejectIt() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> value(ConfigField.required("v", ConfigType.STR)
                    .transformer("trim").transformerConfig("anything"), " x "))
                .withMessageContaining("`trim` has no configuration");

            assertThatIllegalArgumentException()
                .isThrownBy(() -> value(ConfigField.required("v", ConfigType.INT)
                    .transformer("size").transformerConfig("MB"), "10"))
                .withMessageContaining("transformer `size` on field `v`:");
        }

        @Test
        @DisplayName("`number` does have one — a locale — so it understands a configuration, but only a locale one")
        void numberUnderstandsOnlyLocale() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> value(ConfigField.required("v", ConfigType.INT)
                    .transformer("number").transformerConfig("quickly"), "1000"))
                .withMessageContaining("transformer `number` on field `v`:")
                .withMessageContaining("language_REGION");
        }
    }
}
