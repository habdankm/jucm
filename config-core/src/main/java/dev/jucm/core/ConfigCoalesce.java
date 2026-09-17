package dev.jucm.core;

import java.util.Objects;

/**
 * A fallback value: {@code a ?? b}.
 *
 * <p>Instances are immutable. The type of this value is carried by the side that is actually chosen
 * during resolution, so {@link #type()} cannot be called before {@link Config#get} has resolved the
 * value.
 *
 * <p>The right-hand side may be another instance, giving a chain ({@code a ?? b ?? c}).
 *
 * @param left  the value used when it resolves; never null
 * @param right the fallback used when the left side yields no value; never null
 */
public record ConfigCoalesce(ConfigValue left, ConfigValue right) implements ConfigValue {

    /** Validates the components — every one of them is required. */
    public ConfigCoalesce {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
    }

    /**
     * Returns the type of this value.
     *
     * @throws UnsupportedOperationException always; the type is only known after resolution, which is
     *         performed by {@link Config#get}
     */
    @Override
    public ConfigType type() {
        throw new UnsupportedOperationException("ConfigCoalesce has no type before resolution — "
            + "the type is carried by the side chosen during resolution (INTERPOLATION step)");
    }

    /**
     * Returns the notation of this value, which can be parsed back.
     *
     * @return the form {@code ${a ?? b}}; parsing it yields an equal value
     */
    @Override
    public String display() {
        return "${" + inner(left) + " ?? " + inner(right) + "}";
    }

    private static String inner(ConfigValue value) {
        return switch (value) {
            case ConfigReference reference -> (reference.optional() ? "?" : "") + reference.target().canonical();
            case ConfigCoalesce nested -> inner(nested.left()) + " ?? " + inner(nested.right());
            default -> value.display();
        };
    }
}
