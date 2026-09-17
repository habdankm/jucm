package dev.jucm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Transformers: the registry is explicit and immutable, the notation is separated from the type, an
 * unknown name never degrades silently, and the contracts of the transformers already in use must not
 * move.
 */
class TransformerRegistryTest {

    private static Transformer.Outcome outcome(String name, String config, ConfigValue raw) {
        return TransformerRegistry.defaults().require(name).normalize(raw, Optional.ofNullable(config));
    }

    private static ConfigValue normalized(String name, ConfigValue raw) {
        return normalized(name, null, raw);
    }

    private static ConfigValue normalized(String name, String config, ConfigValue raw) {
        Transformer.Outcome outcome = outcome(name, config, raw);

        assertThat(outcome).as("`%s` rejected `%s`", name, raw.display())
            .isInstanceOf(Transformer.Outcome.Normalized.class);
        return ((Transformer.Outcome.Normalized) outcome).value();
    }

    private static String rejection(String name, String config, ConfigValue raw) {
        Transformer.Outcome outcome = outcome(name, config, raw);

        assertThat(outcome).as("`%s` accepted `%s`", name, raw.display())
            .isInstanceOf(Transformer.Outcome.Rejected.class);
        return ((Transformer.Outcome.Rejected) outcome).reason();
    }

    @Nested
    @DisplayName("registry: explicit, immutable, no singleton")
    class Registry {

        @Test
        @DisplayName("defaults() gives number, date, duration, uri, an explicit trim, size and percentage")
        void defaultsCarryTheJucmTransformers() {
            assertThat(TransformerRegistry.defaults().names())
                .containsExactly("number", "date", "duration", "uri", "trim", "size", "percentage");
        }

        @Test
        @DisplayName("registration returns a NEW registry — the old one stays unchanged")
        void registerDoesNotMutate() {
            TransformerRegistry base = TransformerRegistry.defaults();
            TransformerRegistry extended = base.register("my-date",
                () -> (raw, config) -> Transformer.normalized(raw));

            assertThat(base.lookup("my-date")).isEmpty();
            assertThat(extended.lookup("my-date")).isPresent();
            assertThat(extended.size()).isEqualTo(base.size() + 1);
        }

