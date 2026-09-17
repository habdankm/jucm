package dev.jucm.core;

import java.util.Objects;

/**
 * The result of reading a single value: either a value or an error.
 *
 * <p>A read never returns {@code null} and never throws for a missing value. To collect the problems
 * of a whole configuration, use {@link ConfigValidator#build}.
 *
 * @param <T> the type of the value a successful read carries
 */
public sealed interface ConfigResult<T> permits ConfigResult.ValueResult, ConfigResult.ErrorResult {

    /**
     * A successful read.
     *
     * @param <T>   the type of the value carried
     * @param value the value read; never null
     */
    record ValueResult<T>(T value) implements ConfigResult<T> {
        /** Validates the component — it is required. */
        public ValueResult {
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * A failed read.
     *
     * @param <T>   the type of the value the read would have carried
     * @param error the error that made the read fail; never null
     */
    record ErrorResult<T>(ConfigError error) implements ConfigResult<T> {
        /** Validates the component — it is required. */
        public ErrorResult {
            Objects.requireNonNull(error, "error");
        }
    }

    /**
     * A successful read.
     *
     * @param <T>   the type of the value carried
     * @param value the value read; must not be null
     * @return the result carrying that value
     */
    static <T> ConfigResult<T> value(T value) {
        return new ValueResult<>(value);
    }

    /**
     * A failed read.
     *
     * @param <T>   the type of the value the read would have carried
     * @param error the error that made the read fail; must not be null
     * @return the result carrying that error
     */
    static <T> ConfigResult<T> error(ConfigError error) {
        return new ErrorResult<>(error);
    }

    /**
     * Returns whether this result carries a value.
     *
     * @return true when the read succeeded
     */
    default boolean isValue() {
        return this instanceof ValueResult<T>;
    }
}
