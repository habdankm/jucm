package dev.jucm.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Casting: one operation for all sources, a strict notation, and an error carrying path/value/source. */
class ConfigCastTest {

    private static final ConfigPath PORT = ConfigPath.of("server.port");
    private static final ConfigSource FROM_ENV = ConfigSource.of(Layer.ENV, "AIMENAGERIE__SERVER__PORT");

    private static ConfigValue cast(ConfigValue delivered, ConfigType declared) {
        ConfigResult<ConfigValue> result = ConfigCast.to(delivered, declared, PORT, FROM_ENV);
        assertThat(result).isInstanceOf(ConfigResult.ValueResult.class);
        return ((ConfigResult.ValueResult<ConfigValue>) result).value();
    }

    private static ConfigError failure(ConfigValue delivered, ConfigType declared) {
        ConfigResult<ConfigValue> result = ConfigCast.to(delivered, declared, PORT, FROM_ENV);
        assertThat(result).isInstanceOf(ConfigResult.ErrorResult.class);
        return ((ConfigResult.ErrorResult<ConfigValue>) result).error();
    }

    @Nested
    @DisplayName("int: 64 bits, YAML notation, a strict integer notation")
    class Int {

        @Test
        void acceptsIntAndYamlNotations() {
            assertThat(cast(ConfigInt.of(8080), ConfigType.INT)).isEqualTo(ConfigInt.of(8080));
            assertThat(cast(ConfigString.of("8080"), ConfigType.INT)).isEqualTo(ConfigInt.of(8080));
            assertThat(cast(ConfigString.of("010"), ConfigType.INT)).as("octal, as in YAML").isEqualTo(ConfigInt.of(8));
            assertThat(cast(ConfigString.of("0x1F"), ConfigType.INT)).isEqualTo(ConfigInt.of(31));
            assertThat(cast(ConfigString.of("0b1010"), ConfigType.INT)).isEqualTo(ConfigInt.of(10));
            assertThat(cast(ConfigString.of("1_000"), ConfigType.INT)).isEqualTo(ConfigInt.of(1000));
            assertThat(cast(ConfigString.of(" 9000 "), ConfigType.INT)).as("we trim, as YAML does").isEqualTo(ConfigInt.of(9000));
            assertThat(cast(ConfigString.of("-7"), ConfigType.INT)).isEqualTo(ConfigInt.of(-7));
        }

        @Test
        @DisplayName("`2.0` on an int field is an error — the message says what is expected")
        void rejectsFloatNotation() {
            ConfigError error = failure(ConfigFloat.of(2.0), ConfigType.INT);

            assertThat(error).isInstanceOf(ConfigError.CastFailed.class);
            assertThat(error.report())
                .contains("expected an integer notation, e.g. `2` - not `2.0`")
                .contains("path `server.port`")
                .contains("source ENV:AIMENAGERIE__SERVER__PORT");
        }

        @Test
        void rejectsTextAndOverflow() {
            assertThat(failure(ConfigString.of("abba"), ConfigType.INT).kind())
                .isEqualTo(ConfigError.Kind.CAST_FAILED);
            assertThat(failure(ConfigString.of("99999999999999999999"), ConfigType.INT).kind())
                .as("outside the range of long").isEqualTo(ConfigError.Kind.CAST_FAILED);
            assertThat(failure(ConfigString.of(""), ConfigType.INT).kind())
                .isEqualTo(ConfigError.Kind.CAST_FAILED);
        }
    }

    @Nested
    @DisplayName("float: only a notation with a decimal point")
    class Float {

        @Test
        void requiresDot() {
            assertThat(cast(ConfigFloat.of(1.5), ConfigType.FLOAT)).isEqualTo(ConfigFloat.of(1.5));
            assertThat(cast(ConfigString.of("1.10"), ConfigType.FLOAT)).isEqualTo(ConfigFloat.of(1.1));

            assertThat(failure(ConfigString.of("1"), ConfigType.FLOAT).report())
                .contains("path `server.port`");
            assertThat(failure(ConfigInt.of(1), ConfigType.FLOAT).report())
                .contains("expected a notation with a decimal point, e.g. `1.0` - not `1`");
        }

