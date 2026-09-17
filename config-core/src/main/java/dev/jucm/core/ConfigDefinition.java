package dev.jucm.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Application model — **the single source of truth about metadata**: types, `required`,
 * default values, descriptions and aliases, used by `-help`, the TUI, `config-validation` and
 * `config-generate`. Adapters do not duplicate that knowledge.
 *
 * <p>Any node may declare — the position in the chain does not matter; the shape of the
 * call decides: a definition provides **more** than setting a value.
 *
 * <p>Aliases **MUST** be **unique across the whole model** — a collision is an error while building the
 * model, not a silent win of one of the fields.
 *
 * <p>The "type per path" view for the validator is **derived** ({@link #schema()}), so that the truth
 * about a type stays in one place — the declaration.
 */
public final class ConfigDefinition {

    private static final ConfigDefinition EMPTY = new ConfigDefinition(Map.of());

    private final Map<ConfigPath, ConfigField> fields;

    private ConfigDefinition(Map<ConfigPath, ConfigField> fields) {
        this.fields = Map.copyOf(fields);
    }

    /**
     * An empty definition.
     *
     * @return a definition that declares no field
     */
    public static ConfigDefinition empty() {
        return EMPTY;
    }

    /**
     * A definition from the given fields.
     *
     * @param fields the fields to declare; must not be null, and no two of them may share a path or
     *               an alias
     * @return the definition declaring exactly those fields
     * @throws IllegalArgumentException if a path or an alias is declared twice
     */
    public static ConfigDefinition of(ConfigField... fields) {
        ConfigDefinition definition = EMPTY;
        for (ConfigField field : fields) {
            definition = definition.with(field);
        }
        return definition;
    }

    /**
     * A new definition with an added field; checks path and alias collisions.
     *
     * @param field the field to add; must not be null
     * @return a new definition; this one is unchanged
     * @throws IllegalArgumentException if the path or one of the aliases is already declared
     */
    public ConfigDefinition with(ConfigField field) {
        Objects.requireNonNull(field, "field");
        ConfigPath path = field.path();
        if (fields.containsKey(path)) {
            throw new IllegalArgumentException("field `" + path.canonical() + "` declared twice");
        }
        for (ConfigField existing : fields.values()) {
            for (String alias : field.aliases()) {
                if (existing.aliases().contains(alias)) {
                    throw new IllegalArgumentException("alias `" + alias + "` already belongs to field `"
                        + existing.path().canonical() + "` — aliases MUST be unique in the model");
                }
            }
        }
        Map<ConfigPath, ConfigField> extended = new LinkedHashMap<>(fields);
        extended.put(path, field);
        return new ConfigDefinition(extended);
    }

    /**
     * The declared fields, in declaration order.
     *
     * @return an immutable list; changing it does not affect the model
     */
    public List<ConfigField> fields() {
        return List.copyOf(fields.values());
    }

    /**
     * The declaration of one path.
     *
     * @param path the path to look up; must not be null
     * @return the field, or empty when the path is not declared
     */
    public Optional<ConfigField> field(ConfigPath path) {
        return Optional.ofNullable(fields.get(path));
    }

    /**
     * The number of declared fields.
     *
     * @return the number of declarations
     */
    public int size() {
        return fields.size();
    }

    /**
     * The view for the validator: the declared type per path, derived from the declarations.
     *
     * @return the schema built from the declarations
     */
    public ConfigSchema schema() {
        List<ConfigField> ordered = new ArrayList<>(fields.values());
        ConfigSchema schema = ConfigSchema.empty();
        for (ConfigField field : ordered) {
            schema = schema.with(field.path(), field.type());
        }
        return schema;
    }

    /**
     * Fields with a default value — the root layer is materialised from them.
     *
     * @return the fields that declared a default, in declaration order
     */
    public List<ConfigField> fieldsWithDefault() {
        return fields.values().stream().filter(field -> field.defaultValue().isPresent()).toList();
    }

    @Override
    public String toString() {
        return "ConfigDefinition[" + size() + " fields]";
    }
}
