package dev.jucm.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The application schema — <b>always typed</b>: it says what type a path has.
 *
 * <p>The type belongs to the application model, not to the data: YAML, ENV and CLI supply values, and
 * the schema decides what they mean. This type is the input of validation — without it there is
 * nothing to cast to, while a {@code ___TYPE} marker on the source side only shapes the
 * representation.
 *
 * <p>Instances are immutable: every {@link #with} returns a new schema and leaves the receiver
 * unchanged.
 *
 * <p>A path that is <b>absent</b> from the schema is not an error: it is an auxiliary entry, which
 * keeps the form it has in the source.
 *
 * @since 1.0
 */
public final class ConfigSchema {

    private static final ConfigSchema EMPTY = new ConfigSchema(Map.of());

    private final Map<ConfigPath, ConfigType> declarations;

    private ConfigSchema(Map<ConfigPath, ConfigType> declarations) {
        this.declarations = Map.copyOf(declarations);
    }

    /**
     * Returns the schema that declares nothing.
     *
     * @return an empty schema; every path is auxiliary in it
     */
    public static ConfigSchema empty() {
        return EMPTY;
    }

    /**
     * Creates a schema from a set of declarations. The map is copied, so later changes to it are not
     * visible in the schema.
     *
     * @param declarations the declared types, keyed by path; must not be {@code null}
     * @return a schema holding exactly those declarations
     * @throws NullPointerException if {@code declarations} is {@code null}
     */
    public static ConfigSchema of(Map<ConfigPath, ConfigType> declarations) {
        Objects.requireNonNull(declarations, "declarations");
        return new ConfigSchema(declarations);
    }

    /**
     * Returns a new schema with one more declaration — the receiver stays unchanged.
     *
     * @param path the path to declare; must not be {@code null}
     * @param type the type of that path; must not be {@code null}
     * @return a new schema; an already declared path is re-declared with the new type
     * @throws NullPointerException if {@code path} or {@code type} is {@code null}
     */
    public ConfigSchema with(ConfigPath path, ConfigType type) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(type, "type");
        Map<ConfigPath, ConfigType> extended = new LinkedHashMap<>(declarations);
        extended.put(path, type);
        return new ConfigSchema(extended);
    }

    /**
     * {@link #with(ConfigPath, ConfigType)} with the path given in canonical notation.
     *
     * @param canonicalPath the path to declare, in canonical notation; must not be {@code null}
     * @param type the type of that path; must not be {@code null}
     * @return a new schema with that declaration
     * @throws IllegalArgumentException if {@code canonicalPath} is not valid canonical notation
     */
    public ConfigSchema with(String canonicalPath, ConfigType type) {
        return with(ConfigPath.of(canonicalPath), type);
    }

    /**
     * Returns the type declared for a path, or empty when the path is auxiliary.
     *
     * @param path the path to look up
     * @return the declared type, or {@link Optional#empty()} when the path carries no declaration
     */
    public Optional<ConfigType> declaredType(ConfigPath path) {
        return Optional.ofNullable(declarations.get(path));
    }

    /**
     * Returns whether the path is declared — a missing declaration means auxiliary.
     *
     * @param path the path to check; must not be null
     * @return true when the model declares that path
     */
    public boolean declares(ConfigPath path) {
        return declarations.containsKey(path);
    }

    /**
     * Returns the number of declarations in this schema.
     *
     * @return the number of declared paths
     */
    public int size() {
        return declarations.size();
    }

    @Override
    public String toString() {
        return "ConfigSchema" + declarations;
    }
}
