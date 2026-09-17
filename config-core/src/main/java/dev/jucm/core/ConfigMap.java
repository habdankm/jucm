package dev.jucm.core;

import io.vavr.collection.LinkedHashMap;
import io.vavr.collection.Map;
import java.util.Objects;

/**
 * A map from string keys to values.
 *
 * <p>Instances are immutable and preserve insertion order, so both {@link #entries()} and
 * {@link #display()} are deterministic and match the order of the source the map came from.
 *
 * <p>An empty map is a valid instance but an invalid configuration value; validation rejects it,
 * because "no entries" is expressed by omitting the key.
 */
public final class ConfigMap implements ConfigValue {

    private final Map<String, ConfigValue> entries;

    private ConfigMap(Map<String, ConfigValue> entries) {
        this.entries = entries;
    }

    /**
     * Creates a map.
     *
     * @param entries entries of the map, in the order they should be kept; must not be {@code null}
     *        and must contain neither a {@code null} key nor a {@code null} value
     * @return the map
     * @throws IllegalArgumentException if a key or a value is {@code null}
     */
    public static ConfigMap of(java.util.Map<String, ConfigValue> entries) {
        Objects.requireNonNull(entries, "entries");
        for (java.util.Map.Entry<String, ConfigValue> entry : entries.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new IllegalArgumentException("`map` does not accept `null` — neither a key nor a value");
            }
        }
        return new ConfigMap(LinkedHashMap.ofAll(entries));
    }

    /**
     * An empty map value.
     *
     * @return a map with no entries
     */
    public static ConfigMap empty() {
        return new ConfigMap(LinkedHashMap.empty());
    }

    /**
     * Returns the entries in insertion order.
     *
     * @return an unmodifiable copy, in insertion order
     */
    public java.util.Map<String, ConfigValue> entries() {
        java.util.Map<String, ConfigValue> view = new java.util.LinkedHashMap<>();
        entries.forEach(view::put);
        return java.util.Collections.unmodifiableMap(view);
    }

    /**
     * Returns the number of entries.
     *
     * @return the number of entries
     */
    public int size() {
        return entries.size();
    }

    /**
     * Returns whether the map has no entries.
     *
     * @return true when there are no entries
     */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    @Override
    public ConfigType type() {
        return ConfigType.MAP;
    }

    /**
     * Returns the map in YAML notation, for example {@code {dom: 1}}, with entries in insertion order.
     */
    @Override
    public String display() {
        StringBuilder text = new StringBuilder("{");
        entries.forEach((key, value) -> {
            if (text.length() > 1) {
                text.append(", ");
            }
            text.append(key).append(": ").append(value.display());
        });
        return text.append('}').toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ConfigMap map && entries.equals(map.entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    @Override
    public String toString() {
        return display();
    }
}
