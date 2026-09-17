package dev.jucm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Field declarations: one source of metadata, unique aliases, requiredness. */
class ConfigDefinitionTest {

    private static ConfigField port() {
        return ConfigField.required("server.port", ConfigType.INT, ConfigInt.of(8080))
            .described("Port the server listens on.").aliased("-p");
    }

    @Test
    @DisplayName("the definition is the source of truth about the type — the validator's schema is derived")
    void schemaIsDerivedFromDeclarations() {
        ConfigDefinition definition = ConfigDefinition.of(
            port(), ConfigField.optional("api.key", ConfigType.STR));

        assertThat(definition.size()).isEqualTo(2);
        assertThat(definition.schema().declaredType(ConfigPath.of("server.port"))).contains(ConfigType.INT);
        assertThat(definition.schema().declares(ConfigPath.of("api.key"))).isTrue();
        assertThat(definition.field(ConfigPath.of("server.port"))).get()
            .extracting(ConfigField::description).isEqualTo("Port the server listens on.");
    }

    @Test
    @DisplayName("defaultValue and the value that was set are two separate states")
    void defaultValueIsSeparateFromValue() {
        assertThat(port().defaultValue()).contains(ConfigInt.of(8080));
        assertThat(ConfigField.optional("api.key", ConfigType.STR).defaultValue()).isEmpty();
        assertThat(ConfigField.optional("api.key", ConfigType.STR).required()).isFalse();
    }

    @Test
    @DisplayName("only fields with a default materialise the root — the rest end up Absent")
    void onlyFieldsWithDefaultFeedTheRoot() {
        ConfigDefinition definition = ConfigDefinition.of(
            port(), ConfigField.optional("api.key", ConfigType.STR));

        assertThat(definition.fieldsWithDefault()).hasSize(1);
        assertThat(definition.fieldsWithDefault().get(0).path()).isEqualTo(ConfigPath.of("server.port"));
    }

    @Test
    @DisplayName("aliases must be unique across the whole model — a collision is an error, not a silent win")
    void aliasCollisionIsAModelError() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> ConfigDefinition.of(
                port(), ConfigField.optional("api.key", ConfigType.STR).aliased("-p")))
            .withMessageContaining("unique")
            .withMessageContaining("-p");
    }

    @Test
    void samePathTwiceIsAnError() {
        // The message is about a duplicate declaration of one path, so the assertion pins the path it names.
        assertThatIllegalArgumentException()
            .isThrownBy(() -> ConfigDefinition.of(port(), port()))
            .withMessageContaining("`server.port`");
    }
}
