package dev.jucm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Canonical path: form, equality and the grammar of a key. */
class ConfigPathTest {

    @Test
    @DisplayName("the canonical form is the one of() accepts")
    void canonicalRoundTrip() {
        ConfigPath path = ConfigPath.of("server.port");

        assertThat(path.canonical()).isEqualTo("server.port");
        assertThat(path.segments()).containsExactly("server", "port");
        assertThat(ConfigPath.of(path.canonical())).isEqualTo(path);
    }

    @Test
    void childGoesDeeper() {
        assertThat(ConfigPath.of("server").child("port")).isEqualTo(ConfigPath.of("server.port"));
    }

    @Test
    void equalityIsByCanonicalForm() {
        assertThat(ConfigPath.of(List.of("server", "port"))).isEqualTo(ConfigPath.of("server.port"));
        assertThat(ConfigPath.of("server.port")).hasSameHashCodeAs(ConfigPath.of("server.port"));
    }

    @Test
    @DisplayName("a single _ stays an ordinary character")
    void singleUnderscoreIsPartOfTheName() {
        assertThat(ConfigPath.of("server.max_connections").segments())
            .containsExactly("server", "max_connections");
    }

    @Nested
    @DisplayName("grammar: what we reject")
    class Grammar {

        @Test
        void emptyPathIsRejected() {
            assertThatIllegalArgumentException().isThrownBy(() -> ConfigPath.of(""));
        }

        @Test
        void emptySegmentIsRejected() {
            assertThatIllegalArgumentException().isThrownBy(() -> ConfigPath.of("server..port"));
            assertThatIllegalArgumentException().isThrownBy(() -> ConfigPath.of("server."));
        }

        @Test
        @DisplayName("`__` belongs to the engine — it must not be used inside a name")
        void hierarchySeparatorIsRejected() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ConfigPath.of("server__port"))
                .withMessageContaining("`__`");
        }

        @Test
        @DisplayName("`___` belongs to the engine — it is a type marker, not part of the name")
        void typeMarkerIsRejected() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> ConfigPath.of("port___type"))
                .withMessageContaining("`___`");
        }

        @Test
        @DisplayName("the lower-case convention is NOT enforced")
        void uppercaseIsAllowedByConventionOnly() {
            assertThat(ConfigPath.of("Server.Port").canonical()).isEqualTo("Server.Port");
        }
    }

    @Nested
    @DisplayName("order of addresses: BY VALUE, not by text")
    class Order {

        private static List<String> sorted(String... addresses) {
            List<ConfigPath> paths = new java.util.ArrayList<>();
            for (String address : addresses) {
                paths.add(ConfigPath.of(address));
            }
            paths.sort(ConfigPath.BY_VALUE);
            return paths.stream().map(ConfigPath::canonical).toList();
        }

        @Test
        @DisplayName("`ports.2` before `ports.10` — a list address is a NUMBER (`\"10\" < \"2\"` is the trap)")
        void listAddressesCompareAsNumbers() {
            assertThat(sorted("ports.10", "ports.2", "ports.1"))
                .containsExactly("ports.1", "ports.2", "ports.10");
        }

        @Test
        @DisplayName("a numeric KEY orders as a number: `9` before `10` — also deeper in the address")
        void numericKeysCompareAsNumbers() {
            assertThat(sorted("m.10", "m.9")).containsExactly("m.9", "m.10");
            assertThat(sorted("m.10.a", "m.9.a")).containsExactly("m.9.a", "m.10.a");
        }

        @Test
        @DisplayName("text keys stay text, and a prefix comes before what extends it (`a` before `a.0`)")
        void textKeysAndPrefixes() {
            assertThat(sorted("m.banana", "m.apple")).containsExactly("m.apple", "m.banana");
            assertThat(sorted("a.0", "a")).containsExactly("a", "a.0");
            assertThat(sorted("ports.10", "port")).containsExactly("port", "ports.10");
        }

        @Test
        @DisplayName("leading zeros compare by VALUE, and a long number does not overflow (no parsing)")
        void leadingZerosAndLongNumbers() {
            assertThat(sorted("m.007", "m.10", "m.2")).containsExactly("m.2", "m.007", "m.10");
            assertThat(sorted("m.999999999999999999999", "m.1000000000000000000000"))
                .containsExactly("m.999999999999999999999", "m.1000000000000000000000");
        }
    }

    @Test
    @DisplayName("no null: a null path is a programmer error, not a configuration state")
    void nullIsRejected() {
        assertThatExceptionOfType(NullPointerException.class).isThrownBy(() -> ConfigPath.of((String) null));
    }
}
