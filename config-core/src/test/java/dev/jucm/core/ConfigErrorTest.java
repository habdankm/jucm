package dev.jucm.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The error contract: five questions, a closed set, and no `null` used as a failure signal. */
class ConfigErrorTest {

    private static final ConfigSource SOURCE = ConfigSource.of(Layer.ENV, "AIMENAGERIE__SERVER__PORT");

    @Test
    @DisplayName("fourteen kinds — a closed set")
    void kindsAreClosed() {
        assertThat(ConfigError.Kind.values()).hasSize(14);
        assertThat(Arrays.stream(ConfigError.Kind.values()).map(Enum::name).toList())
            .containsExactly("ABSENT", "UNRESOLVED_REFERENCE", "UNRESOLVED_EXPRESSION", "CYCLE",
                "CAST_FAILED", "VALIDATION_FAILED", "PARSE_FAILED", "INVALID_SYNTAX",
                "UNKNOWN_PATH", "NULL_VALUE", "FILE_NOT_EXIST", "UNUSED_AUXILIARY", "TRANSFORM_FAILED",
                // The 14th kind was added deliberately — a rule of ordering applied to a textual value is a
                // model error, so adding such a kind has to go through this list.
                "RULE_MISMATCH");
        assertThat(ConfigError.class.getPermittedSubclasses())
            .as("seventeen records over fourteen kinds: `UnusableStatement`, `UnusableRule` and "
                + "`UnusableFallback` reuse existing kinds on purpose — a statement, a rule or a fallback that "
                + "cannot be used is not a new KIND of failure, but it is a different MESSAGE, and the message "
                + "must say what is wrong with it")
            .hasSize(17);
    }

    @Test
    @DisplayName("a transformation failure carries EVERYTHING: the transformer, its configuration, the value and the reason")
    void transformFailedCarriesEverything() {
        ConfigError error = new ConfigError.TransformFailed(ConfigPath.of("start_date"), "date",
            java.util.Optional.of("yyyy-MM-dd HH:mm:ss"), ConfigString.of("12.09.2026"),
            "the value does not match the pattern", SOURCE);

        assertThat(error.kind()).isEqualTo(ConfigError.Kind.TRANSFORM_FAILED);
        assertThat(ConfigError.pathOf(error)).as("the path is there — otherwise nobody knows what to fix")
            .contains(ConfigPath.of("start_date"));
        assertThat(ConfigError.valueOf(error)).as("the offending value travels with the error")
            .contains(ConfigString.of("12.09.2026"));
        assertThat(error.report())
            .contains("transformer `date`")
            .contains("config `yyyy-MM-dd HH:mm:ss`")
            .contains("the value does not match the pattern")
            .contains("path `start_date`")
            .contains("source ENV:AIMENAGERIE__SERVER__PORT");
    }

    @Test
    @DisplayName("a pathless error is not `null`, it is an empty Optional (ParseFailed)")
    void pathlessErrorHasEmptyPath() {
        ConfigError error = new ConfigError.ParseFailed(SOURCE, "cannot be parsed");

        assertThat(ConfigError.pathOf(error)).isEmpty();
        assertThat(error.report()).doesNotContain("path");
    }

    @Test
    @DisplayName("only the error that really points at the value carries it (CastFailed)")
    void onlyCastFailedCarriesTheValue() {
        ConfigError cast = new ConfigError.CastFailed(ConfigPath.of("server.port"), ConfigType.INT,
            ConfigString.of("abba"), SOURCE);
        ConfigError missing = new ConfigError.Absent(ConfigPath.of("server.port"));

        assertThat(ConfigError.valueOf(cast)).contains(ConfigString.of("abba"));
        assertThat(ConfigError.valueOf(missing)).isEmpty();
    }

    @Test
    @DisplayName("a cycle carries all paths, because one does not say where it looped")
    void cycleCarriesAllPaths() {
        ConfigError error = new ConfigError.Cycle(
            List.of(ConfigPath.of("a"), ConfigPath.of("b"), ConfigPath.of("a")), SOURCE);

        assertThat(error.report()).contains("a").contains("b");
    }

    @Test
    void cycleWithoutPathsIsMeaningless() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
            () -> new ConfigError.Cycle(List.of(), SOURCE)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the report answers all five questions")
    void reportAnswersFiveQuestions() {
        ConfigError error = new ConfigError.CastFailed(ConfigPath.of("server.port"), ConfigType.INT,
            ConfigString.of("abba"), SOURCE);

        assertThat(error.kind()).isEqualTo(ConfigError.Kind.CAST_FAILED);
        assertThat(error.source().display()).isEqualTo("ENV:AIMENAGERIE__SERVER__PORT");
        assertThat(ConfigError.pathOf(error)).contains(ConfigPath.of("server.port"));
        assertThat(ConfigError.valueOf(error)).contains(ConfigString.of("abba"));
        assertThat(error.report())
            .startsWith("cast-failed:")
            .contains("path `server.port`")
            .contains("value `abba`")
            .contains("source ENV:AIMENAGERIE__SERVER__PORT");
    }

    @Test
    @DisplayName("Absent has no source — no layer contributed anything")
    void absentHasNoLayerSource() {
        ConfigError error = new ConfigError.Absent(ConfigPath.of("server.port"));

        assertThat(error.source().layer()).isEqualTo(Layer.CUSTOM);
        assertThat(error.message()).isEqualTo("no value in any layer");
    }
}
