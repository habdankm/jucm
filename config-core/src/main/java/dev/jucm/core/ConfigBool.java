package dev.jucm.core;

import java.util.Objects;

/**
 * A boolean value.
 *
 * <p>The only accepted literals are {@code true} and {@code false} (case-insensitive). Other forms
 * commonly used in configuration files — {@code yes}, {@code no}, {@code on}, {@code off},
 * {@code 1}, {@code 0} — are rejected instead of being read as {@code false}.
 *
 * @param value the boolean value; it comes from the literals {@code true} and {@code false} only
 */
public record ConfigBool(boolean value) implements ConfigValue {

    /**
     * Creates a boolean value without parsing a literal.
     *
     * @param value the value to carry
     * @return the value
     */
    public static ConfigBool of(boolean value) {
        return new ConfigBool(value);
    }

    /**
     * Parses a boolean literal.
     *
     * @param raw literal to parse; must not be {@code null}
     * @return {@code true} for a {@code true} literal, {@code false} for a {@code false} literal
     * @throws IllegalArgumentException if the literal is neither, with a message naming the allowed
     *         literals
     */
    public static ConfigBool parseStrict(String raw) {
        Objects.requireNonNull(raw, "raw");
        if (raw.equalsIgnoreCase("true")) {
            return TRUE;
        }
        if (raw.equalsIgnoreCase("false")) {
            return FALSE;
        }
        throw new IllegalArgumentException("literal `" + raw
            + "` is not a `bool` value — allowed: `true`, `false`");
    }

    private static final ConfigBool TRUE = new ConfigBool(true);
    private static final ConfigBool FALSE = new ConfigBool(false);

    @Override
    public ConfigType type() {
        return ConfigType.BOOL;
    }

    @Override
    public String display() {
        return Boolean.toString(value);
    }
}
