package dev.jucm.picocli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import dev.jucm.core.Config;
import dev.jucm.core.ConfigBool;
import dev.jucm.core.ConfigDefinition;
import dev.jucm.core.ConfigError;
import dev.jucm.core.ConfigField;
import dev.jucm.core.ConfigPatch;
import dev.jucm.core.ConfigSeq;
import dev.jucm.core.ConfigResult;
import dev.jucm.core.ConfigString;
import dev.jucm.core.ConfigType;
import java.util.List;
import dev.jucm.core.ConfigValidator;
import dev.jucm.core.Layer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** Reading arguments into a patch: text, the `--x--type`/`--x--tconf` markers, unknown options → auxiliary. */
class CliSourceTest {

    private static final ConfigDefinition SCHEMA = ConfigDefinition.of(
        ConfigField.required("server.port", ConfigType.INT).described("HTTP port").aliased("p"),
        ConfigField.required("server.tls", ConfigType.BOOL, ConfigBool.of(true)));

    /** A parse result as the adapter gets it: a spec from `CliSpec`, unknown options allowed. */
    private static ConfigResult<ConfigPatch> read(String... args) {
        return read(SCHEMA, args);
    }

    private static ConfigResult<ConfigPatch> read(ConfigDefinition definition, String... args) {
        CommandLine line = new CommandLine(CliSpec.of(definition));
        line.setUnmatchedArgumentsAllowed(true);
        line.parseArgs(args);
        return CliSource.read(line.getParseResult());
    }

    private static ConfigPatch patch(String... args) {
        ConfigResult<ConfigPatch> result = read(args);
        assertThat(result).as("expected entries, got an error").isInstanceOf(ConfigResult.ValueResult.class);
        return ((ConfigResult.ValueResult<ConfigPatch>) result).value();
    }

    /** The same, for a model of its own — the schema of this test may not declare the field under test. */
    private static ConfigPatch patch(ConfigDefinition definition, String... args) {
        ConfigResult<ConfigPatch> result = read(definition, args);
        assertThat(result).as("expected entries, got an error").isInstanceOf(ConfigResult.ValueResult.class);
        return ((ConfigResult.ValueResult<ConfigPatch>) result).value();
    }

    private static ConfigError error(String... args) {
        ConfigResult<ConfigPatch> result = read(args);
        assertThat(result).as("expected an error, got entries").isInstanceOf(ConfigResult.ErrorResult.class);
        return ((ConfigResult.ErrorResult<ConfigPatch>) result).error();
    }

    private static ConfigPatch.SetValue entry(ConfigPatch patch, String path) {
        return (ConfigPatch.SetValue) patch.operation(path).orElseThrow();
    }

    @Nested
    @DisplayName("values and origin")
    class Values {

        @Test
        @DisplayName("a declared option lands on its address, with the CLI layer and the option name as origin")
        void declaredOptionBecomesEntry() {
            ConfigPatch patch = patch("--server-port", "8080");

            assertThat(entry(patch, "server.port").value().display()).isEqualTo("8080");
            assertThat(entry(patch, "server.port").source().layer()).isEqualTo(Layer.CLI);
            assertThat(entry(patch, "server.port").source().origin()).isEqualTo("--server-port");
        }

        @Test
        @DisplayName("the alias `-p` lands on the SAME address — the long name is the address, not an alias")
        void aliasLandsOnTheSameAddress() {
            ConfigPatch patch = patch("-p", "8080");

            assertThat(patch.paths()).hasSize(1);
            assertThat(entry(patch, "server.port").value().display()).isEqualTo("8080");
        }

        @Test
        @DisplayName("the value is TEXT: `010` arrives as `010`, and only the validator types it")
        void valueStaysText() {
            ConfigPatch patch = patch("--server-port", "010");

            assertThat(entry(patch, "server.port").value().display()).isEqualTo("010");
            ConfigValidator.Report report = ConfigValidator.build(SCHEMA, Config.root(SCHEMA).apply(patch));

            assertThat(report.hasErrors()).as("the CLI brought in text and the validator turned it into a number")
                .isFalse();
            assertThat(report.values()).anySatisfy(value -> {
                if (value.path().canonical().equals("server.port")) {
                    assertThat(value.value().display()).isEqualTo("8");
                }
            });
        }

        @Test
        @DisplayName("`-strict-config-parser` is NOT an entry — it is an engine option")
        void engineOptionIsNotAnEntry() {
            ConfigPatch patch = patch("--server-port", "8080", "-strict-config-parser", "true");

            assertThat(patch.paths()).extracting(path -> path.canonical()).containsExactly("server.port");
        }
    }

