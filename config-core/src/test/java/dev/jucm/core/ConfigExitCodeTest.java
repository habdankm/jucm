package dev.jucm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** `XY` codes: the layer's decade plus the category of the cause, and one code when there are many errors. */
class ConfigExitCodeTest {

    private static final ConfigPath PORT = ConfigPath.of("server.port");

    private static ConfigError castFailed(Layer layer, String origin) {
        return new ConfigError.CastFailed(PORT, ConfigType.INT, ConfigString.of("abba"),
            ConfigSource.of(layer, origin));
    }

    @Test
    @DisplayName("the decade is the layer's position in the pipeline: compiled=1, YAML=2, ENV=3, CLI=4, CUSTOM=5, TUI=6")
    void decadeIsThePositionInThePipeline() {
        var pipeline = ConfigExitCode.Pipeline.STANDARD;

        assertThat(ConfigExitCode.of(castFailed(Layer.COMPILED, "declaration"), pipeline)).as("Y=1 -> 11").isEqualTo(11);
        assertThat(ConfigExitCode.of(new ConfigError.ParseFailed(
            ConfigSource.of(Layer.COMPILED, "declaration"), "broken literal"), pipeline))
            .as("Y=0 -> 10").isEqualTo(10);
        assertThat(ConfigExitCode.of(castFailed(Layer.YAML, "application.yaml:12"), pipeline)).as("21").isEqualTo(21);
        assertThat(ConfigExitCode.of(castFailed(Layer.ENV, "AIMENAGERIE__X"), pipeline)).as("31").isEqualTo(31);
        assertThat(ConfigExitCode.of(castFailed(Layer.CLI, "--x"), pipeline)).as("41").isEqualTo(41);
        assertThat(ConfigExitCode.of(castFailed(Layer.CUSTOM, "application code"), pipeline)).as("51").isEqualTo(51);
        assertThat(ConfigExitCode.of(castFailed(Layer.TUI, "editor"), pipeline)).as("61").isEqualTo(61);
    }

    @Test
    @DisplayName("the category comes from the CAUSE, not from the Java class of the error")
    void categoryComesFromTheCause() {
        var pipeline = ConfigExitCode.Pipeline.STANDARD;
        ConfigSource cli = ConfigSource.of(Layer.CLI, "--server-port");

        assertThat(ConfigExitCode.of(new ConfigError.ParseFailed(cli, "broken literal"), pipeline)).as("Y=0").isEqualTo(40);
        assertThat(ConfigExitCode.of(new ConfigError.NullValue(PORT, cli), pipeline)).as("Y=0").isEqualTo(40);
        assertThat(ConfigExitCode.of(new ConfigError.TransformFailed(PORT, "date", ConfigString.of("12.09.2026"),
            "does not match the pattern", cli), pipeline)).as("Y=0 — an input problem").isEqualTo(40);
        assertThat(ConfigExitCode.of(new ConfigError.InvalidSyntax(PORT, "${a", cli), pipeline)).as("Y=0").isEqualTo(40);
        assertThat(ConfigExitCode.of(castFailed(Layer.CLI, "--x"), pipeline)).as("Y=1").isEqualTo(41);
        assertThat(ConfigExitCode.of(new ConfigError.UnresolvedReference(PORT, PORT, cli), pipeline)).as("Y=2").isEqualTo(42);
        assertThat(ConfigExitCode.of(new ConfigError.Cycle(List.of(PORT), cli), pipeline)).as("Y=2").isEqualTo(42);
        assertThat(ConfigExitCode.of(new ConfigError.ValidationFailed(PORT, "http or https", ConfigString.of("ftp"), cli), pipeline)).as("Y=3").isEqualTo(43);
        assertThat(ConfigExitCode.of(new ConfigError.UnusedAuxiliary(PORT, cli), pipeline)).as("Y=3").isEqualTo(43);
        assertThat(ConfigExitCode.of(new ConfigError.Absent(PORT), pipeline)).as("Y=4").isEqualTo(14);
        assertThat(ConfigExitCode.of(new ConfigError.UnknownPath(PORT, cli), pipeline)).as("Y=5").isEqualTo(45);
        assertThat(ConfigExitCode.of(new ConfigError.FileNotExist(PORT, cli, "missing file"), pipeline)).as("Y=7").isEqualTo(47);
    }

    @Test
    @DisplayName("Absent without a source counts as missing in the declaration layer")
    void absentWithoutSourceIsCountedAsDeclaration() {
        assertThat(ConfigExitCode.of(new ConfigError.Absent(PORT), ConfigExitCode.Pipeline.STANDARD))
            .isEqualTo(14);
    }

    @Test
    @DisplayName("with many errors the lowest XY wins — regardless of the order of detection")
    void lowestCodeWins() {
        var pipeline = ConfigExitCode.Pipeline.STANDARD;
        List<ConfigError> errors = List.of(
            castFailed(Layer.CLI, "--x"),
            castFailed(Layer.YAML, "application.yaml:12"),
            castFailed(Layer.ENV, "AIMENAGERIE__X"));

        assertThat(ConfigExitCode.lowestOf(errors, pipeline)).isEqualTo(21);
        assertThat(ConfigExitCode.lowestOf(errors.reversed(), pipeline)).as("the order does not matter").isEqualTo(21);
    }

