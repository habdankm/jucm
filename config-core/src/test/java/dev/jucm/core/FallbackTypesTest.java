package dev.jucm.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * `??`: the type of an operand comes from its **form**, and every operand must fit the type of the value.
 *
 * <p>A literal has no declaration behind it, so its form is the only thing that can speak - `9999` is `int`,
 * `"9999"` is `str`, exactly as the form of a YAML node speaks for a key outside the schema. An address takes
 * its type from the declaration in code, and for a key outside the schema from its statement - and `str` when
 * it states nothing. A mismatch is an ERROR, and so is a fallback that is taken and delivers nothing: `??`
 * must never hand over the layer below in silence.
 */
class FallbackTypesTest {

    private static final ConfigSource YAML = ConfigSource.of(Layer.YAML, "application.yaml:2");

    /** `port:int` / `name:str` with a default, plus `extra` of the field's own type and an optional fallback. */
    private static ConfigDefinition model(String field, ConfigType type, ConfigValue defaultValue,
                                          ConfigType extraType, ConfigType fallbackType) {
        List<ConfigField> fields = new java.util.ArrayList<>();
        fields.add(ConfigField.required(field, type, defaultValue));
        fields.add(ConfigField.optional("extra", extraType));
        if (fallbackType == ConfigType.INT) {
            fields.add(ConfigField.required("fallback", ConfigType.INT, ConfigInt.of(8443)));
        } else if (fallbackType == ConfigType.STR) {
            fields.add(ConfigField.required("fallback", ConfigType.STR, ConfigString.of("http")));
        }
        return ConfigDefinition.of(fields.toArray(new ConfigField[0]));
    }

    private static ConfigDefinition intField(ConfigType extraType, ConfigType fallbackType) {
        return model("port", ConfigType.INT, ConfigInt.of(8080), extraType, fallbackType);
    }

    private static ConfigDefinition strField(ConfigType extraType, ConfigType fallbackType) {
        return model("name", ConfigType.STR, ConfigString.of("default-name"), extraType, fallbackType);
    }

    private static Config flow(ConfigDefinition definition, String field, String value) {
        ConfigPatch patch = ConfigPatch.empty().with(field, ConfigString.of(value), YAML);
        return StandardPipeline.of(definition, TransformerRegistry.defaults(), patch);
    }

    private static List<ConfigError> problems(ConfigDefinition definition, Config config) {
        return ConfigValidator.build(definition, config).errors();
    }

    private static ConfigValue valueOf(ConfigDefinition definition, Config config, String field) {
        ConfigResult<ConfigValue> read = config.get(ConfigPath.of(field));
        assertThat(read).as("expected a value under `%s`", field).isInstanceOf(ConfigResult.ValueResult.class);
        return ((ConfigResult.ValueResult<ConfigValue>) read).value();
    }

    @Test
    @DisplayName("a NUMBER is an `int` literal: `port:int` + `?? 9999` delivers 9999 when the left is empty")
    void numberLiteralFitsAnIntField() {
        ConfigDefinition definition = intField(ConfigType.INT, null);

        assertThat(valueOf(definition, flow(definition, "port", "${extra ?? 9999}"), "port"))
            .isEqualTo(ConfigInt.of(9999));
        assertThat(problems(definition, flow(definition, "port", "${extra ?? 9999}")))
            .as("no problem: `9999` is `int`, and the value is `int`").isEmpty();
    }

    @Test
    @DisplayName("a QUOTED text is a `str` literal: `\"9999\"` fits a `str` field and does NOT fit an `int` one")
    void quotedTextIsAStrLiteral() {
        ConfigDefinition text = strField(ConfigType.STR, null);
        assertThat(valueOf(text, flow(text, "name", "${extra ?? \"9999\"}"), "name"))
            .as("a text literal keeps its characters").isEqualTo(ConfigString.of("9999"));
        assertThat(problems(text, flow(text, "name", "${extra ?? \"9999\"}"))).isEmpty();

        ConfigDefinition number = intField(ConfigType.INT, null);
        ConfigError problem = problems(number, flow(number, "port", "${extra ?? \"9999\"}")).get(0);
        assertThat(problem.kind()).isEqualTo(ConfigError.Kind.RULE_MISMATCH);
        assertThat(problem.message())
            .as("the form of the literal is what made it `str`")
            .contains("\"9999\"").contains("its form makes it `str`").contains("`int`");
    }

