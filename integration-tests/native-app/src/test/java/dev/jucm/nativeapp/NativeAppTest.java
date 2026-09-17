package dev.jucm.nativeapp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jucm.casciian.DraftWriter;
import dev.jucm.core.ConfigPatch;
import dev.jucm.core.ConfigPath;
import dev.jucm.core.ConfigSource;
import dev.jucm.core.ConfigString;
import dev.jucm.core.Layer;
import dev.jucm.core.SourceDeclaration;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The same application that we run as a native binary — here on the JVM, so the difference is visible
 * right away. Everything asserted is ASCII and deterministic, so the same set can be repeated on macOS
 * and Windows.
 */
class NativeAppTest {

    private record Run(int code, String out, String err) {
    }

    private static Run run(Path directory, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code;
        try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
             PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            code = NativeApp.run(args, outStream, errStream, directory);
        }
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static void yaml(Path directory, String content) throws Exception {
        Files.writeString(directory.resolve("application.yaml"), content, StandardCharsets.UTF_8);
    }

    /** The overlay from a configurator SESSION — written with EXACTLY the writer the `Save` button uses. */
    private static void saved(Path directory, String rule) throws Exception {
        DraftWriter.write(ConfigPatch.set(ConfigPath.of("port"), ConfigString.of("8080"),
            ConfigSource.of(Layer.TUI, "port"), SourceDeclaration.constraints(rule)),
            directory.resolve("application.applied.yaml"));
    }

    @Test
    @DisplayName("default values from the model: `uri` computed from `host` and `port` (interpolation in a value)")
    void defaultsComeFromTheModel() {
        Run run = run(Path.of("no-such-directory"));

        assertThat(run.code()).as("a missing file is not an error — the values come from code").isZero();
        assertThat(run.out()).contains("host=localhost").contains("port=8080")
            .contains("uri=http://localhost:8080/api")
            .contains("layer.host=COMPILED").contains("layer.uri=COMPILED");
    }

    @Test
    @DisplayName("the file sets host and port, and `uri` is recomputed from them (@TempDir, relative path)")
    void fileFeedsTheInterpolation() throws Exception {
        Path directory = Files.createTempDirectory("uri-app-test");
        try {
            yaml(directory, "host: example.org\nport: 443\n");

            Run run = run(directory);

            assertThat(run.code()).isZero();
            assertThat(run.out()).contains("uri=http://example.org:443/api")
                .contains("layer.host=YAML").contains("layer.port=YAML")
                .as("the layer says WHO provided the value: nobody provided `uri`, it comes from the model and is only recomputed")
                .contains("layer.uri=COMPILED");
        } finally {
            Files.deleteIfExists(directory.resolve("application.yaml"));
            Files.deleteIfExists(directory);
        }
    }

    @Test
    @DisplayName("the command line overrides the file — and that is visible in the origin of every field")
    void commandLineWins() throws Exception {
        Path directory = Files.createTempDirectory("uri-app-test");
        try {
            yaml(directory, "host: example.org\nport: 443\n");

            Run run = run(directory, "--port", "8080");

            assertThat(run.code()).isZero();
            assertThat(run.out()).contains("port=8080").contains("layer.port=CLI")
                .contains("uri=http://example.org:8080/api")
                .as("`host` stayed from the file, so `uri` is computed from both layers").contains("layer.host=YAML");
        } finally {
            Files.deleteIfExists(directory.resolve("application.yaml"));
            Files.deleteIfExists(directory);
        }
    }

    @Test
    @DisplayName("the oddity a binary may show only in a binary: pre-cast notation in an int field")
    void preCastTextLeaksIntoTheInterpolatedValue() throws Exception {
        Path directory = Files.createTempDirectory("uri-app-test");
        try {
            yaml(directory, "host: localhost\nport: 0673\n");

            Run run = run(directory);

            assertThat(run.out()).as("the value itself is typed: 443 in octal").contains("port=443");
            assertThat(run.out())
                .as("while `uri` glues together the NOTATION from before the cast — that is the pinned contract")
                .contains("uri=http://localhost:0673/api");
        } finally {
            Files.deleteIfExists(directory.resolve("application.yaml"));
            Files.deleteIfExists(directory);
        }
    }

