package dev.jucm.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jucm.core.Config;
import dev.jucm.core.ConfigConstraint;
import dev.jucm.core.ConfigDefinition;
import dev.jucm.core.ConfigField;
import dev.jucm.core.ConfigType;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A field declared {@code map}, filled from a YAML MAPPING — the whole way through the facade, because
 * this is where the exit codes live.
 *
 * <ul>
 *   <li><b>a mapping under a {@code map} field is the VALUE of that field</b> ({@code m: {a: 1}}), so the
 *       application runs and reads it;</li>
 *   <li><b>an empty mapping is emptiness</b> ({@code m: {}}): an empty value is an error exactly as an
 *       empty list is, and "the field may have no elements" is expressed by the field being OPTIONAL —
 *       a field with no entry at all is simply absent;</li>
 *   <li><b>a scalar under a {@code map} field is a type error</b> ({@code m: 1}): the declaration decides
 *       how the mapping is read, and the cast decides whether the value fits the type;</li>
 *   <li><b>a mapping under any other address is still a set of addresses</b>, so nothing that used to
 *       work through the nesting (`server.host`) changes.</li>
 * </ul>
 *
 * <p>The adapter-side view of the same rule (what the patch contains) is pinned in
 * {@code config-yaml/…/YamlMapValueTest}: an adapter knows nothing about exit codes, so both halves
 * have to exist.
 */
class YamlMapFieldTest {

    /** A directory that does not exist: a legitimate fixture, so the values come from the model. */
    private static final Path NO_DIRECTORY = Path.of("no-such-directory");

    /** One required map field: a mapping in the file is the only way to fill it. */
    private static ConfigDefinition requiredMap() {
        return ConfigDefinition.of(ConfigField.map("m", ConfigType.STR, ConfigType.STR));
    }

    /** An optional map field: "no elements" is expressed by the field being optional. */
    private static ConfigDefinition optionalMap() {
        return ConfigDefinition.of(ConfigField.optionalMap("m", ConfigType.STR, ConfigType.STR));
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

    private static Ran run(Path dir, String file, ConfigDefinition definition) throws Exception {
        Files.writeString(dir.resolve("application.yaml"), file, StandardCharsets.UTF_8);
        return run(ConfigTool.of(definition).sources(dir));
    }

    @Test
    @DisplayName("a mapping in the file fills a `map` field: the application runs and reads the value")
    void aMappingFillsAMapField(@TempDir Path dir) throws Exception {
        Ran flow = run(dir, "m: {a: 1}\n", requiredMap());

        assertThat(flow.code()).as("the declaration says `map`, the file spells a mapping — they agree").isZero();
        assertThat(flow.config()).as("and the application is started").isNotNull();
        assertThat(ConfigTool.display(flow.config(), "m")).isEqualTo("{a: 1}");
        assertThat(flow.err()).isEmpty();

        Ran block = run(dir, "m:\n  a: 1\n  b: two\n", requiredMap());

        assertThat(block.code()).isZero();
        assertThat(ConfigTool.display(block.config(), "m"))
            .as("the block form is the same value").isEqualTo("{a: 1, b: two}");
    }

    @Test
    @DisplayName("an EMPTY mapping is the emptiness error (20) — the same as an empty list")
    void anEmptyMappingIsEmptiness(@TempDir Path dir) throws Exception {
        Ran empty = run(dir, "m: {}\n", requiredMap());

        assertThat(empty.code()).as("20 = emptiness in the file (YAML position 2, category 0)").isEqualTo(20);
        assertThat(empty.err()).as("the message names what was empty, so the fix is clear")
            .contains("null-value").contains("empty map");
        assertThat(empty.config()).as("a broken configuration does not reach the application").isNull();

        Ran nothing = run(dir, "other: 1\n", optionalMap());

        assertThat(nothing.code()).as("a field with NO entry is absent, and absent is valid when optional")
            .isZero();
        assertThat(ConfigTool.display(nothing.config(), "m"))
            .as("nothing states a value for it — not even an empty one").isEqualTo("[none]");
    }

    @Test
    @DisplayName("a scalar under a `map` field is a TYPE error (21) — the declaration decides the type")
    void aScalarUnderAMapFieldIsATypeError(@TempDir Path dir) throws Exception {
        Ran scalar = run(dir, "m: 1\n", requiredMap());

        assertThat(scalar.code()).as("21 = a failed cast in the file (YAML position 2, category 1)").isEqualTo(21);
        assertThat(scalar.err()).as("the message names the type that was expected").contains("expected type `map`");
        assertThat(scalar.config()).as("the application is not started").isNull();
    }

    @Test
    @DisplayName("a mapping under any OTHER address stays a set of addresses — nesting keeps working")
    void aMappingUnderAnotherAddressIsSplit(@TempDir Path dir) throws Exception {
        // `server.port` is declared, `server` is not: the mapping is the address path, not a value.
        ConfigDefinition nested = ConfigDefinition.of(
            ConfigField.optional("server.port", ConfigType.INT),
            ConfigField.optionalMap("m", ConfigType.STR, ConfigType.STR));
        Ran nestedRun = run(dir, "server:\n  port: 8080\n", nested);

        assertThat(nestedRun.code()).as("the nested address is filled exactly as before").isZero();
        assertThat(ConfigTool.display(nestedRun.config(), "server.port")).isEqualTo("8080");

        // And a required `map` field stays ABSENT when the file only nests under another key.
        Ran absent = run(dir, "server:\n  port: 8080\n", requiredMap());

        assertThat(absent.code()).as("14 = ABSENT — a nested address is not a value of the map field")
            .isEqualTo(14);
        assertThat(absent.err()).contains("absent");
    }

    @Test
    @DisplayName("a whole map may also arrive from a source that delivers ONE value (ENV), unchanged")
    void aWholeMapFromEnvStillWorks(@TempDir Path dir) throws Exception {
        java.util.Map<String, String> env = new java.util.LinkedHashMap<>();
        env.put("APP__M", "{a: 1}");

        Ran fromEnv = run(ConfigTool.of(requiredMap()).sources(NO_DIRECTORY).env("APP", env));

        assertThat(fromEnv.code()).isZero();
        assertThat(ConfigTool.display(fromEnv.config(), "m")).isEqualTo("{a: 1}");
    }

    @Test
    @DisplayName("a file written from the model (`config-generate`) fills a `map` field — it reads back")
    void aGeneratedFileForAMapFieldIsReadable(@TempDir Path dir) throws Exception {
        // The generator writes a map field as a value in one line, with the rule in a comment next to it
        // (`m: {a: 1}   # CONST(keys[a])`). Both halves are now well defined: the mapping IS the value of
        // the field, and the comment stands on the pair of that value — so the file it writes is readable.
        ConfigDefinition generated = ConfigDefinition.of(
            ConfigField.map("m", ConfigType.STR, ConfigType.STR).constrained(ConfigConstraint.Const("keys[a]")));

        String text = run(ConfigTool.of(generated).sources(dir), "config-generate").out();
        assertThat(text).as("the sample file spells the map as a value").contains("m: {a: 1}");

        Files.writeString(dir.resolve("application.yaml"), text, StandardCharsets.UTF_8);
        Ran read = run(ConfigTool.of(generated).sources(dir));

        assertThat(read.code()).as("and it comes back without a problem").isZero();
        assertThat(ConfigTool.display(read.config(), "m")).isEqualTo("{a: 1}");
    }
}
