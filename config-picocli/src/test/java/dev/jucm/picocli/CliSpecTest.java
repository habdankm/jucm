package dev.jucm.picocli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.jucm.core.ConfigDefinition;
import dev.jucm.core.ConfigField;
import dev.jucm.core.ConfigType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.MissingParameterException;
import picocli.CommandLine.Model.CommandSpec;

/**
 * The CLI spec derived from the model: names from the canonical path, aliases from the declaration,
 * `bool` as a raw literal, engine-reserved options, no defaults from picocli.
 */
class CliSpecTest {

    private static final CommandSpec SPEC = CliSpec.of(definition());

    private static ConfigDefinition definition() {
        return ConfigDefinition.of(
            ConfigField.required("server.port", ConfigType.INT).described("HTTP port").aliased("p"),
            ConfigField.required("server.max_connections", ConfigType.INT),
            ConfigField.required("server.tls", ConfigType.BOOL).described("TLS enabled"),
            ConfigField.required("ratio", ConfigType.FLOAT),
            ConfigField.required("name", ConfigType.STR));
    }

    private static Object parsed(String... args) {
        CommandLine parsed = new CommandLine(SPEC);
        parsed.parseArgs(args);
        return parsed.getParseResult().matchedOptionValue("--server-tls",
            parsed.getParseResult().matchedOptionValue("--server-port", null));
    }

    @Test
    @DisplayName("`-help` is an ENGINE option (one dash), so `--help` is FREE for a model field of that name")
    void helpOptionIsSingleDashAndTheModelKeepsTheDoubleDash() {
        assertThat(SPEC.findOption("-help")).as("available always, without declaring it").isNotNull();
        assertThat(SPEC.findOption("-help").usageHelp()).as("that is the request for help").isTrue();
        assertThat(SPEC.findOption("-h")).as("there is no short `-h`: short names are DECLARED, never guessed")
            .isNull();

        CommandSpec withHelpField = CliSpec.of(ConfigDefinition.of(
            ConfigField.required("help", ConfigType.STR)));

        assertThat(withHelpField.findOption("--help")).as("the field's own option: two dashes")
            .isNotNull();
        assertThat(withHelpField.findOption("--help").usageHelp())
            .as("the field is NOT help — it takes a value").isFalse();
        assertThat(withHelpField.findOption("-help")).as("the engine's help sits next to it: one dash")
            .isNotNull();
        assertThat(withHelpField.findOption("-help").usageHelp()).isTrue();
    }

