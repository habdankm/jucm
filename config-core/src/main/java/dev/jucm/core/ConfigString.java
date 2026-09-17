package dev.jucm.core;

import java.util.Objects;

/**
 * A string value.
 *
 * <p>An empty string is a valid instance of this type, but configuration validation rejects empty
 * values coming from any source, so an empty string normally does not reach an application.
 *
 * @param value the string content; never null, may be empty
 */
public record ConfigString(String value) implements ConfigValue {

    /** Validates the component — it is required. */
    public ConfigString {
        Objects.requireNonNull(value, "value");
    }

    /**
     * Creates a value without parsing a notation.
     *
     * @param value the value to carry; must not be null
     * @return the value
     */
    public static ConfigString of(String value) {
        return new ConfigString(value);
    }

    @Override
    public ConfigType type() {
        return ConfigType.STR;
    }

    /** Returns the raw string, without quoting. */
    @Override
    public String display() {
        return value;
    }
}
