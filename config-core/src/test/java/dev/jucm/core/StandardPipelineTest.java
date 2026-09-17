package dev.jucm.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The standard flow: it merges the layers in the ORDER GIVEN, runs the canonical steps, and that order is
 * the application's convention — not an invariant of the library.
 */
class StandardPipelineTest {

    private static final TransformerRegistry REGISTRY = TransformerRegistry.defaults();

    /** `Config.get` returns a `ConfigResult`, so unwrapping a value has one path in this file. */
    private static String value(Config config, String path) {
        ConfigResult<ConfigValue> found = config.get(path);
        assertThat(found).as("a value was expected under `" + path + "`").isInstanceOf(ConfigResult.ValueResult.class);
        return ((ConfigResult.ValueResult<ConfigValue>) found).value().display();
    }

    private static ConfigPatch yaml(String text) {
        return ConfigPatch.set(ConfigPath.of("port"), ConfigString.of(text),
            ConfigSource.of(Layer.YAML, "application.yaml"));
    }

    private static ConfigPatch cli(String text) {
        return ConfigPatch.set(ConfigPath.of("port"), ConfigString.of(text),
            ConfigSource.of(Layer.CLI, "--port"));
    }

    @Test
    @DisplayName("standard: the last layer has the highest priority")
    void lastLayerWins() {
        ConfigDefinition definition = ConfigDefinition.of(ConfigField.required("port", ConfigType.INT));

        assertThat(value(StandardPipeline.of(definition, REGISTRY, yaml("8080"), cli("9000")), "port")).isEqualTo("9000");
    }

    @Test
    @DisplayName("CONFIGURATION AS THE SOURCE OF TRUTH: it is enough to pass the layers in a different order")
    void theOrderIsTheApplicationsConvention() {
        ConfigDefinition definition = ConfigDefinition.of(ConfigField.required("port", ConfigType.INT));
        ConfigPatch fromFile = yaml("8080");
        ConfigPatch fromCli = cli("9000");

        assertThat(value(StandardPipeline.of(definition, REGISTRY, fromFile, fromCli), "port"))
            .as("the default order: the command line overrides the file").isEqualTo("9000");
        assertThat(value(StandardPipeline.of(definition, REGISTRY, fromCli, fromFile), "port"))
            .as("a configuration-driven application says the same thing differently — the file is the source of truth")
            .isEqualTo("8080");
    }

    @Test
    @DisplayName("standard runs the CANONICAL steps: interpolation first, then the type from the declaration")
    void theStandardFlowRuns() {
        ConfigDefinition definition = ConfigDefinition.of(
            ConfigField.required("host", ConfigType.STR),
            ConfigField.required("url", ConfigType.STR),
            ConfigField.required("port", ConfigType.INT));
        ConfigSource file = ConfigSource.of(Layer.YAML, "application.yaml");
        ConfigPatch patch = ConfigPatch.set(ConfigPath.of("host"), ConfigString.of("localhost"), file)
            .with(ConfigPath.of("url"), ConfigString.of("http://${host}:${port}/api"), file, SourceDeclaration.none())
            .with(ConfigPath.of("port"), ConfigString.of("010"), file, SourceDeclaration.none());

        Config built = StandardPipeline.of(definition, REGISTRY, patch);

        // AN ASYMMETRY THAT IS A CONTRACT: for VALUES the interpolation runs BEFORE the cast, so `${port}`
        // still sees the notation `010`; for KEYS it is the other way round (transformation -> cast ->
        // interpolation, because a key is an address)
        assertThat(value(built, "url"))
            .as("interpolating a value sees the notation from before the cast").isEqualTo("http://localhost:010/api");
        assertThat(value(built, "port"))
            .as("and the value itself is typed: `010` is octal eight").isEqualTo("8");
    }

    @Test
    @DisplayName("an empty layer is allowed — \"this source contributed nothing\"")
    void emptyLayerIsAllowed() {
        ConfigDefinition definition = ConfigDefinition.of(ConfigField.required("port", ConfigType.INT));

        assertThat(value(StandardPipeline.of(definition, REGISTRY, ConfigPatch.empty(), yaml("8080")), "port")).isEqualTo("8080");
        assertThat(StandardPipeline.of(definition, REGISTRY).get("port"))
            .as("without layers there is nowhere to take a value from").isInstanceOf(ConfigResult.ErrorResult.class);
    }

    @Test
    @DisplayName("validation is a SEPARATE, explicit step — building does not pretend to be diagnostics")
    void validationStaysASeparateStep() {
        ConfigDefinition definition = ConfigDefinition.of(ConfigField.required("port", ConfigType.INT));
        Config built = StandardPipeline.of(definition, REGISTRY);

        ConfigValidator.Report report = ConfigValidator.build(definition, built);

        assertThat(report.errors()).extracting(ConfigError::kind).contains(ConfigError.Kind.ABSENT);
    }
}