    @Nested
    @DisplayName("the `--x--type` / `--x--tconf` / `--x--const` markers")
    class Markers {

        @Test
        @DisplayName("`--x--const` gives a RULE to a key outside the schema, next to the type from `--x--type`")
        void constMarkerOnAuxiliary() {
            ConfigPatch patch = patch("--default-port", "9000", "--default-port--type", "int",
                "--default-port--const", ">=1024&<=65535");

            assertThat(entry(patch, "default.port").declaration().type()).contains(ConfigType.INT);
            assertThat(entry(patch, "default.port").declaration().constraints()).contains(">=1024&<=65535");
        }

        @Test
        @DisplayName("a broken chain in `--x--const` is a SOURCE error naming the option, not an exception")
        void brokenChainNamesTheOption() {
            ConfigResult<ConfigPatch> read = read("--default-port", "9000", "--default-port--type", "int",
                "--default-port--const", "<2>0");

            assertThat(read.isValue()).as("an error, not an exception").isFalse();
            assertThat(((ConfigResult.ErrorResult<ConfigPatch>) read).error().report())
                .contains("--const")
                .contains("missing separator");
        }

        @Test
        @DisplayName("a type from a marker reaches the entry of a key OUTSIDE the schema (`--default-port--type int`)")
        void typeMarkerOnAuxiliary() {
            ConfigPatch patch = patch("--default-port", "9000", "--default-port--type", "int");

            assertThat(entry(patch, "default.port").declaration().type()).contains(ConfigType.INT);
            assertThat(entry(patch, "default.port").value().display()).isEqualTo("9000");
        }

        @Test
        @DisplayName("`--x--type=int` in a single token works the same")
        void typeMarkerInOneToken() {
            assertThat(entry(patch("--default-port", "9000", "--default-port--type=int"), "default.port")
                .declaration().type()).contains(ConfigType.INT);
        }

        @Test
        @DisplayName("`--x--tconf` carries the CONFIGURATION of the notation, not a transformer name")
        void tconfMarkerCarriesConfigOnly() {
            ConfigPatch patch = patch("-strict-config-parser", "true", "--start", "07.03.2026",
                "--start--type", "timestamp", "--start--tconf", "dd.MM.yyyy");

            assertThat(entry(patch, "start").declaration().transformerConfig()).contains("dd.MM.yyyy");
            assertThat(entry(patch, "start").declaration().type()).contains(ConfigType.TIMESTAMP);
        }

        @Test
        @DisplayName("a marker without a value is a parse error, never a silent skip")
        void markerWithoutValueIsAnError() {
            ConfigError error = error("--server-port", "8080", "--server-port--type");

            assertThat(error.kind()).isEqualTo(ConfigError.Kind.PARSE_FAILED);
            assertThat(error.report()).contains("without a value");
        }

        @Test
        @DisplayName("a marker without the option it refers to is an error too")
        void markerWithoutAnOptionIsAnError() {
            ConfigError error = error("--default-port--type", "int");

            assertThat(error.kind()).isEqualTo(ConfigError.Kind.PARSE_FAILED);
            assertThat(error.report()).contains("nothing to type");
        }
    }

    @Nested
    @DisplayName("unknown options → auxiliary")
    class Unknown {

        @Test
        @DisplayName("a typo (`--prot 9000`) yields an entry nobody uses — not a silent failure")
        void typoBecomesAuxiliary() {
            ConfigPatch patch = patch("--server-port", "8080", "--prot", "9000");

            assertThat(entry(patch, "prot").value().display()).isEqualTo("9000");
            assertThat(entry(patch, "prot").source().origin()).as("the origin shows WHICH option brought it in")
                .isEqualTo("--prot");

            ConfigValidator.Report report = ConfigValidator.build(SCHEMA, Config.root(SCHEMA).apply(patch));
            assertThat(report.errors()).extracting(ConfigError::kind)
                .as("an auxiliary nothing leads to")
                .contains(ConfigError.Kind.UNUSED_AUXILIARY);
        }

        @Test
        @DisplayName("`-action` is not the name `--action`: short names must be DECLARED")
        void undeclaredShortNameIsAnError() {
            ConfigError error = error("-action", "1");

            assertThat(error.kind()).isEqualTo(ConfigError.Kind.PARSE_FAILED);
            assertThat(error.report())
                .as("the message says what to do instead of guessing the intent")
                .containsIgnoringCase("declared").contains("`--action`");
        }

