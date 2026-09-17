package dev.jucm.core;

import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads numbers written in YAML notation.
 *
 * <p>Accepted forms follow YAML: decimal, octal with a leading zero ({@code 010} is 8), hexadecimal
 * ({@code 0x1F}), binary ({@code 0b1010}), underscores between digits ({@code 1_000}) and exponents
 * ({@code 1e6}). Surrounding whitespace is ignored.
 *
 * <p>Any notation using grouping or decimal separators needs a locale of the form
 * {@code language_REGION}, for example {@code en_US} or {@code da_DK}, because {@code 1,000} means one
 * thousand in {@code en_US} and one-point-zero in {@code pl_PL}. Without a locale such a text is
 * rejected instead of guessed. Any other configuration value is a model error.
 *
 * <p>Rejected: {@code NaN}, {@code Infinity} and {@code 0o755} (which YAML 1.1 treats as a string).
 *
 * <p>This transformer does not know the field type, so it does not decide between {@code int} and
 * {@code float}: it returns the form that follows from the notation. Requiring a decimal point on a
 * {@code float} field is the cast's job.
 */
final class NumberTransformer implements Transformer {

    /** The locale has the form `language_REGION` — the region is mandatory, only it carries separators. */
    private static final Pattern LOCALE = Pattern.compile("[a-z]{2,3}[_-][A-Za-z]{2}");

    private static final Set<ConfigType> NUMERIC = Set.of(ConfigType.INT, ConfigType.FLOAT);

    @Override
    public Set<ConfigType> allowedTypes() {
        return NUMERIC;
    }

    @Override
    public Optional<String> configProblem(Optional<String> config) {
        if (config.isEmpty() || localeOf(config.get()).isPresent()) {
            return Optional.empty();
        }
        return Optional.of("the `number` configuration is a locale of the form `language_REGION` "
            + "(e.g. `pl_PL`, `da_DK`, `en_US`) — unknown `" + config.get() + "`");
    }

    @Override
    public Outcome normalize(ConfigValue raw, Optional<String> config) {
        if (raw instanceof ConfigInt || raw instanceof ConfigFloat) {
            return Transformer.normalized(raw); // already a number — there is nothing to normalise
        }
        if (!(raw instanceof ConfigString text)) {
            return Transformer.rejected("expected a number in YAML notation — the incoming value has type `"
                + raw.type().yamlName() + "`");
        }
        String trimmed = text.value().trim();
        if (trimmed.isEmpty()) {
            return Transformer.rejected("an empty text is not a number");
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.contains("nan") || lower.contains("inf")) {
            return Transformer.rejected("`NaN` and `Infinity` are rejected — they are not a numeric configuration value");
        }
        if (config.isEmpty()) {
            return canonical(trimmed);
        }
        Optional<Locale> locale = localeOf(config.get());
        if (locale.isEmpty()) {
            return Transformer.rejected("the `number` configuration is a locale of the form `language_REGION` "
                + "(e.g. `pl_PL`, `da_DK`) — unknown `" + config.get() + "`");
        }
        return localized(trimmed, locale.get(), config.get());
    }

    /** Canonical YAML notation, without a locale: an integer, and when that is impossible a float. */
    private static Outcome canonical(String text) {
        Optional<Long> integral = parseYamlLong(text);
        if (integral.isPresent()) {
            return Transformer.normalized(ConfigInt.of(integral.get()));
        }
        return parseYamlDouble(text)
            .<Outcome>map(value -> Transformer.normalized(ConfigFloat.of(value)))
            .orElseGet(() -> notANumber(text));
    }

    /**
     * A locale-specific notation: the grouping separator **must stand in the right places** (the first
     * group 1-3 digits, every following one exactly 3), and the decimal separator marks the fractional
     * part. Without that check `1,5` in `en_US` would mean 15 — a number nobody wrote.
     */
    private static Outcome localized(String text, Locale locale, String config) {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(locale);
        char grouping = canonicalSeparator(symbols.getGroupingSeparator());
        char decimal = canonicalSeparator(symbols.getDecimalSeparator());
        if (grouping == decimal) {
            return Transformer.rejected("locale `" + config + "` has the same grouping and decimal separator");
        }
        String normalized = canonicalCharacters(text);
        if (normalized.indexOf(grouping) >= 0 && !validGrouping(normalized, grouping, decimal)) {
            return Transformer.rejected("grouping separator in the wrong place — `" + text
                + "` is not a number in `" + config + "` (and we do not guess what the author meant)");
        }
        String plain = normalized.replace(String.valueOf(grouping), "").replace(decimal, '.');
        return canonical(plain);
    }

