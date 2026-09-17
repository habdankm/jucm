package dev.jucm.picocli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.jucm.core.ConfigDefinition;
import dev.jucm.core.ConfigField;
import dev.jucm.core.ConfigPatch;
import dev.jucm.core.ConfigResult;
import dev.jucm.core.ConfigType;
import dev.jucm.core.ConfigValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.ParseResult;

/**
 * APPLICATION ACTIONS: bare words, extensible on the application side, so `--tui` would be setting the
 * field `tui` while the mode is started with a word — `my-app tui --port 8080`.
 *
 * <p>The contract pinned here: options work on BOTH sides of the action, an action is not a
 * configuration entry, and reserved names and repetitions are a MODEL error.
 */
class AppActionsTest {

    private static ConfigDefinition definition() {
        return ConfigDefinition.of(
            ConfigField.required("host", ConfigType.STR),
            ConfigField.required("port", ConfigType.INT));
    }

    private static ParseResult parse(String... args) {
        CommandLine line = new CommandLine(CliSpec.of(definition(), "tui"));
        line.setUnmatchedArgumentsAllowed(true);
        return line.parseArgs(args);
    }

    private static String value(ConfigResult<ConfigPatch> patch, String path) {
        ConfigPatch applied = ((ConfigResult.ValueResult<ConfigPatch>) patch).value();
        return ((ConfigPatch.SetValue) applied.operation(path).orElseThrow()).value().display();
    }

    @Test
    @DisplayName("`tui --port 8080` and `--port 8080 tui` mean the same: an action plus configuration")
    void actionWorksOnBothSides() {
        for (String[] args : new String[][] {{"tui", "--port", "8080"}, {"--port", "8080", "tui"}}) {
            ParseResult parsed = parse(args);

            assertThat(CliSource.selectedAction(parsed)).as("selected action: %s", String.join(" ", args))
                .contains("tui");
            assertThat(value(CliSource.read(parsed), "port")).as("the option works: %s", String.join(" ", args))
                .isEqualTo("8080");
        }
    }

    @Test
    @DisplayName("an action is NOT a configuration entry — the patch carries fields only")
    void actionIsNotAnEntry() {
        ConfigResult<ConfigPatch> patch = CliSource.read(parse("tui", "--host", "example.org"));

        assertThat(value(patch, "host")).isEqualTo("example.org");
        assertThat(((ConfigResult.ValueResult<ConfigPatch>) patch).value().paths())
            .extracting(path -> path.canonical())
            .as("`tui` MUST NOT become an entry — it is an action, not a setting")
            .doesNotContain("tui");
    }

    @Test
    @DisplayName("without an action nothing changes: no action selected, an ordinary patch")
    void withoutActionNothingChanges() {
        ParseResult parsed = parse("--port", "9000");

        assertThat(CliSource.selectedAction(parsed)).isEmpty();
        assertThat(value(CliSource.read(parsed), "port")).isEqualTo("9000");
    }

    @Test
    @DisplayName("an unknown bare word says which actions are known — the application's one included")
    void unknownActionListsKnownOnes() {
        ConfigResult<ConfigPatch> patch = CliSource.read(parse("tuii", "--port", "1"));

        assertThat(patch).isInstanceOf(ConfigResult.ErrorResult.class);
        assertThat(((ConfigResult.ErrorResult<ConfigPatch>) patch).error().message())
            .contains("tui").contains("config-validation")
            .as("and it suggests that configuration is `--tuii`").contains("--tuii");
    }

    @Test
    @DisplayName("an action name with a dash = a model error (actions are BARE WORDS)")
    void dashedActionIsAModelError() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> CliSpec.of(definition(), "-tui"))
            .withMessageContaining("BARE WORDS");
    }

    @Test
    @DisplayName("a name reserved to the engine = a model error, not a silent override")
    void reservedActionIsAModelError() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> CliSpec.of(definition(), "config-validation"))
            .withMessageContaining("reserved");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> CliSpec.of(definition(), "tui", "tui"))
            .withMessageContaining("twice");
    }

    @Test
    @DisplayName("the same option before the action and inside it = ParseFailed (not \"the last one wins\")")
    void repeatedOptionAcrossLevelsIsRefused() {
        ConfigResult<ConfigPatch> patch = CliSource.read(parse("--port", "1", "tui", "--port", "2"));

        assertThat(patch).isInstanceOf(ConfigResult.ErrorResult.class);
        assertThat(((ConfigResult.ErrorResult<ConfigPatch>) patch).error().message())
            .contains("twice");
    }

    @Test
    @DisplayName("an alias and a type declared for a field work inside an action too")
    void declarationsWorkInsideAction() {
        ConfigDefinition definition = ConfigDefinition.of(
            ConfigField.required("port", ConfigType.INT).aliased("-p"));
        CommandLine line = new CommandLine(CliSpec.of(definition, "tui"));
        line.setUnmatchedArgumentsAllowed(true);
        ParseResult parsed = line.parseArgs("tui", "-p", "7000", "--port--type", "int");

        ConfigPatch applied = ((ConfigResult.ValueResult<ConfigPatch>) CliSource.read(parsed)).value();
        ConfigValue entry = ((ConfigPatch.SetValue) applied.operation("port").orElseThrow()).value();
        assertThat(entry.display()).isEqualTo("7000");
    }
}