        @Test
        @DisplayName("`-version` WORKS when the application declared it — the engine does not take that name")
        void declaredMultiCharacterShortNameWorks() {
            ConfigDefinition declared = ConfigDefinition.of(
                ConfigField.required("server.port", ConfigType.INT).aliased("version"));
            CommandLine line = new CommandLine(CliSpec.of(declared));
            line.parseArgs("-version", "8080");

            ConfigResult<ConfigPatch> result = CliSource.read(line.getParseResult());
            assertThat(((ConfigResult.ValueResult<ConfigPatch>) result).value()
                .operation("server.port")).isNotNull();
        }

        @Test
        @DisplayName("a bare word without a registered action is an error that SPEAKS about the namespaces")
        void barePositionalWithoutAnActionIsAnError() {
            ConfigError error = error("--server-port", "8080", "run");

            assertThat(error.kind()).isEqualTo(ConfigError.Kind.PARSE_FAILED);
            assertThat(error.report()).contains("is not an action").contains("`--run`");
        }

        @Test
        @DisplayName("a bare word is an ACTION: `config-validation` runs an action instead of being a field")
        void bareWordIsAnAction() {
            // Engine-reserved actions come from `CliSpec` WITHOUT adding anything: the subcommand is
            // registered there, so the action can be invoked straight away.
            CommandLine line = new CommandLine(CliSpec.of(SCHEMA));
            line.parseArgs("--server-port", "8080", "config-validation");

            assertThat(CliSource.selectedAction(line.getParseResult())).contains("config-validation");
            assertThat(CliSource.read(line.getParseResult()))
                .as("the parameters of an action do not enter configuration")
                .isInstanceOf(ConfigResult.ValueResult.class);
        }

        @Test
        @DisplayName("an unknown action says WHAT is known — and suggests `--` for configuration")
        void unknownActionListsWhatIsKnown() {
            ConfigError error = error("--server-port", "8080", "config-genrate");

            assertThat(error.kind()).isEqualTo(ConfigError.Kind.PARSE_FAILED);
            assertThat(error.report())
                .contains("is not an action").contains("config-validation").contains("config-generate")
                .contains("`--config-genrate`");
        }

        @Test
        @DisplayName("an empty value (`--server-port \"\"`) is an error on a DECLARED field — `NullValue`")
        void emptyValueOnADeclaredFieldIsNullValue() {
            ConfigPatch patch = patch("--server-port", "");

            assertThat(entry(patch, "server.port").value().display()).isEmpty();
            ConfigValidator.Report report = ConfigValidator.build(SCHEMA, Config.root(SCHEMA).apply(patch));

            assertThat(report.errors()).extracting(ConfigError::kind).contains(ConfigError.Kind.NULL_VALUE);
        }

        @Test
        @DisplayName("an empty auxiliary arrives as empty text, and whoever uses it passes the verdict")
        void emptyAuxiliaryIsDelivered() {
            ConfigPatch patch = patch("--api-key", "");

            assertThat(entry(patch, "api.key").value().display()).isEmpty();
            ConfigValidator.Report report = ConfigValidator.build(SCHEMA, Config.root(SCHEMA).apply(patch));

            assertThat(report.errors()).extracting(ConfigError::kind)
                .as("an auxiliary has no declaration, so there is no cast — it stays \"unused\"")
                .contains(ConfigError.Kind.UNUSED_AUXILIARY);
        }
    }

    @Nested
    @DisplayName("the help of the engine (`-help`) and the hint about the double-dash spelling")
    class EngineHelp {

        @Test
        @DisplayName("a bare `--help` names the form the engine has: `-help`")
        void bareDoubleDashHelpGetsAHint() {
            assertThatExceptionOfType(CommandLine.ParameterException.class)
                .isThrownBy(() -> read("--help"))
                .withMessageContaining("`--help`")
                .as("the engine's options are single-dash, so the double-dash form is the likely mistake")
                .withMessageContaining("did you mean `-help`")
                .withMessageContaining("the engine's help");
        }

        @Test
        @DisplayName("the hint is about the ENGINE's names only — a typo keeps the plain message")
        void aTypoGetsNoHint() {
            assertThatExceptionOfType(CommandLine.ParameterException.class)
                .isThrownBy(() -> read("--prot"))
                .withMessageContaining("`--prot`")
                .as("`--prot` is not an engine name: the boundary is the value, and nothing is suggested")
                .withMessageNotContaining("did you mean");
        }

        @Test
        @DisplayName("`--strict-config-parser` gets the same hint — the rule is general, not about help alone")
        void bareStrictParserGetsAHint() {
            assertThatExceptionOfType(CommandLine.ParameterException.class)
                .isThrownBy(() -> read("--strict-config-parser"))
                .withMessageContaining("did you mean `-strict-config-parser`");
        }

