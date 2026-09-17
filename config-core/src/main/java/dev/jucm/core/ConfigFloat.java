package dev.jucm.core;

/**
 * A floating-point value.
 *
 * <p>On a field declared as {@code float}, values must be written with a decimal point: {@code 1.0},
 * not {@code 1}. The stricter form is required so that a value never changes meaning between
 * declaring a field as an integer and as a float.
 *
 * @param value the numeric value
 */
public record ConfigFloat(double value) implements ConfigValue {

    /**
     * Creates a value without parsing a notation.
     *
     * @param value the value to carry
     * @return the value
     */
    public static ConfigFloat of(double value) {
        return new ConfigFloat(value);
    }

    @Override
    public ConfigType type() {
        return ConfigType.FLOAT;
    }

    @Override
    public String display() {
        return Double.toString(value);
    }
}
