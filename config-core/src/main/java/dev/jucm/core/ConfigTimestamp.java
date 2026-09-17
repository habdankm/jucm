package dev.jucm.core;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;

/**
 * Value of type {@code timestamp} — {@link Instant}.
 *
 * <p><b>Default format: {@value #DEFAULT_FORMAT}</b>. That is the canonical form:
 * a full instant with the `Z` zone. A form with an explicit offset ({@code +02:00}) is accepted, because
 * it says the same thing — an exact moment — only written differently.
 *
 * <p><b>What we do not accept, and why:</b> everything that would need an **assumption** the declaration
 * did not state:
 *
 * <pre>
 *   2026-01-01T12:00:00Z      -> ok (the default format)
 *   2026-01-01T14:00:00+02:00 -> ok (the same moment, an explicit offset — we guess nothing)
 *   2026-01-01                -> ERROR (how would we know it is midnight? the declaration did not say)
 *   2026-01-01T12:00:00       -> ERROR (no zone — the meaning would depend on the environment)
 *   2026-01-01 12:00:00       -> ERROR (a different format; it needs an explicit formatter)
 * </pre>
 *
 * <p>Formats other than the default arrive through a **formatter** in the field declaration — then the
 * assumption (e.g. "this is UTC") is stated by the declaration, not the parser silently.
 *
 * <p>{@code java.util.Date} is **not** a value carrier: its interpretation depends on the behaviour of a
 * third-party library and on the default zone.
 *
 * @param value the moment; never null
 */
public record ConfigTimestamp(Instant value) implements ConfigValue {

    /** The canonical and default form — the same one error messages show. */
    public static final String DEFAULT_FORMAT = "2026-01-01T12:00:00Z";

    /** Validates the component — it is required. */
    public ConfigTimestamp {
        Objects.requireNonNull(value, "value");
    }

    /**
     * Creates a value without parsing a notation.
     *
     * @param value the moment to carry; must not be null
     * @return the value
     */
    public static ConfigTimestamp of(Instant value) {
        return new ConfigTimestamp(value);
    }

    /**
      * Parses a text in the default format (or with an explicit offset). Empty = not this format — the
      * caller turns that into a {@link ConfigError.CastFailed} with the raw value and the source.
     *
     * <p>Deterministically: there is neither a local nor a default zone here — the zone must come
     * with the text itself, as a `Z` or an explicit offset.
     *
     * @param raw the text to parse; must not be null
     * @return the moment, or empty when the text is neither an instant nor a date-time with an offset
     */
    public static Optional<ConfigTimestamp> parseStrict(String raw) {
        Objects.requireNonNull(raw, "raw");
        String text = raw.trim();
        try {
            return Optional.of(new ConfigTimestamp(Instant.parse(text)));
        } catch (DateTimeParseException notAnInstant) {
            // the same moment written with an offset is just as unambiguous — we keep trying
        }
        try {
            return Optional.of(new ConfigTimestamp(OffsetDateTime.parse(text).toInstant()));
        } catch (DateTimeParseException notAnOffsetDateTime) {
            return Optional.empty();
        }
    }

    @Override
    public ConfigType type() {
        return ConfigType.TIMESTAMP;
    }

    @Override
    public String display() {
        return value.toString();
    }
}
