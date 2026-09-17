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
import dev.jucm.core.TransformerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * How a mapping in a file is read: as the VALUE of an address, or as a set of ADDRESSES.
 *
 * <p>The contract under test: <b>the declaration under the address decides</b>. A field declared as
 * {@code map} takes the whole mapping as its value, every other address keeps reading it as addresses,
 * and emptiness keeps its meaning — {@code m: {}} is an EMPTY VALUE (an emptiness error), never an
 * absent field; "the field may have no elements" is expressed by the field being optional.
 *
 * <p>The same cases end to end (exit codes through the facade) are pinned in
 * {@code config-toolbox/…/YamlMapFieldTest}: the adapter knows nothing about the codes, so the two
 * halves belong in different modules.
 */
class YamlMapValueTest {

    private static final TransformerRegistry REGISTRY = TransformerRegistry.defaults();
    private static final ConfigDefinition EMPTY = ConfigDefinition.of();

    /** A model with one field of the given type. */
    private static ConfigDefinition field(String path, ConfigType type) {
        return ConfigDefinition.of(required(path, type));
    }

    private static ConfigDefinition optionalField(String path, ConfigType type) {
        return ConfigDefinition.of(optional(path, type));
    }

    /**
     * A field of the given type. A collection STATES what it holds, so a `seq`/`map` asked for here is declared
     * with the type of its elements - this is the shape every model has since a collection cannot be declared
     * without one.
     */
    private static ConfigField required(String path, ConfigType type) {
        return switch (type) {
            case SEQ -> ConfigField.seq(path, ConfigType.STR);
            case MAP -> ConfigField.map(path, ConfigType.STR, ConfigType.STR);
            default -> ConfigField.required(path, type);
        };
    }

    private static ConfigField optional(String path, ConfigType type) {
        return switch (type) {
            case SEQ -> ConfigField.optionalSeq(path, ConfigType.STR);
            case MAP -> ConfigField.optionalMap(path, ConfigType.STR, ConfigType.STR);
            default -> ConfigField.optional(path, type);
        };
    }

