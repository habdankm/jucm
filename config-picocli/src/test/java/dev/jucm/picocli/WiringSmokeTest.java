package dev.jucm.picocli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The whole chain of modules in one run — picocli + SnakeYAML + ENV + Vavr. */
class WiringSmokeTest {

    private static String run(String... args) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            WiringSmoke.run(args, out);
        }
        return buffer.toString(StandardCharsets.UTF_8).trim();
    }

    @Test
    @DisplayName("reads a YAML value, the state of an ENV variable and the layer order in one line")
    void readsYamlEnvAndLayers() {
        String line = run("--yaml", "port: 8080", "--env", "PATH");

        assertThat(line).startsWith("JUCM ")
            .contains("yaml=port:8080")
            .contains("env=PATH:present")
            .contains("layers=compiled > yaml > env")
            .contains("1.0");
    }

    @Test
    @DisplayName("without options it reports `n/a` for both parts instead of failing")
    void worksWithoutOptions() {
        assertThat(run()).contains("yaml=n/a").contains("env=n/a");
    }

    @Test
    @DisplayName("`-help` exits with zero and runs no application (the engine's options take one dash)")
    void helpExitsWithZero() {
        int code = WiringSmoke.run(new String[] {"-help"},
            new PrintStream(java.io.OutputStream.nullOutputStream()));

        assertThat(code).isZero();
    }
}
