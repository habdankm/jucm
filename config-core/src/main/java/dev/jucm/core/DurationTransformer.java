package dev.jucm.core;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads durations written in a human-readable notation, for example {@code 30s}, {@code 5m},
 * {@code 1h30m} or {@code 500ms}.
 *
 * <p>The units {@code ms}, {@code s}, {@code m}, {@code h} and {@code d} are accepted, in full spelling
 * as well ({@code 2 hours}). A unit must be followed by the end of a word, so {@code 5 months} is
 * rejected instead of being read as five minutes.
 *
 * <p>The normalised form uses a single unit — the largest one that divides the duration without a
 * remainder — and no spaces, so {@code 1h30m} becomes {@code 90m} while {@code 1500ms} stays
 * {@code 1500ms}. The result is a string, because {@code duration} is a cast target for declared fields
 * rather than a value type.
 *
 * <p>This transformer takes no configuration; custom units are not supported.
 */
final class DurationTransformer implements Transformer {

    /** `duration` handles the `duration` type only. */
    @Override
    public Set<ConfigType> allowedTypes() {
        return Set.of(ConfigType.DURATION);
    }

    /**
     * The configuration does not exist yet: **custom** units would arrive as their own unit table.
     * Until then a provided configuration is rejected, not ignored.
     */
    @Override
    public Optional<String> configProblem(Optional<String> config) {
        return config.map(value -> "`duration` has no configuration yet (custom units are "
            + "still pending) — unknown `" + value + "`");
    }

    /**
     * Units from the longest name to the shortest, and after a unit a word **MUST** end (`(?![a-z])`).
     * Without that, `2 hours` would split into `h` + the rest "ours", and `5 months` would be silently read
     * as minutes — that is, we would add a unit nobody stated.
     */
    private static final Pattern COMPONENT = Pattern.compile(
        "\\s*(\\d+)\\s*(milliseconds?|seconds?|minutes?|hours?|days?|ms|secs?|hrs?|mins?|h|m|s|d)(?![a-z])",
        Pattern.CASE_INSENSITIVE);

    @Override
    public Outcome normalize(ConfigValue raw, Optional<String> config) {
        if (!(raw instanceof ConfigString text)) {
            return Transformer.rejected("expected a duration as text — the incoming value has type `"
                + raw.type().yamlName() + "`");
        }
        String value = text.value().trim();
        if (value.isEmpty()) {
            return Transformer.rejected("an empty text is not a duration");
        }
        long millis = 0;
        int position = 0;
        Matcher matcher = COMPONENT.matcher(value);
        while (position < value.length()) {
            matcher.region(position, value.length());
            if (!matcher.lookingAt()) {
                return Transformer.rejected("cannot read the notation `" + value + "` — expected a number with a unit"
                    + " (`30s`, `5m`, `2h`, `1d`, `500ms`)");
            }
            try {
                millis = Math.addExact(millis, Math.multiplyExact(Long.parseLong(matcher.group(1)),
                    millisPer(matcher.group(2))));
            } catch (ArithmeticException | NumberFormatException tooBig) {
                return Transformer.rejected("the duration `" + value + "` exceeds the range of numbers we can compute");
            }
            position = matcher.end();
        }
        return Transformer.normalized(ConfigString.of(canonical(millis)));
    }

    private static long millisPer(String unit) {
        String lowered = unit.toLowerCase(java.util.Locale.ROOT);
        if (lowered.startsWith("ms") || lowered.startsWith("millisecond")) {
            return 1L;
        }
        if (lowered.startsWith("s") || lowered.startsWith("sec")) {
            return 1_000L;
        }
        if (lowered.startsWith("m")) {
            return 60_000L;
        }
        if (lowered.startsWith("h")) {
            return 3_600_000L;
        }
        return 86_400_000L;
    }

    /** One unit, without spaces — the largest one that divides without a remainder. */
    private static String canonical(long millis) {
        long[] factors = {86_400_000L, 3_600_000L, 60_000L, 1_000L};
        String[] units = {"d", "h", "m", "s"};
        for (int index = 0; index < factors.length; index++) {
            if (millis != 0 && millis % factors[index] == 0) {
                return (millis / factors[index]) + units[index];
            }
        }
        return millis + "ms";
    }
}
