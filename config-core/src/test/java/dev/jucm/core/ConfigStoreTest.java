package dev.jucm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The handle to the current configuration: an atomic swap, an old snapshot, a loud `compact()`. */
class ConfigStoreTest {

    private static final ConfigPath PORT = ConfigPath.of("server.port");
    private static final ConfigSource YAML = ConfigSource.of(Layer.YAML, "application.yaml:12");
    private static final ConfigSource CLI = ConfigSource.of(Layer.CLI, "--server-port");

    private static ConfigPatch patch(ConfigSource source, int value) {
        return ConfigPatch.empty().with("server.port", ConfigInt.of(value), source);
    }

    @Test
    @DisplayName("current() returns the newest snapshot after a swap")
    void currentReturnsTheNewestSnapshot() {
        ConfigStore store = ConfigStore.of(Config.empty().apply(patch(YAML, 8080)));

        store.set(store.current().apply(patch(CLI, 9001)));

        assertThat(store.current().get(PORT)).isEqualTo(ConfigResult.value(ConfigInt.of(9001)));
        assertThat(store.current().layer()).isEqualTo(Layer.CLI);
    }

    @Test
    @DisplayName("a reader holding an old snapshot sees the old picture — that is a feature, not a bug")
    void readerWithOldSnapshotSeesOldValues() {
        ConfigStore store = ConfigStore.of(Config.empty().apply(patch(YAML, 8080)));
        Config heldInHand = store.current();

        store.set(heldInHand.apply(patch(CLI, 9001)));

        assertThat(heldInHand.get(PORT)).isEqualTo(ConfigResult.value(ConfigInt.of(8080)));
        assertThat(store.current().get(PORT)).isEqualTo(ConfigResult.value(ConfigInt.of(9001)));
        assertThat(store.current().parent()).contains(heldInHand);
    }

    @Test
    @DisplayName("update(operator) swaps atomically and sees the current snapshot")
    void updateSeesTheCurrentSnapshot() {
        ConfigStore store = ConfigStore.of(Config.empty().apply(patch(YAML, 8080)));
        UnaryOperator<Config> fromCli = config -> config.apply(patch(CLI, 9001));

        Config result = store.update(fromCli);

        assertThat(result).isSameAs(store.current());
        assertThat(result.get(PORT)).isEqualTo(ConfigResult.value(ConfigInt.of(9001)));
        assertThat(result.depth()).isEqualTo(3); // the empty root + YAML + CLI
    }

    @Test
    @DisplayName("set() returns the previous snapshot — history lives in the chain, not in a separate mechanism")
    void setReturnsThePreviousSnapshot() {
        ConfigStore store = ConfigStore.of(Config.empty());
        Config previous = store.current();

        Config displaced = store.set(previous.apply(patch(YAML, 8080)));

        assertThat(displaced).isSameAs(previous);
    }

    @Test
    @DisplayName("compact() is in the API but without an implementation — and it has to be loud")
    void compactIsLoudlyNotImplemented() {
        ConfigStore store = ConfigStore.of(Config.empty());

        assertThatExceptionOfType(UnsupportedOperationException.class)
            .isThrownBy(store::compact)
            .withMessageContaining("retention is not implemented in this version");
    }

    @Test
    @DisplayName("the layer pipeline is just a function Config -> Config")
    void pipelineIsJustAFunction() {
        Function<Config, Config> pipeline = config -> config
            .apply(patch(YAML, 8080))
            .apply(patch(CLI, 9001));

        Config result = pipeline.apply(Config.empty());

        assertThat(result.get(PORT)).isEqualTo(ConfigResult.value(ConfigInt.of(9001)));
        assertThat(result.history(PORT)).extracting(Config.Step::display)
            .as("the order of the layers follows from composing functions, not from a library invariant")
            .containsExactly("CLI:--server-port = 9001", "YAML:application.yaml:12 = 8080");
    }
}
