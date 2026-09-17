package dev.jucm.core;

import java.util.Objects;

/**
 * Where a value came from: a layer plus an address within that layer.
 *
 * <p>The address is whatever identifies the value in its source — a file and line, an environment
 * variable name, an option name. It is kept per value, so diagnostics can point at the exact place a
 * value was taken from.
 *
 * <p>Origin is not part of the identity of a value or a patch: two patches that set the same value
 * are equal even when they came from different layers. See {@link ConfigPatch.SetValue}.
 *
 * <pre>{@code
 * ConfigSource.of(Layer.YAML, "application.yaml:12")      -> YAML:application.yaml:12
 * ConfigSource.of(Layer.ENV,  "APP__SERVER__TLS")         -> ENV:APP__SERVER__TLS
 * ConfigSource.of(Layer.CLI,  "--server-tls")             -> CLI:--server-tls
 * ConfigSource.application()                              -> CUSTOM:application code
 * }</pre>
 */
public final class ConfigSource {

    private final Layer layer;
    private final String origin;

    private ConfigSource(Layer layer, String origin) {
        this.layer = layer;
        this.origin = origin;
    }

    /**
     * Creates a source.
     *
     * @param layer layer the value belongs to; must not be {@code null}
     * @param origin address of the value within the layer, such as a file and line or a variable
     *        name; must not be {@code null} or blank
     * @return a source for that layer and address
     * @throws IllegalArgumentException if {@code origin} is blank
     */
    public static ConfigSource of(Layer layer, String origin) {
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(origin, "origin");
        if (origin.isBlank()) {
            throw new IllegalArgumentException("origin cannot be empty — we do not know where the value came from");
        }
        return new ConfigSource(layer, origin);
    }

    /**
     * Creates a source for a value contributed by application code.
     *
     * @return a source on {@link Layer#CUSTOM}
     */
    public static ConfigSource application() {
        return new ConfigSource(Layer.CUSTOM, "application code");
    }

    /**
     * Creates a source for a value materialised from a field declaration.
     *
     * @param path path of the field whose declaration supplied the value; must not be {@code null}
     * @return a source on {@link Layer#COMPILED}
     */
    public static ConfigSource declaration(ConfigPath path) {
        Objects.requireNonNull(path, "path");
        return new ConfigSource(Layer.COMPILED, "declaration " + path.canonical());
    }

    /**
     * Returns the layer this value belongs to.
     *
     * @return the layer, never null
     */
    public Layer layer() {
        return layer;
    }

    /**
     * Returns the address of the value within its layer, without the layer prefix.
     *
     * @return the address, never null or blank
     */
    public String origin() {
        return origin;
    }

    /**
     * Returns the printable form of this source.
     *
     * @return the layer and address joined with a colon, for example {@code YAML:application.yaml:12}
     */
    public String display() {
        return layer + ":" + origin;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ConfigSource source
            && layer == source.layer
            && origin.equals(source.origin);
    }

    @Override
    public int hashCode() {
        return Objects.hash(layer, origin);
    }

    @Override
    public String toString() {
        return display();
    }
}
