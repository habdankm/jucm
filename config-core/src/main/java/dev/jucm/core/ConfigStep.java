package dev.jucm.core;

/**
 * A stage of the value pipeline.
 *
 * <p>Every value carries a verdict per stage, because a value may have been transformed without
 * having been cast yet, and callers need to tell those states apart. The declaration order of the
 * constants is the order in which the stages run.
 */
public enum ConfigStep {

    /** Turns text into symbols, and symbols into values, against the current configuration. */
    INTERPOLATION,

    /** Normalises the written notation using the transformer declared by the field. */
    TRANSFORMATION,

    /** Casts the value onto the type declared by the field. */
    CAST,

    /** Checks the value rules declared by the field, after a successful cast. */
    CONSTRAINTS
}
