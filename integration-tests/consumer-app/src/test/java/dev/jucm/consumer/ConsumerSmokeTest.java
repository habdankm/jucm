package dev.jucm.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.jucm.core.ConfigInt;
import dev.jucm.core.ConfigMap;
import dev.jucm.core.ConfigPatch;
import dev.jucm.core.ConfigPath;
import dev.jucm.core.Version;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JVM consumer tests: the library works as an **external dependency**.
 *
 * <p>These tests do not belong to JUCM — they are a consumer. They check what the tests inside the
 * library cannot: whether the published artifact is usable at all.
 */
class ConsumerSmokeTest {

    @Test
    @DisplayName("resources from the JUCM JAR are readable (version.properties)")
    void resourcesFromJucmJarAreReadable() {
        assertThat(Version.get())
            .as("the version is read from a resource; a resource missing from the JAR would give `unknown`")
            .isNotBlank()
            .isNotEqualTo("unknown")
            .isEqualTo("1.0");
    }

    @Test
    @DisplayName("the public API is sufficient: a patch built from the outside")
    void publicApiIsSufficient() {
        ConfigPatch patch = ConsumerApp.examplePatch();

        assertThat(patch.size()).isEqualTo(4);
        // the canonical form as a String works from the outside exactly like ConfigPath
        assertThat(ConfigPatch.empty().with("server.port", ConfigInt.of(8080)).paths())
            .containsExactly(ConfigPath.of("server.port"));
        assertThat(patch.operation(ConfigPath.of("server.port")))
            .contains(new ConfigPatch.SetValue(ConfigInt.of(8080)));
        assertThat(ConsumerApp.describe(patch))
            .contains("consumer: JUCM 1.0")
            .contains("server.port=8080")
            .contains("server.tls=true")
            .as("display() of a collection shows the shape, not the size")
            .contains("server.features=[metrics, tracing]");
    }

    @Test
    @DisplayName("the consumer needs no Vavr in its code — the API is plain Java")
    void consumerCodeHasNoVavrImports() {
        // This test does not scan anything magically: if the consumer had to import io.vavr, this file
        // would not compile. So it checks what can be checked at runtime: the public JUCM types are
        // visible, and null is not a value.
        assertThat(ConfigPath.of("server.port").canonical()).isEqualTo("server.port");
        assertThatExceptionOfType(IllegalArgumentException.class)
            .isThrownBy(() -> ConfigPath.of("server__port"));
    }

    @Test
    @DisplayName("the example walks the whole structure: model → layers → tree → report → code 0")
    void exampleWalksTheWholeStructure() {
        var definition = ConsumerApp.applicationModel();
        var config = ConsumerApp.buildConfig(definition);

        var report = ConsumerApp.buildReport(definition, config);
        assertThat(report.hasErrors()).as(report.describe()).isFalse();
        assertThat(ConsumerApp.exitCode(report)).isZero();

        // precedence: the newest layer wins, and the origin says where the value came from
        assertThat(ConsumerApp.provenanceOf(config, "server.port"))
            .as("ENV overrode YAML, and the history also shows the declaration")
            .contains("ENV:AIMENAGERIE__SERVER__PORT = 9001")
            .contains("YAML:application.yaml:12 = 8080")
            .contains("COMPILED:declaration server.port");
        assertThat(ConsumerApp.provenanceOf(config, "server.tls"))
            .contains("CLI:--server-tls")
            .as("the default value from the declaration, overridden by the CLI")
            .contains("COMPILED:declaration server.tls = false");
    }

    @Test
    @DisplayName("broken layers: every problem at once and the lowest code wins")
    void brokenLayersReportAllProblemsAndLowestCodeWins() {
        var report = ConsumerApp.validateBrokenLayers(ConsumerApp.applicationModel());

        assertThat(report.errors()).extracting(error -> error.kind().name())
            .containsExactlyInAnyOrder("CAST_FAILED", "NULL_VALUE");
        assertThat(String.join("\n", ConsumerApp.problemCodes(report)))
            .as("NULL_VALUE in ENV is 30, CAST_FAILED is 31")
            .contains("NULL_VALUE → 30")
            .contains("CAST_FAILED → 31");
        assertThat(ConsumerApp.exitCode(report)).as("the lowest XY wins").isEqualTo(30);
    }

