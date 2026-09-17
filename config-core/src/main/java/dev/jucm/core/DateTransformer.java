package dev.jucm.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;
import java.util.Optional;
import java.util.Set;

/**
 * The default `date` transformer: brings a date notation to the **canonical form**
 * ({@value ConfigTimestamp#DEFAULT_FORMAT}, plus an explicit offset — the same moment, written differently).
 *
 * <p><b>Without a configuration</b> — it behaves like the previous cast: it accepts only an unambiguous
 * notation and rejects everything that would need an **assumption** the declaration did not state:
 *
 * <pre>
 *   2026-01-01T12:00:00Z        ok
 *   2026-01-01T14:00:00+02:00   ok
 *   2026-01-01                  rejected (how would we know it is midnight?)
 *   2026-01-01 12:00:00         rejected (a different format — it has to be stated)
 * </pre>
 *
 * <p><b>With a configuration</b> — the configuration is a **notation pattern** (`yyyy-MM-dd HH:mm:ss`),
 * that is, the declaration states the notation of its source. A pattern without a zone means UTC, and a
 * pattern without a time means midnight: that is exactly the stated intent the parser itself cannot
 * guess. Then, and only then, a "bare date" comes back as a valid value.
 */
final class DateTransformer implements Transformer {

    /** `date` handles the `timestamp` type only. */
    @Override
    public Set<ConfigType> allowedTypes() {
        return Set.of(ConfigType.TIMESTAMP);
    }

    @Override
    public Outcome normalize(ConfigValue raw, Optional<String> config) {
        if (raw instanceof ConfigTimestamp) {
            return Transformer.normalized(raw);
        }
        if (!(raw instanceof ConfigString text)) {
            return Transformer.rejected("expected a date as text — the incoming value has type `" + raw.type().yamlName() + "`");
        }
        String value = text.value().trim();
        if (config.isEmpty()) {
            return ConfigTimestamp.parseStrict(value)
                .<Outcome>map(Transformer::normalized)
                .orElseGet(() -> Transformer.rejected("expected the format `" + ConfigTimestamp.DEFAULT_FORMAT
                    + "` (or a form with an explicit offset) — another format needs a pattern in the declaration"));
        }
        return withPattern(value, config.get());
    }

    /** The notation stated by the declaration: the pattern says how to read the value (and what a missing part means). */
    private static Outcome withPattern(String value, String pattern) {
        DateTimeFormatter formatter;
        try {
            formatter = DateTimeFormatter.ofPattern(pattern);
        } catch (IllegalArgumentException badPattern) {
            return Transformer.rejected("invalid pattern `" + pattern + "`: " + badPattern.getMessage());
        }
        TemporalAccessor parsed;
        try {
            parsed = formatter.parseBest(value, ZonedDateTime::from, OffsetDateTime::from,
                LocalDateTime::from, LocalDate::from);
        } catch (DateTimeParseException notThisNotation) {
            return Transformer.rejected("the value does not match the pattern `" + pattern + "`");
        }
        return Transformer.normalized(ConfigTimestamp.of(toInstant(parsed)));
    }

    /**
     * A pattern without a zone reads as UTC, and without a time as midnight: that decision belongs to the
     * **declaration** that chose the pattern, not to the parser (otherwise we are back to guessing).
     */
    private static Instant toInstant(TemporalAccessor parsed) {
        if (parsed instanceof ZonedDateTime zoned) {
            return zoned.toInstant();
        }
        if (parsed instanceof OffsetDateTime offset) {
            return offset.toInstant();
        }
        if (parsed instanceof LocalDateTime local) {
            return local.toInstant(ZoneOffset.UTC);
        }
        return ((LocalDate) parsed).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