    private static ConfigPatch patch(String text, ConfigDefinition definition) {
        ConfigResult<ConfigPatch> result =
            YamlSource.of("application.yaml", text).patch(Config.root(definition), definition, REGISTRY);
        assertThat(result).as("expected entries, but there is an error: " + errorOf(result))
            .isInstanceOf(ConfigResult.ValueResult.class);
        return ((ConfigResult.ValueResult<ConfigPatch>) result).value();
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

    private static ConfigValue value(ConfigPatch patch, String canonicalPath) {
        return ((ConfigPatch.SetValue) patch.operation(canonicalPath).orElseThrow()).value();
    }

    private static String text(ConfigPatch patch, String canonicalPath) {
        return value(patch, canonicalPath).display();
    }

    @Nested
    @DisplayName("a field declared `map` takes the whole mapping as ITS VALUE")
    class MapValue {

        @Test
        @DisplayName("block and flow style are the same value — and there is no entry under `m.a`")
        void bothStylesAreOneValue() {
            ConfigDefinition definition = field("m", ConfigType.MAP);

            ConfigPatch block = patch("m:\n  a: 1\n  b: two\n", definition);
            assertThat(block.paths()).extracting(path -> path.canonical())
                .as("one entry: the address of the field, not its pairs").containsExactly("m");
            assertThat(text(block, "m")).isEqualTo("{a: 1, b: two}");
            assertThat(value(block, "m")).as("a real `ConfigMap`, so the cast has something to cast")
                .isInstanceOf(ConfigMap.class);
            assertThat(block.operation("m.a")).as("the pairs are the CONTENT of the value").isEmpty();

            ConfigPatch flow = patch("m: {a: 1, b: two}\n", definition);
            assertThat(flow.paths()).extracting(path -> path.canonical()).containsExactly("m");
            assertThat(text(flow, "m")).isEqualTo(text(block, "m"));
        }

        @Test
        @DisplayName("a map inside the value stays inside it — the value is one tree of text")
        void nestedMapsBelongToTheValue() {
            ConfigPatch patch = patch("m:\n  outer:\n    inner: 1\n", field("m", ConfigType.MAP));

            assertThat(patch.paths()).extracting(path -> path.canonical()).containsExactly("m");
            assertThat(value(patch, "m")).isInstanceOf(ConfigMap.class);
            assertThat(text(patch, "m")).isEqualTo("{outer: {inner: 1}}");
        }

        @Test
        @DisplayName("the declaration decides at EVERY depth: `a.b` declared `map` is filled, `a` itself is not")
        void theDeclarationUnderTheAddressDecides() {
            ConfigDefinition definition = field("a.b", ConfigType.MAP);
            ConfigPatch patch = patch("a:\n  b: {x: 1}\n  c: 2\n", definition);

            assertThat(patch.paths()).extracting(path -> path.canonical())
                .as("`a` is not declared, so it stays a set of addresses — but `a.b` is a map field")
                .containsExactlyInAnyOrder("a.b", "a.c");
            assertThat(text(patch, "a.b")).isEqualTo("{x: 1}");
            assertThat(text(patch, "a.c")).isEqualTo("2");
        }

        @Test
        @DisplayName("a DOTTED key is the address `m.a`, not a value of `m` — the two spellings are not the same thing")
        void aDottedKeyIsAnotherAddress() {
            ConfigPatch patch = patch("m.a: 1\n", field("m", ConfigType.MAP));

            assertThat(patch.paths()).extracting(path -> path.canonical()).containsExactly("m.a");
            assertThat(patch.operation("m")).as("only a MAPPING (or a single-value source) fills a `map` field")
                .isEmpty();
        }

        @Test
        @DisplayName("the value keeps its ORIGIN, so a message about it can name the line")
        void theValueCarriesItsOrigin() {
            // In flow style the value stands on the line of the key; in block style the mapping node
            // starts where its first pair starts — either way the message can point into the file.
            assertThat(origin(patch("m: {a: 1}\n", field("m", ConfigType.MAP)), "m"))
                .isEqualTo("application.yaml:1");
            assertThat(origin(patch("m:\n  a: 1\n", field("m", ConfigType.MAP)), "m"))
                .isEqualTo("application.yaml:2");
        }

        private static String origin(ConfigPatch patch, String path) {
            return ((ConfigPatch.SetValue) patch.operation(path).orElseThrow()).source().origin();
        }
    }

    @Nested
    @DisplayName("every other address keeps reading a mapping as ADDRESSES")
    class Addresses {

        @Test
        @DisplayName("a key outside the schema is split into addresses, exactly as before")
        void outsideTheSchemaStaysAddresses() {
            ConfigPatch patch = patch("server:\n  host: example.org\n  port: 8080\n", EMPTY);

            assertThat(patch.paths()).extracting(path -> path.canonical())
                .containsExactlyInAnyOrder("server.host", "server.port");
            assertThat(patch.operation("server")).as("there is no value under the whole key").isEmpty();
        }

        @Test
        @DisplayName("a declared field of ANOTHER type takes it too — and the pair inside stops being an address")
        void anotherTypeTakesTheMappingAsItsValue() {
            ConfigPatch patch = patch("server:\n  host: example.org\n", field("server", ConfigType.STR));

            assertThat(patch.paths()).extracting(path -> path.canonical())
                .as("the declaration decides, whatever the type").containsExactly("server");
            assertThat(patch.operation("server.host")).as("the pair is content of the value, not an address")
                .isEmpty();
        }

        @Test
        @DisplayName("a marker on such a mapping is still LOUD — a set of addresses has no entry to type")
        void markerOnAnAddressMappingIsStillLoud() {
            ConfigError error = error("server:            # CONST(c[host])\n  host: example.org\n", EMPTY);

            assertThat(error.kind()).isEqualTo(ConfigError.Kind.PARSE_FAILED);
            assertThat(error.report()).contains("is a MAP").contains("in CODE");
        }
    }

    @Nested
    @DisplayName("emptiness keeps its meaning: an empty mapping is an EMPTY VALUE")
    class Emptiness {

        @Test
        @DisplayName("`m: {}` states the value (an empty map) — it is NOT an absent entry")
        void emptyMappingIsAValueNotEmpty() {
            ConfigPatch patch = patch("m: {}\n", field("m", ConfigType.MAP));

            assertThat(patch.paths()).extracting(path -> path.canonical())
                .as("the entry exists — the emptiness check of the pipeline is what rejects it")
                .containsExactly("m");
            assertThat(value(patch, "m")).isInstanceOf(ConfigMap.class);
            assertThat(((ConfigMap) value(patch, "m")).isEmpty()).isTrue();
            assertThat(text(patch, "m")).isEqualTo("{}");
        }

        @Test
        @DisplayName("`m:` with nothing after the colon is the EMPTY SCALAR — the case nobody has decided yet")
        void aKeyWithNothingUnderItIsNotAMapping() {
            ConfigPatch patch = patch("m:\nother: 1\n", field("m", ConfigType.MAP));

            // There is no node under `m:` at all, so there is no mapping to take as the value: for every
            // type this states an empty text (emptiness), which is the one case the owner left open.
            assertThat(value(patch, "m")).isInstanceOf(ConfigString.class);
            assertThat(text(patch, "m")).isEmpty();
        }

        @Test
        @DisplayName("a field with no entry at all is simply absent — optionality is the way to say \"no elements\"")
        void noEntryAtAllIsAbsent() {
            ConfigPatch patch = patch("other: 1\n", optionalField("m", ConfigType.MAP));

            assertThat(patch.operation("m")).isEmpty();
            assertThat(patch.paths()).extracting(path -> path.canonical()).containsExactly("other");
        }
    }

    @Nested
    @DisplayName("a scalar under a `map` field is TEXT — the declaration is what rejects it later")
    class Scalars {

        @Test
        @DisplayName("`m: 1` reaches the pipeline as the text `1`, and the cast is what fails")
        void aScalarStaysText() {
            ConfigPatch patch = patch("m: 1\n", field("m", ConfigType.MAP));

            assertThat(value(patch, "m")).isInstanceOf(ConfigString.class);
            assertThat(text(patch, "m")).isEqualTo("1");
        }
    }

    @Nested
    @DisplayName("markers: the value of a `map` field is ONE pair, so both places are well defined")
    class Markers {

        @Test
        @DisplayName("a marker next to the address belongs to the value (the same place as next to a list)")
        void markerOnTheAddressLineTravels() {
            ConfigPatch patch = patch("m: {a: 1}   # CONST(keys[a])\n", field("m", ConfigType.MAP));

            assertThat(((ConfigPatch.SetValue) patch.operation("m").orElseThrow()).declaration().constraints())
                .contains("keys[a]");
        }

        @Test
        @DisplayName("a marker INSIDE the value is loud — those pairs are content, not entries")
        void markerInsideTheValueIsLoud() {
            ConfigError error = error("m:\n  a: 1   # CONST(>0)\n", field("m", ConfigType.MAP));

            assertThat(error.kind()).isEqualTo(ConfigError.Kind.PARSE_FAILED);
            assertThat(error.report()).contains("INSIDE a mapping that is the VALUE");
            assertThat(error.report()).contains("application.yaml:2");
        }
    }

    @Nested
    @DisplayName("`seq` — the MEASUREMENT for the question open to the owner")
    class Sequence {

        @Test
        @DisplayName("a list under a `seq` field is ALREADY its value — the rule asked for `map` holds here")
        void aSequenceIsAlreadyAValue() {
            ConfigDefinition definition = field("features", ConfigType.SEQ);

            ConfigPatch block = patch("features:\n  - a\n  - b\n", definition);
            assertThat(block.paths()).extracting(path -> path.canonical())
                .as("a list creates an entry under its key — it is never split into addresses")
                .containsExactly("features");
            assertThat(value(block, "features")).isInstanceOf(ConfigSeq.class);
            assertThat(text(block, "features")).isEqualTo("[a, b]");

            ConfigPatch flow = patch("features: [a, b]\n", definition);
            assertThat(flow.paths()).extracting(path -> path.canonical()).containsExactly("features");
            assertThat(text(flow, "features")).isEqualTo("[a, b]");
        }

        @Test
        @DisplayName("a list under a key OUTSIDE the schema is a value too — the asymmetry is only in mappings")
        void outsideTheSchemaItIsAValueAsWell() {
            ConfigPatch patch = patch("features: [a, b]\n", EMPTY);

            assertThat(patch.paths()).extracting(path -> path.canonical()).containsExactly("features");
            assertThat(text(patch, "features")).isEqualTo("[a, b]");
        }

        @Test
        @DisplayName("a MAPPING under a `seq` field IS that field's value — the cast refuses it loudly, not silence")
        void aMappingUnderASeqFieldIsTheFieldsValue() {
            ConfigPatch patch = patch("features:\n  a: 1\n", field("features", ConfigType.SEQ));

            assertThat(patch.paths()).extracting(path -> path.canonical())
                .as("the declaration decides, whatever the field's type").containsExactly("features");
            assertThat(patch.operation("features")).as("one value under the declared address").isPresent();
            assertThat(patch.operation("features.a")).as("the pairs inside are content, not addresses").isEmpty();
        }
    }
}
