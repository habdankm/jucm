package dev.jucm.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jucm.core.Config;
import dev.jucm.core.ConfigDefinition;
import dev.jucm.core.ConfigField;
import dev.jucm.core.ConfigInt;
import dev.jucm.core.ConfigString;
import dev.jucm.core.ConfigType;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The four faces of "no value", checked through the FACADE — the way an application really enters:
 * a file, the environment and the command line. Judging them by hand (building a patch directly)
 * cannot see an adapter, and every one of these rules lives in an adapter.
 *
 * <ul>
 *   <li><b>`dropped` with nothing below is `ABSENT`</b>: `${?missing}` contributes nothing, so an
 *       optional field runs (`0`) and a required one fails as missing (`14`) — never as the
 *       emptiness error (`20`).</li>
 *   <li><b>An empty environment variable is ignored</b>: a variable left without a value states
 *       nothing about the address, so no entry is produced, an optional field still runs and a
 *       required one is `ABSENT`. A **marker** with an empty payload is the exception and stays a
 *       loud source error.</li>
 *   <li><b>A bare option token is a usage error</b> (`125`) and the application is not started — for a
 *       DECLARED option (`--port`) and for an UNKNOWN one (`--prot`) alike: someone meant to set a
 *       parameter and set nothing. The boundary is the VALUE, not the name: an unknown option WITH a
 *       value (`--prot 9000`) stays an auxiliary key (`0`).</li>
 *   <li><b>Emptiness written on purpose is still an error</b>: `--host ""` (`40`) and `x: ""` in a
 *       file (`20`).</li>
 * </ul>
 */
class AbsenceAndEmptinessTest {

    /** A directory that does not exist: a legitimate fixture, so the values come from the model. */
    private static final Path NO_DIRECTORY = Path.of("no-such-directory");

    private static ConfigDefinition optionalHost() {
        return ConfigDefinition.of(
            ConfigField.optional("host", ConfigType.STR),
            ConfigField.required("port", ConfigType.INT, ConfigInt.of(8080)));
    }

    private static ConfigDefinition requiredHost() {
        return ConfigDefinition.of(
            ConfigField.required("host", ConfigType.STR),
            ConfigField.required("port", ConfigType.INT, ConfigInt.of(8080)));
    }

    private static ConfigDefinition declaredBoth() {
        return ConfigDefinition.of(
            ConfigField.required("host", ConfigType.STR, ConfigString.of("localhost")),
            ConfigField.required("port", ConfigType.INT, ConfigInt.of(8080)));
    }

    /** The result of one run: exit code, output and errors — everything the user sees. */
    private record Ran(int code, String out, String err, Config config) {
    }

