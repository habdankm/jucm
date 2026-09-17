package dev.jucm.env;

/**
 * The process environment, told apart into <b>absent</b> and <b>empty</b>.
 *
 * <p>An absent variable is not the same as an empty one: {@code System.getenv} returns {@code null} for
 * the first and {@code ""} for the second, and an application that reads the environment itself may well
 * want to tell them apart. The library's own {@code EnvSource} treats both the same way — as a variable
 * that brings in nothing — because a variable left without a value states nothing about the address. This
 * class exists so that an application can make its own, different decision.
 *
 * @since 1.0
 */
public final class EnvVars {

    /**
     * How a variable stands: not set at all, set to an empty value, or set to something.
     *
     * @since 1.0
     */
    public enum State {
        /** The variable is not set at all. */
        ABSENT,
        /** The variable is set to an empty value. */
        EMPTY,
        /** The variable is set to a non-empty value. */
        PRESENT
    }

    private EnvVars() {
    }

    /**
     * Returns how a variable stands.
     *
     * @param name name of the variable; must not be {@code null}
     * @return {@link State#ABSENT} when the variable is not set, {@link State#EMPTY} when it is set to
     *         an empty value, otherwise {@link State#PRESENT}
     * @since 1.0
     */
    public static State state(String name) {
        String value = System.getenv(name);
        if (value == null) {
            return State.ABSENT;
        }
        return value.isEmpty() ? State.EMPTY : State.PRESENT;
    }

    /**
     * Returns the value of a variable when it is present and not empty.
     *
     * @param name name of the variable; must not be {@code null}
     * @return the value, or an empty {@link java.util.Optional} when the variable is absent or empty
     * @since 1.0
     */
    public static java.util.Optional<String> value(String name) {
        return java.util.Optional.ofNullable(System.getenv(name)).filter(v -> !v.isEmpty());
    }
}
