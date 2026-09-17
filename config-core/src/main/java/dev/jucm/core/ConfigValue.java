package dev.jucm.core;

/**
 * A value in the configuration tree.
 *
 * <p>Implementations are immutable. The set of implementations is closed, so callers may switch over
 * a value exhaustively.
 */
public sealed interface ConfigValue
    permits ConfigString, ConfigInt, ConfigFloat, ConfigBool, ConfigTimestamp,
            ConfigSeq, ConfigMap, ConfigBinary, ConfigReference, ConfigExpression, ConfigCoalesce {

    /**
     * Returns the type of this value, named as in YAML.
     *
     * @return the type, never null
     */
    ConfigType type();

    /**
     * Returns a notation intended for humans and diagnostics.
     *
     * <p>Binary content and whole containers are never included: a blob is shown as its size, a
     * container as its shape.
     *
     * @return a notation intended for humans, never null
     */
    String display();
}
