package dev.jucm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Values: the variants, the YAML types, immutability of the containers, binaries. */
class ConfigValueTest {

    @Test
    @DisplayName("a value's type is its YAML tag name")
    void typesComeFromYamlTags() {
        assertThat(ConfigString.of("x").type()).isEqualTo(ConfigType.STR);
        assertThat(ConfigInt.of(1).type()).isEqualTo(ConfigType.INT);
        assertThat(ConfigFloat.of(1.5).type()).isEqualTo(ConfigType.FLOAT);
        assertThat(ConfigBool.of(true).type()).isEqualTo(ConfigType.BOOL);
        assertThat(ConfigTimestamp.of(Instant.EPOCH).type()).isEqualTo(ConfigType.TIMESTAMP);
        assertThat(ConfigSeq.empty().type()).isEqualTo(ConfigType.SEQ);
        assertThat(ConfigMap.empty().type()).isEqualTo(ConfigType.MAP);
        assertThat(ConfigBinary.of(new byte[] {1}).type()).isEqualTo(ConfigType.BINARY);
    }

    @Test
    @DisplayName("int is 64-bit (the KNOWN LIMIT concerns the CLI, not the model)")
    void intIs64Bit() {
        long big = Long.MAX_VALUE;
        assertThat(ConfigInt.of(big).value()).isEqualTo(big);
    }

    @Test
    void equalityIsByValue() {
        assertThat(ConfigInt.of(7)).isEqualTo(ConfigInt.of(7)).hasSameHashCodeAs(ConfigInt.of(7));
        assertThat(ConfigString.of("a")).isNotEqualTo(ConfigString.of("b"));
        assertThat(ConfigInt.of(1)).isNotEqualTo(ConfigInt.of(2));
    }

    @Test
    void displayDoesNotLeakContents() {
        assertThat(ConfigBinary.of(new byte[4096]).display()).isEqualTo("binary[4096 B]");
    }

    @Test
    @DisplayName("display() of a collection shows its shape: [1, 2] and {a: 1}, not seq[2]/map[1]")
    void containersDisplayTheirShape() {
        assertThat(ConfigSeq.of(List.of(ConfigInt.of(1), ConfigInt.of(2))).display()).isEqualTo("[1, 2]");
        assertThat(ConfigMap.of(Map.of("a", ConfigInt.of(1))).display()).isEqualTo("{a: 1}");
        assertThat(ConfigSeq.of(List.of(
            ConfigString.of("metrics"),
            ConfigSeq.of(List.of(ConfigInt.of(1))))).display())
            .as("nesting is visible in the notation — display() descends into the elements")
            .isEqualTo("[metrics, [1]]");
        assertThat(ConfigMap.of(linkedEntries("retries", ConfigInt.of(3), "backoff", ConfigString.of("2s"))).display())
            .as("the map is ordered as in the source")
            .isEqualTo("{retries: 3, backoff: 2s}");
    }

    @Test
    @DisplayName("an empty collection exists as a value, but validation will not let it through")
    void emptyContainersAreKnownToBeEmpty() {
        assertThat(ConfigSeq.empty().isEmpty()).isTrue();
        assertThat(ConfigSeq.of(List.of(ConfigInt.of(1))).isEmpty()).isFalse();
        assertThat(ConfigMap.empty().isEmpty()).isTrue();
        assertThat(ConfigMap.of(Map.of("a", ConfigInt.of(1))).isEmpty()).isFalse();
    }

    private static Map<String, ConfigValue> linkedEntries(String firstKey, ConfigValue firstValue,
                                                          String secondKey, ConfigValue secondValue) {
        Map<String, ConfigValue> ordered = new java.util.LinkedHashMap<>();
        ordered.put(firstKey, firstValue);
        ordered.put(secondKey, secondValue);
        return ordered;
    }

    @Nested
    @DisplayName("containers: a JDK view, unmodifiable")
    class Containers {

        @Test
        void seqExposesUnmodifiableJavaList() {
            ConfigSeq seq = ConfigSeq.of(List.of(ConfigInt.of(1)));

            assertThat(seq.elements()).containsExactly(ConfigInt.of(1));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> seq.elements().add(ConfigInt.of(2)));
        }

