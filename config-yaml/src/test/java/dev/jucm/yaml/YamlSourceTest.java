package dev.jucm.yaml;

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
import dev.jucm.core.ConfigValue;
import dev.jucm.core.ConfigType;
import dev.jucm.core.Layer;
import dev.jucm.core.TransformerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Contracts of the YAML loader: a tree to entries, positions, file errors and keys with `${…}`. */
class YamlSourceTest {

    private static final TransformerRegistry REGISTRY = TransformerRegistry.defaults();
    private static final ConfigDefinition EMPTY = ConfigDefinition.of();

    private static ConfigPatch patch(String text) {
        return patch(text, EMPTY);
    }

    private static ConfigPatch patch(String text, ConfigDefinition definition) {
        ConfigResult<ConfigPatch> result =
            YamlSource.of("application.yaml", text).patch(Config.root(definition), definition, REGISTRY);
        assertThat(result).as("expected entries, but there is an error: " + errorOf(result))
            .isInstanceOf(ConfigResult.ValueResult.class);
        return ((ConfigResult.ValueResult<ConfigPatch>) result).value();
    }

    private static ConfigError error(String text) {
        return error(text, EMPTY);
    }

    private static ConfigError error(String text, ConfigDefinition definition) {
        ConfigResult<ConfigPatch> result =
            YamlSource.of("application.yaml", text).patch(Config.root(definition), definition, REGISTRY);
        assertThat(result).as("expected an error, but there are entries")
            .isInstanceOf(ConfigResult.ErrorResult.class);
        return ((ConfigResult.ErrorResult<ConfigPatch>) result).error();
    }

    private static String errorOf(ConfigResult<?> result) {
        return result instanceof ConfigResult.ErrorResult<?> bad ? bad.error().report() : "-";
    }

    private static String value(ConfigPatch patch, String canonicalPath) {
        return ((ConfigPatch.SetValue) patch.operation(canonicalPath).orElseThrow()).value().display();
    }

    @Nested
    @DisplayName("a tree to entries")
    class Tree {

        @Test
        @DisplayName("a key is an address, a value is RAW text, the origin carries `file:line`")
        void scalarsBecomeAddresses() {
            ConfigPatch patch = patch("port: 8080\nname: auto\n");

            assertThat(value(patch, "port")).as("nothing is typed — the declaration decides the type")
                .isEqualTo("8080");
            assertThat(value(patch, "name")).isEqualTo("auto");
            assertThat(((ConfigPatch.SetValue) patch.operation("port").orElseThrow()).source().layer())
                .isEqualTo(Layer.YAML);
            assertThat(((ConfigPatch.SetValue) patch.operation("port").orElseThrow()).source().origin())
                .as("diagnostics must be able to show WHERE it stands").contains("application.yaml:1");
        }

        @Test
        @DisplayName("a mapping inside a mapping is still an ADDRESS, not a value")
        void nestedMappingsAreStillAddresses() {
            ConfigPatch patch = patch("server:\n  http:\n    port: 8080\n");

            assertThat(patch.paths()).hasSize(1);
            assertThat(value(patch, "server.http.port")).isEqualTo("8080");
        }

        @Test
        @DisplayName("a sequence is a `ConfigSeq`, a map inside it a `ConfigMap` — with text values")
        void sequencesAndMapsAsValues() {
            ConfigPatch patch = patch("features:\n  - a\n  - b\nhosts:\n  - name: first\n    port: 1\n");

            assertThat(value(patch, "features")).isEqualTo("[a, b]");
            assertThat(value(patch, "hosts")).contains("first");
            assertThat(patch.paths()).extracting(path -> path.canonical()).containsExactlyInAnyOrder("features", "hosts");
        }

        @Test
        @DisplayName("`x:` with no value is empty — the validator turns that into `NullValue`")
        void emptyValueIsEmpty() {
            assertThat(value(patch("x:\n"), "x")).isEmpty();
        }

        @Test
        @DisplayName("an alias is the same value in two places, and comments do NOT enter the entries")
        void aliasesAndComments() {
            ConfigPatch patch = patch("# header\nshared: &x 5\n# in the middle\nalias: *x\n");

            assertThat(value(patch, "shared")).isEqualTo("5");
            assertThat(value(patch, "alias")).isEqualTo("5");
            assertThat(patch.paths()).as("a comment is not an entry").hasSize(2);
        }
    }

    @Nested
    @DisplayName("a single value — the seam for the ENV adapter")
    class Values {

        @Test
        @DisplayName("a flow literal yields a structure with TEXT elements: `[a, b]` becomes `ConfigSeq`")
        void flowLiteralsBecomeStructures() {
            assertThat(read("[a, b]")).as("the elements are ConfigString — the cast gives the type")
                .isInstanceOf(ConfigSeq.class);
            assertThat(read("{home: 1}")).isInstanceOf(ConfigMap.class);
            assertThat(read("8080")).as("plain text stays text").isEqualTo(ConfigString.of("8080"));
        }