    @Test
    @DisplayName("the RULE from the model: the port is 8080 or 443 only — a foreign port is `validation-failed` with the chain")
    void portRuleAllowsOnlyTwoValues() throws Exception {
        Path directory = Files.createTempDirectory("uri-app-test");
        try {
            yaml(directory, "host: localhost\nport: 9999\n");

            Run broken = run(directory);
            assertThat(broken.code()).as("23 = validation-failed in the YAML layer (Y=3, source decade 2)").isEqualTo(23);
            assertThat(broken.err())
                .as("the message carries the RULE and the offending value")
                .contains("in[8080,443]")
                .contains("9999");

            Run secure = run(directory, "--port", "443");
            assertThat(secure.code()).as("443 is in the rule's list").isZero();
            assertThat(secure.out()).contains("port=443").contains("uri=http://localhost:443/api");
        } finally {
            Files.deleteIfExists(directory.resolve("application.yaml"));
            Files.deleteIfExists(directory);
        }
    }

    @Test
    @DisplayName("a picocli usage error gives 125 — a BARE option too; an unknown option WITH a value stays auxiliary (0)")
    void usageErrorVersusUnknownOption() {
        Run broken = run(Path.of("no-such-directory"), "--port");

        assertThat(broken.code()).as("125 = a missing option value").isEqualTo(125);
        assertThat(broken.err()).as("a single-line, ASCII message").startsWith("error=USAGE message=");

        Run bareTypo = run(Path.of("no-such-directory"), "--prot");

        assertThat(bareTypo.code()).as("a bare option sets nothing, whatever its name")
            .isEqualTo(125);
        assertThat(bareTypo.err()).startsWith("error=USAGE message=").contains("--prot");

        // The one SUGGESTION the engine makes: `--help` with two dashes, while the help of the engine is
        // `-help`. Any other bare name keeps the plain message (the boundary is the value, not the name).
        Run doubleDashHelp = run(Path.of("no-such-directory"), "--help");

        assertThat(doubleDashHelp.code()).as("still a usage error (125) — the code does not change")
            .isEqualTo(125);
        assertThat(doubleDashHelp.err())
            .as("the message names the form the user probably meant")
            .contains("`--help`").contains("did you mean `-help`");
        assertThat(bareTypo.err()).as("no suggestion for a name that is not an engine option")
            .doesNotContain("did you mean");

        Run typo = run(Path.of("no-such-directory"), "--prot", "9000");

        assertThat(typo.code()).as("a typo WITH a value is an auxiliary key nobody will use — not a usage error")
            .isZero();
        assertThat(typo.err()).as("and it does not clutter the errors").isEmpty();
    }

    @Test
    @DisplayName("`-help`: the help from the model, the PROGRAM and the ENV prefix, plus the application's own section")
    void helpShowsTheModelAndTheApplicationSection() throws Exception {
        Run run = run(Path.of("no-such-directory"), "-help");

        assertThat(run.code()).isZero();
        assertThat(run.out())
            .as("from the model: the field description and the type").contains("--port=<int>")
            .as("the program name of THIS application, not picocli's placeholder").contains("Usage: uri-app ")
            .doesNotContain("<main class>")
            .as("the ENV prefix of this application, with the variable of a declared field")
            .contains("`URIAPP`").contains("URIAPP__HOST")
            .as("from the application: its own section").contains("URI-APP").contains("config-validation");
        assertThat(run.err()).as("nothing on the error stream").isEmpty();

        Run action = run(Path.of("no-such-directory"), "config-validation", "-help");
        assertThat(action.code()).isZero();
        assertThat(action.out()).as("an action's help carries the same facts")
            .contains("Usage: uri-app config-validation ").contains("URIAPP__HOST")
            .as("and the action does not run on the way").doesNotContain("OK: ");
    }

    @Test
    @DisplayName("CLOSED LOOP: what `Save` writes the application READS back and enforces (a rule from a file)")
    void savedRuleIsReadBackAndEnforced() throws Exception {
        Path directory = Files.createTempDirectory("uri-app-test");
        yaml(directory, "host: example.org\nport: 8080\n");

        // A rule from a TUI session, REPLACING the rule from the code: 443 only.
        saved(directory, "in[443]");
        Run broken = run(directory);
        assertThat(broken.code()).as("23 = validation-failed in the overlay layer (YAML)").isEqualTo(23);
        assertThat(broken.err()).as("the message carries the rule FROM THE FILE and points at the file")
            .contains("in[443]").contains("application.applied.yaml");

        // The same value, but the rule from the session admits it — proof that the rule from the file decides.
        saved(directory, "in[8080,443]");
        Run fine = run(directory);
        assertThat(fine.code()).as("in accordance with the rule from the session").isZero();
        assertThat(fine.out()).contains("port=8080");
    }
}