    /** Character family: CLDR groups with a non-breaking space (`pl_PL`) or a narrow one (`fr_FR`), not a plain space. */
    private static String canonicalCharacters(String text) {
        return text.replace('\u00A0', ' ').replace('\u202F', ' ').replace('\u2007', ' ')
            .replace('\u2009', ' ')
            .replace('\u2019', '\'').replace('\u02BC', '\'').replace('\u00B4', '\'');
    }

    private static char canonicalSeparator(char separator) {
        return canonicalCharacters(String.valueOf(separator)).charAt(0);
    }

    private static boolean validGrouping(String text, char grouping, char decimal) {
        String body = text;
        if (!body.isEmpty() && (body.charAt(0) == '+' || body.charAt(0) == '-')) {
            body = body.substring(1);
        }
        int decimalAt = body.indexOf(decimal);
        if (decimalAt >= 0) {
            body = body.substring(0, decimalAt);
        }
        String[] groups = body.split(Pattern.quote(String.valueOf(grouping)), -1);
        if (groups.length == 1) {
            return true;
        }
        if (groups[0].isEmpty() || groups[0].length() > 3 || !digits(groups[0])) {
            return false;
        }
        for (int index = 1; index < groups.length; index++) {
            if (groups[index].length() != 3 || !digits(groups[index])) {
                return false;
            }
        }
        return true;
    }

    private static boolean digits(String text) {
        return text.chars().allMatch(character -> character >= '0' && character <= '9');
    }

    private static Optional<Locale> localeOf(String config) {
        if (!LOCALE.matcher(config).matches()) {
            return Optional.empty();
        }
        Locale locale = Locale.forLanguageTag(config.replace('_', '-'));
        if (locale.getLanguage().isEmpty() || locale.getCountry().isEmpty()) {
            return Optional.empty(); // an underscore passed straight to `forLanguageTag` loses the country
        }
        return Optional.of(locale);
    }

    /**
     * The rejection reason says **what to fix**: when the text looks like a number with a separator but
     * cannot be decided without a locale, we point at the locale directly — otherwise the user hears
     * "not a number" and does not know that `1,000` is a different value in every country.
     */
    private static Outcome notANumber(String text) {
        boolean looksLocalized = text.matches("[0-9_.,'+\\-\\s\\u00A0\\u202F]+") && text.matches(".*[0-9].*")
            && text.matches(".*[,\\s'\\u00A0\\u202F\\u2009].*");
        if (looksLocalized) {
            return Transformer.rejected("the separator is ambiguous (`1,000` is a different number in every country) "
                + "— state the locale: `transformer(\"number\").transformerConfig(\"pl_PL\")`");
        }
        return Transformer.rejected("not a number in YAML notation (`010` = 8, `0x1F` = 31, `1_000` = 1000, `1e6`)");
    }

    /** An integer in YAML formats; a value outside the `long` range is a rejection, not a `BigInteger`. */
    static Optional<Long> parseYamlLong(String raw) {
        String text = raw.trim();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        boolean negative = false;
        String body = text;
        if (text.startsWith("+") || text.startsWith("-")) {
            negative = text.startsWith("-");
            body = text.substring(1);
        }
        String digits = body.replace("_", "");
        if (digits.isEmpty()) {
            return Optional.empty();
        }
        int radix = 10;
        String numeric = digits;
        if (digits.length() > 2 && (digits.startsWith("0x") || digits.startsWith("0X"))) {
            radix = 16;
            numeric = digits.substring(2);
        } else if (digits.length() > 2 && (digits.startsWith("0b") || digits.startsWith("0B"))) {
            radix = 2;
            numeric = digits.substring(2);
        } else if (digits.length() > 1 && digits.startsWith("0")) {
            radix = 8;
            numeric = digits.substring(1);
        }
        if (radix == 10 && numeric.matches(".*[eE].*")) {
            return Optional.empty(); // an exponent is a floating-point notation (`1e6`), not an integer one
        }
        try {
            long value = Long.parseLong(numeric, radix);
            return Optional.of(negative ? -value : value);
        } catch (NumberFormatException outOfRangeOrNotANumber) {
            return Optional.empty();
        }
    }

    /** A `float` with a decimal point **or with an exponent** (`1e6`) — exactly as YAML reads it. */
    static Optional<Double> parseYamlDouble(String raw) {
        String text = raw.trim().replace("_", "");
        if (text.isEmpty()) {
            return Optional.empty();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("nan") || lower.contains("inf")) {
            return Optional.empty();
        }
        if (!text.contains(".") && !text.matches(".*[eE].*")) {
            return Optional.empty();
        }
        try {
            return Optional.of(Double.parseDouble(text));
        } catch (NumberFormatException notADouble) {
            return Optional.empty();
        }
    }
}
