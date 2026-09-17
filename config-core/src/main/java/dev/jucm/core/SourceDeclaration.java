package dev.jucm.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a **source stated** about an entry: the type (`___TYPE` / `--x--type`), the transformation
 * configuration (`___TCONF` / `--x--tconf`) and a **rule** (`___CONST` / `--x--const`, and in YAML the
 * `CONST(...)` section in a comment next to the value). It travels with the value and the origin in
 * {@link ConfigPatch}, and then in the tree entry — because pipeline steps must know what the source
 * wanted without knowing which source the value came from.
 *
 * <p><b>A source says HOW TO READ, not WITH WHAT</b>. A source does not express a transformer name,
 * and that is not an oversight: a transformer points at an implementation in the registry, and the
 * registry is an application concept. The name comes from the **declaration in code** or from the
 * **type** (`___TYPE` -> its default notation). That is why this record carries only the three
 * statements a source can make:
 *
 * <pre>
 *   AIMENAGERIE__START_DATE___TCONF="yyyy-MM-dd HH:mm:ss"   the type from the application model (`timestamp`)
 *   AIMENAGERIE__START_DATE___TYPE=timestamp                the type straight from the source
 *   AIMENAGERIE__RETRIES___CONST='&gt;=1&amp;&lt;=10'       the rule straight from the source
 * </pre>
 *
 * <p><b>This is not a second declaration.</b> The declaration from code is complete (type, constraints,
 * description) and always **wins**: for a field in the schema, source markers have nothing to say beyond
 * filling in a missing notation configuration and replacing the rules. The set is meant for **keys outside
 * the schema** — an auxiliary — where the statement IS the declaration: its type is the one the source
 * brought, and `str` when it brought none, because such a key arrives from a source as TEXT.
 *
 * <p>{@link #needsType()} names a statement that carries a rule or a notation and no type. No adapter asks
 * it any more: "no type" is not a problem, because a key outside the schema is `str` unless its statement
 * says otherwise ({@link #asField}). It stays for a caller that must name a missing piece; the error that
 * remains is a notation for a type that has none.
 *
 * <p>Ordinary values (without markers) carry {@link #none()} — not "null", not an empty object to check:
 * the absence of a statement is an absence, not a state.
 *
 * @param type              the type the source stated; never null, empty when the source stated none
 * @param transformerConfig the notation configuration the source stated; never null, empty when none
 * @param constraints       the rule chain the source stated; never null, empty when the source stated none
 */
public record SourceDeclaration(Optional<ConfigType> type, Optional<String> transformerConfig,
                                Optional<String> constraints, Optional<ConfigType> elementType,
                                Optional<ConfigType> keyType) {

    private static final SourceDeclaration NONE =
        new SourceDeclaration(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty());

    /** Validates the components — every one of them is required. */
    public SourceDeclaration {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(transformerConfig, "transformerConfig");
        Objects.requireNonNull(constraints, "constraints");
        Objects.requireNonNull(elementType, "elementType");
        Objects.requireNonNull(keyType, "keyType");
        if ((elementType.isPresent() || keyType.isPresent())
            && (type.isEmpty() || (type.get() != ConfigType.SEQ && type.get() != ConfigType.MAP))) {
            throw new IllegalArgumentException("an element or key type belongs to a collection: the statement's "
                + "type is `" + type.map(ConfigType::yamlName).orElse("none") + "`");
        }
    }

    /**
     * The source stated nothing — the typical case.
     *
     * @return the shared empty statement; never null
     */
    public static SourceDeclaration none() {
        return NONE;
    }

    /**
     * `___TYPE` / `--x--type`: an explicit type request (only for keys outside the schema).
     *
     * @param type the type the source requests; must not be null
     * @return a statement carrying only the type
     */
    public static SourceDeclaration type(ConfigType type) {
        return new SourceDeclaration(Optional.of(Objects.requireNonNull(type, "type")), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * `___TCONF` / `--x--tconf`: the notation configuration for the type from the declaration or from {@link #type}.
     *
     * @param transformerConfig the notation configuration; must not be null or empty
     * @return a statement carrying only the notation configuration
     * @throws IllegalArgumentException when the configuration is empty — a configuration without content
     *         means nothing, so it is rejected here rather than silently ignored later
     */
    public static SourceDeclaration config(String transformerConfig) {
        Objects.requireNonNull(transformerConfig, "transformerConfig");
        if (transformerConfig.isEmpty()) {
            throw new IllegalArgumentException("a transformation configuration without content means nothing");
        }
        return new SourceDeclaration(Optional.empty(), Optional.of(transformerConfig), Optional.empty(),
            Optional.empty(), Optional.empty());
    }

    /**
     * `___CONST` / `--x--const` or the `CONST(...)` section in a YAML comment: a rule straight from the source.
     * The chain is **the same** as in Java (`ConfigConstraint.Const("&gt;=1&amp;&lt;=10")`) — one syntax, one meaning
     * in all media.
     *
     * @param chain the rule chain; must not be null or blank
     * @return a statement carrying only the rule chain
     * @throws IllegalArgumentException when the chain is blank, or when it does not parse as a chain
     */
    public static SourceDeclaration constraints(String chain) {
        Objects.requireNonNull(chain, "chain");
        if (chain.isBlank()) {
            throw new IllegalArgumentException("a constraint without conditions means nothing");
        }
        // The parser runs HERE, not only at validation: a syntax error in a source marker must be a source
        // error with its name, not a surprise in the middle of the pipeline.
        ConfigConstraint.Const(chain);
        return new SourceDeclaration(Optional.empty(), Optional.empty(), Optional.of(chain), Optional.empty(),
            Optional.empty());
    }


    /**
     * `___TYPE=seq` with the type of an ELEMENT known — what a carrier states when it read a list and can see
     * what the elements are (the form of the node in YAML, the value it parsed in ENV or CLI).
     *
     * @param elementType the type of one element; must not be {@code null}
     * @return a statement carrying the collection type and its element type
     */
    public static SourceDeclaration seq(ConfigType elementType) {
        return new SourceDeclaration(Optional.of(ConfigType.SEQ), Optional.empty(), Optional.empty(),
            Optional.of(Objects.requireNonNull(elementType, "elementType")), Optional.empty());
    }

    /**
     * `___TYPE=map` with both types known — see {@link #seq(ConfigType)}.
     *
     * @param keyType the type of one key; must not be {@code null}
     * @param valueType the type of one value; must not be {@code null}
     * @return a statement carrying the collection type and both of its types
     */
    public static SourceDeclaration map(ConfigType keyType, ConfigType valueType) {
        return new SourceDeclaration(Optional.of(ConfigType.MAP), Optional.empty(), Optional.empty(),
            Optional.of(Objects.requireNonNull(valueType, "valueType")),
            Optional.of(Objects.requireNonNull(keyType, "keyType")));
    }

    /**
     * A statement carrying both a type and its notation configuration.
     *
     * @param type              the requested type; must not be null
     * @param transformerConfig the notation configuration for it; must not be null
     * @return a statement carrying the type and the configuration
     */
    public static SourceDeclaration of(ConfigType type, String transformerConfig) {
        return new SourceDeclaration(Optional.of(Objects.requireNonNull(type, "type")),
            Optional.of(Objects.requireNonNull(transformerConfig, "transformerConfig")), Optional.empty(),
            Optional.empty(), Optional.empty());
    }

    /**
     * A composition of two statements: the axes are independent, so each comes from the one that carries it.
     *
     * @param other the statement to merge in; it wins on every axis it has set, must not be null
     * @return a new statement; neither operand is modified
     */
    public SourceDeclaration mergedWith(SourceDeclaration other) {
        Objects.requireNonNull(other, "other");
        return new SourceDeclaration(other.type().or(this::type),
            other.transformerConfig().or(this::transformerConfig),
            other.constraints().or(this::constraints),
            other.elementType().or(this::elementType),
            other.keyType().or(this::keyType));
    }

    /**
     * Whether the source stated nothing at all.
     *
     * @return true when the type, the notation configuration and the rule are all absent
     */
    public boolean isNone() {
        return type.isEmpty() && transformerConfig.isEmpty() && constraints.isEmpty();
    }



    /**
     * The same statement with the TYPES a carrier can see for itself: the carrier read the value, so the members
     * of a collection are in front of it. This is the other half of what YAML does with the FORM of its nodes —
     * one rule, two carriers: a collection holds ONE type of thing, and whoever can see the members states it.
     *
     * <p>Used where a collection type is stated for a key with no declaration (`___TYPE=seq` in ENV or CLI): the
     * elements would otherwise be typed by the value's own shape, and a structural member (`[[1,2]]`) would be
     * refused as if it had to be text.
     *
     * @param value the value the carrier read; must not be null
     * @return the statement with the element type (and the key type for a map) filled in, or empty when the
     *         members DISAGREE — a mixed collection has no type to state, and the carrier reports it
     */
    public Optional<SourceDeclaration> holding(ConfigValue value) {
        Objects.requireNonNull(value, "value");
        if (type.isEmpty() || (type.get() != ConfigType.SEQ && type.get() != ConfigType.MAP)) {
            return Optional.of(this);
        }
        List<ConfigValue> members = switch (value) {
            case ConfigSeq seq -> seq.elements();
            case ConfigMap map -> List.copyOf(map.entries().values());
            default -> List.of();
        };
        return memberType(members).map(element -> type.get() == ConfigType.SEQ
            ? new SourceDeclaration(type, transformerConfig, constraints, Optional.of(element), keyType)
            // A map's keys are text everywhere; what varies is the type of the VALUES.
            : new SourceDeclaration(type, transformerConfig, constraints, Optional.of(element),
                Optional.of(ConfigType.STR)));
    }

    /**
     * The type every member has — the one type a collection is allowed to hold.
     *
     * @param members the members of a collection
     * @return the common type, or empty when they disagree; `str` when there are no members at all (the value
     *         is emptiness then, which is an error of its own)
     */
    private static Optional<ConfigType> memberType(List<ConfigValue> members) {
        ConfigType common = null;
        for (ConfigValue member : members) {
            ConfigType one = member.type();
            if (common == null) {
                common = one;
            } else if (common != one) {
                return Optional.empty();
            }
        }
        return Optional.of(common == null ? ConfigType.STR : common);
    }

    /**
     * The field for a stated type. A collection STATES what it holds: the element type comes from the carrier
     * (it saw the node, or the value it parsed). When only the collection type is stated — a `seq` marker over a
     * value that is not a list — {@code str} is used for the elements, and the value is refused by the cast
     * anyway ("expected `seq`, got `str`"): the type that was never used cannot mislead.
     */
    private ConfigField base(String canonicalPath, ConfigType stated) {
        return switch (stated) {
            case SEQ -> ConfigField.seq(canonicalPath, elementType.orElse(ConfigType.STR));
            case MAP -> ConfigField.map(canonicalPath, keyType.orElse(ConfigType.STR),
                elementType.orElse(ConfigType.STR));
            default -> ConfigField.required(canonicalPath, stated);
        };
    }

    /**
     * What in this statement **needs a type that is missing** — for the message when a source wants a notation
     * or a rule for a key whose type it did not even state.
     *
     * <p>Empty means "the statement is as complete as it can be". A source adapter asks about it in ONE place,
     * so that `___TCONF` and `___CONST` do not diverge in behaviour.
     *
     * @return the first statement that cannot be used without a type, in the order notation, rule; empty when
     *         nothing is missing
     */
    public Optional<String> needsType() {
        if (type.isPresent()) {
            return Optional.empty();
        }
        return transformerConfig.or(this::constraints);
    }

    /**
     * A view as a field declaration: the type from the marker + **its default notation** + the configuration
     * from the source + the rule from the source.
     *
     * <p><b>Nothing stated means {@code str}.</b> The schema is CODE and it is complete: every key DECLARED
     * there has its type from the declaration, and this view is built only for a key the code does **not**
     * declare - an auxiliary. Such a key arrives from a source as TEXT, so when the statement states no type,
     * the type is {@code str}. There is therefore no state "a statement without a type": a rule always has
     * something to compare against, and a notation always has a type whose notation is looked up.
     *
     * <p>A statement that states <b>nothing at all</b> ({@link #isNone()}) builds no field: there is nothing
     * to read the value with and nothing to read, so the value stays exactly as the source delivered it.
     *
     * @param path the path the field is built for
     * @return the field declaration; empty only when the statement states nothing at all
     */
    Optional<ConfigField> asField(ConfigPath path) {
        if (isNone()) {
            return Optional.empty();
        }
        ConfigType stated = type.orElse(ConfigType.STR);
        Optional<String> notation = stated.defaultTransformer();
        ConfigField field;
        if (notation.isEmpty()) {
            // A type without a notation (`str`, `bool`, `seq`...): there is nothing to use the configuration for.
            // We do NOT add it to the field — "a configuration without a name" is an invalid model
            // (`ConfigField`) and would blow up while building. It is an ENTRY error, so the transformation
            field = base(path.canonical(), stated);
        } else {
            field = base(path.canonical(), stated).transformer(notation.get());
            if (transformerConfig.isPresent()) {
                field = field.transformerConfig(transformerConfig.get());
            }
        }
        if (constraints.isPresent()) {
            field = field.constrained(ConfigConstraint.Const(constraints.get()));
        }
        return Optional.of(field);
    }

    @Override
    public String toString() {
        return "SourceDeclaration[type=" + type.map(ConfigType::name).orElse("-")
            + ", config=" + transformerConfig.map(text -> "\"" + text + "\"").orElse("-")
            + ", rule=" + constraints.map(text -> "\"" + text + "\"").orElse("-") + "]";
    }
}