    @Test
    @DisplayName("an ALIAS `help` is a model error — `-help` is the engine's, and the short space is shared")
    void aliasHelpIsAModelError() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> CliSpec.of(ConfigDefinition.of(
                ConfigField.required("host", ConfigType.STR).aliased("help"))))
            .withMessageContaining("-help")
            .as("the message says which form the model keeps").withMessageContaining("`--help`");
    }

    @Test
    @DisplayName("engine-RESERVED actions are subcommands — otherwise they cannot be invoked at all")
    void reservedActionsAreSubcommands() {
        // The reserved actions are registered as subcommands just like application actions; a token
        // that landed in `unmatched()` would be rejected by `CliSource` as an unknown action, while
        // the message about unknown actions listed them as KNOWN.
        CommandLine line = new CommandLine(SPEC);
        line.setUnmatchedArgumentsAllowed(true);
        line.parseArgs("config-validation");

        assertThat(line.getParseResult().subcommand()).as("the action selected as a subcommand").isNotNull();
        assertThat(CliSource.selectedAction(line.getParseResult())).contains("config-validation");
        assertThat(CliSource.read(line.getParseResult()))
            .as("the reader does not reject it as an unknown bare word").isNotNull();
        assertThat(SPEC.subcommands().keySet()).as("both reserved ones are in the spec")
            .contains("config-validation", "config-generate");
    }

    @Test
    @DisplayName("the long name comes from the canonical path: a dot becomes a dash, an underscore stays")
    void longNamesComeFromCanonicalPath() {
        assertThat(SPEC.findOption("--server-port")).isNotNull();
        assertThat(SPEC.findOption("--server-max_connections")).as("the underscore does NOT become a dash").isNotNull();
        assertThat(SPEC.findOption("--server-max-connections")).as("and there is no dash made from it").isNull();
    }

    @Test
    @DisplayName("a short alias comes from the field declaration — next to `description`, not in a CLI registry")
    void aliasesComeFromTheField() {
        assertThat(SPEC.findOption("--server-port").names()).contains("-p");
        assertThat(SPEC.findOption("-p")).as("the alias leads to the same option").isSameAs(
            SPEC.findOption("--server-port"));
    }

    @Test
    @DisplayName("`bool` takes the RAW literal: `--server-tls=false` arrives as text")
    void booleanIsTakenAsRawText() {
        assertThat(SPEC.findOption("--server-tls").type()).isEqualTo(String.class);
        assertThat(SPEC.findOption("--server-tls").arity().max()).isEqualTo(1);

        assertThat(parsed("--server-tls=false")).isEqualTo("false");
        assertThat(parsed("--server-tls=TRUE")).as("our validator decides about letter case").isEqualTo("TRUE");
    }

    @Test
    @DisplayName("`--server-tls=yes` does NOT end silently (picocli on `Boolean` would answer `null`)")
    void booleanLiteralsAreNotSilentlyAccepted() {
        assertThat(parsed("--server-tls=yes"))
            .as("picocli lets it through and only our validator rejects it — that is why we take text")
            .isEqualTo("yes");
    }

    @Test
    @DisplayName("a bare `--server-tls` is a usage error (no value), not a silent `true`")
    void bareBooleanFlagIsAUsageError() {
        assertThatExceptionOfType(MissingParameterException.class)
            .isThrownBy(() -> new CommandLine(SPEC).parseArgs("--server-tls"))
            .withMessageContaining("--server-tls");
    }

    @Test
    @DisplayName("numbers are TEXT too: `--server-port 010` arrives as `010`, not as 10")
    void numbersKeepTheirWrittenForm() {
        assertThat(parsed("--server-port", "010")).as("the notation is shared by all sources")
            .isEqualTo("010");
        assertThat(SPEC.findOption("--ratio").type()).isEqualTo(String.class);
    }

    @Test
    @DisplayName("help comes from the model metadata, and picocli injects no defaults")
    void helpFromModelAndNoDefaults() {
        String usage = new CommandLine(SPEC).getUsageMessage();

        assertThat(usage).contains("--server-port").contains("HTTP port").contains("-p,");
        assertThat(usage).contains("--server-tls").contains("TLS enabled");
        assertThat(usage).as("the help of the engine is single-dash, and it is listed like any option")
            .contains("-help").doesNotContain("-h,");
        assertThat(SPEC.findOption("--server-port").defaultValue())
            .as("the default lives in the COMPILED layer — otherwise the origin would lie about the layer")
            .isNull();
    }

    @Test
    @DisplayName("`-strict-config-parser` is always in the spec, even though the model does not have it")
    void strictParserIsAlwaysThere() {
        assertThat(SPEC.findOption("-strict-config-parser")).isNotNull();
        assertThat(SPEC.findOption("-strict-config-parser").arity().max()).isEqualTo(1);
    }

    @Test
    @DisplayName("`--strict-config-parser` belongs to the MODEL — the engine's form is the single-dash one")
    void strictParserLongNameBelongsToTheModel() {
        ConfigDefinition own = ConfigDefinition.of(
            ConfigField.required("strict.config.parser", ConfigType.STR));

        var spec = CliSpec.of(own);

        assertThat(spec.findOption("--strict-config-parser")).as("the field's own option").isNotNull();
        assertThat(spec.findOption("-strict-config-parser")).as("the engine's switch stays where it was")
            .isNotNull();
        assertThat(spec.findOption("-strict-config-parser").arity().max()).isEqualTo(1);
    }

    @Test
    @DisplayName("the engine brings no aliases: the short space is the application's, so `-v`/`-h` do not exist")
    void engineClaimsNoShortNames() {
        assertThat(SPEC.findOption("-v")).isNull();
        assertThat(SPEC.findOption("-h")).isNull();
        assertThat(SPEC.findOption("-V")).as("the standard picocli `-V/--version` MUST NOT be enabled")
            .isNull();
        assertThat(SPEC.findOption("-p")).as("while this one is an alias DECLARED on a field").isNotNull();
    }

    @Test
    @DisplayName("a multi-character short name is legal when it is declared (`-version`)")
    void multiCharacterShortNameWorksWhenDeclared() {
        CommandSpec spec = CliSpec.of(ConfigDefinition.of(
            ConfigField.required("port", ConfigType.INT).aliased("version")));

        assertThat(spec.findOption("-version")).isNotNull();
        CommandLine line = new CommandLine(spec);
        line.parseArgs("-version", "8080");
        assertThat(line.getParseResult().matchedOptionValue("-version", "")).isEqualTo("8080");
    }

    @Test
    @DisplayName("a field name cannot take an ACTION name — a reserved one and an unimplemented one alike")
    void fieldCannotTakeAnActionName() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> CliSpec.of(ConfigDefinition.of(
                ConfigField.required("config.validation", ConfigType.STR))))
            .withMessageContaining("config-validation");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> CliSpec.of(ConfigDefinition.of(
                ConfigField.required("config.generate", ConfigType.STR))))
            .as("`config-generate` is reserved even though it is not implemented yet")
            .withMessageContaining("config-generate");
    }

    @Test
    @DisplayName("alias uniqueness is enforced by the MODEL, before a spec even exists")
    void duplicateAliasIsAModelError() {
        assertThatIllegalArgumentException()
            .as("`ConfigDefinition` rejects a repeated alias on its own — the CLI does not repeat that")
            .isThrownBy(() -> ConfigDefinition.of(
                ConfigField.required("a", ConfigType.STR).aliased("x"),
                ConfigField.required("b", ConfigType.STR).aliased("x")))
            .withMessageContaining("MUST be unique");
    }
}
