package dev.jucm.core;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Optional;
import java.util.Set;

/**
 * The `percentage` transformer: `10%` -> **`0.1`**. Like `size` — it is
 * **not a type**, only a conversion, so the field declares `float`.
 *
 * <pre>
 *   10%    -> 0.1
 *   0.5%   -> 0.005
 *   100%   -> 1.0
 * </pre>
 *
 * <p>We accept **only `%`** and without spaces (`10 %` is not our notation — a space means something, so
 * we do not guess). `percentage` **has no** configuration, and one that is given is **rejected with a
 * reason**. The numeric part is in canonical notation (decimal point) — only `number`
 * has a locale.
 */
final class PercentageTransformer implements Transformer {

    private static final Set<ConfigType> FRACTIONAL = Set.of(ConfigType.FLOAT);

    @Override
    public Set<ConfigType> allowedTypes() {
        return FRACTIONAL;
    }

    @Override
    public Optional<String> configProblem(Optional<String> config) {
        return config.map(value -> "`percentage` has no configuration — unknown `" + value + "`");
    }

    @Override
    public Outcome normalize(ConfigValue raw, Optional<String> config) {
        if (config.isPresent()) {
            return Transformer.rejected("`percentage` has no configuration — unknown `" + config.get() + "`");
        }
        if (!(raw instanceof ConfigString text)) {
            return Transformer.rejected("expected a percentage as text (`10%`) — the incoming value has type `"
                + raw.type().yamlName() + "`");
        }
        String trimmed = text.value().trim();
        if (!trimmed.endsWith("%") || trimmed.length() == 1) {
            return Transformer.rejected("expected a number with `%` (`10%`) — no spaces and no other units");
        }
        String number = trimmed.substring(0, trimmed.length() - 1);
        if (!number.matches("[0-9_]+(\\.[0-9]+)?|\\.[0-9]+")) {
            return Transformer.rejected("`" + number + "` is not a number (canonical notation: decimal point, no spaces)");
        }
        BigDecimal percent;
        try {
            percent = new BigDecimal(number.replace("_", ""), MathContext.UNLIMITED);
        } catch (NumberFormatException notANumber) {
            return Transformer.rejected("`" + number + "` is not a number (canonical notation: decimal point)");
        }
        return Transformer.normalized(ConfigFloat.of(percent.movePointLeft(2).doubleValue()));
    }
}