        @Test
        @DisplayName("a DECLARED field `help` means `--help` is a field: a missing value is reported as a value")
        void declaredHelpFieldTakesTheDoubleDash() {
            ConfigDefinition withHelp = ConfigDefinition.of(ConfigField.required("help", ConfigType.STR));

            // `--help` belongs to the field, so the parse never reaches the reader's own check: picocli
            // reports the missing parameter and no engine hint appears — the user forgot a value, they did
            // not mistake the help.
            assertThatExceptionOfType(CommandLine.ParameterException.class)
                .isThrownBy(() -> read(withHelp, "--help"))
                .withMessageNotContaining("did you mean");

            assertThat(entry(patch(withHelp, "--help", "1"), "help").value().display()).isEqualTo("1");
        }

        @Test
        @DisplayName("a token after an action word is READ — a bare one is a usage error there too")
        void tokensAfterAnActionWordAreRead() {
            assertThat(entry(patch("config-validation", "--prot", "9000"), "prot").value().display())
                .as("an unknown option WITH a value is auxiliary on both sides of the action word")
                .isEqualTo("9000");
            assertThatExceptionOfType(CommandLine.ParameterException.class)
                .isThrownBy(() -> read("config-validation", "--prot"))
                .as("a bare one is a usage error, whichever side of the action it stands on")
                .withMessageContaining("`--prot`");
            assertThatExceptionOfType(CommandLine.ParameterException.class)
                .isThrownBy(() -> read("config-validation", "--help"))
                .as("and the help of an action is `-help` too, so the double dash is not dropped in silence")
                .withMessageContaining("did you mean `-help`");
        }
    }

    @Nested
    @DisplayName("a flow literal — the reader the toolbox wires (`YamlSource::value`)")
    class FlowLiterals {

        /** The same as the class helper, with the reader that turns `[80,443]` into a list. */
        private ConfigResult<ConfigPatch> read(ConfigDefinition definition, String... args) {
            CommandLine line = new CommandLine(CliSpec.of(definition));
            line.setUnmatchedArgumentsAllowed(true);
            line.parseArgs(args);
            return CliSource.read(line.getParseResult(), dev.jucm.yaml.YamlSource::value);
        }

        private ConfigPatch patch(ConfigDefinition definition, String... args) {
            ConfigResult<ConfigPatch> result = read(definition, args);
            assertThat(result).as("expected entries, got an error").isInstanceOf(ConfigResult.ValueResult.class);
            return ((ConfigResult.ValueResult<ConfigPatch>) result).value();
        }

        @Test
        @DisplayName("`--ports [80,443]` is a LIST — a declared collection can be filled from the command line")
        void aDeclaredCollectionFromAFlowLiteral() {
            ConfigPatch patch = patch(ConfigDefinition.of(ConfigField.seq("ports", ConfigType.INT)),
                "--ports", "[80,443]");

            assertThat(entry(patch, "ports").value())
                .as("the members are TEXT here — the declaration types them in the cast step")
                .isEqualTo(ConfigSeq.of(List.of(ConfigString.of("80"), ConfigString.of("443"))));
        }

        @Test
        @DisplayName("`--m [[1,2]] --m--type seq` states that the members are LISTS — the carrier read them")
        void aCollectionOnAnAuxiliaryStatesItsMembers() {
            ConfigPatch patch = patch(SCHEMA, "--m", "[[1,2]]", "--m--type", "seq");

            assertThat(entry(patch, "m").declaration().type()).contains(ConfigType.SEQ);
            assertThat(entry(patch, "m").declaration().elementType())
                .as("without this the members would be cast as text and refused")
                .contains(ConfigType.SEQ);
        }

        @Test
        @DisplayName("a broken literal is a SOURCE error with its cause, not a text that looks odd")
        void aBrokenLiteralIsASourceError() {
            ConfigError problem = ((ConfigResult.ErrorResult<ConfigPatch>)
                read(SCHEMA, "--ports", "[80,")).error();

            assertThat(problem.kind()).isEqualTo(ConfigError.Kind.PARSE_FAILED);
            assertThat(problem.message()).contains("flow literal");
        }

        @Test
        @DisplayName("a collection with MIXED members is a source error — a collection holds one type of thing")
        void aMixedCollectionIsASourceError() {
            ConfigError problem = ((ConfigResult.ErrorResult<ConfigPatch>)
                read(SCHEMA, "--m", "[a,[1]]", "--m--type", "seq")).error();

            assertThat(problem.kind()).isEqualTo(ConfigError.Kind.PARSE_FAILED);
            assertThat(problem.message()).contains("ONE type of thing");
        }
    }
}
