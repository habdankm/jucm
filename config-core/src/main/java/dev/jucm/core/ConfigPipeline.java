package dev.jucm.core;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Pipeline steps for values: each one is a function {@code Config -> Config} and each **returns a new
 * variant**, marking its verdict on the entries it processed ({@link Config.Verdict}). Hence:
 *
 * <pre>
 *   a step processes only entries without its own verdict  -> checked once per value, a repeat is free
 *   a step never mutates a shared object                   -> an old snapshot keeps its own view
 *   an entry stopped earlier does not go further           -> one mistake is one problem, not two
 * </pre>
 *
 * <p>The order is **interpolation -> transformation -> cast -> constraints**.
 * {@link #all(ConfigDefinition, TransformerRegistry)} composes all four, and each step is a separate
 * entry point.
 *
 * <p>The steps are **an explicit input**: an application may compose a flow of its own - a different
 * order, a step of its own, a wrapper. The library supplies a well-set default and nothing more.
 *
 * @since 1.0
 */
public final class ConfigPipeline {

    private ConfigPipeline() {
    }

    /**
     * Interpolation: text to symbol to value, against **this** snapshot. The only step that needs the WHOLE
     * tree, because resolving {@code ${a}} means looking under {@code a} and catching a cycle means walking
     * the entire chain - so it runs over the tree rather than entry by entry.
     *
     * <pre>
     *   `${a}`              -> the value of `a` (resolved recursively, chains `a -> b -> c`)
     *   `${?a}` with no `a` -> the entry DISAPPEARS from this layer (it contributes nothing)
     *   a cycle             -> FAILED on ALL participants, with the full path of the cycle
     *   broken syntax       -> FAILED with the address of the entry that holds the text (`InvalidSyntax`)
     * </pre>
     *
     * <p><b>A side effect of materialisation</b>: the transformation and cast steps receive values rather
     * than symbols, which is intended, because interpolation comes FIRST. Reading itself stays lazy for the
     * symbolic tree ({@link Config#get} resolves symbols against the snapshot), so parsing and resolving
     * remain two distinct moments.
     *
     * @return a step that resolves interpolation against the snapshot it is applied to
     * @since 1.0
     */
    public static Function<Config, Config> interpolation() {
        return config -> config.resolve(ConfigStep.INTERPOLATION, new InterpolationStep(null));
    }

    /**
     * The same step with the DECLARATIONS in hand. `??` then checks that every operand fits the type of the
     * value: its form for a literal (`9999` is `int`, `"9999"` is `str`), its declaration or statement for an
     * address. Without the definition the read stays exactly as it was - the check needs a type to compare with,
     * and a bare read of an unparsed tree has none.
     *
     * @param definition the model the values are judged against; must not be null
     * @return a step that resolves interpolation against the snapshot it is applied to
     * @since 1.0
     */
    public static Function<Config, Config> interpolation(ConfigDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        return config -> config.resolve(ConfigStep.INTERPOLATION, new InterpolationStep(definition));
    }

    // One resolver per pass: the memo and the resolution stack (cycles) are shared across the whole run.
    private static final class InterpolationStep implements Config.ValueStep {

        private final ConfigDefinition definition;
        private Interpolation.Resolver resolver;

        InterpolationStep(ConfigDefinition definition) {
            this.definition = definition;
        }

        @Override
        public Optional<Config.Rewrite> apply(Config view, ConfigPath path, ConfigValue value,
                                              ConfigSource source) {
            if (resolver == null) {
                resolver = new Interpolation.Resolver(view, definition);
            }
            return switch (resolver.lookup(path)) {
                case Interpolation.Outcome.Value ok ->
                    Optional.of(new Config.Rewrite.Replaced(ok.value(), Config.Verdict.passed()));
                case Interpolation.Outcome.YieldsAbsent ignored ->
                    Optional.of(new Config.Rewrite.Dropped());
                case Interpolation.Outcome.Problem problem ->
                    Optional.of(new Config.Rewrite.Replaced(value, Config.Verdict.failed(problem.error())));
            };
        }
    }

    /**
     * Transformation: the notation stated in the declaration ({@code transformer} together with
     * {@code transformerConfig}), and for the types this library recognises ({@code duration}, {@code uri})
     * the notation carried by the type itself. The decision is unambiguous:
     *
     * <pre>
     *   a transformer in the declaration        -> it wins
     *   a type this library recognises          -> its notation: `30s`, `scheme://host`
     *   neither of the two                      -> the value goes to the cast as it is
     *   a key outside the schema (auxiliary)    -> no verdict; a statement from the source decides
     * </pre>
     *
     * <p>Types from the YAML vocabulary get no notation here automatically, because how they are written is
     * the source's business ({@code int} does not have to be ours) - while the types this library owns have
     * no notation other than ours, so requiring them to be repeated in every declaration would be typing the
     * same thing over and over.
     *
     * <p>An unknown transformer name is a **model** error, not a configuration one: it arises as soon as the
     * configuration begins to exist, so it is loud and it is not reported through {@link ConfigError}.
     *
     * @param definition the declared fields
     * @param registry the transformers that declarations may name
     * @return a step that normalises values according to the stated notation
     * @since 1.0
     */
    public static Function<Config, Config> transformation(ConfigDefinition definition,
                                                          TransformerRegistry registry) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(registry, "registry");

        return config -> config.advance(ConfigStep.TRANSFORMATION, (path, value, source, declared) -> {
            // A key outside the schema always has a type to work against: the one the source stated, or `str`
            // (see `SourceDeclaration.asField`). Every statement is therefore read here — and a notation for a
            // type that has none is an entry error raised by `transform`, not a silent skip.
            return declaredField(definition, path, declared)
                .map(field -> transform(field, value, source, registry, declared));
        });
    }

    private static Config.EntryStep.Outcome transform(ConfigField field, ConfigValue value,
                                                      ConfigSource source, TransformerRegistry registry,
                                                      SourceDeclaration declared) {
        Optional<String> config = configOf(field, declared);
        Optional<String> notation = notationOf(field, config);
        if (notation.isEmpty()) {
            if (declared.transformerConfig().isPresent()) {
                return new Config.EntryStep.Outcome(value, Config.Verdict.failed(new ConfigError.UnusableStatement(
                    field.path(), "___TCONF=" + declared.transformerConfig().orElseThrow(),
                    "type `" + field.type().name() + "` has no notation, so there is nothing to apply the "
                        + "configuration with", source)));
            }
            return new Config.EntryStep.Outcome(value, Config.Verdict.passed());
        }
        String name = notation.get();
        Transformer transformer = registry.require(name);
        requireUsable(field, name, transformer);
        return switch (transformer.normalize(value, config)) {
            case Transformer.Outcome.Normalized ok -> new Config.EntryStep.Outcome(ok.value(), Config.Verdict.passed());
            case Transformer.Outcome.Rejected rejected -> new Config.EntryStep.Outcome(value,
                Config.Verdict.failed(new ConfigError.TransformFailed(field.path(), name, config, value,
                    rejected.reason(), source)));
        };
    }

    // Compatibility of the DECLARATION with the transformer, checked at first use and out loud: both cases are
    // MODEL errors, not configuration ones, so they are not reported through `ConfigError`:
    //   a configuration the transformer does not know -> "`number` expects a locale, it was given ..."
    //   a numeric transformer on a non-numeric field  -> it would lie about the intent
    //
    // Kept separate from rejecting a VALUE: a bad declaration is not the data's fault, so it must not travel
    // as a `TransformFailed` - nobody touched the data, and the mistake is in the model's code.
    private static void requireUsable(ConfigField field, String name, Transformer transformer) {
        Optional<String> configProblem = transformer.configProblem(field.transformerConfig());
        if (configProblem.isPresent()) {
            throw new IllegalArgumentException("transformer `" + name + "` on field `"
                + field.path().canonical() + "`: " + configProblem.get());
        }
        Set<ConfigType> allowed = transformer.allowedTypes();
        if (!allowed.isEmpty() && !allowed.contains(field.type())) {
            throw new IllegalArgumentException("transformer `" + name + "` on field `"
                + field.path().canonical() + "` of type `" + field.type().yamlName() + "` - this transformer"
                + " works on types: " + allowed.stream().map(ConfigType::yamlName).sorted().toList());
        }
    }

    /**
     * Cast: the type from the declaration, in strict notation. The value arrives here already normalised by
     * the transformation step, or in the default notation of its type when the declaration stated nothing
     * ({@code int}/{@code float} to {@code number}, {@code timestamp} to {@code date}).
     *
     * <p>Emptiness is checked **before** the cast, within this same step, so that an empty field produces
     * {@code NullValue} rather than a misleading "expected type {@code bool}".
     *
     * @param definition the declared fields
     * @param registry the transformers, used for the default notation of a type
     * @return a step that casts values onto their declared types
     * @since 1.0
     */
    public static Function<Config, Config> cast(ConfigDefinition definition, TransformerRegistry registry) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(registry, "registry");

        return config -> config.advance(ConfigStep.CAST, (path, value, source, declared) ->
            declaredField(definition, path, declared).map(field -> cast(field, value, source, registry)));
    }

    private static Config.EntryStep.Outcome cast(ConfigField field, ConfigValue value, ConfigSource source,
                                                 TransformerRegistry registry) {
        Optional<ConfigError.NullValue> empty = ConfigValidator.emptiness(field.path(), value, source);
        if (empty.isPresent()) {
            return new Config.EntryStep.Outcome(value, Config.Verdict.failed(empty.get()));
        }
        return switch (ConfigCast.to(value, field.type(), field.path(), source, registry)) {
            case ConfigResult.ValueResult<ConfigValue> ok ->
                switch (castMembers(field, ok.value(), source, registry)) {
                    case ConfigResult.ValueResult<ConfigValue> members ->
                        new Config.EntryStep.Outcome(members.value(), Config.Verdict.passed());
                    case ConfigResult.ErrorResult<ConfigValue> bad ->
                        new Config.EntryStep.Outcome(value, Config.Verdict.failed(bad.error()));
                };
            case ConfigResult.ErrorResult<ConfigValue> bad ->
                new Config.EntryStep.Outcome(value, Config.Verdict.failed(bad.error()));
        };
    }

    /**
     * A collection types its MEMBERS: the declared element type says what one member is, and the members are cast
     * by the same rules as any value — by OUR transformer, on the TEXT the source delivered. A map checks its
     * KEYS against the key type as well (a key is text in the model, so the declaration is what makes `10` a
     * number rather than two characters).
     *
     * <p>A member that does not fit fails as an entry error **at its own address** (`ports.0`, `m.10`), so the
     * reader knows which member is the problem, and the value that was refused keeps its place in the chain.
     */
    private static ConfigResult<ConfigValue> castMembers(ConfigField field, ConfigValue delivered,
                                                         ConfigSource source, TransformerRegistry registry) {
        ConfigType elementType = field.elementType().orElse(null);
        if (elementType == null) {
            return ConfigResult.value(delivered);
        }
        if (delivered instanceof ConfigSeq seq) {
            Map<Integer, ConfigValue> members = new LinkedHashMap<>();
            for (int address : seq.addresses()) {
                ConfigPath at = field.path().child(String.valueOf(address));
                ConfigResult<ConfigValue> cast = ConfigCast.to(seq.at(address).orElseThrow(), elementType, at,
                    source, registry);
                if (cast instanceof ConfigResult.ErrorResult<ConfigValue> bad) {
                    return bad;
                }
                members.put(address, ((ConfigResult.ValueResult<ConfigValue>) cast).value());
            }
            return ConfigResult.value(ConfigSeq.of(members));
        }
        if (delivered instanceof ConfigMap map) {
            Map<String, ConfigValue> members = new LinkedHashMap<>();
            for (Map.Entry<String, ConfigValue> entry : map.entries().entrySet()) {
                ConfigPath at = field.path().child(entry.getKey());
                // The key is an address, so it is text; the declared key type says what that text must BE.
                if (field.keyType().isPresent()) {
                    ConfigResult<ConfigValue> key = ConfigCast.to(ConfigString.of(entry.getKey()),
                        field.keyType().get(), at, source, registry);
                    if (key instanceof ConfigResult.ErrorResult<ConfigValue> bad) {
                        return bad;
                    }
                }
                ConfigResult<ConfigValue> cast = ConfigCast.to(entry.getValue(), elementType, at, source, registry);
                if (cast instanceof ConfigResult.ErrorResult<ConfigValue> bad) {
                    return bad;
                }
                members.put(entry.getKey(), ((ConfigResult.ValueResult<ConfigValue>) cast).value());
            }
            return ConfigResult.value(ConfigMap.of(members));
        }
        return ConfigResult.value(delivered);
    }

    /**
     * Constraints: rules on a value **after** its type is known. A value stopped by an earlier step never
     * reaches this one, so a rule does not add a second error for the same value.
     *
     * <p>A rule parameter **MAY** be a reference (such as {@code >${min_port}}), resolved by the same
     * resolver that values go through: {@link Config#get} uses the same lookup, so the chain of layers and
     * the cycle detection behave exactly as they do for values. An unresolved reference is a RESOLUTION error
     * ({@link ConfigError.UnresolvedReference}), because a constraint is not in the business of resolving. A
     * resolved value MUST be usable as an operand: text where a number belongs is an entry error
     * ({@link ConfigError.InvalidSyntax}) and not an exception in the middle of a flow.
     *
     * @param definition the declared fields
     * @return a step that applies the declared rules
     * @since 1.0
     */
    public static Function<Config, Config> constraints(ConfigDefinition definition) {
        Objects.requireNonNull(definition, "definition");

        return config -> config.advance(ConfigStep.CONSTRAINTS, (path, value, source, declared) ->
            declaredField(definition, path, declared).map(field -> checkParameters(field, config, value, source)));
    }

    // Rule parameters may be interpolations: `Config.get` resolves them, so a reference in a parameter behaves
    // like a reference in a value. Resolving happens here because this is the only place with both the `Config`
    // in reach and the rules known.
    private static Config.EntryStep.Outcome checkParameters(ConfigField field, Config config, ConfigValue value,
                                                            ConfigSource source) {
        List<ConfigConstraint> rules = new ArrayList<>();
        boolean filled = false;
        for (ConfigConstraint rule : field.constraints()) {
            // A rule always judges a value OF A TYPE: bind it to the field's type (from the DECLARATION, which
            // is the source of truth) and let it refuse an operand that can never be a value of that type.
            ConfigConstraint bound;
            try {
                bound = rule.forType(field.type());
            } catch (IllegalArgumentException broken) {
                return unusable(field, rule, value, broken.getMessage(), source);
            }
            if (bound.parameters().isEmpty()) {
                rules.add(bound);
                continue;
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (String reference : bound.parameters()) {
                ConfigResult<ConfigValue> found = config.get(reference);
                if (!(found instanceof ConfigResult.ValueResult<ConfigValue> present)) {
                    ConfigError problem = found instanceof ConfigResult.ErrorResult<ConfigValue> bad
                        ? bad.error() : new ConfigError.Absent(ConfigPath.of(reference));
                    return new Config.EntryStep.Outcome(value, Config.Verdict.failed(
                        problem instanceof ConfigError.UnresolvedReference ? problem
                            : new ConfigError.UnresolvedReference(field.path(), ConfigPath.of(reference), source)));
                }
                // A rule compares values of ONE type. A parameter of another type is a mismatch even when its
                // text happens to fit (`str` holding `9000` where the field is an `int`) - that is exactly how
                // apples end up compared with cars and pass. A parameter that fills a LIST brings its
                // ELEMENTS: there the type is checked on them, when the filled chain is bound below.
                if (!bound.fillsAList(reference) && present.value().type() != field.type()) {
                    return unusable(field, rule, value, "parameter `${" + reference + "}` is `"
                        + present.value().type().yamlName() + "` and this field is `" + field.type().yamlName()
                        + "` - a rule compares values of one type, not their text", source);
                }
                values.put(reference, present.value().display());
            }
            try {
                rules.add(bound.withParameters(values));
            } catch (IllegalArgumentException broken) {
                // A substituted operand (an element of a list, an operand of a comparison) is not a value of
                // this type: the error names the operand instead of blaming the interpolation syntax.
                return unusable(field, rule, value, broken.getMessage(), source);
            }
            filled = true;
        }
        ConfigField resolved = filled ? field.constrainedInstead(rules.toArray(ConfigConstraint[]::new)) : field;
        return check(resolved, value, source);
    }

    /** A rule that cannot judge this field: the error is created HERE, while the rule is processed. */
    private static Config.EntryStep.Outcome unusable(ConfigField field, ConfigConstraint rule, ConfigValue value,
                                                     String why, ConfigSource source) {
        return new Config.EntryStep.Outcome(value, Config.Verdict.failed(
            new ConfigError.UnusableRule(field.path(), rule.rule(), why, source)));
    }

    private static Config.EntryStep.Outcome check(ConfigField field, ConfigValue value, ConfigSource source) {
        for (ConfigConstraint constraint : rulesOf(field)) {
            // The same gate as `ConfigValidator.checkConstraints` - these are twin call paths and both have to
            // say the same thing: an ordering rule on text is a DECLARATION error.
            if (!constraint.appliesTo(value)) {
                return new Config.EntryStep.Outcome(value, Config.Verdict.failed(
                    new ConfigError.RuleMismatch(field.path(), constraint.rule(), value.type(), source)));
            }
            if (!constraint.accepts(value)) {
                return new Config.EntryStep.Outcome(value, Config.Verdict.failed(
                    new ConfigError.ValidationFailed(field.path(), constraint.rule(), value, source)));
            }
        }
        return new Config.EntryStep.Outcome(value, Config.Verdict.passed());
    }

    // The field's rules PLUS the type's default rule (`uri` gets a regex on the scheme) - and the default one
    // applies ALWAYS, not "unless the field has its own": an exception to a type's rule would be a rule with an
    // exception, and a type has to mean the same in every field. It comes first on the list, so the message
    // shows what the TYPE expects first.
    private static List<ConfigConstraint> rulesOf(ConfigField field) {
        List<ConfigConstraint> rules = new ArrayList<>();
        field.type().defaultConstraint().ifPresent(rules::add);
        rules.addAll(field.constraints());
        return rules;
    }

    /**
     * The default pipeline: interpolation, transformation, cast and constraints, in that order.
     *
     * @param definition the declared fields
     * @param registry the transformers that declarations may name
     * @return a single step that runs all four in that order
     * @since 1.0
     */
    public static Function<Config, Config> all(ConfigDefinition definition, TransformerRegistry registry) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(registry, "registry");

        Function<Config, Config> interpolation = interpolation(definition);
        Function<Config, Config> transformation = transformation(definition, registry);
        Function<Config, Config> cast = cast(definition, registry);
        Function<Config, Config> constraints = constraints(definition);
        return config -> constraints.apply(cast.apply(transformation.apply(interpolation.apply(config))));
    }

    private static Optional<ConfigField> declaration(ConfigDefinition definition, ConfigPath path) {
        return definition.field(path);
    }

    // What an entry has to decide: a declaration from CODE, and when there is none, what the SOURCE stated
    // (`___TYPE`, `___TCONF`).
    //
    // The order is a rule, not a convenience: the code of the application wins over a marker from a source.
    // Markers speak for keys OUTSIDE the schema, and only there. A rule from a SOURCE REPLACES the rules from
    // code: it is neither added to them nor silently ignored, so a rule stated in a file really applies and
    // the message shows the rule that was used. The TYPE's default rule stays (`rulesOf`): it belongs to the
    // type, not to the field.
    private static Optional<ConfigField> declaredField(ConfigDefinition definition, ConfigPath path,
                                                       SourceDeclaration declared) {
        Optional<ConfigField> field = declaration(definition, path);
        if (field.isEmpty()) {
            return declared.asField(path);
        }
        return Optional.of(declared.constraints()
            .map(chain -> field.get().constrainedInstead(ConfigConstraint.Const(chain)))
            .orElseGet(field::get));
    }

    // The configuration of the notation: from the declaration, and the source fills in a missing one
    // (`___TCONF` supplies the configuration when the field has none). The notation NAME never comes from the
    // source - it is taken from the declaration or from the type.
    private static Optional<String> configOf(ConfigField field, SourceDeclaration declared) {
        return field.transformerConfig().isPresent() ? field.transformerConfig() : declared.transformerConfig();
    }

    // The notation for this entry: the one named in the declaration, and when there is none, the TYPE's
    // default. A type's default notation can be demanding: `duration` and `uri` manage on their own, but
    // `number` and `date` MUST be given a configuration (a locale, a format). It is therefore used when there
    // is something to use: for the types this library recognises always, for the rest only with a
    // configuration from the code or from the source. Without that, `___TCONF` on a `timestamp` field would
    // have no way to take effect.
    private static Optional<String> notationOf(ConfigField field, Optional<String> config) {
        if (field.transformer().isPresent()) {
            return field.transformer();
        }
        return field.type().isDeclaredTarget() || config.isPresent()
            ? field.type().defaultTransformer()
            : Optional.empty();
    }
}