        @Test
        @DisplayName("NaN and Infinity rejected, and a decimal comma too")
        void rejectsSpecialValues() {
            assertThat(failure(ConfigString.of("NaN"), ConfigType.FLOAT).kind()).isEqualTo(ConfigError.Kind.CAST_FAILED);
            assertThat(failure(ConfigString.of("Infinity"), ConfigType.FLOAT).kind()).isEqualTo(ConfigError.Kind.CAST_FAILED);
            assertThat(failure(ConfigString.of("1,10"), ConfigType.FLOAT).kind()).isEqualTo(ConfigError.Kind.CAST_FAILED);
        }
    }

    @Nested
    @DisplayName("bool: the cast decides on literals, at the source")
    class Bool {

        @Test
        void onlyTrueAndFalse() {
            assertThat(cast(ConfigString.of("true"), ConfigType.BOOL)).isEqualTo(ConfigBool.of(true));
            assertThat(cast(ConfigString.of("FALSE"), ConfigType.BOOL)).isEqualTo(ConfigBool.of(false));
            assertThat(cast(ConfigBool.of(true), ConfigType.BOOL)).isEqualTo(ConfigBool.of(true));
        }

        @Test
        @DisplayName("`--x=yes` from the CLI ends in CastFailed with the raw value and the source, not a silent null")
        void yesFromCliBecomesCastFailedWithSource() {
            ConfigSource fromCli = ConfigSource.of(Layer.CLI, "--server-tls");

            ConfigResult<ConfigValue> result =
                ConfigCast.to(ConfigString.of("yes"), ConfigType.BOOL, ConfigPath.of("server.tls"), fromCli);

            assertThat(result).isInstanceOf(ConfigResult.ErrorResult.class);
            ConfigError error = ((ConfigResult.ErrorResult<ConfigValue>) result).error();
            assertThat(error.kind()).isEqualTo(ConfigError.Kind.CAST_FAILED);
            assertThat(error.report())
                .contains("CLI:--server-tls")
                .contains("value `yes`")
                .contains("path `server.tls`");
        }

        @Test
        void otherTypesAreRejected() {
            assertThat(failure(ConfigInt.of(1), ConfigType.BOOL).kind()).isEqualTo(ConfigError.Kind.CAST_FAILED);
            assertThat(failure(ConfigString.of("on"), ConfigType.BOOL).kind()).isEqualTo(ConfigError.Kind.CAST_FAILED);
        }
    }

    @Nested
    @DisplayName("timestamp: our own parser, deterministic, without a local time zone")
    class Timestamp {

        @Test
        @DisplayName("the default format is 2026-01-01T12:00:00Z; an explicit offset is the same instant")
        void acceptsDefaultFormatAndExplicitOffset() {
            assertThat(ConfigTimestamp.DEFAULT_FORMAT).isEqualTo("2026-01-01T12:00:00Z");

            assertThat(cast(ConfigString.of("2026-01-01T12:00:00Z"), ConfigType.TIMESTAMP))
                .isEqualTo(ConfigTimestamp.of(Instant.parse("2026-01-01T12:00:00Z")));
            assertThat(cast(ConfigString.of("2026-01-01T14:00:00+02:00"), ConfigType.TIMESTAMP))
                .as("the same instant, written explicitly — we guess nothing")
                .isEqualTo(ConfigTimestamp.of(Instant.parse("2026-01-01T12:00:00Z")));
            assertThat(cast(ConfigTimestamp.of(Instant.EPOCH), ConfigType.TIMESTAMP))
                .isEqualTo(ConfigTimestamp.of(Instant.EPOCH));
        }

