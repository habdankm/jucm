package dev.jucm.core;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The `size` transformer: `10MB` -> **a number of bytes**. It is **not a type** — it is a conversion,
 * so the field declares `int` and the result is a number.
 *
 * <pre>
 *   10MB  -> 10 000 000          decimal (SI)
 *   2GiB  -> 2 147 483 648       binary (IEC)
 *   1.5MB -> 1 500 000
 * </pre>
 *
 * <p>The units are **written with the `B` suffix**, because `10M` does not say whether it means decimal
 * or binary — and that is a difference of almost 5%. A unit outside the dictionary is an error with the
 * list of allowed ones (just like `5 months` in `duration`). Fractional bytes (`0.5B`) are an error too:
 * a byte is whole.
 *
 * <p>`size` **has no** configuration — and when someone provides one, the transformer **rejects it with
 * a reason** instead of ignoring it silently. The numeric part is in canonical notation
 * (decimal point), without a locale: only `number` has a locale.
 */
final class SizeTransformer implements Transformer {

    private static final Map<String, BigDecimal> UNITS = units();

    private static final Set<ConfigType> INTEGRAL = Set.of(ConfigType.INT);

    private static Map<String, BigDecimal> units() {
        Map<String, BigDecimal> units = new LinkedHashMap<>();
        units.put("B", BigDecimal.ONE);
        units.put("KB", BigDecimal.valueOf(1_000L));
        units.put("MB", BigDecimal.valueOf(1_000_000L));
        units.put("GB", BigDecimal.valueOf(1_000_000_000L));
        units.put("TB", BigDecimal.valueOf(1_000_000_000_000L));
        units.put("KiB", BigDecimal.valueOf(1L << 10));
        units.put("MiB", BigDecimal.valueOf(1L << 20));
        units.put("GiB", BigDecimal.valueOf(1L << 30));
        units.put("TiB", BigDecimal.valueOf(1L << 40));
        return java.util.Collections.unmodifiableMap(units);
    }

    /** Unit names for messages — in insertion order, so the list is stable. */
    static String unitNames() {
        return String.join(", ", UNITS.keySet());
    }

    @Override
    public Set<ConfigType> allowedTypes() {
        return INTEGRAL;
    }

    @Override
    public Optional<String> configProblem(Optional<String> config) {
        return config.map(value -> "`size` has no configuration — unknown `" + value + "`");
    }

    @Override
    public Outcome normalize(ConfigValue raw, Optional<String> config) {
        if (config.isPresent()) {
            return Transformer.rejected("`size` has no configuration — unknown `" + config.get() + "`");
        }
        if (!(raw instanceof ConfigString text)) {
            return Transformer.rejected("expected a size as text (`10MB`) — the incoming value has type `"
                + raw.type().yamlName() + "`");
        }
        String trimmed = text.value().trim();
        int splitAt = splitPoint(trimmed);
        if (splitAt <= 0) {
            return Transformer.rejected("expected a number with a unit (`10MB`, `2GiB`) — allowed units: "
                + unitNames());
        }
        String number = trimmed.substring(0, splitAt);
        String unit = trimmed.substring(splitAt);
        BigDecimal multiplier = UNITS.get(unit);
        if (multiplier == null) {
            return Transformer.rejected("unknown unit `" + unit + "` — allowed: " + unitNames()
                + " (with the `B` suffix, because decimal and binary are not the same)");
        }
        BigDecimal count;
        try {
            count = new BigDecimal(number.replace("_", ""), MathContext.UNLIMITED);
        } catch (NumberFormatException notANumber) {
            return Transformer.rejected("`" + number + "` is not a number (canonical notation: decimal point)");
        }
        BigDecimal bytes = count.multiply(multiplier);
        if (bytes.stripTrailingZeros().scale() > 0) {
            return Transformer.rejected("`" + trimmed + "` is a fraction of a byte — a byte is whole");
        }
        try {
            return Transformer.normalized(ConfigInt.of(bytes.longValueExact()));
        } catch (ArithmeticException outOfRange) {
            return Transformer.rejected("`" + trimmed + "` exceeds the range of `long`");
        }
    }

    /** The boundary between number and unit: the first letter after the digits (a dot and an underscore belong to the number). */
    private static int splitPoint(String text) {
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isLetter(character)) {
                return index;
            }
            if (character != '.' && character != '_' && character != ',' && character != ' '
                && character != '\u00A0' && !Character.isDigit(character)) {
                return -1; // a character outside the notation (including a sign — a size is never negative)
            }
        }
        return -1;
    }
}
