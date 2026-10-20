package dev.jucm.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Validator: **a single pass over the values of every layer**, with the originating {@link ConfigSource}
 * kept.
 *
 * <pre>
 *   raw value + source + path  ->  validation  ->  typed value / ConfigError
 * </pre>
 *
 * <p>The validator **does not stop at the first error**: {@link #build} returns **all** problems at once.
 * Stopping at the first one is the worst possible mode of operation, because the user then fixes the
 * configuration one error at a time.
 *
 * <p>The validator knows the layer and the source, so it is the validator that reports problems:
 * adapters only transport values, and the decision belongs here. That is why a {@code bool} coming from
 * the command line also lands here - picocli hands over the raw literal, and {@code yes} ends as a
 * {@link ConfigError.CastFailed} carrying the raw value and its origin instead of a silent {@code null}.
 *
 * <p><b>An empty value written on purpose is an error</b>: an explicit empty string in YAML
 * ({@code x: ""}) and an explicit empty command-line argument ({@code --host ""}) both end as
 * {@link ConfigError.NullValue} - with no exception for the origin of the value. YAML *may* accept such a
 * notation, but this configuration does not, and an allowance for the notation format would be a rule with
 * an exception. The check runs **before** the cast, so that an empty field produces {@code NullValue}
 * rather than a misleading "expected type {@code bool}". A {@code null} value does not exist in the model,
 * so emptiness travels as an empty {@link ConfigString}.
 *
 * <p>The two neighbouring cases never reach this class, and the difference is the whole point:
 * an **empty environment variable** is ignored by the ENV adapter (the layer brings in nothing, as if the
 * variable were absent, so the value comes from a lower layer or from the declaration), and a
 * **command-line option with no parameter at all** ({@code --host} with nothing after it) is a
 * USAGE error raised while argv is read - before a patch, let alone a value, exists. That holds for EVERY
 * option name, declared or not: a bare unknown token ({@code --prot}) is the same usage error, because
 * someone meant to set a parameter and set nothing. An option WITH a value is not that case - even an
 * unknown name ({@code --prot 9000}) stays an auxiliary key. What is left is emptiness someone
 * actually wrote down, and that is what this rule is about.
 *
 * <p>The same rule covers **empty collections**: {@code features: []} and {@code metadata: {}} are
 * {@code NullValue} just like an empty scalar - a collection gets no exception merely because YAML lets
 * it be written down. "I want no elements" is expressed by omitting the key, not by a value pretending
 * to be absent.
 *
 * <p>Only {@code set} operations are validated here: {@code reset} belongs to lookup semantics and an
 * unused auxiliary key is decided by reachability analysis, so neither is reported from this class.
 *
 * @since 1.0
 */
public final class ConfigValidator {

    private ConfigValidator() {
    }

    /**
     * One input layer: the kind of layer and the patch it contributed.
     *
     * @param layer kind of layer
     * @param patch operations that layer contributed
     * @since 1.0
     */
    public record LayerInput(Layer layer, ConfigPatch patch) {
        /** Validates the components — every one of them is required. */
        public LayerInput {
            Objects.requireNonNull(layer, "layer");
            Objects.requireNonNull(patch, "patch");
        }
    }

    /**
     * A validated value: typed, with its origin and layer kept.
     *
     * @param path path of the value
     * @param value the typed value
     * @param source address the value was taken from
     * @param layer layer the value belongs to
     * @since 1.0
     */
    public record TypedValue(ConfigPath path, ConfigValue value, ConfigSource source, Layer layer) {
        /** Validates the components — every one of them is required. */
        public TypedValue {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(layer, "layer");
        }
    }

    /**
     * Result of a validation: typed values and **all** problems at once.
     *
     * <p>Both lists are immutable copies. Values keep the order in which they were collected - layer order
     * for {@link #validate}, declaration order for {@link #build}.
     *
     * <p>Errors are ordered by path, then by kind, then by source, so the report is comparable between
     * runs and platforms and the first problem is always the same one. Problems without a path - a source
     * that cannot be read as a whole - come first, because they outrank individual fields.
     *
     * @since 1.0
     */
    public static final class Report {

        private final List<TypedValue> values;
        private final List<ConfigError> errors;

        private Report(List<TypedValue> values, List<ConfigError> errors) {
            this.values = List.copyOf(values);
            // ORDER BY ADDRESS: the report has to be comparable between runs and platforms, and "the first
            // problem" has to be the same one every time. Problems without a path (a whole source that cannot
            // be read) come first, because they outrank fields.
            List<ConfigError> ordered = new ArrayList<>(errors);
            ordered.sort(Comparator
                .comparing((ConfigError error) -> ConfigError.pathOf(error).orElse(null),
                    Comparator.nullsFirst(ConfigPath.BY_VALUE))
                .thenComparing(error -> error.kind().name())
                .thenComparing(error -> error.source().display()));
            this.errors = List.copyOf(ordered);
        }

        /**
         * Returns the typed values.
         *
         * @return an immutable list of values, none of which is a problem
         */
        public List<TypedValue> values() {
            return values;
        }

        /**
         * Returns every problem found, at once rather than one at a time.
         *
         * @return an immutable list ordered by path, kind and source
         */
        public List<ConfigError> errors() {
            return errors;
        }

        /**
         * Returns whether any problem was found.
         *
         * @return {@code true} when {@link #errors()} is not empty
         */
        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        /**
         * Returns a listing for a human, one entry per line: {@code ok} for each value and {@code ERROR}
         * for each problem.
         *
         * @return the listing, always ending with a line separator
         */
        public String describe() {
            StringBuilder text = new StringBuilder();
            values.forEach(entry -> text.append("ok   ")
                .append(entry.path().canonical()).append(" = ").append(entry.value().display())
                .append("   [").append(entry.source().display()).append("]\n"));
            errors.forEach(error -> text.append("ERROR ").append(error.report()).append('\n'));
            return text.toString();
        }
    }

    /**
     * Validates a configuration as a whole: runs the pipeline steps and reads their verdicts.
     *
     * <pre>
     *   required + a value from any layer   -> ok
     *   required, no value                  -> Absent    -> error
     *   optional, no value                  -> Absent    -> valid state, no error
     *   an expression yielded nothing       -> Absent    -> the layer is treated as if it had no entry
     *   a source delivered nothing          -> NullValue -> error
     *   a pipeline step stopped the value   -> the FAILED verdict carries the problem
     * </pre>
     *
     * <p><b>{@code build} does not repeat validation</b>: it runs the steps ({@link ConfigPipeline#all}),
     * and those process only the entries **without a verdict of their own**, so a second call is free and
     * a value contributed programmatically is checked **once**. Problems come from the verdicts, which is
     * why the report can show a failed transformation together with the transformer name and its
     * configuration.
     *
     * <p>Values win according to the chain (precedence belongs to {@link Config#get}), and their type and
     * notation come from the declaration. Keys outside the schema are not decided here: they require
     * reachability analysis from the fields of the schema.
     *
     * @param definition the declared fields
     * @param config the configuration to validate
     * @return the typed values and every problem found
     * @since 1.0
     */
    public static Report build(ConfigDefinition definition, Config config) {
        return build(definition, config, TransformerRegistry.defaults());
    }

    /**
     * Validates a configuration as a whole, using the given registry of transformers instead of the
     * built-in one.
     *
     * @param definition the declared fields
     * @param config the configuration to validate
     * @param registry the transformers that may be named by declarations
     * @return the typed values and every problem found
     * @since 1.0
     */
    public static Report build(ConfigDefinition definition, Config config, TransformerRegistry registry) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(registry, "registry");

        Config resolved = ConfigPipeline.all(definition, registry).apply(config);

        List<TypedValue> values = new ArrayList<>();
        List<ConfigError> errors = new ArrayList<>();

        for (ConfigField field : definition.fields()) {
            ConfigResult<ConfigValue> found = resolved.get(field.path());
            if (found instanceof ConfigResult.ErrorResult<ConfigValue> bad) {
                ConfigError error = bad.error();
                boolean absentOptional = error instanceof ConfigError.Absent && !field.required();
                if (!absentOptional) {
                    addOnce(errors, error);
                }
                continue;
            }
            Config.Entry entry = resolved.entry(field.path()).orElseThrow(() -> new IllegalStateException(
                "field `" + field.path().canonical() + "` has a value but no entry in the chain"));
            // Verdicts instead of re-validation: the value is already typed, and the problems were recorded
            // where they arose - in the step that stopped the value.
            // A SECRET MUST NOT COME FROM A SETTINGS FILE (owner, 21 X 2026): the mark on the declaration says the value is a password, and a password in
            // `maswerk.yaml` is a password in every copy, backup and commit of that file. The vault and the environment are the ways in.
            if (field.isSensitive() && entry.source().layer() == Layer.YAML) {
                // AND THE VALUE IS NOT CARRIED into the error: `actual` is what a renderer prints, so it holds a redaction - otherwise the check
                // against leaks would be the thing that leaks.
                errors.add(new ConfigError.ValidationFailed(field.path(),
                    "sensitive: a secret cannot be set in a settings file - put it in the vault, or in the environment",
                    ConfigString.of("***"), entry.source()));
                continue;
            }
            List<ConfigError> problems = entry.problems();
            if (!problems.isEmpty()) {
                problems.forEach(problem -> addOnce(errors, problem));
                continue;
            }
            values.add(new TypedValue(field.path(), entry.value(), entry.source(), entry.source().layer()));
        }

        // Auxiliary: a key outside the schema. Two DIFFERENT things are decided here, and they must not share
        // one guard - sharing it made the first of them silent in the route every application uses.
        //
        //  * PROBLEMS carried by such an entry (a refused value, a rule that does not hold, a notation for a
        //    type that has none). These are visible ALWAYS: the entry has a verdict, and a verdict nobody sees
        //    is a marker that "does not work, but nobody knows why". A tree that has already been through the
        //    pipeline carries them as well - and an application builds exactly such a tree.
        //  * REACHABILITY (`UnusedAuxiliary`) is computed STATICALLY on a tree that has NOT seen the
        //    interpolation step: after that step the values are resolved and the symbols are gone, so the
        //    count would be a lie. Keeping it under that condition is what makes `build` idempotent when it
        //    is handed the result of a pipeline.
        boolean countReachability = !config.steps().contains(ConfigStep.INTERPOLATION);
        Set<ConfigPath> reached = countReachability ? reachable(definition, config) : Set.of();
        for (ConfigPath path : resolved.pathsInChain()) {
            if (definition.field(path).isPresent()) {
                continue; // fields of the schema have their own loop above
            }
            Config.Entry entry = resolved.entry(path).orElse(null);
            if (entry == null) {
                continue;
            }
            List<ConfigError> problems = entry.problems();
            if (!problems.isEmpty()) {
                problems.forEach(problem -> addOnce(errors, problem));
                continue; // one mistake = one problem: a broken entry instead of an "unused" one
            }
            if (countReachability && !reached.contains(path)) {
                addOnce(errors, new ConfigError.UnusedAuxiliary(path, entry.source()));
            }
        }

        return new Report(values, errors);
    }

    // A cycle is one mistake even though every participant carries a FAILED verdict, so the report shows it
    // once. The same guard keeps any other problem from appearing twice when it arrives from two places (for
    // example a step verdict and a read).
    private static void addOnce(List<ConfigError> errors, ConfigError error) {
        if (!errors.contains(error)) {
            errors.add(error);
        }
    }

    // Paths reachable from the fields of the schema: a field's own references, then the references of their
    // targets. Computed on the symbolic view (`Config.entry`) with parsing done on the fly, so the result is
    // the same for a tree before the interpolation step and after it.
    private static Set<ConfigPath> reachable(ConfigDefinition definition, Config config) {
        Set<ConfigPath> reached = new LinkedHashSet<>();
        Deque<ConfigPath> visit = new ArrayDeque<>();
        for (ConfigField field : definition.fields()) {
            visit.add(field.path());
        }
        while (!visit.isEmpty()) {
            ConfigPath path = visit.poll();
            Optional<Config.Entry> entry = config.entry(path);
            if (entry.isEmpty()) {
                continue; // no value refers to nothing
            }
            // A RULE is a statement too: its parameters (`${min}` in `>=${min}`) are references, and a key they
            // lead to is USED by the model even when no value points at it. Without this a rule parameter was
            // reported as `unused-auxiliary` ("a key outside the schema that nothing leads to") while the rule
            // was reading it on every run.
            for (String parameter : parametersOf(definition, path, entry.get())) {
                ConfigPath target = ConfigPath.of(parameter);
                if (reached.add(target)) {
                    visit.add(target);
                }
            }
            ConfigResult<ConfigValue> parsed =
                Interpolation.parseTree(path, entry.get().value(), entry.get().source());
            if (!(parsed instanceof ConfigResult.ValueResult<ConfigValue> ok)) {
                continue; // text with broken syntax refers to nothing meaningful
            }
            for (ConfigPath target : Interpolation.references(ok.value())) {
                if (reached.add(target)) {
                    visit.add(target);
                }
            }
        }
        return reached;
    }


    /**
     * The parameters of every rule that judges this address: the ones declared in code and the one a statement
     * brought in (`___CONST` in ENV or CLI, `CONST(…)` in a YAML comment). A broken chain refers to nothing,
     * exactly as text with broken syntax does.
     */
    private static List<String> parametersOf(ConfigDefinition definition, ConfigPath path, Config.Entry entry) {
        List<String> parameters = new ArrayList<>();
        definition.field(path).ifPresent(field -> field.constraints()
            .forEach(rule -> parameters.addAll(rule.parameters())));
        entry.declaration().constraints().ifPresent(chain -> {
            try {
                parameters.addAll(ConfigConstraint.Const(chain).parameters());
            } catch (IllegalArgumentException broken) {
                // A rule that does not parse is a source error, reported where it belongs - here it refers to
                // nothing, so it must not pretend to reach anything.
            }
        });
        return parameters;
    }

    /**
     * Reports an empty value as a problem: one rule, three entries - an empty scalar, an empty list and an
     * empty map. The check runs **before** the cast, so that the message is about emptiness and not about
     * the type the field expected.
     *
     * <p>Shared by the cast step of the pipeline and by layer validation, because the rule is one and two
     * copies of it would drift apart at the first change.
     *
     * <p><b>Emptiness is an error WHEREVER it stands</b>, not only at a declared address: `{}`, `[]` and `""`
     * say "I meant to give something and gave nothing", and a collection is no place to say that quietly. The
     * error names the NESTED address (`ports.0`), so the reader knows which member is the empty one. An
     * optional collection that is simply never stated is a different fact — it is `ABSENT`, and that is fine.
     *
     * @param path path of the value
     * @param value value contributed by a layer
     * @param source where that value came from
     * @return the emptiness problem, or empty when the value is not empty
     */
    static Optional<ConfigError.NullValue> emptiness(ConfigPath path, ConfigValue value, ConfigSource source) {
        if (value instanceof ConfigString text && text.value().isEmpty()) {
            return Optional.of(new ConfigError.NullValue(path, source, ConfigError.NullValue.Shape.VALUE));
        }
        if (value instanceof ConfigSeq seq) {
            if (seq.isEmpty()) {
                return Optional.of(new ConfigError.NullValue(path, source, ConfigError.NullValue.Shape.LIST));
            }
            for (int address : seq.addresses()) {
                Optional<ConfigError.NullValue> nested = emptiness(path.child(String.valueOf(address)),
                    seq.at(address).orElseThrow(), source);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }
        if (value instanceof ConfigMap map) {
            if (map.isEmpty()) {
                return Optional.of(new ConfigError.NullValue(path, source, ConfigError.NullValue.Shape.MAP));
            }
            for (Map.Entry<String, ConfigValue> entry : map.entries().entrySet()) {
                Optional<ConfigError.NullValue> nested = emptiness(path.child(entry.getKey()), entry.getValue(),
                    source);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }
        return Optional.empty();
    }

    // Constraints are checked after a successful cast, on the value from the declaration. If the cast
    // failed there is nothing to check, so the rule does not add a second error.
    private static void checkConstraints(ConfigField field, ConfigValue value, ConfigSource source,
                                         List<ConfigError> errors) {
        for (ConfigConstraint constraint : field.constraints()) {
            // A rule judges a value OF A TYPE (see `ConfigConstraint.forType`): an operand that can never be a
            // value of the field's type is a DECLARATION error — and the error says WHICH operand and why.
            try {
                constraint = constraint.forType(field.type());
            } catch (IllegalArgumentException broken) {
                errors.add(new ConfigError.UnusableRule(field.path(), constraint.rule(), broken.getMessage(),
                    source));
                continue;
            }
            // An ordering rule on a value that cannot be compared is a DECLARATION error, not a configuration
            // one: `lowerThan` on a `str` field has nothing to compare, and a textual comparison would give a
            // silent, inverted result (`"10" < "2"` is true as text).
            if (!constraint.appliesTo(value)) {
                errors.add(new ConfigError.RuleMismatch(field.path(), constraint.rule(), value.type(), source));
                continue;
            }
            if (!constraint.accepts(value)) {
                errors.add(new ConfigError.ValidationFailed(field.path(), constraint.rule(), value, source));
            }
        }
    }

    /**
     * Validates layer by layer, in the given order, and casts every value onto the type from the schema.
     * A path with no declaration (an auxiliary key) passes without a cast and keeps the form it had in the
     * source.
     *
     * <p><b>This is a preview of the types from the schema, not a verdict.</b> A schema does not know the
     * notation stated in a declaration ({@code transformer} together with {@code transformerConfig}), so a
     * value written in that notation - such as a date-time under a {@code date} transformer with a pattern -
     * comes out of here as a {@link ConfigError.CastFailed}, even though
     * {@link #build(ConfigDefinition, Config)} accepts it. To judge a configuration, use {@code build} or
     * the pipeline steps.
     *
     * @param schema the declared types
     * @param layers layers to validate, in the order they are applied
     * @return the typed values and every problem found
     * @since 1.0
     */
    public static Report validate(ConfigSchema schema, List<LayerInput> layers) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(layers, "layers");

        List<TypedValue> values = new ArrayList<>();
        List<ConfigError> errors = new ArrayList<>();

        for (LayerInput input : layers) {
            for (Map.Entry<ConfigPath, ConfigPatch.Operation> entry : input.patch().operations().entrySet()) {
                ConfigPath path = entry.getKey();

                if (entry.getValue() instanceof ConfigPatch.NullDelivered nothing) {
                    // The ADAPTER's own statement — the only path that reaches `NullValue` without a value.
                    // The same decision as for an empty string: the source delivered nothing, and empty
                    // means an error. When a loader starts carrying the reason for the failure, that reason
                    // will reach the message through here.
                    errors.add(new ConfigError.NullValue(path, nothing.source()));
                    continue;
                }
                if (!(entry.getValue() instanceof ConfigPatch.SetValue set)) {
                    continue; // `reset` is decided by lookup, not by value validation
                }

                // An empty value that reaches a layer is emptiness, with no distinction by origin: an empty
                // scalar, an empty COLLECTION, an explicit empty string in YAML, an explicit empty argument.
                // Checked before the cast, so that an empty field produces NullValue rather than a
                // misleading "expected type bool". What an ADAPTER refuses to deliver (an empty ENV
                // variable) or refuses to read at all (an option with no parameter) never gets this far.
                Optional<ConfigError.NullValue> empty = emptiness(path, set.value(), set.source());
                if (empty.isPresent()) {
                    errors.add(empty.get());
                    continue;
                }

                var declared = schema.declaredType(path);
                if (declared.isEmpty()) {
                    values.add(new TypedValue(path, set.value(), set.source(), input.layer()));
                    continue;
                }

                ConfigResult<ConfigValue> cast =
                    ConfigCast.to(set.value(), declared.get(), path, set.source());
                if (cast instanceof ConfigResult.ValueResult<ConfigValue> ok) {
                    values.add(new TypedValue(path, ok.value(), set.source(), input.layer()));
                } else if (cast instanceof ConfigResult.ErrorResult<ConfigValue> bad) {
                    errors.add(bad.error());
                }
            }
        }

        return new Report(values, errors);
    }
}
