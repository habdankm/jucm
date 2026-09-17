package dev.jucm.core;

import java.util.Optional;

/**
 * Closed set of types — **two families in one dictionary**:
 *
 * <pre>
 * VALUE types (eight, the YAML dictionary):   str, int, float, bool, timestamp, seq, map, binary
 * types WE identify ourselves:                 duration, uri
 * </pre>
 *
 * <p>The first family is a subset of the YAML schema and it must stay that way: a value in the tree
 * is one of those eight {@link ConfigValue} variants. The second family are **cast targets for
 * declared fields**: YAML does not know such types, so we recognise them on our side — `duration`
 * (`30s`, `5m`) and `uri` (an address with a scheme). Thanks to that, `___TYPE=duration` from ENV means
 * something specific instead of "unknown type", and a field declared as `uri` does not have to repeat
 * its notation.
 *
 * <p><b>A type carries its own rules</b>: the notation
 * ({@link #defaultTransformer()}) and the default shape constraint ({@link #defaultConstraint()}), e.g.
 * `uri` -> `~`^[a-z][a-z0-9+.\-]*://.*``. The user must not have to guess that "this type is really a
 * transformer with that name" — the type is identified by us and says itself what it means.
 *
 * <p>The names are YAML-like, not Java-like, and that is intended: for YAML types the overriding
 * pattern is YAML, and for our two we do not invent another dictionary — hence `duration`, not
 * `Duration`.
 */
public enum ConfigType {

    /** Text. */
    STR("str"),
    /** A 64-bit signed integer. */
    INT("int"),
    /** A floating-point number; in a source notation it must carry a decimal point. */
    FLOAT("float"),
    /** A boolean. */
    BOOL("bool"),
    /** A moment in time. */
    TIMESTAMP("timestamp"),
    /** A sequence of values. */
    SEQ("seq"),
    /** A map from string keys to values. */
    MAP("map"),
    /** An encoded blob. */
    BINARY("binary"),

    /** Duration (`30s`, `5m`) — a type outside the YAML dictionary, identified by us. */
    DURATION("duration"),

    /** Address with a scheme — a type outside the YAML dictionary; default shape constraint: `~`^[a-z][a-z0-9+.\-]*://.*``. */
    URI("uri");

    private final String yamlName;

    ConfigType(String yamlName) {
        this.yamlName = yamlName;
    }

    /**
     * The name as written in YAML and in `___TYPE` / `--x--type`.
     *
     * @return the name, never null
     */
    public String yamlName() {
        return yamlName;
    }

    /**
     * Is this a VALUE type from the YAML dictionary — it lands in the tree as a {@link ConfigValue}.
     *
     * @return true for the eight value types, false for a cast target
     */
    public boolean isYamlValueType() {
        return !isDeclaredTarget();
    }

    /**
     * Is this a type identified by **us** — a cast target for declared fields.
     *
     * @return true for `duration` and `uri`, false for a value type
     */
    public boolean isDeclaredTarget() {
        return this == DURATION || this == URI;
    }

    /**
     * The default notation of a type: the name of the transformer that handles it (`int`/`float` ->
     * `number`, `timestamp` -> `date`, our types -> themselves). Empty = the value goes to the cast as
     * it is, with the notation its source brought.
     *
     * <p>`str` has **no** default notation: a default `trim` would be a silent change of data.
     *
     * @return the transformer name, or empty when the type brings no notation of its own
     */
    public Optional<String> defaultTransformer() {
        return switch (this) {
            case INT, FLOAT -> Optional.of("number");
            case TIMESTAMP -> Optional.of("date");
            case DURATION -> Optional.of("duration");
            case URI -> Optional.of("uri");
            case STR, BOOL, SEQ, MAP, BINARY -> Optional.empty();
        };
    }

    /**
     * The default shape constraint a type carries — applied together with the field's own rules, so
     * that obvious things do not need a declaration (`uri` -> `~`^[a-z][a-z0-9+.\-]*://.*``).
     *
     * <p>Types without such a rule leave it empty, and that is an answer, not a gap: `duration` has a
     * **notation** rule enforced by the transformer, and a constraint on the canonical form would be a
     * rule that never fails — that is, a lie in the report.
     *
     * @return the constraint, or empty when the type carries none
     */
    public Optional<ConfigConstraint> defaultConstraint() {
        return this == URI ? Optional.of(ConfigConstraint.Const("~`^[a-z][a-z0-9+.\\-]*://.*`"))
            : Optional.empty();
    }

    /**
     * A name from YAML (lower case) or from our dictionary; an unknown name is an error.
     *
     * @param name the name as written in the source; must not be null
     * @return the type with that name
     * @throws IllegalArgumentException if no type carries that name, with the allowed names in the message
     */
    public static ConfigType fromYamlName(String name) {
        for (ConfigType type : values()) {
            if (type.yamlName.equals(name)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown type `" + name + "` — allowed: "
            + "str, int, float, bool, timestamp, seq, map, binary (value types) and "
            + "duration, uri (cast targets for declared fields)");
    }
}
