package dev.jucm.core;

/**
 * A 64-bit integer value.
 *
 * <p>Note the asymmetry of the sources: YAML and environment variables can carry the full 64 bits,
 * while a value supplied on the command line is limited to 32 bits by the argument parser.
 *
 * @param value the numeric value, a full 64-bit signed integer
 */
public record ConfigInt(long value) implements ConfigValue {

    /**
     * Creates a value without parsing a notation.
     *
     * @param value the value to carry
     * @return the value
     */
    public static ConfigInt of(long value) {
        return new ConfigInt(value);
    }

    @Override
    public ConfigType type() {
        return ConfigType.INT;
    }

    @Override
    public String display() {
        return Long.toString(value);
    }
}
