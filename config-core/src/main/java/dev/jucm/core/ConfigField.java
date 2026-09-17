package dev.jucm.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The definition of a configuration field: an entity **independent of values**.
 *
 * <p>{@code defaultValue} and a value that was set are **two separate states**: without that
 * distinction there would be no way to tell "someone set this" from "this is the default from the
 * declaration", and that difference is needed at once in diagnostics, in {@code reset}, in the UI and
 * in the answer to "where did this value come from".
 *
 * <p>{@code defaultValue} is the **data source for the root layer**, not a second value: the root
 * layer is materialised from declarations, so the truth about a default value is exactly one.
 *
 * <p>{@code required} is independent of whether a {@code defaultValue} exists:
 *
 * <pre>
 *   required + defaultValue      -&gt; no override gives the default value (correctly)
 *   required, no defaultValue    -&gt; no value means `Absent` -&gt; error
 *   optional, no defaultValue    -&gt; `Absent` is a valid state
 * </pre>
 *
 * <p>The definition **MUST NOT** carry a "current value" as state: a read is computed from the chain.
 *
 * <p><b>An empty collection as {@code defaultValue} is a model error</b>: {@code []} and {@code {}}
 * mean "I want no elements", and that is expressed by omitting {@code defaultValue} (the field then
 * stays optional), not by a value pretending to be absent. It is caught **at declaration time**, so a
 * mistake in code shows up at startup rather than only when the root layer is materialised.
 *
 * <p><b>A transformation is declared here and nowhere else</b>: {@code transformer} is WHAT (a name in
 * the registry), {@code transformerConfig} is HOW (a configuration, opaque to this class - the
 * transformer interprets it). A value that is already typed needs no notation interpretation, so a
 * patch does **not** carry it: if a transformation travelled with the value, the same patch rebuilt
 * without it would give a different configuration.
 *
 * <p><b>The transformer name is not checked here</b>: a declaration knows no registry, and guessing
 * "it is probably there" is excluded. An unknown name is a **loud** error at first use (in
 * {@link ConfigCast} or in the transformation step). What can be checked here is checked.
 *
 * @param path canonical path of the field; must not be {@code null}
 * @param type declared type of the field; must not be {@code null}
 * @param required whether a missing value is an error ({@code true}) or a valid state ({@code false})
 * @param defaultValue value contributed by the field declaration; must not be {@code null}, and if
 *        present it must not be an empty collection
 * @param description human-readable description of the field; empty when not stated
 * @param aliases short CLI option aliases; must not contain blank entries
 * @param metadata free-form metadata carried by the declaration
 * @param constraints value rules declared for this field
 * @param transformer name of the transformation in the registry; if present it must not be blank
 * @param transformerConfig configuration of that transformation; only allowed together with
 *        {@code transformer}
 * @param elementType what this field HOLDS when it is a collection: the element of a {@code seq}, the value of a
 *        {@code map}; only allowed for those two types
 * @param keyType what a {@code map}'s KEY must be; only allowed for {@code map} — a key is text in the model, so
 *        the declaration says what that text must BE, and it is what orders keys BY VALUE
 * @since 1.0
 */