        @Test
        @DisplayName("an unreadable literal is a parse error, not an exception in the middle of the pipeline")
        void brokenLiteralIsAnError() {
            ConfigResult<ConfigValue> broken = YamlSource.value("[1, 2");

            assertThat(broken).isInstanceOf(ConfigResult.ErrorResult.class);
            assertThat(((ConfigResult.ErrorResult<ConfigValue>) broken).error().kind())
                .isEqualTo(ConfigError.Kind.PARSE_FAILED);
        }

        private ConfigValue read(String text) {
            ConfigResult<ConfigValue> result = YamlSource.value(text);
            assertThat(result).isInstanceOf(ConfigResult.ValueResult.class);
            return ((ConfigResult.ValueResult<ConfigValue>) result).value();
        }
    }

    @Nested
    @DisplayName("file errors — with a position, never silently")
    class Broken {

        @Test
        @DisplayName("a duplicate key is a `ParseFailed` with the line (YAML keeps BOTH entries — we do not guess)")
        void duplicateKeyFails() {
            ConfigError error = error("port: 1\nport: 2\n");

            assertThat(error.kind()).isEqualTo(ConfigError.Kind.PARSE_FAILED);
            assertThat(error.report()).contains("duplicate key").contains("application.yaml:2");
        }

        @Test
        @DisplayName("a recursive anchor is an error, not a looping walk")
        void recursiveAnchorFails() {
            ConfigError error = error("a: &x\n  - *x\n");

            assertThat(error.kind()).isEqualTo(ConfigError.Kind.PARSE_FAILED);
            assertThat(error.report()).contains("recursive anchor");
        }

        @Test
        @DisplayName("a document that is not a mapping has no addresses — and says so plainly")
        void nonMappingDocumentFails() {
            ConfigError error = error("42\n");

            assertThat(error.kind()).isEqualTo(ConfigError.Kind.PARSE_FAILED);
            assertThat(error.report()).contains("must be a mapping");
        }

        @Test
        @DisplayName("unparsable YAML: `file:line:column` from the library")
        void brokenYamlCarriesPosition() {
            ConfigError error = error("x: 1\n  y: 2\n");

            assertThat(error.kind()).isEqualTo(ConfigError.Kind.PARSE_FAILED);
            assertThat(error.report()).as("the position comes from the library: file, line, column")
                .contains("application.yaml:").containsPattern("application.yaml:\\d+:\\d+");
        }
    }

    @Nested
    @DisplayName("keys with `${…}` — two passes, canonical content")
    class Keys {

        @Test
        @DisplayName("a key referring to a declaration LOWER in the file (pass 1 collects, pass 2 substitutes)")
        void forwardReferenceInKey() {
            ConfigPatch patch = patch("${prefix}_port: 8080\nprefix: server\n");

            assertThat(patch.paths()).extracting(path -> path.canonical())
                .as("the substituted key AND the declaration that fed it — the adapter returns everything")
                .containsExactlyInAnyOrder("server_port", "prefix");
            assertThat(value(patch, "server_port")).isEqualTo("8080");
        }

        @Test
        @DisplayName("key content after the FULL intake: `${port}` on an `int` field gives `8`, not `010`")
        void keyIsCanonical() {
            ConfigDefinition definition = ConfigDefinition.of(ConfigField.required("port", ConfigType.INT));
            ConfigPatch patch = patch("${port}: http\nport: 010\n", definition);

            assertThat(patch.paths()).extracting(path -> path.canonical())
                .as("a key is an address: it goes to text after transformation and cast")
                .containsExactlyInAnyOrder("8", "port");
            assertThat(value(patch, "8")).isEqualTo("http");
        }

        @Test
        @DisplayName("an unresolved key is a PARSE error of the file — with a position, not an empty address")
        void unresolvedKeyFails() {
            ConfigError error = error("${no_such_key}: 1\n");

            assertThat(error.kind()).isEqualTo(ConfigError.Kind.PARSE_FAILED);
            assertThat(error.report()).contains("no_such_key").contains("application.yaml:1");
        }

        @Test
        @DisplayName("a value with `${…}` stays SYMBOLIC — only the interpolation step of the pipeline resolves it")
        void valuesStayLazy() {
            ConfigPatch patch = patch("url: http://localhost:${port}\n");

            assertThat(value(patch, "url")).as("the adapter does not resolve values")
                .isEqualTo("http://localhost:${port}");
        }

        @Test
        @DisplayName("the original stays untouched — the substitution happens on a CLONE")
        void originalIsUntouched() {
            YamlSource source = YamlSource.of("application.yaml", "${a}: 1\na: 2\n");

            ConfigPatch first = ((ConfigResult.ValueResult<ConfigPatch>) source.patch(
                Config.root(EMPTY), EMPTY, REGISTRY)).value();
            ConfigPatch second = ((ConfigResult.ValueResult<ConfigPatch>) source.patch(
                Config.root(EMPTY), EMPTY, REGISTRY)).value();

            assertThat(first.paths()).extracting(path -> path.canonical())
                .containsExactlyInAnyOrder("2", "a");
            assertThat(second).as("a second read sees the same — nothing was consumed").isEqualTo(first);
        }
    }
}
