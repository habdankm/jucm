/*
 * The three outputs of the configurator (save, YAML, command line) seen as TEXT. Each of them has to
 * carry the rules: without them the written file would reproduce values without the rules that bind
 * them.
 */
package dev.jucm.casciian;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jucm.core.ConfigConstraint;
import dev.jucm.core.ConfigDefinition;
import dev.jucm.core.ConfigDraft;
import dev.jucm.core.ConfigField;
import dev.jucm.core.ConfigInt;
import dev.jucm.core.ConfigPath;
import dev.jucm.core.ConfigPatch;
import dev.jucm.core.ConfigSource;
import dev.jucm.core.ConfigString;
import dev.jucm.core.ConfigType;
import dev.jucm.core.Layer;
import dev.jucm.core.SourceDeclaration;
import dev.jucm.core.TransformerRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DraftWriterTest {

    private static final TransformerRegistry REGISTRY = TransformerRegistry.defaults();

    private static ConfigDefinition model() {
        return ConfigDefinition.of(
            ConfigField.required("host", ConfigType.STR, ConfigString.of("localhost")),
            ConfigField.required("port", ConfigType.INT, ConfigString.of("8080"))
                .constrained(ConfigConstraint.Const("in[8080,443]")));
    }

    /** A draft as it looks after a session: {@code host} from the file, {@code port} written with a rule. */
    private static ConfigDraft draftWith(String rule) {
        ConfigPatch initial = ConfigPatch.set(ConfigPath.of("host"), ConfigString.of("example.org"),
            ConfigSource.of(Layer.YAML, "application.yaml"));
        ConfigPatch port = rule.isEmpty()
            ? ConfigPatch.set(ConfigPath.of("port"), ConfigString.of("443"))
            : ConfigPatch.set(ConfigPath.of("port"), ConfigString.of("443"),
                ConfigSource.of(Layer.TUI, "port"), SourceDeclaration.constraints(rule));
        return ConfigDraft.of(model(), REGISTRY, initial, port);
    }

    @Test
    @DisplayName("save: a rule given in the session travels in a comment next to the value, same grammar as in YAML")
    void saveCarriesTheRule() throws Exception {
        ConfigDraft draft = draftWith("");            // layers: `host` from the file, `port` from the declaration
        draft.edit(ConfigPath.of("port"), ConfigString.of("443"), ConfigSource.of(Layer.TUI, "port"),
            SourceDeclaration.constraints("in[8080,443]"));

        Path file = Files.createTempDirectory("jucm-save").resolve("application.applied.yaml");
        int written = DraftWriter.write(draft.patch(), file);

        assertThat(written).as("`patch()` holds the SESSION changes only — a single field").isEqualTo(1);
        String text = Files.readString(file);
        assertThat(text).as("the rule next to the value, in a comment on the SAME LINE")
            .contains("port: \"443\"   # CONST(in[8080,443])");
        assertThat(text).as("the layers below (the host from the file) are not copied into the overlay")
            .doesNotContain("host:");
    }

    @Test
    @DisplayName("YAML: the rule comes from the SESSION, and from the field declaration when the session gave none")
    void yamlCarriesRules() {
        String session = DraftWriter.asYaml(model(), draftWith("in[443]"));
        assertThat(session).as("the session overrides the rule from code, and the session rule is the one printed")
            .contains("port: \"443\"   # CONST(in[443])");

        String fromCode = DraftWriter.asYaml(model(), draftWith(""));
        assertThat(fromCode).as("with no session rule the declared rule of the field is written")
            .contains("port: \"443\"   # CONST(in[8080,443])");
    }

    @Test
    @DisplayName("command line: the rule is appended as `--port--const`, in single quotes (`&` and `|` belong to the shell)")
    void cliCarriesRules() {
        String line = DraftWriter.asCommandLine(model(), draftWith(">1&<10"), "uri-app");
        assertThat(line).contains("--port--const '>1&<10'");
        assertThat(line).as("values and flags unchanged — the rule is appended, nothing is replaced")
            .contains("--port 443");

        String script = DraftWriter.asScript(model(), draftWith(">1&<10"), "uri-app");
        assertThat(script).startsWith("#!/bin/sh");
        assertThat(script).contains("--port--const '>1&<10'");
        assertThat(script).as("trailing `\"$@\"` so run-time arguments are passed on").endsWith(" \"$@\"\n");

        assertThat(SourceDeclaration.constraints(">1&<10")).as("what was printed is a VALID chain")
            .isNotNull();
    }

    @Test
    @DisplayName("several declared rules are joined with `&` — the joined chain is VALID for the parser")
    void manyRulesJoinWithAnd() {
        ConfigDefinition two = ConfigDefinition.of(ConfigField.required("port", ConfigType.INT,
            ConfigInt.of(8080)).constrained(ConfigConstraint.Const("in[8080,443]"),
                ConfigConstraint.Const(">1024")));

        String text = DraftWriter.asYaml(two, ConfigDraft.of(two, REGISTRY));
        assertThat(text).as("validation requires ALL of them, so `&` rather than concatenation").contains(
            "# CONST(in[8080,443]&>1024)");
        assertThat(SourceDeclaration.constraints("in[8080,443]&>1024")).as("the parser accepts this")
            .isNotNull();
    }
}
