package dev.jucm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Patches: sparseness, no `remove`, merging, immutability. */
class ConfigPatchTest {

    private static final ConfigPath PORT = ConfigPath.of("server.port");
    private static final ConfigPath HOST = ConfigPath.of("server.host");

    @Test
    @DisplayName("an empty patch carries nothing — no placeholders")
    void emptyPatchIsSparse() {
        assertThat(ConfigPatch.empty().isEmpty()).isTrue();
        assertThat(ConfigPatch.empty().size()).isZero();
        assertThat(ConfigPatch.empty().paths()).isEmpty();
    }

    @Test
    @DisplayName("a patch contains only what was contributed — a missing entry means 'I do not know'")
    void patchCarriesOnlyWhatWasSet() {
        ConfigPatch patch = ConfigPatch.set(PORT, ConfigInt.of(8080));

        assertThat(patch.size()).isEqualTo(1);
        assertThat(patch.paths()).containsExactly(PORT);
        assertThat(patch.operation(HOST)).isEmpty();
        assertThat(patch.operation(PORT))
            .contains(new ConfigPatch.SetValue(ConfigInt.of(8080)));
    }

    @Test
    void resetIsAnOperationNotARemove() {
        ConfigPatch patch = ConfigPatch.reset(PORT);

        assertThat(patch.operation(PORT)).get().isInstanceOf(ConfigPatch.Reset.class);
        // `remove` does not exist in this API; there are three operations:
        // set, reset, and "no value was delivered" (the seam for a loader failure)
        assertThat(ConfigPatch.Operation.class.getPermittedSubclasses()).hasSize(3);
    }

    @Test
    @DisplayName("merging: for the same path the later patch wins")
    void mergeLetsTheLaterPatchWin() {
        ConfigPatch yaml = ConfigPatch.set(PORT, ConfigInt.of(8080));
        ConfigPatch env = ConfigPatch.set(PORT, ConfigInt.of(9000)).with(HOST, new ConfigPatch.SetValue(ConfigString.of("localhost")));

        ConfigPatch merged = yaml.merge(env);

        assertThat(merged.operation(PORT)).contains(new ConfigPatch.SetValue(ConfigInt.of(9000)));
        assertThat(merged.operation(HOST)).contains(new ConfigPatch.SetValue(ConfigString.of("localhost")));
        assertThat(merged.size()).isEqualTo(2);
    }

    @Test
    void mergeWithEmptyChangesNothing() {
        ConfigPatch patch = ConfigPatch.set(PORT, ConfigInt.of(8080));

        assertThat(patch.merge(ConfigPatch.empty())).isEqualTo(patch);
        assertThat(ConfigPatch.empty().merge(patch)).isEqualTo(patch);
    }

    @Test
    @DisplayName("a patch is immutable — with() returns a new object")
    void patchesAreImmutable() {
        ConfigPatch base = ConfigPatch.set(PORT, ConfigInt.of(8080));
        ConfigPatch extended = base.with(HOST, new ConfigPatch.SetValue(ConfigString.of("localhost")));

        assertThat(base.paths()).containsExactly(PORT);
        assertThat(extended.paths()).containsExactlyInAnyOrder(PORT, HOST);
        assertThatExceptionOfType(UnsupportedOperationException.class)
            .isThrownBy(() -> base.operations().clear());
    }

    @Test
    @DisplayName("String is only a convenient input — inside it is always a ConfigPath")
    void stringApiIsConvenienceOnly() {
        ConfigPatch byString = ConfigPatch.empty().with("server.port", ConfigInt.of(8080));
        ConfigPatch byPath = ConfigPatch.empty().with(ConfigPath.of("server.port"), ConfigInt.of(8080));

        assertThat(byString).isEqualTo(byPath);
        assertThat(byString.paths()).containsExactly(PORT);
        assertThat(byString.operation("server.port")).contains(new ConfigPatch.SetValue(ConfigInt.of(8080)));
    }

    @Test
    @DisplayName("the key grammar applies to the String input just the same")
    void stringInputIsStillValidated() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> ConfigPatch.empty().with("server__port", ConfigInt.of(1)))
            .withMessageContaining("`__`");
    }

    @Test
    @DisplayName("a value goes in without an explicit SetValue (the static set/with and the chain)")
    void valueGoesInWithoutExplicitOperation() {
        ConfigPatch chained = ConfigPatch.empty()
            .with("server.port", ConfigInt.of(8080))
            .with("server.host", ConfigString.of("localhost"))
            .with(ConfigPath.of("server.tls"), ConfigBool.of(true));

        assertThat(chained.size()).isEqualTo(3);
        assertThat(ConfigPatch.set("server.port", ConfigInt.of(8080)))
            .isEqualTo(ConfigPatch.set(PORT, ConfigInt.of(8080)));
        assertThat(ConfigPatch.reset("server.port")).isEqualTo(ConfigPatch.reset(PORT));
    }

    @Test
    @DisplayName("a value carries its origin, while equality does NOT include it")
    void sourceTravelsWithValueButStaysOutOfEquality() {
        ConfigSource fromCli = ConfigSource.of(Layer.CLI, "--server-port");

        ConfigPatch fromAdapter = ConfigPatch.empty().with("server.port", ConfigInt.of(8080), fromCli);
        ConfigPatch handBuilt = ConfigPatch.empty().with("server.port", ConfigInt.of(8080));

        assertThat(fromAdapter).isEqualTo(handBuilt);
        assertThat(fromAdapter.operation("server.port")).get()
            .extracting(op -> ((ConfigPatch.SetValue) op).source().display())
            .isEqualTo("CLI:--server-port");
        assertThat(handBuilt.operation("server.port")).get()
            .extracting(op -> ((ConfigPatch.SetValue) op).source().layer())
            .isEqualTo(Layer.CUSTOM);
        assertThat(ConfigPatch.set(ConfigPath.of("a"), ConfigInt.of(1), fromCli)).isEqualTo(ConfigPatch.set("a", ConfigInt.of(1)));
    }

    @Test
    @DisplayName("\"no value was delivered\" is an operation of its own — not an empty text")
    void nullDeliveredIsItsOwnOperation() {
        ConfigSource fromEnv = ConfigSource.of(Layer.ENV, "AIMENAGERIE__SERVER__PORT");

        ConfigPatch deliveredNothing = ConfigPatch.nullDelivered("server.port", fromEnv);

        assertThat(deliveredNothing.operation("server.port")).contains(new ConfigPatch.NullDelivered(fromEnv));
        assertThat(deliveredNothing).isNotEqualTo(ConfigPatch.empty().with("server.port", ConfigString.of("")));
    }

    @Test
    void equalityIsByOperations() {
        assertThat(ConfigPatch.set(PORT, ConfigInt.of(1))).isEqualTo(ConfigPatch.set(PORT, ConfigInt.of(1)));
        assertThat(ConfigPatch.set(PORT, ConfigInt.of(1))).isNotEqualTo(ConfigPatch.set(PORT, ConfigInt.of(2)));
        assertThat(ConfigPatch.reset(PORT)).isNotEqualTo(ConfigPatch.set(PORT, ConfigInt.of(1)));
    }
}
