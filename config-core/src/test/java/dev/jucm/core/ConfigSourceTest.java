package dev.jucm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Value origin: structure, printable form, and its absence from equality. */
class ConfigSourceTest {

    @Test
    @DisplayName("the printable form shows the layer and the address — exactly what diagnostics needs")
    void displayNamesLayerAndOrigin() {
        assertThat(ConfigSource.of(Layer.YAML, "application.yaml:12").display())
            .isEqualTo("YAML:application.yaml:12");
        assertThat(ConfigSource.of(Layer.ENV, "AIMENAGERIE__SERVER__TLS").display())
            .isEqualTo("ENV:AIMENAGERIE__SERVER__TLS");
        assertThat(ConfigSource.of(Layer.CLI, "--server-tls").display())
            .isEqualTo("CLI:--server-tls");
        assertThat(ConfigSource.application().display()).isEqualTo("CUSTOM:application code");
        assertThat(ConfigSource.declaration(ConfigPath.of("server.port")).display())
            .isEqualTo("COMPILED:declaration server.port");
    }

    @Test
    void layerIsAvailableSeparately() {
        assertThat(ConfigSource.of(Layer.ENV, "X").layer()).isEqualTo(Layer.ENV);
        assertThat(ConfigSource.of(Layer.ENV, "X").origin()).isEqualTo("X");
    }

    @Test
    @DisplayName("a missing origin is an error — we do not know where the value came from, so there is nothing to show")
    void blankOriginIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> ConfigSource.of(Layer.CLI, "  "));
        assertThatIllegalArgumentException().isThrownBy(() -> ConfigSource.of(Layer.CLI, ""));
    }

    @Test
    void equalityIsStructural() {
        assertThat(ConfigSource.of(Layer.CLI, "--x")).isEqualTo(ConfigSource.of(Layer.CLI, "--x"));
        assertThat(ConfigSource.of(Layer.CLI, "--x")).isNotEqualTo(ConfigSource.of(Layer.ENV, "--x"));
        assertThat(ConfigSource.of(Layer.CLI, "--x")).hasSameHashCodeAs(ConfigSource.of(Layer.CLI, "--x"));
    }
}