        @Test
        @DisplayName("the same name twice is an error — overriding would change behaviour depending on order")
        void duplicateNameIsRejected() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> TransformerRegistry.defaults()
                    .register("date", () -> (raw, config) -> Transformer.normalized(raw)))
                .withMessageContaining("is already registered");
        }

        @Test
        @DisplayName("an unknown name is loud and lists what to choose from")
        void unknownNameIsLoud() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> TransformerRegistry.defaults().require("formatter"))
                .withMessageContaining("`formatter`")
                .withMessageContaining("number");
        }

        @Test
        @DisplayName("the default notation: int/float -> number, timestamp -> date; the rest have none")
        void defaultForType() {
            TransformerRegistry registry = TransformerRegistry.defaults();

            assertThat(registry.defaultFor(ConfigType.INT)).isPresent();
            assertThat(registry.defaultFor(ConfigType.FLOAT)).isPresent();
            assertThat(registry.defaultFor(ConfigType.TIMESTAMP)).isPresent();
            assertThat(registry.defaultFor(ConfigType.STR)).as("`str` does not get a trim automatically").isEmpty();
            assertThat(registry.defaultFor(ConfigType.BOOL)).isEmpty();
            assertThat(registry.defaultFor(ConfigType.SEQ)).isEmpty();
        }

        @Test
        @DisplayName("transformers are stateless: the same transformer can be used for many values at once")
        void transformersAreStateless() {
            Transformer number = TransformerRegistry.defaults().require("number");
            Optional<String> none = Optional.empty();

            assertThat(number.normalize(ConfigString.of("010"), none)).isEqualTo(number.normalize(ConfigString.of("010"), none));
            assertThat(number.normalize(ConfigString.of("010"), none)).isEqualTo(Transformer.normalized(ConfigInt.of(8)));
            assertThat(number.normalize(ConfigString.of("1.10"), none)).isEqualTo(Transformer.normalized(ConfigFloat.of(1.1)));
        }
    }

    @Nested
    @DisplayName("number: the YAML notation, not the field type")
    class Numbers {

        @Test
        void yamlNotations() {
            assertThat(normalized("number", ConfigString.of("010"))).isEqualTo(ConfigInt.of(8));
            assertThat(normalized("number", ConfigString.of("0x1F"))).isEqualTo(ConfigInt.of(31));
            assertThat(normalized("number", ConfigString.of("0b1010"))).isEqualTo(ConfigInt.of(10));
            assertThat(normalized("number", ConfigString.of("1_000"))).isEqualTo(ConfigInt.of(1000));
            assertThat(normalized("number", ConfigString.of(" 9000 "))).isEqualTo(ConfigInt.of(9000));
            assertThat(normalized("number", ConfigString.of("-7"))).isEqualTo(ConfigInt.of(-7));
        }

        @Test
        @DisplayName("the decimal point decides the shape — the int/float strictness stays in the cast")
        void dotDecidesTheShape() {
            assertThat(normalized("number", ConfigString.of("1.10"))).isEqualTo(ConfigFloat.of(1.1));
            assertThat(normalized("number", ConfigInt.of(1))).isEqualTo(ConfigInt.of(1));
        }

        @Test
        void rejectsWhatIsNotANumber() {
            assertThat(rejection("number", null, ConfigString.of("abba"))).contains("not a number");
            assertThat(rejection("number", null, ConfigString.of(""))).contains("empty text");
            assertThat(rejection("number", null, ConfigString.of("NaN"))).contains("rejected");
            assertThat(rejection("number", null, ConfigString.of("Infinity"))).contains("rejected");
            assertThat(rejection("number", null, ConfigString.of("1,10")))
                .as("an ambiguous separator: the reason must point at the missing configuration, not at \"not a number\"")
                .contains("ambiguous");
            assertThat(rejection("number", null, ConfigString.of("99999999999999999999"))).contains("not a number");
        }
    }

    @Nested
    @DisplayName("date: by default only an unambiguous form; with a pattern the notation is stated")
    class Dates {

        @Test
        @DisplayName("without a pattern: the canonical format and an explicit offset; nothing that requires an assumption")
        void withoutPatternKeepsTheStrictContract() {
            assertThat(normalized("date", ConfigString.of("2026-01-01T12:00:00Z")))
                .isEqualTo(ConfigTimestamp.of(Instant.parse("2026-01-01T12:00:00Z")));
            assertThat(normalized("date", ConfigString.of("2026-01-01T14:00:00+02:00")))
                .isEqualTo(ConfigTimestamp.of(Instant.parse("2026-01-01T12:00:00Z")));

            assertThat(rejection("date", null, ConfigString.of("2026-09-12")))
                .contains("expected the format `2026-01-01T12:00:00Z`");
            assertThat(rejection("date", null, ConfigString.of("2026-01-01 12:00:00")))
                .contains("expected the format `2026-01-01T12:00:00Z`");
        }

        @Test
        @DisplayName("with a pattern: `yyyy-MM-dd HH:mm:ss` is a declaration saying \"this is UTC\"")
        void withPatternTheDeclarationSpeaks() {
            assertThat(normalized("date", "yyyy-MM-dd HH:mm:ss", ConfigString.of("2026-01-01 12:00:00")))
                .as("a pattern without a zone means UTC, and a space instead of `T` stops being a problem")
                .isEqualTo(ConfigTimestamp.of(Instant.parse("2026-01-01T12:00:00Z")));
            assertThat(normalized("date", "yyyy-MM-dd", ConfigString.of("2026-01-01")))
                .as("a pattern without an hour says explicitly that the missing hour is midnight")
                .isEqualTo(ConfigTimestamp.of(Instant.parse("2026-01-01T00:00:00Z")));
        }

        @Test
        void badValueOrPatternIsLoud() {
            assertThat(rejection("date", "yyyy-MM-dd HH:mm:ss", ConfigString.of("12.09.2026")))
                .contains("does not match the pattern");
            assertThat(rejection("date", "not-[a-pattern", ConfigString.of("whatever")))
                .contains("invalid pattern");
        }
    }

    @Nested
    @DisplayName("duration, uri, trim")
    class Others {

        @Test
        @DisplayName("duration: one unit, no spaces, the largest one that divides without a remainder")
        void durationCanonicalises() {
            assertThat(normalized("duration", ConfigString.of("30s"))).isEqualTo(ConfigString.of("30s"));
            assertThat(normalized("duration", ConfigString.of("5 m"))).isEqualTo(ConfigString.of("5m"));
            assertThat(normalized("duration", ConfigString.of("1h30m"))).isEqualTo(ConfigString.of("90m"));
            assertThat(normalized("duration", ConfigString.of("2 hours"))).isEqualTo(ConfigString.of("2h"));
            assertThat(normalized("duration", ConfigString.of("1500ms")))
                .as("a second does not divide 1500 ms without a remainder")
                .isEqualTo(ConfigString.of("1500ms"));
            assertThat(rejection("duration", null, ConfigString.of("soon")))
                .contains("expected a number with a unit");
            assertThat(rejection("duration", null, ConfigString.of("5 months")))
                .as("a unit outside the vocabulary must not be appended silently")
                .contains("expected a number with a unit");
        }

        @Test
        @DisplayName("uri: a scheme is mandatory, the syntax is checked, the value is not rewritten")
        void uriNeedsScheme() {
            assertThat(normalized("uri", ConfigString.of("https://api.openai.com")))
                .isEqualTo(ConfigString.of("https://api.openai.com"));
            assertThat(rejection("uri", null, ConfigString.of("api.openai.com"))).contains("missing scheme");
            assertThat(rejection("uri", null, ConfigString.of("http://a b"))).contains("URI");
        }

        @Test
        @DisplayName("trim: explicit, on a string; rejected on a number, because it would mean nothing")
        void trimIsExplicit() {
            assertThat(normalized("trim", ConfigString.of("  x  "))).isEqualTo(ConfigString.of("x"));
            assertThat(rejection("trim", null, ConfigInt.of(7))).contains("works on a string");
        }
    }

    @Nested
    @DisplayName("the cast delegates the notation, but the contract stays")
    class CastDelegation {

        private static final ConfigPath PORT = ConfigPath.of("server.port");
        private static final ConfigSource FROM_ENV = ConfigSource.of(Layer.ENV, "AIMENAGERIE__SERVER__PORT");

        @Test
        @DisplayName("replacing the registry changes the default notation")
        void customRegistryChangesTheDefaultNotation() {
            TransformerRegistry registry = TransformerRegistry.defaults()
                .override("number", () -> (raw, config) -> raw instanceof ConfigString text
                    ? Transformer.normalized(ConfigInt.of(Long.parseLong(text.value())))
                    : Transformer.rejected("strings only"));

            ConfigResult<ConfigValue> result =
                ConfigCast.to(ConfigString.of("010"), ConfigType.INT, PORT, FROM_ENV, registry);

            assertThat(result).as("a custom `number` reads decimally, not as octal")
                .isEqualTo(ConfigResult.value(ConfigInt.of(10)));
        }

        @Test
        @DisplayName("taking the default notation away from JUCM is explicit: `override`, not `register`")
        void overridingADefaultIsExplicit() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> TransformerRegistry.defaults()
                    .override("no-such-transformer", () -> (raw, config) -> Transformer.normalized(raw)))
                .withMessageContaining("there is nothing to replace");
        }

        @Test
        @DisplayName("without a registry the JUCM notation applies: `010` is 8, ` 9000 ` is 9000, a date alone is a CastFailed")
        void jucmDefaultsStillApply() {
            assertThat(ConfigCast.to(ConfigString.of("010"), ConfigType.INT, PORT, FROM_ENV))
                .isEqualTo(ConfigResult.value(ConfigInt.of(8)));
            assertThat(ConfigCast.to(ConfigString.of(" 9000 "), ConfigType.INT, PORT, FROM_ENV))
                .isEqualTo(ConfigResult.value(ConfigInt.of(9000)));
            assertThat(ConfigCast.to(ConfigString.of("1.10"), ConfigType.FLOAT, PORT, FROM_ENV))
                .isEqualTo(ConfigResult.value(ConfigFloat.of(1.1)));
            assertThat(ConfigCast.to(ConfigString.of("2026-09-12"), ConfigType.TIMESTAMP, PORT, FROM_ENV))
                .as("the declaration has to state the pattern, not the parser")
                .isInstanceOf(ConfigResult.ErrorResult.class);
        }
    }
}