        @Test
        void mapExposesUnmodifiableJavaMap() {
            ConfigMap map = ConfigMap.of(Map.of("port", ConfigInt.of(8080)));

            assertThat(map.entries()).containsEntry("port", ConfigInt.of(8080));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> map.entries().put("host", ConfigString.of("x")));
        }

        @Test
        void sourceCollectionIsCopied() {
            List<ConfigValue> source = new ArrayList<>(List.of(ConfigInt.of(1)));
            ConfigSeq seq = ConfigSeq.of(source);
            source.add(ConfigInt.of(2));

            assertThat(seq.size()).isEqualTo(1);
        }

        @Test
        void mapSourceIsCopied() {
            Map<String, ConfigValue> source = new HashMap<>(Map.of("a", ConfigInt.of(1)));
            ConfigMap map = ConfigMap.of(source);
            source.put("b", ConfigInt.of(2));

            assertThat(map.size()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("null does not exist in the configuration tree")
    class NoNull {

        @Test
        void nullIsRejectedEverywhere() {
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> ConfigString.of(null));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> ConfigTimestamp.of(null));
            assertThatIllegalArgumentException().isThrownBy(() -> ConfigSeq.of(listWithNull()));
            assertThatIllegalArgumentException().isThrownBy(() -> ConfigMap.of(mapWithNullValue()));
        }

        private List<ConfigValue> listWithNull() {
            List<ConfigValue> withNull = new ArrayList<>();
            withNull.add(ConfigInt.of(1));
            withNull.add(null);
            return withNull;
        }

        private Map<String, ConfigValue> mapWithNullValue() {
            Map<String, ConfigValue> withNull = new HashMap<>();
            withNull.put("a", null);
            return withNull;
        }
    }

    @Nested
    @DisplayName("binary: a blob is a value, a URI is only an instruction")
    class Binary {

        @Test
        void base64SchemeIsDecodedWithoutIo() {
            ConfigBinary binary = ConfigBinary.fromUri(URI.create("base64://SGVsbG8gd29ybGQ="));

            assertThat(new String(binary.blob())).isEqualTo("Hello world");
            assertThat(binary.size()).isEqualTo(11);
        }

        @Test
        @DisplayName("bare base64 without a scheme is an error — the message lists the allowed schemes")
        void bareBase64IsRejected() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ConfigBinary.fromUri(URI.create("SGVsbG8gd29ybGQ=")))
                .withMessageContaining("base64");
        }

        @Test
        @DisplayName("file:///https:// need I/O — the method says plainly that it is not ready yet")
        void ioSchemesAreLoudlyNotImplemented() {
            assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> ConfigBinary.fromUri(URI.create("file:///tmp/data.bin")))
                .withMessageContaining("needs reading a resource");
            assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> ConfigBinary.fromUri(URI.create("https://example.com/data.bin")));
        }

        @Test
        @DisplayName("the blob does not leak through a reference — a copy in both directions")
        void blobIsDefensivelyCopied() {
            byte[] source = {1, 2, 3};
            ConfigBinary binary = ConfigBinary.of(source);
            source[0] = 99;
            assertThat(binary.blob()).containsExactly((byte) 1, (byte) 2, (byte) 3);

            byte[] handed = binary.blob();
            handed[1] = 99;
            assertThat(binary.blob()).containsExactly((byte) 1, (byte) 2, (byte) 3);
        }

        @Test
        void equalityIsByBlobContent() {
            assertThat(ConfigBinary.of(new byte[] {1, 2})).isEqualTo(ConfigBinary.of(new byte[] {1, 2}));
            assertThat(ConfigBinary.of(new byte[] {1, 2})).isNotEqualTo(ConfigBinary.of(new byte[] {1}));
        }
    }

    @Nested
    @DisplayName("bool: the validator decides on literals of the raw source")
    class Bool {

        @Test
        @DisplayName("only true/false, letter case as in YAML")
        void onlyTrueAndFalsePass() {
            assertThat(ConfigBool.parseStrict("true").value()).isTrue();
            assertThat(ConfigBool.parseStrict("false").value()).isFalse();
            assertThat(ConfigBool.parseStrict("TRUE").value()).isTrue();
            assertThat(ConfigBool.parseStrict("False").value()).isFalse();
        }

        @Test
        @DisplayName("yes/1/on/0 is an ERROR, never a silent false")
        void yamlAndCliTrapLiteralsAreRejected() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ConfigBool.parseStrict("yes"))
                .withMessageContaining("allowed: `true`, `false`");
            assertThatIllegalArgumentException().isThrownBy(() -> ConfigBool.parseStrict("1"));
            assertThatIllegalArgumentException().isThrownBy(() -> ConfigBool.parseStrict("on"));
            assertThatIllegalArgumentException().isThrownBy(() -> ConfigBool.parseStrict("0"));
            assertThatIllegalArgumentException().isThrownBy(() -> ConfigBool.parseStrict("no"));
            assertThatIllegalArgumentException().isThrownBy(() -> ConfigBool.parseStrict(" true "));
        }
    }

    @Nested
    @DisplayName("a reference is symbolic")
    class Reference {

        @Test
        void holdsCanonicalPathNotAValue() {
            ConfigReference reference = ConfigReference.to("server.port");

            assertThat(reference.target()).isEqualTo(ConfigPath.of("server.port"));
            assertThat(reference.display()).isEqualTo("${server.port}");
        }

        @Test
        @DisplayName("the type of a reference does not exist before resolution — calling is loud, it does not guess")
        void hasNoTypeBeforeResolution() {
            assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> ConfigReference.to("a").type())
                .withMessageContaining("INTERPOLATION");
        }

        @Test
        @DisplayName("`${?a}` is the same value with a flag — `display()` reproduces the notation")
        void optionalKeepsTheSign() {
            assertThat(ConfigReference.optional("a").display()).isEqualTo("${?a}");
            assertThat(ConfigReference.optional("a")).isNotEqualTo(ConfigReference.to("a"));
        }
    }

    @Test
    void closedSetHasNoOtherVariants() {
        // Guard of the closed set: a new variant is a deliberate contract change — the two interpolation
        // variants arrived last, so the count grew 9 -> 11.
        assertThat(ConfigValue.class.getPermittedSubclasses()).hasSize(11);
        assertThatNoException().isThrownBy(() -> ConfigType.fromYamlName("str"));
    }
}