    @Test
    void noErrorsMeansZero() {
        assertThat(ConfigExitCode.lowestOf(List.of(), ConfigExitCode.Pipeline.STANDARD)).isZero();
        assertThat(ConfigExitCode.OK).isZero();
    }

    @Test
    @DisplayName("a one-digit code is never ours — decade 1..9 belongs to the application")
    void singleDigitCodesAreNeverOurs() {
        var pipeline = ConfigExitCode.Pipeline.STANDARD;

        for (ConfigError.Kind kind : ConfigError.Kind.values()) {
            ConfigError error = switch (kind) {
                case CAST_FAILED -> castFailed(Layer.CLI, "--x");
                case UNRESOLVED_REFERENCE -> new ConfigError.UnresolvedReference(PORT, PORT, ConfigSource.of(Layer.CLI, "--x"));
                case UNRESOLVED_EXPRESSION -> new ConfigError.UnresolvedExpression(PORT, "${a}", ConfigSource.of(Layer.CLI, "--x"));
                case CYCLE -> new ConfigError.Cycle(List.of(PORT), ConfigSource.of(Layer.CLI, "--x"));
                case VALIDATION_FAILED -> new ConfigError.ValidationFailed(PORT, "rule", ConfigString.of("x"), ConfigSource.of(Layer.CLI, "--x"));
                case PARSE_FAILED -> new ConfigError.ParseFailed(ConfigSource.of(Layer.CLI, "--x"), "reason");
                case INVALID_SYNTAX -> new ConfigError.InvalidSyntax(PORT, "${a", ConfigSource.of(Layer.CLI, "--x"));
                case NULL_VALUE -> new ConfigError.NullValue(PORT, ConfigSource.of(Layer.CLI, "--x"));
                case TRANSFORM_FAILED -> new ConfigError.TransformFailed(PORT, "date", ConfigString.of("12.09.2026"),
                    "reason", ConfigSource.of(Layer.CLI, "--x"));
                case UNKNOWN_PATH -> new ConfigError.UnknownPath(PORT, ConfigSource.of(Layer.CLI, "--x"));
                case FILE_NOT_EXIST -> new ConfigError.FileNotExist(PORT, ConfigSource.of(Layer.CLI, "--x"), "reason");
                case UNUSED_AUXILIARY -> new ConfigError.UnusedAuxiliary(PORT, ConfigSource.of(Layer.CLI, "--x"));
                case ABSENT -> new ConfigError.Absent(PORT);
                case RULE_MISMATCH -> new ConfigError.RuleMismatch(PORT, "rule", ConfigType.STR,
                    ConfigSource.of(Layer.CLI, "--x"));
            };
            assertThat(ConfigExitCode.of(error, pipeline)).as(kind.name()).isGreaterThanOrEqualTo(10);
        }
    }

    @Test
    @DisplayName("the application's pipeline shifts the decades; a layer outside the pipeline is a programmer error")
    void pipelineIsConfigurableAndValidated() {
        var swapped = new ConfigExitCode.Pipeline(List.of(Layer.CLI, Layer.YAML));

        assertThat(ConfigExitCode.of(castFailed(Layer.YAML, "application.yaml:12"), swapped)).isEqualTo(21);
        assertThat(ConfigExitCode.of(castFailed(Layer.CLI, "--x"), swapped)).as("CLI is first now").isEqualTo(11);
        assertThatIllegalArgumentException()
            .isThrownBy(() -> ConfigExitCode.of(castFailed(Layer.ENV, "X"), swapped))
            .withMessageContaining("does not occur in this pipeline");
    }

    @Test
    void usageErrorIsNotAConfigurationError() {
        assertThat(ConfigExitCode.USAGE_ERROR).isEqualTo(125);
    }

    @Test
    @DisplayName("an error from the layer the APPLICATION contributes is numbered — it used to have no position at all")
    void theApplicationLayerIsNumberedToo() {
        var pipeline = ConfigExitCode.Pipeline.STANDARD;
        ConfigSource application = ConfigSource.application();

        assertThat(application.layer())
            .as("the library itself states values on CUSTOM: that is where application code lands")
            .isEqualTo(Layer.CUSTOM);
        assertThat(ConfigExitCode.of(castFailed(Layer.CUSTOM, "application code"), pipeline))
            .as("the application's own values sit below the window").isEqualTo(51);
        assertThat(ConfigExitCode.lowestOf(List.of(castFailed(Layer.CUSTOM, "application code")), pipeline))
            .as("one CUSTOM error used to be unnumberable, so the whole verdict threw instead of reporting")
            .isEqualTo(51);
    }

    @Test
    @DisplayName("STANDARD numbers every layer an entry can come from — a missing one could not be reported")
    void standardPipelineCoversEveryLayer() {
        assertThat(ConfigExitCode.Pipeline.STANDARD.layers())
            .as("a layer absent from the pipeline has no position, so its errors cannot be numbered")
            .containsExactlyInAnyOrder(Layer.values());
    }
}