    @Test
    @DisplayName("a bare NUMBER on a `str` field is a mismatch — the form decides, not the field's wish")
    void numberLiteralDoesNotFitAStrField() {
        ConfigDefinition definition = strField(ConfigType.STR, null);
        ConfigError problem = problems(definition, flow(definition, "name", "${extra ?? 9999}")).get(0);

        assertThat(problem.message()).contains("9999").contains("`int`").contains("`str`");
    }

    @Test
    @DisplayName("an ADDRESS takes its type from the declaration: `fallback:int` works, `fallback:str` is a mismatch")
    void anAddressTakesItsTypeFromTheDeclaration() {
        ConfigDefinition fits = intField(ConfigType.INT, ConfigType.INT);
        assertThat(valueOf(fits, flow(fits, "port", "${extra ?? fallback}"), "port"))
            .isEqualTo(ConfigInt.of(8443));
        assertThat(problems(fits, flow(fits, "port", "${extra ?? fallback}"))).isEmpty();

        ConfigDefinition mismatch = intField(ConfigType.INT, ConfigType.STR);
        assertThat(problems(mismatch, flow(mismatch, "port", "${extra ?? fallback}")).get(0).message())
            .contains("${fallback}").contains("an address of type `str`").contains("`int`");
    }

    @Test
    @DisplayName("a fallback that is TAKEN and delivers nothing is an error — also where the types agree")
    void aTakenFallbackMustDeliver() {
        ConfigDefinition text = strField(ConfigType.STR, null);
        ConfigError problem = problems(text, flow(text, "name", "${extra ?? nothing_states_me}")).get(0);

        assertThat(problem.kind()).isEqualTo(ConfigError.Kind.RULE_MISMATCH);
        assertThat(problem.message()).contains("nothing_states_me").contains("delivered nothing");
    }

    @Test
    @DisplayName("when the left-hand side delivers, the fallback is not consulted at all — and stays unchecked")
    void theFallbackIsNotConsultedWhenTheLeftDelivers() {
        ConfigDefinition definition = intField(ConfigType.INT, null);
        ConfigPatch yaml = ConfigPatch.empty().with("port", ConfigString.of("${extra ?? 9999}"), YAML);
        ConfigPatch env = ConfigPatch.empty().with("extra", ConfigInt.of(7000), ConfigSource.of(Layer.ENV, "APP__EXTRA"));

        Config config = StandardPipeline.of(definition, TransformerRegistry.defaults(), yaml, env);

        assertThat(valueOf(definition, config, "port")).isEqualTo(ConfigInt.of(7000));
        assertThat(problems(definition, config)).isEmpty();
    }

    @Nested
    @DisplayName("form of an operand, read by the parser")
    class Forms {

        @Test
        @DisplayName("`9999` -> int, `\"9999\"` -> str, `1e3` -> float, `true` -> bool, a bare word -> an address")
        void theFormDecidesTheType() {
            assertThat(Interpolation.literalType("9999")).isEqualTo(ConfigType.INT);
            assertThat(Interpolation.literalType("010")).isEqualTo(ConfigType.INT);
            assertThat(Interpolation.literalType("\"9999\"")).isEqualTo(ConfigType.STR);
            assertThat(Interpolation.literalType("1e3")).isEqualTo(ConfigType.FLOAT);
            assertThat(Interpolation.literalType("true")).isEqualTo(ConfigType.BOOL);
        }

        @Test
        @DisplayName("a QUOTED operand is TEXT, not an address: `\"fallback\"` is the word, `fallback` is the value")
        void aQuotedOperandIsTextNotAnAddress() {
            ConfigDefinition definition = strField(ConfigType.STR, ConfigType.STR);

            assertThat(valueOf(definition, flow(definition, "name", "${extra ?? \"fallback\"}"), "name"))
                .as("quotes are the form of text - the word itself, not the value of the key `fallback`")
                .isEqualTo(ConfigString.of("fallback"));
            assertThat(valueOf(definition, flow(definition, "name", "${extra ?? fallback}"), "name"))
                .as("without quotes the same word is an ADDRESS, and its value is `http`")
                .isEqualTo(ConfigString.of("http"));
        }
    }
}
