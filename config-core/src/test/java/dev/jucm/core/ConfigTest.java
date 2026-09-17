package dev.jucm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The configuration chain: sparseness, precedence, lookup, reset, history. */
class ConfigTest {

    private static final ConfigPath PORT = ConfigPath.of("server.port");
    private static final ConfigSource YAML = ConfigSource.of(Layer.YAML, "application.yaml:12");
    private static final ConfigSource ENV = ConfigSource.of(Layer.ENV, "AIMENAGERIE__SERVER__PORT");
    private static final ConfigSource CLI = ConfigSource.of(Layer.CLI, "--server-port");

    private static ConfigPatch from(Layer layer, ConfigSource source, int value) {
        return ConfigPatch.empty().with("server.port", ConfigInt.of(value), source);
    }

    @Test
    @DisplayName("the root is materialised from the declarations; a field without a default gets no placeholder")
    void rootIsMaterialisedFromDeclarations() {
        ConfigDefinition definition = ConfigDefinition.of(
            ConfigField.required("server.port", ConfigType.INT, ConfigInt.of(8080)),
            ConfigField.optional("api.key", ConfigType.STR));

        Config root = Config.root(definition);

        assertThat(root.layer()).isEqualTo(Layer.COMPILED);
        assertThat(root.get(PORT)).isEqualTo(ConfigResult.value(ConfigInt.of(8080)));
        assertThat(root.entry(PORT)).get().extracting(entry -> entry.source().display())
            .isEqualTo("COMPILED:declaration server.port");
        assertThat(root.ownPaths()).as("a field without a default does not exist in the root")
            .containsExactly(PORT);
    }

    @Test
    @DisplayName("a node holds only what it contributed — the rest lives in the parents (storage is sparse)")
    void storageIsSparse() {
        Config config = Config.empty()
            .apply(from(Layer.YAML, YAML, 8080))
            .apply(from(Layer.ENV, ENV, 9000))
            .apply(from(Layer.CLI, CLI, 9001));

        assertThat(config.ownPaths()).containsExactly(PORT);
        assertThat(config.parent().orElseThrow().ownPaths()).containsExactly(PORT);
        // `Config.empty()` is a snapshot too (an empty root), so the chain has 4 links
        assertThat(config.depth()).isEqualTo(4);
    }

    @Test
    @DisplayName("the first hit wins, walking from the newest node down to the root")
    void newestLayerWins() {
        Config config = Config.empty()
            .apply(from(Layer.YAML, YAML, 8080))
            .apply(from(Layer.ENV, ENV, 9000))
            .apply(from(Layer.CLI, CLI, 9001));

        assertThat(config.get(PORT)).isEqualTo(ConfigResult.value(ConfigInt.of(9001)));
        assertThat(config.entry(PORT)).get().extracting(entry -> entry.source().display())
            .isEqualTo("CLI:--server-port");
    }

    @Test
    @DisplayName("a missing entry in a layer falls through to the parent (Missing); the end of the chain is Absent")
    void missingFallsThroughToParent() {
        Config config = Config.empty()
            .apply(ConfigPatch.empty().with("server.host", ConfigString.of("localhost"), YAML));

        assertThat(config.get("server.host")).isEqualTo(ConfigResult.value(ConfigString.of("localhost")));
        assertThat(config.get("server.port")).isEqualTo(ConfigResult.error(new ConfigError.Absent(PORT)));
        assertThat(config.has(PORT)).isFalse();
    }

    @Test
    @DisplayName("immutability: apply() creates a new snapshot, the old one stays untouched")
    void snapshotsAreImmutable() {
        Config yaml = Config.empty().apply(from(Layer.YAML, YAML, 8080));
        Config env = yaml.apply(from(Layer.ENV, ENV, 9000));

        assertThat(yaml.get(PORT)).as("the old snapshot reads its own value").isEqualTo(ConfigResult.value(ConfigInt.of(8080)));
        assertThat(env.get(PORT)).isEqualTo(ConfigResult.value(ConfigInt.of(9000)));
        assertThat(yaml.ownPaths()).containsExactly(PORT);
    }

    @Test
    @DisplayName("reset removes the override from the layer directly below, and the lookup continues")
    void resetRemovesTheOverrideBelow() {
        Config config = Config.empty()
            .apply(from(Layer.YAML, YAML, 8080))
            .apply(from(Layer.ENV, ENV, 9000))
            .apply(ConfigPatch.reset(PORT, ConfigSource.of(Layer.TUI, "reset in TUI")));

        assertThat(config.get(PORT)).as("we fall back to YAML, not to a default copied upwards")
            .isEqualTo(ConfigResult.value(ConfigInt.of(8080)));
    }

    @Test
    @DisplayName("an empty value does not fall through — it is an error, not an absence")
    void nullDeliveredBlocksLookup() {
        Config config = Config.empty()
            .apply(from(Layer.YAML, YAML, 8080))
            .apply(ConfigPatch.nullDelivered(PORT, ENV));

        ConfigResult<ConfigValue> result = config.get(PORT);

        assertThat(result).isInstanceOf(ConfigResult.ErrorResult.class);
        assertThat(((ConfigResult.ErrorResult<ConfigValue>) result).error().kind())
            .isEqualTo(ConfigError.Kind.NULL_VALUE);
        assertThat(config.entry(PORT)).as("the symbolic view does not show a value either").isEmpty();
    }

    @Test
    @DisplayName("history is read off the chain: from the newest layer down to the root")
    void historyWalksTheChain() {
        Config config = Config.root(ConfigDefinition.of(
                ConfigField.required("server.port", ConfigType.INT, ConfigInt.of(80))))
            .apply(from(Layer.YAML, YAML, 8080))
            .apply(from(Layer.ENV, ENV, 9000));

        assertThat(config.history(PORT)).extracting(ConfigTest::displayOf)
            .containsExactly("ENV:AIMENAGERIE__SERVER__PORT = 9000", "YAML:application.yaml:12 = 8080",
                "COMPILED:declaration server.port = 80");
    }

    private static String displayOf(Config.Step step) {
        return step.display();
    }

    @Test
    @DisplayName("one patch contributes one layer — mixing layers is a programmer error")
    void onePatchIsOneLayer() {
        ConfigPatch mixed = ConfigPatch.empty()
            .with("server.port", ConfigInt.of(8080), YAML)
            .with("server.host", ConfigString.of("localhost"), ENV);

        assertThatIllegalArgumentException()
            .isThrownBy(() -> Config.empty().apply(mixed))
            .withMessageContaining("mixes layers");
    }

    @Test
    void emptyPatchDoesNotCreateALayer() {
        Config config = Config.empty();

        assertThat(config.apply(ConfigPatch.empty())).isSameAs(config);
    }
}
