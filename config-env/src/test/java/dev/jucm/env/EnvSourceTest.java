package dev.jucm.env;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jucm.core.Config;
import dev.jucm.core.ConfigDefinition;
import dev.jucm.core.ConfigError;
import dev.jucm.core.ConfigField;
import dev.jucm.core.ConfigMap;
import dev.jucm.core.ConfigPatch;
import dev.jucm.core.ConfigResult;
import dev.jucm.core.ConfigSeq;
import dev.jucm.core.ConfigString;
import dev.jucm.core.ConfigType;
import dev.jucm.core.ConfigValidator;
import dev.jucm.core.ConfigValue;
import dev.jucm.core.Layer;
import dev.jucm.core.TransformerRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The contract of the ENV adapter: the order of the cuts, the grammar of a name, the
 * {@code ___TYPE}/{@code ___TCONF}/{@code ___CONST} markers, absent and empty (each of them brings in
 * nothing), and a flow literal read by the injected reader.
 */
class EnvSourceTest {

    private static final String PREFIX = "AIMENAGERIE";
    private static final ConfigDefinition EMPTY = ConfigDefinition.empty();

    private static Map<String, String> env(String... pairs) {
        Map<String, String> environment = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            environment.put(pairs[index], pairs[index + 1]);
        }
        return environment;
    }

    private static ConfigPatch patch(Map<String, String> environment) {
        return patch(environment, EMPTY);
    }

    private static ConfigPatch patch(Map<String, String> environment, ConfigDefinition definition) {
        return patch(environment, definition, StructureReader.none());
    }


    @Test
    @DisplayName("`___TYPE=seq` on a key OUTSIDE the schema states what the MEMBERS are — the carrier read them")
    void aCollectionStatesWhatItsMembersAre() {
        StructureReader reader = text -> ConfigResult.value(ConfigSeq.of(List.of(
            ConfigSeq.of(List.of(ConfigString.of("1"))), ConfigSeq.of(List.of(ConfigString.of("3"))))));

        ConfigPatch patch = patch(env("AIMENAGERIE__MATRIX", "[[1],[3]]", "AIMENAGERIE__MATRIX___TYPE", "seq"),
            EMPTY, reader);

        assertThat(entry(patch, "matrix").declaration().elementType())
            .as("`[[1],[3]]` is a list of LISTS; without this the members would be cast as text and refused")
            .contains(ConfigType.SEQ);
    }

    @Test
    @DisplayName("a collection with MIXED members is a source error — a collection holds ONE type of thing")
    void aMixedCollectionIsASourceError() {
        StructureReader reader = text -> ConfigResult.value(ConfigSeq.of(List.of(
            ConfigString.of("a"), ConfigSeq.of(List.of(ConfigString.of("1"))))));

        ConfigError problem = error(env("AIMENAGERIE__MIXED", "[a, [1]]", "AIMENAGERIE__MIXED___TYPE", "seq"),
            EMPTY, reader);

        assertThat(problem.kind()).isEqualTo(ConfigError.Kind.PARSE_FAILED);
        assertThat(problem.message()).contains("ONE type of thing");
    }

    private static ConfigPatch patch(Map<String, String> environment, ConfigDefinition definition,
                                     StructureReader reader) {
        ConfigResult<ConfigPatch> result = EnvSource.of(environment, PREFIX, reader).patch(definition);
        assertThat(result).as("expected entries, got an error: " + report(result))
            .isInstanceOf(ConfigResult.ValueResult.class);
        return ((ConfigResult.ValueResult<ConfigPatch>) result).value();
    }

    private static ConfigError error(Map<String, String> environment) {
        return error(environment, EMPTY, StructureReader.none());
    }

    private static ConfigError error(Map<String, String> environment, ConfigDefinition definition,
                                     StructureReader reader) {
        ConfigResult<ConfigPatch> result = EnvSource.of(environment, PREFIX, reader).patch(definition);
        assertThat(result).as("expected an error, got entries").isInstanceOf(ConfigResult.ErrorResult.class);
        return ((ConfigResult.ErrorResult<ConfigPatch>) result).error();
    }

    private static String report(ConfigResult<?> result) {
        return result instanceof ConfigResult.ErrorResult<?> bad ? bad.error().report() : "-";
    }

    private static ConfigPatch.SetValue entry(ConfigPatch patch, String path) {
        return (ConfigPatch.SetValue) patch.operation(path).orElseThrow();
    }

    private static String value(ConfigPatch patch, String path) {
        return entry(patch, path).value().display();
    }

    @Nested
    @DisplayName("the order of the cuts, and the grammar of a name")
    class Names {

        @Test
        @DisplayName("prefix → marker → split: `AIM__SERVER__PORT___TYPE` is `server.port` with a type")
        void cutOrderIsPrefixMarkerSplit() {
            ConfigPatch patch = patch(env("AIMENAGERIE__SERVER__PORT", "8080",
                "AIMENAGERIE__SERVER__PORT___TYPE", "int"));

            assertThat(patch.paths()).extracting(path -> path.canonical()).containsExactly("server.port");
            assertThat(value(patch, "server.port")).isEqualTo("8080");
            assertThat(entry(patch, "server.port").declaration().type()).contains(ConfigType.INT);
        }

        @Test
        @DisplayName("variables without the prefix are IGNORED — `PATH`, `HOME` and `TERM` are not configuration")
        void foreignVariablesAreIgnored() {
            ConfigPatch patch = patch(env("PATH", "/usr/bin", "HOME", "/root",
                "AIMENAGERIE__PORT", "8080", "AIMENAGERIE_PORT", "not-this-prefix"));

            assertThat(patch.paths()).extracting(path -> path.canonical()).containsExactly("port");
        }

        @Test
        @DisplayName("a single `_` stays in the segment, and case is normalised")
        void singleUnderscoreStaysAndCaseIsNormalised() {
            ConfigPatch patch = patch(env("AIMENAGERIE__Server__MAX_CONNECTIONS", "10",
                "AIMENAGERIE__X__MiXeD", "1"));

            assertThat(patch.paths()).extracting(path -> path.canonical())
                .as("the prefix is case sensitive — `aimenagerie__` is a DIFFERENT variable")
                .containsExactlyInAnyOrder("server.max_connections", "x.mixed");
        }

        @Test
        @DisplayName("`__` and `___` inside a segment are an error naming the variable — the grammar is enforced")
        void brokenGrammarNamesTheVariable() {
            ConfigError error = error(env("AIMENAGERIE____X", "1"));

            assertThat(error.kind()).isEqualTo(ConfigError.Kind.PARSE_FAILED);
            assertThat(error.report()).contains("AIMENAGERIE____X").contains("segment");
        }

        @Test
        @DisplayName("the origin carries the ENV layer and the NAME of the variable")
        void sourceCarriesTheVariable() {
            ConfigPatch patch = patch(env("AIMENAGERIE__PORT", "8080"));

            assertThat(entry(patch, "port").source().layer()).isEqualTo(Layer.ENV);
            assertThat(entry(patch, "port").source().origin()).isEqualTo("AIMENAGERIE__PORT");
        }
    }

    @Nested
    @DisplayName("an absent variable and an empty one both bring in nothing")
    class Emptiness {

        @Test
        @DisplayName("an absent variable is not in the patch at all")
        void absentIsNotAnEntry() {
            assertThat(patch(env("AIMENAGERIE__OTHER", "1")).paths()).hasSize(1);
        }

        @Test
        @DisplayName("an EMPTY variable is not in the patch either — the layer behaves as if it were absent")
        void emptyEntersNothing() {
            ConfigDefinition definition = ConfigDefinition.of(ConfigField.required("port", ConfigType.INT));
            ConfigPatch patch = patch(env("AIMENAGERIE__PORT", ""), definition);

            assertThat(patch.paths()).as("the value comes from a lower layer or from the declaration")
                .isEmpty();
        }

        @Test
        @DisplayName("so the validator says ABSENT (a missing value), never NULL_VALUE (an empty one)")
        void theValidatorSeesAbsence() {
            ConfigDefinition definition = ConfigDefinition.of(ConfigField.required("port", ConfigType.INT));
            ConfigPatch patch = patch(env("AIMENAGERIE__PORT", ""), definition);

            ConfigValidator.Report report = ConfigValidator.build(definition, Config.root(definition).apply(patch));

            assertThat(report.errors()).extracting(ConfigError::kind)
                .containsExactly(ConfigError.Kind.ABSENT)
                .doesNotContain(ConfigError.Kind.NULL_VALUE);
        }
    }

    @Nested
    @DisplayName("a flow literal — one reader per structure")
    class Structures {

        @Test
        @DisplayName("`[a, b]` goes through the reader and enters as a `ConfigSeq` with TEXT elements")
        void flowSequenceGoesThroughTheReader() {
            StructureReader reader = text -> ConfigResult.value(ConfigSeq.of(List.of(
                ConfigString.of(text.substring(1, 2)), ConfigString.of(text.substring(4, 5)))));
            ConfigPatch patch = patch(env("AIMENAGERIE__FEATURES", "[a, b]"), EMPTY, reader);

            assertThat(entry(patch, "features").value()).isInstanceOf(ConfigSeq.class);
            assertThat(value(patch, "features")).as("the elements are TEXT — the cast gives them a type")
                .isEqualTo("[a, b]");
        }

        @Test
        @DisplayName("without a reader a flow literal is an ERROR, not a silent string")
        void flowWithoutAReaderIsAnError() {
            ConfigError error = error(env("AIMENAGERIE__FEATURES", "[a, b]"));

            assertThat(error.kind()).isEqualTo(ConfigError.Kind.PARSE_FAILED);
            assertThat(error.report()).contains("flow literal").contains("reader");
        }

        @Test
        @DisplayName("an unreadable literal is an error naming the VARIABLE, not a stack of exceptions")
        void brokenFlowNamesTheVariable() {
            StructureReader broken = text -> ConfigResult.error(
                new ConfigError.ParseFailed(dev.jucm.core.ConfigSource.of(Layer.YAML, "value"), "unbalanced bracket"));
            ConfigError error = error(env("AIMENAGERIE__FEATURES", "[1, 2"), EMPTY, broken);

            assertThat(error.kind()).isEqualTo(ConfigError.Kind.PARSE_FAILED);
            assertThat(error.report()).contains("AIMENAGERIE__FEATURES").contains("flow literal");
        }

        @Test
        @DisplayName("`8080` is NOT read as a structure — it is plain text")
        void plainValuesNeverHitTheReader() {
            ConfigPatch patch = patch(env("AIMENAGERIE__PORT", "8080"), EMPTY,
                text -> ConfigResult.error(new ConfigError.ParseFailed(
                    dev.jucm.core.ConfigSource.of(Layer.YAML, "value"), "the reader must not be called")));

            assertThat(entry(patch, "port").value()).isEqualTo(ConfigString.of("8080"));
        }

        @Test
        @DisplayName("`{a: 1}` yields a `ConfigMap`, and the values stay text")
        void flowMappingWorks() {
            StructureReader reader = text -> ConfigResult.value(ConfigMap.of(Map.of("home", ConfigString.of("1"))));
            ConfigPatch patch = patch(env("AIMENAGERIE__WEIGHT", "{home: 1}"), EMPTY, reader);

            assertThat(entry(patch, "weight").value()).isInstanceOf(ConfigMap.class);
        }
    }

    @Nested
    @DisplayName("the `___TYPE` / `___TCONF` / `___CONST` markers")
    class Markers {

        @Test
        @DisplayName("`___TCONF` alone supplies the notation configuration, and the type comes from the marker next to it")
        void bothMarkersMerge() {
            ConfigPatch patch = patch(env("AIMENAGERIE__START", "07.03.2026",
                "AIMENAGERIE__START___TYPE", "timestamp", "AIMENAGERIE__START___TCONF", "dd.MM.yyyy"));

            assertThat(entry(patch, "start").declaration().type()).contains(ConfigType.TIMESTAMP);
            assertThat(entry(patch, "start").declaration().transformerConfig()).contains("dd.MM.yyyy");
        }

        @Test
        @DisplayName("`___CONST` gives a RULE to a key outside the schema — type from `___TYPE`, chain from the marker value")
        void constMarkerIsCarried() {
            ConfigPatch patch = patch(env("AIMENAGERIE__RETRIES", "3",
                "AIMENAGERIE__RETRIES___TYPE", "int", "AIMENAGERIE__RETRIES___CONST", ">=1&<=10"));

            assertThat(entry(patch, "retries").declaration().type()).contains(ConfigType.INT);
            assertThat(entry(patch, "retries").declaration().constraints()).contains(">=1&<=10");
        }

        @Test
        @DisplayName("`___CONST` on a DECLARED field does not vanish — it carries the RULE alone, without a type")
        void constMarkerSurvivesOnADeclaredPath() {
            ConfigDefinition definition = ConfigDefinition.of(
                ConfigField.required("port", ConfigType.INT));
            ConfigPatch patch = patch(env("AIMENAGERIE__PORT", "8080",
                "AIMENAGERIE__PORT___CONST", "in[443]"), definition);

            assertThat(entry(patch, "port").declaration().constraints())
                .as("the rule from the marker MUST reach the model (and there it REPLACES the rules from code)")
                .contains("in[443]");
            assertThat(entry(patch, "port").declaration().type())
                .as("the marker does not impose a type — the declaration from code wins").isEmpty();
        }

        @Test
        @DisplayName("`___CONST` without a type on a key outside the schema is CARRIED — the value lands, nothing to compare")
        void constWithoutTypeIsCarried() {
            ConfigPatch patch = patch(env("AIMENAGERIE__RETRIES", "3",
                "AIMENAGERIE__RETRIES___CONST", ">=1&<=10"));

            assertThat(entry(patch, "retries").value().display())
                .as("an auxiliary is how an application supplies a value — the marker must not block it")
                .isEqualTo("3");
            assertThat(entry(patch, "retries").declaration().constraints()).contains(">=1&<=10");
            assertThat(entry(patch, "retries").declaration().type())
                .as("the ADAPTER states no type — a key outside the schema is typed by the core (`str` when nothing is stated)").isEmpty();
        }

        @Test
        @DisplayName("a broken chain in `___CONST` is a SOURCE error naming the variable, not an exception in the pipeline")
        void brokenChainNamesTheVariable() {
            ConfigError error = error(env("AIMENAGERIE__RETRIES", "3",
                "AIMENAGERIE__RETRIES___TYPE", "int", "AIMENAGERIE__RETRIES___CONST", "<2>0"));

            assertThat(error.kind()).isEqualTo(ConfigError.Kind.PARSE_FAILED);
            assertThat(error.report())
                .contains("AIMENAGERIE__RETRIES___CONST")
                .contains("missing separator");
        }

        @Test
        @DisplayName("`___TCONF` without a type on a key outside the schema is CARRIED too — one rule for both markers")
        void tconfWithoutTypeIsCarried() {
            ConfigPatch patch = patch(env("AIMENAGERIE__START", "07.03.2026",
                "AIMENAGERIE__START___TCONF", "dd.MM.yyyy"));

            assertThat(entry(patch, "start").value().display()).isEqualTo("07.03.2026");
            assertThat(entry(patch, "start").declaration().transformerConfig()).contains("dd.MM.yyyy");
            assertThat(entry(patch, "start").declaration().type())
                .as("the ADAPTER states no type — a key outside the schema is typed by the core (`str` when nothing is stated)").isEmpty();
        }

        @Test
        @DisplayName("a marker without a value is an error, not a silent omission")
        void markerWithoutValueIsAnError() {
            ConfigError error = error(env("AIMENAGERIE__PORT", "8080", "AIMENAGERIE__PORT___TYPE", ""));

            assertThat(error.kind()).isEqualTo(ConfigError.Kind.PARSE_FAILED);
            assertThat(error.report()).contains("marker without a value");
        }

        @Test
        @DisplayName("a marker whose variable is missing is an error too — there is nothing to type")
        void markerWithoutItsVariableIsAnError() {
            ConfigError error = error(env("AIMENAGERIE__X___TYPE", "int"));

            assertThat(error.kind()).isEqualTo(ConfigError.Kind.PARSE_FAILED);
            assertThat(error.report()).contains("has nothing to type");
        }

        @Test
        @DisplayName("an unknown type name lists the KNOWN ones — and a test guards that list")
        void unknownTypeNameListsTheKnownOnes() {
            ConfigError error = error(env("AIMENAGERIE__X", "1", "AIMENAGERIE__X___TYPE", "integer"));

            assertThat(error.kind()).isEqualTo(ConfigError.Kind.PARSE_FAILED);
            assertThat(error.report()).contains("integer");
            EnvSource.KNOWN_TYPES.forEach(name -> assertThat(error.report()).contains(name));
            EnvSource.KNOWN_TYPES.forEach(name -> assertThat(ConfigType.fromYamlName(name))
                .as("the list in the message must not drift away from the type dictionary").isNotNull());
        }

        @Test
        @DisplayName("on a DECLARED path the type marker does not matter — the declaration from code wins")
        void markerIsIgnoredOnADeclaredPath() {
            ConfigDefinition definition = ConfigDefinition.of(ConfigField.required("port", ConfigType.STR));
            ConfigPatch patch = patch(env("AIMENAGERIE__PORT", "8080", "AIMENAGERIE__PORT___TYPE", "int"),
                definition);

            assertThat(entry(patch, "port").declaration().isNone())
                .as("otherwise a type from ENV would be a second, silent way of typing next to the declaration")
                .isTrue();
        }
    }

    @Test
    @DisplayName("the patch is REPEATABLE: the order in the environment does not change the result (patch equality)")
    void orderIsStable() {
        ConfigPatch first = patch(env("AIMENAGERIE__B", "2", "AIMENAGERIE__A", "1"));
        ConfigPatch second = patch(env("AIMENAGERIE__A", "1", "AIMENAGERIE__B", "2"));

        assertThat(first).as("this is the contract: the same set of entries regardless of the environment order")
            .isEqualTo(second);
        assertThat(first.paths()).extracting(path -> path.canonical())
            .as("and nobody can promise the order entries are LISTED in: `paths()` is an immutable set,"
                + " and those iterate in an order that varies between runs of the JVM")
            .containsExactlyInAnyOrder("a", "b");
    }
}