        @Test
        @DisplayName("a date alone is REJECTED — it would assume midnight, and the declaration did not say so")
        void rejectsDateOnlyBecauseItAssumesMidnight() {
            ConfigError error = failure(ConfigString.of("2026-09-12"), ConfigType.TIMESTAMP);

            assertThat(error.kind()).isEqualTo(ConfigError.Kind.CAST_FAILED);
            assertThat(error.report())
                .contains("expected the format `2026-01-01T12:00:00Z`")
                .contains("needs a formatter in the declaration");
        }

        @Test
        @DisplayName("a form without a zone is rejected — its meaning would depend on the environment")
        void rejectsZonelessDateTime() {
            assertThat(failure(ConfigString.of("2026-09-12T10:00:00"), ConfigType.TIMESTAMP).kind())
                .isEqualTo(ConfigError.Kind.CAST_FAILED);
            assertThat(failure(ConfigString.of("2026-01-01 12:00:00"), ConfigType.TIMESTAMP).kind())
                .as("a space instead of `T` — another format, it needs a formatter").isEqualTo(ConfigError.Kind.CAST_FAILED);
            assertThat(failure(ConfigString.of("12.09.2026"), ConfigType.TIMESTAMP).kind())
                .isEqualTo(ConfigError.Kind.CAST_FAILED);
        }
    }

    @Nested
    @DisplayName("str, seq, map, binary")
    class Others {

        @Test
        @DisplayName("str: scalars are shown as text, an object and an array are an error")
        void strAcceptsScalarsOnly() {
            assertThat(cast(ConfigInt.of(8080), ConfigType.STR)).isEqualTo(ConfigString.of("8080"));
            assertThat(cast(ConfigBool.of(true), ConfigType.STR)).isEqualTo(ConfigString.of("true"));
            assertThat(cast(ConfigString.of(""), ConfigType.STR)).as("an EmptyString is a value").isEqualTo(ConfigString.of(""));

            assertThat(failure(ConfigSeq.empty(), ConfigType.STR).kind()).isEqualTo(ConfigError.Kind.CAST_FAILED);
            assertThat(failure(ConfigMap.empty(), ConfigType.STR).kind()).isEqualTo(ConfigError.Kind.CAST_FAILED);
        }

        @Test
        void containersMustMatchTheirType() {
            assertThat(cast(ConfigSeq.empty(), ConfigType.SEQ)).isInstanceOf(ConfigSeq.class);
            assertThat(cast(ConfigMap.empty(), ConfigType.MAP)).isInstanceOf(ConfigMap.class);
            assertThat(failure(ConfigSeq.empty(), ConfigType.MAP).kind()).isEqualTo(ConfigError.Kind.CAST_FAILED);
            assertThat(failure(ConfigInt.of(1), ConfigType.SEQ).kind()).isEqualTo(ConfigError.Kind.CAST_FAILED);
        }

        @Test
        @DisplayName("binary from text: a scheme is mandatory; a scheme that needs I/O is a CastFailed")
        void binaryNeedsScheme() {
            assertThat(cast(ConfigString.of("base64://SGVsbG8="), ConfigType.BINARY))
                .isInstanceOf(ConfigBinary.class);
            assertThat(failure(ConfigString.of("SGVsbG8="), ConfigType.BINARY).kind())
                .as("bare base64 without a scheme").isEqualTo(ConfigError.Kind.CAST_FAILED);
        }
    }

    @Test
    @DisplayName("a reference is resolved by the interpolation step — the cast does not guess it")
    void referenceIsNotCastable() {
        ConfigError error = failure(ConfigReference.to("server.port"), ConfigType.INT);

        assertThat(error.kind())
            .as("whoever skips the interpolation step gets the truth: a reference leads to no value")
            .isEqualTo(ConfigError.Kind.UNRESOLVED_REFERENCE);
        assertThat(error.report()).contains("`server.port`").contains("leads to no value");
    }
}