    private static Ran run(ConfigTool tool, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        List<Config> seen = new ArrayList<>();
        int code = tool
            .onRun(seen::add)
            .run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Ran(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8),
            seen.isEmpty() ? null : seen.get(0));
    }

    private static void write(Path file, String text) throws Exception {
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("(a) `dropped` with nothing below is ABSENT: optional runs (0), required is 14 — never 20")
    void droppedWithoutAnythingBelowIsAbsent(@TempDir Path dir) throws Exception {
        write(dir.resolve("application.yaml"), "host: \"${?missing}\"\nport: 8080\n");

        Ran optional = run(ConfigTool.of(optionalHost()).sources(dir));
        assertThat(optional.code()).as("ABSENT is a valid state for an optional field").isZero();
        assertThat(optional.config()).as("and the application runs").isNotNull();
        assertThat(optional.err()).as("no problem at all — in particular not the emptiness error").isEmpty();

        Ran required = run(ConfigTool.of(requiredHost()).sources(dir));
        assertThat(required.code())
            .as("14 = ABSENT, settled by the declaration (COMPILED sits at position 1)").isEqualTo(14);
        assertThat(required.err()).as("the message names a missing value, not an empty one")
            .contains("absent").doesNotContain("null-value");
        assertThat(required.config()).as("a broken configuration does not reach the application").isNull();
    }

    @Test
    @DisplayName("(b) an empty ENV variable enters NOTHING: optional runs (0), required is 14, a marker is loud")
    void anEmptyEnvironmentVariableIsIgnored(@TempDir Path dir) throws Exception {
        Map<String, String> empty = new LinkedHashMap<>();
        empty.put("APP__HOST", "");

        Ran optional = run(ConfigTool.of(optionalHost()).sources(NO_DIRECTORY).env("APP", empty));
        assertThat(optional.code()).as("the variable brings in nothing, so the field stays absent").isZero();
        assertThat(optional.err()).as("and there is nothing to report about it").isEmpty();

        Ran required = run(ConfigTool.of(requiredHost()).sources(NO_DIRECTORY).env("APP", empty));
        assertThat(required.code())
            .as("14 = ABSENT from the declaration; 30 would be the ENV emptiness error").isEqualTo(14);
        assertThat(required.err()).as("the empty variable is not a value, so it cannot be an empty one")
            .doesNotContain("null-value");

        Ran overDefault = run(ConfigTool.of(declaredBoth()).sources(NO_DIRECTORY).env("APP", empty));
        assertThat(overDefault.code()).as("an empty variable does not push a declared default out of the way")
            .isZero();
        assertThat(ConfigTool.display(overDefault.config(), "host")).isEqualTo("localhost");
        assertThat(overDefault.out()).as("the value comes from the declaration, not from the empty layer")
            .contains("layer.host=COMPILED");

        // The exception: a MARKER with an empty payload is a statement that states nothing — a source
        // error. Silently ignoring it would be a rule that does not work for reasons nobody can see.
        Map<String, String> marker = new LinkedHashMap<>();
        marker.put("APP__PORT___TYPE", "");
        Ran loud = run(ConfigTool.of(declaredBoth()).sources(NO_DIRECTORY).env("APP", marker));
        assertThat(loud.code()).as("30 = the source cannot be read").isEqualTo(30);
        assertThat(loud.err()).contains("marker without a value");
        assertThat(loud.config()).as("and the application is not started").isNull();
    }

    @Test
    @DisplayName("(c) a bare option sets nothing: `--port` and `--prot` are BOTH usage errors (125)")
    void aBareOptionIsAUsageErrorWhateverItsName() {
        Ran declared = run(ConfigTool.of(declaredBoth()).sources(NO_DIRECTORY), "--port");

        assertThat(declared.code()).as("125 = a usage error, outside the configuration families").isEqualTo(125);
        assertThat(declared.err()).as("a single-line message from the parser").startsWith("error=USAGE");
        assertThat(declared.config()).as("the application is not started").isNull();
        assertThat(declared.out()).as("and no origin report is printed").isEmpty();

        // The NAME decides nothing here: someone who writes an option and no value meant to set a
        // parameter and set nothing — an empty auxiliary key would let the mistyped line run as if it
        // were correct.
        Ran bareTypo = run(ConfigTool.of(declaredBoth()).sources(NO_DIRECTORY), "--prot");

        assertThat(bareTypo.code()).as("no value and no name in the schema — still 125").isEqualTo(125);
        assertThat(bareTypo.err()).startsWith("error=USAGE").contains("--prot");
        assertThat(bareTypo.config()).as("the application is not started").isNull();

        // The boundary is the VALUE, not the name: with a value, an unknown option IS an auxiliary key
        // and the run continues — the point of accepting it is to be able to report it.
        Ran typoWithValue = run(ConfigTool.of(declaredBoth()).sources(NO_DIRECTORY), "--prot", "9000");

        assertThat(typoWithValue.code()).as("a typo WITH a value is auxiliary, not a usage error").isZero();
        assertThat(typoWithValue.config()).as("and the application runs").isNotNull();
    }

    @Test
    @DisplayName("(d) emptiness written on purpose IS an error: `--host \"\"` is 40, `x: \"\"` in a file is 20")
    void emptinessWrittenOnPurposeIsStillAnError(@TempDir Path dir) throws Exception {
        Ran argument = run(ConfigTool.of(declaredBoth()).sources(NO_DIRECTORY), "--host", "");

        assertThat(argument.code()).as("40 = emptiness in the command line (CLI position 4, category 0)")
            .isEqualTo(40);
        assertThat(argument.err()).contains("null-value").contains("--host");
        assertThat(argument.config()).as("the application is not started").isNull();

        write(dir.resolve("application.yaml"), "host: \"\"\nport: 8080\n");
        Ran node = run(ConfigTool.of(declaredBoth()).sources(dir));

        assertThat(node.code()).as("20 = emptiness in the file (YAML position 2, category 0)").isEqualTo(20);
        assertThat(node.err()).contains("null-value");
        assertThat(node.config()).as("the application is not started").isNull();
    }
}