    @Test
    @DisplayName("value rules and auxiliary keys are settled by build(), because they need the declaration")
    void buildResolvesConstraintsAndAuxiliary() {
        var definition = ConsumerApp.applicationModel();
        var config = dev.jucm.core.Config.root(definition).apply(dev.jucm.core.ConfigPatch.empty()
            .with("transport_protocol", dev.jucm.core.ConfigString.of("ftp"),
                dev.jucm.core.ConfigSource.of(dev.jucm.core.Layer.ENV, "AIMENAGERIE__TRANSPORT_PROTOCOL"))
            .with("default_port", dev.jucm.core.ConfigInt.of(9000),
                dev.jucm.core.ConfigSource.of(dev.jucm.core.Layer.ENV, "AIMENAGERIE__DEFAULT_PORT")));

        var report = ConsumerApp.buildReport(definition, config);

        assertThat(report.describe())
            .as("the type matches, the rule does not — and the message shows the WHOLE condition chain")
            .contains("in[http,https]")
            .contains("value `ftp`");
        assertThat(report.errors()).extracting(error -> error.kind().name())
            .contains("VALIDATION_FAILED", "UNUSED_AUXILIARY", "ABSENT");
        assertThat(ConsumerApp.exitCode(report))
            .as("ABSENT is settled by the declaration (COMPILED), so 14 wins over 33").isEqualTo(14);
    }

    @Test
    @DisplayName("three sources → the SAME engine: the same value from a file, ENV and CLI gives the same state")
    void theSameValueFromThreeSourcesGivesTheSameState() {
        var definition = ConsumerApp.applicationModel();
        var port = ConfigInt.of(9001);

        // Three separate configurations: an identical value, a different origin each time.
        var fromFile = dev.jucm.core.Config.root(definition).apply(ConfigPatch.empty()
            .with("server.port", port,
                dev.jucm.core.ConfigSource.of(dev.jucm.core.Layer.YAML, "application.yaml:12")));
        var fromEnv = dev.jucm.core.Config.root(definition).apply(ConfigPatch.empty()
            .with("server.port", port,
                dev.jucm.core.ConfigSource.of(dev.jucm.core.Layer.ENV, "AIMENAGERIE__SERVER__PORT")));
        var fromCli = dev.jucm.core.Config.root(definition).apply(ConfigPatch.empty()
            .with("server.port", port,
                dev.jucm.core.ConfigSource.of(dev.jucm.core.Layer.CLI, "--server-port")));

        assertThat(fromFile.get("server.port"))
            .as("there is ONE engine: the value and the verdict depend on the CONTENT, not on where it came from")
            .isEqualTo(fromEnv.get("server.port"))
            .isEqualTo(fromCli.get("server.port"));
        // The verdict has to be IDENTICAL for all three sources — not "zero": the consumer model has
        // required fields these configurations do not provide, and that is correct. The point is that
        // the source does NOT change the verdict.
        var codes = java.util.List.of(fromFile, fromEnv, fromCli).stream()
            .map(config -> ConsumerApp.buildReport(definition, config))
            .map(ConsumerApp::exitCode)
            .distinct()
            .toList();
        assertThat(codes).as("three sources, one verdict — the source does not change how the engine behaves")
            .hasSize(1);
        var kinds = java.util.List.of(fromFile, fromEnv, fromCli).stream()
            .map(config -> ConsumerApp.buildReport(definition, config))
            .map(report -> report.errors().stream().map(error -> error.kind().name()).sorted().toList())
            .distinct()
            .toList();
        assertThat(kinds).as("and the same SET of problems").hasSize(1);

        // ...and the only difference is the origin — which has to be visible, because that is what
        // diagnostics reach for.
        assertThat(ConsumerApp.provenanceOf(fromFile, "server.port")).contains("YAML:application.yaml:12");
        assertThat(ConsumerApp.provenanceOf(fromEnv, "server.port")).contains("ENV:AIMENAGERIE__SERVER__PORT");
        assertThat(ConsumerApp.provenanceOf(fromCli, "server.port")).contains("CLI:--server-port");
    }

    @Test
    @DisplayName("the containers from the JAR are immutable for the consumer as well")
    void containersAreImmutableForConsumer() {
        ConfigMap map = ConfigMap.of(Map.of("port", ConfigInt.of(8080)));

        assertThatExceptionOfType(UnsupportedOperationException.class)
            .isThrownBy(() -> map.entries().put("host", ConfigInt.of(1)));
    }
}