public record ConfigField(
    ConfigPath path,
    ConfigType type,
    boolean required,
    Optional<ConfigValue> defaultValue,
    String description,
    List<String> aliases,
    Map<String, String> metadata,
    List<ConfigConstraint> constraints,
    Optional<String> transformer,
    Optional<String> transformerConfig,
    Optional<ConfigType> elementType,
    Optional<ConfigType> keyType) {

    /**
     * Validates the declaration and copies every collection into an immutable one.
     *
     * @throws NullPointerException if any component except {@code required} is {@code null}
     * @throws IllegalArgumentException if an alias or a transformer name is blank, if
     *         {@code transformerConfig} is present without {@code transformer}, or if
     *         {@code defaultValue} is an empty list or an empty map
     */
    public ConfigField {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(defaultValue, "defaultValue");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(transformer, "transformer");
        Objects.requireNonNull(transformerConfig, "transformerConfig");
        Objects.requireNonNull(elementType, "elementType");
        Objects.requireNonNull(keyType, "keyType");
        if (keyType.isPresent() && type != ConfigType.MAP) {
            throw new IllegalArgumentException("field `" + path.canonical() + "` declares a KEY type (`"
                + keyType.get().yamlName() + "`), but its type is `" + type.yamlName()
                + "` - only a `map` has keys");
        }
        if (elementType.isPresent() && type != ConfigType.SEQ && type != ConfigType.MAP) {
            throw new IllegalArgumentException("field `" + path.canonical() + "` declares an element type (`"
                + elementType.get().yamlName() + "`), but its type is `" + type.yamlName()
                + "` - an element type belongs to a `seq` or a `map`");
        }
        if ((type == ConfigType.SEQ || type == ConfigType.MAP) && elementType.isEmpty()) {
            // A collection says what it HOLDS: without the type of its elements the declaration does not know
            // what is inside, and the elements would have to be guessed. A field built from a SOURCE statement
            // gets its types from the carrier (`SourceDeclaration.base`), so this fires for DECLARATIONS.
            throw new IllegalArgumentException("field `" + path.canonical() + "` is a `" + type.yamlName()
                + "` and states no element type - a collection says what it holds: use `"
                + (type == ConfigType.SEQ ? "ConfigField.seq(path, elementType)"
                    : "ConfigField.map(path, keyType, valueType)") + "`");
        }
        if (type == ConfigType.MAP && keyType.isEmpty()) {
            throw new IllegalArgumentException("field `" + path.canonical() + "` is a `map` and states no key type"
                + " - a map states BOTH: `ConfigField.map(path, keyType, valueType)`");
        }
        aliases = List.copyOf(aliases);
        metadata = Map.copyOf(metadata);
        constraints = List.copyOf(constraints);
        for (String alias : aliases) {
            if (alias.isBlank()) {
                throw new IllegalArgumentException("empty alias in field `" + path.canonical() + "`");
            }
        }
        transformer.ifPresent(name -> {
            if (name.isBlank()) {
                throw new IllegalArgumentException("empty transformer name in field `" + path.canonical() + "`");
            }
        });
        if (transformerConfig.isPresent() && transformer.isEmpty()) {
            throw new IllegalArgumentException("field `" + path.canonical() + "` has a transformation configuration ("
                + transformerConfig.get() + "), but no transformer - a configuration without a name means nothing");
        }
        defaultValue.ifPresent(value -> {
            if (value instanceof ConfigSeq seq && seq.isEmpty()) {
                throw new IllegalArgumentException("`defaultValue` of field `" + path.canonical()
                    + "` is an empty list - an empty collection is an error: remove `defaultValue`"
                    + " (the field stays optional) or provide elements");
            }
            if (value instanceof ConfigMap map && map.isEmpty()) {
                throw new IllegalArgumentException("`defaultValue` of field `" + path.canonical()
                    + "` is an empty map - an empty collection is an error: remove `defaultValue`"
                    + " (the field stays optional) or provide entries");
            }
        });
    }

    /**
     * Creates a required field with a default value - the most common case.
     *
     * @param canonicalPath path of the field, in canonical form
     * @param type declared type of the field
     * @param defaultValue value used when no layer overrides the field
     * @return a required field with that default value
     */
    public static ConfigField required(String canonicalPath, ConfigType type, ConfigValue defaultValue) {
        return new ConfigField(ConfigPath.of(canonicalPath), type, true, Optional.of(defaultValue), "",
            List.of(), Map.of(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty());
    }

    /**
     * Creates a required field without a default value: no value means {@code Absent}, that is, an
     * error.
     *
     * @param canonicalPath path of the field, in canonical form
     * @param type declared type of the field
     * @return a required field without a default value
     */
    public static ConfigField required(String canonicalPath, ConfigType type) {
        return new ConfigField(ConfigPath.of(canonicalPath), type, true, Optional.empty(), "",
            List.of(), Map.of(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty());
    }

    /**
     * Creates an optional field: {@code Absent} is a valid state.
     *
     * @param canonicalPath path of the field, in canonical form
     * @param type declared type of the field
     * @return an optional field without a default value
     */
    public static ConfigField optional(String canonicalPath, ConfigType type) {
        return new ConfigField(ConfigPath.of(canonicalPath), type, false, Optional.empty(), "",
            List.of(), Map.of(), List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty());
    }

    /**
     * Creates a required `seq` whose ELEMENTS are of the given type.
     *
     * <p>A collection states the type of its elements: the declaration is the source of truth, so "what is
     * inside" is never guessed. A `map` states the type of its VALUES - keys are text everywhere (YAML, ENV and
     * the command line all name an address with text).
     *
     * @param canonicalPath path of the field, in canonical form
     * @param elementType type of one element; must not be {@code null}
     * @return a required `seq` with typed elements and no default value
     */
    public static ConfigField seq(String canonicalPath, ConfigType elementType) {
        return new ConfigField(ConfigPath.of(canonicalPath), ConfigType.SEQ, true, Optional.empty(), "",
            List.of(), Map.of(), List.of(), Optional.empty(), Optional.empty(), Optional.of(elementType), Optional.empty());
    }

    /**
     * Creates a required `seq` with a default value - see {@link #seq(String, ConfigType)}.
     *
     * @param canonicalPath path of the field, in canonical form
     * @param elementType type of one element; must not be {@code null}
     * @param defaultValue value used when no layer overrides the field
     * @return a required `seq` with typed elements and that default value
     */
    public static ConfigField seq(String canonicalPath, ConfigType elementType, ConfigValue defaultValue) {
        return new ConfigField(ConfigPath.of(canonicalPath), ConfigType.SEQ, true, Optional.of(defaultValue), "",
            List.of(), Map.of(), List.of(), Optional.empty(), Optional.empty(), Optional.of(elementType), Optional.empty());
    }

    /**
     * Creates an optional `seq` with typed elements: {@code Absent} is a valid state, and an unstated optional
     * collection is exactly that.
     *
     * @param canonicalPath path of the field, in canonical form
     * @param elementType type of one element; must not be {@code null}
     * @return an optional `seq` with typed elements
     */
    public static ConfigField optionalSeq(String canonicalPath, ConfigType elementType) {
        return new ConfigField(ConfigPath.of(canonicalPath), ConfigType.SEQ, false, Optional.empty(), "",
            List.of(), Map.of(), List.of(), Optional.empty(), Optional.empty(), Optional.of(elementType), Optional.empty());
    }

    /**
     * Creates a required `map` with BOTH of its types stated: the KEY type and the VALUE type.
     *
     * <p>A key is TEXT in the model - every source names an address with text - so the key type is a
     * declaration <b>over text</b>: it says what a key may be (each key must be readable as that type) and, when
     * the type has an order (`int`, `float`, `timestamp`), it is what an output may be ordered by. Without it
     * `{10: a, 9: b}` orders as text, and `10` comes before `9`.
     *
     * @param canonicalPath path of the field, in canonical form
     * @param keyType type of one key; must not be {@code null}
     * @param valueType type of one value; must not be {@code null}
     * @return a required `map` with typed keys and values, and no default value
     */
    public static ConfigField map(String canonicalPath, ConfigType keyType, ConfigType valueType) {
        return new ConfigField(ConfigPath.of(canonicalPath), ConfigType.MAP, true, Optional.empty(), "",
            List.of(), Map.of(), List.of(), Optional.empty(), Optional.empty(), Optional.of(valueType),
            Optional.of(keyType));
    }

    /**
     * Creates a required `map` with a default value - see {@link #map(String, ConfigType, ConfigType)}.
     *
     * @param canonicalPath path of the field, in canonical form
     * @param keyType type of one key; must not be {@code null}
     * @param valueType type of one value; must not be {@code null}
     * @param defaultValue value used when no layer overrides the field
     * @return a required `map` with typed keys and values, and that default value
     */
    public static ConfigField map(String canonicalPath, ConfigType keyType, ConfigType valueType,
                                  ConfigValue defaultValue) {
        return new ConfigField(ConfigPath.of(canonicalPath), ConfigType.MAP, true, Optional.of(defaultValue), "",
            List.of(), Map.of(), List.of(), Optional.empty(), Optional.empty(), Optional.of(valueType),
            Optional.of(keyType));
    }

    /**
     * Creates an optional `map` with typed keys and values - see {@link #optionalSeq(String, ConfigType)}.
     *
     * @param canonicalPath path of the field, in canonical form
     * @param keyType type of one key; must not be {@code null}
     * @param valueType type of one value; must not be {@code null}
     * @return an optional `map` with typed keys and values
     */
    public static ConfigField optionalMap(String canonicalPath, ConfigType keyType, ConfigType valueType) {
        return new ConfigField(ConfigPath.of(canonicalPath), ConfigType.MAP, false, Optional.empty(), "",
            List.of(), Map.of(), List.of(), Optional.empty(), Optional.empty(), Optional.of(valueType),
            Optional.of(keyType));
    }

    /**
     * Returns a copy with the given description - a label for {@code -help}, for the UI and for the
     * comment emitted by {@code config-generate}.
     *
     * @param text description of the field
     * @return a copy carrying that description
     */
    public ConfigField described(String text) {
        return new ConfigField(path, type, required, defaultValue, text, aliases, metadata, constraints,
            transformer, transformerConfig, elementType, keyType);
    }

    /**
     * Returns a copy with the given short CLI option aliases - part of the declaration, not a separate
     * registry.
     *
     * @param shortNames aliases used on the command line
     * @return a copy carrying exactly those aliases
     * @throws IllegalArgumentException if any alias is blank
     */
    public ConfigField aliased(String... shortNames) {
        return new ConfigField(path, type, required, defaultValue, description, List.of(shortNames), metadata,
            constraints, transformer, transformerConfig, elementType, keyType);
    }

    /**
     * Returns a copy with the given rules added - for example a choice from a closed set.
     *
     * @param rules value rules to add to the ones already declared
     * @return a copy carrying the old rules followed by the given ones
     */
    public ConfigField constrained(ConfigConstraint... rules) {
        List<ConfigConstraint> both = new java.util.ArrayList<>(constraints);
        both.addAll(List.of(rules));
        return new ConfigField(path, type, required, defaultValue, description, aliases, metadata, both,
            transformer, transformerConfig, elementType, keyType);
    }

    /**
     * Returns a copy with the rules **REPLACED** by the given ones - for a rule stated by a source that
     * SUPERSEDES the rules from code ({@code ___CONST}, {@code --x--const}, the {@code CONST(...)}
     * section of a YAML comment).
     *
     * <p>A marker from a source **replaces** the field's rules: it is neither added to them nor silently
     * ignored, so {@code retries: 12 # CONST(<=10)} on a field declared in code really does something.
     * The **type's** default rule stays: it belongs to the type, not to the field, and a type must mean
     * the same in every field.
     *
     * @param rules value rules that replace the declared ones
     * @return a copy carrying exactly those rules
     */
    public ConfigField constrainedInstead(ConfigConstraint... rules) {
        return new ConfigField(path, type, required, defaultValue, description, aliases, metadata,
            List.of(rules), transformer, transformerConfig, elementType, keyType);
    }

    /**
     * Returns a copy with the transformation name - WHAT, such as {@code transformer("date")}.
     *
     * @param name name under which the transformer is registered
     * @return a copy carrying that transformation
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public ConfigField transformer(String name) {
        Objects.requireNonNull(name, "name");
        return new ConfigField(path, type, required, defaultValue, description, aliases, metadata, constraints,
            Optional.of(name), transformerConfig, elementType, keyType);
    }

    /**
     * Returns a copy with the transformer configuration - HOW, opaque to this class, such as
     * {@code transformerConfig("yyyy-MM-dd")}.
     *
     * @param config configuration interpreted by the transformer
     * @return a copy carrying that configuration
     * @throws NullPointerException if {@code config} is {@code null}
     * @throws IllegalArgumentException if no transformer was stated
     */
    public ConfigField transformerConfig(String config) {
        Objects.requireNonNull(config, "config");
        return new ConfigField(path, type, required, defaultValue, description, aliases, metadata, constraints,
            transformer, Optional.of(config), elementType, keyType);
    }

    /**
     * Returns a copy with one metadata entry added or replaced.
     *
     * @param key metadata key
     * @param value metadata value
     * @return a copy carrying that entry
     */
    public ConfigField withMetadata(String key, String value) {
        java.util.Map<String, String> extended = new java.util.LinkedHashMap<>(metadata);
        extended.put(key, value);
        return new ConfigField(path, type, required, defaultValue, description, aliases, extended, constraints,
            transformer, transformerConfig, elementType, keyType);
    }

    /**
     * Returns whether the field states its input notation - the cast and the transformation step ask
     * about it.
     *
     * @return {@code true} when a transformer is declared
     */
    public boolean declaresTransformation() {
        return transformer.isPresent();
    }
}
