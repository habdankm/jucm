package dev.jucm.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Interpolation: the **parser** of `${...}` and the **lazy resolver**, that is, the one place where text
 * becomes a symbol and a symbol becomes a value.
 *
 * <pre>
 *   parseTree  "http://${host}:${port}"  ->  ConfigExpression[Text, Ref, Text, Ref]
 *              "${a}"                    ->  ConfigReference(a)
 *              "${a ?? b}"               ->  ConfigCoalesce(a, b)
 *   lookup     ${a} against a snapshot   ->  a value, "contributed nothing", or a problem
 * </pre>
 *
 * <p><b>Parsing and resolving are two different moments</b>:
 *
 * <pre>
 *   PARSE   once, in the interpolation step (text -> symbol) - the tree stays symbolic
 *   LOOKUP  on EVERY read, against one particular snapshot (`Config.get`)
 * </pre>
 *
 * <p>That is why a newer snapshot that sets `a` changes the result of `c = ${a}` without touching `c`: the
 * tree still holds a **reference**, not a copied value. To see the symbol, use the symbolic view
 * ({@link Config#entry}); to see the value, use {@link Config#get}.
 *
 * <p>The syntax is **narrow and without exceptions**: one level of substitution, the body is a reference
 * expression, and anything else is a {@link ConfigError.InvalidSyntax}:
 *
 * <pre>
 *   ACCEPTED  ${a}   ${?a}   ${a ?? b}   "http://${host}:${port}"   "$ ${a}"
 *   REJECTED  $${a} (there is NO escape)   ${a (unclosed)   ${{a}} / ${${a}} (braces in the body)
 * </pre>
 *
 * <p>Resolving leaves a **record of each `??` decision** as an object of its own per value: at `debug`
 * level the engine has to be able to say which side it chose and **why**. The record is computed on demand
 * ({@link #resolve(Config, ConfigPath)}), so a hot path pays nothing for it.
 *
 * <p><b>One algorithm for two modes</b>: values resolve **lazily** (this resolver), and keys resolve
 * **eagerly**, before the YAML parser. The eager mode will take the model and the transformer registry as
 * parameters, because it renders the text of a key through the full intake - but it is the same walk along
 * the chain and the same cycle detector, not a second implementation.
 *
 * @since 1.0
 */
public final class Interpolation {

    private static final String OPEN = "${";
    private static final String NO_ESCAPE = "$${";
    private static final String FALLBACK = "??";
    private static final String OPTIONAL = "?";

    private Interpolation() {
    }

    /**
     * The side of `??` the engine chose - material for `debug` output.
     *
     * @since 1.0
     */
    public enum Side {
        /** The left-hand side of `??` decided the result. */
        LEFT,
        /** The right-hand side of `??` decided the result. */
        RIGHT
    }

    /**
     * The result of resolving **one side** within the decision trace.
     *
     * @since 1.0
     */
    public sealed interface Outcome permits Outcome.Value, Outcome.YieldsAbsent, Outcome.Problem {

        /**
         * The side produced a value.
         *
         * @param value the value that side produced
         */
        record Value(ConfigValue value) implements Outcome {
            /** Validates the component — it is required. */
            public Value {
                Objects.requireNonNull(value, "value");
            }
        }

        /**
         * The side contributed nothing (`Absent`, or an optional reference with no target) - the chain
         * continues below it.
         */
        record YieldsAbsent() implements Outcome {
        }

        /**
         * The side failed.
         *
         * @param error the problem that side produced
         */
        record Problem(ConfigError error) implements Outcome {
            /** Validates the component — it is required. */
            public Problem {
                Objects.requireNonNull(error, "error");
            }
        }
    }

    /**
     * The record of a `??` decision: both sides and the choice together with its reason.
     *
     * @param coalesce the operator this decision belongs to
     * @param left the result of resolving the left-hand side
     * @param right the result of resolving the right-hand side; empty when the right-hand side was not
     *        reached
     * @param chosen the side that decided the result
     * @param reason why that side was the one
     * @since 1.0
     */
    public record Decision(ConfigCoalesce coalesce, Outcome left, Optional<Outcome> right, Side chosen,
                           String reason) {
    }

    /**
     * A resolved value together with the trace of `??` decisions.
     *
     * @param value the resolved value
     * @param trace the decisions in the order they were made; immutable
     * @since 1.0
     */
    public record Resolved(ConfigValue value, List<Decision> trace) {
        /** Validates the components — the value is required and the trace is copied immutably. */
        public Resolved {
            Objects.requireNonNull(value, "value");
            trace = List.copyOf(trace);
        }
    }

    // --- PARSER -----------------------------------------------------------------------------------

    /**
     * Parses a whole tree of values: text becomes symbols and containers are walked recursively (a list
     * element may be `${a}` too, and {@code display()} of a collection has to show what the user wrote).
     *
     * <p>A list additionally enforces the ban on `${?x}` in an element - **statically**, before anything at
     * all is resolved.
     *
     * @param path path of the entry being parsed
     * @param value the value as the source delivered it
     * @param source where the entry came from
     * @return the symbolic value, or the first syntax problem found
     * @since 1.0
     */
    public static ConfigResult<ConfigValue> parseTree(ConfigPath path, ConfigValue value, ConfigSource source) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(source, "source");
        return switch (value) {
            case ConfigString text -> parse(path, text.value(), source);
            case ConfigSeq seq -> parseSequence(path, seq, source);
            case ConfigMap map -> parseMapping(path, map, source);
            default -> ConfigResult.value(value);
        };
    }

    private static ConfigResult<ConfigValue> parseSequence(ConfigPath path, ConfigSeq seq, ConfigSource source) {
        List<ConfigValue> elements = new ArrayList<>();
        for (int address : seq.addresses()) {
            ConfigPath elementPath = path.child(String.valueOf(address));
            ConfigResult<ConfigValue> parsed = parseTree(elementPath, seq.at(address).orElseThrow(), source);
            if (parsed instanceof ConfigResult.ErrorResult<ConfigValue> bad) {
                return bad;
            }
            // An element that MAY be missing is legal here: it vanishes while the value is resolved (see
            // `sequence`) and leaves its address empty. That is not a syntax error — the syntax is fine.
            elements.add(((ConfigResult.ValueResult<ConfigValue>) parsed).value());
        }
        return ConfigResult.value(ConfigSeq.of(elements));
    }

    // `${?x}` in a list element is legal: the element VANISHES while the value is resolved and leaves its
    // address empty (`ConfigSeq`). The ban that used to stand here ("an element has no absent state, and
    // dropping it would shift the indexes") was wrong on both counts - dropping needs no absent state, and
    // the addresses are what keeps a neighbour in place.

    private static ConfigResult<ConfigValue> parseMapping(ConfigPath path, ConfigMap map, ConfigSource source) {
        Map<String, ConfigValue> entries = new LinkedHashMap<>();
        for (Map.Entry<String, ConfigValue> entry : map.entries().entrySet()) {
            ConfigResult<ConfigValue> parsed = parseTree(path.child(entry.getKey()), entry.getValue(), source);
            if (parsed instanceof ConfigResult.ErrorResult<ConfigValue> bad) {
                return bad;
            }
            entries.put(entry.getKey(), ((ConfigResult.ValueResult<ConfigValue>) parsed).value());
        }
        return ConfigResult.value(ConfigMap.of(entries));
    }

    // `holdsOptional` used to live here: it recognised an element that MAY be missing, so that a list could
    // REJECT it. A list no longer rejects it — the element vanishes while the value is resolved and leaves its
    // address empty, which is the same rule an expression follows when a part contributes nothing.

    /**
     * Turns text into a value: a literal stays as it is, and `${...}` becomes a {@link ConfigReference}, a
     * {@link ConfigCoalesce} or a {@link ConfigExpression}.
     *
     * <p>A syntax error is a **result**, not an exception: what is parsed here is configuration, not program
     * code.
     *
     * @param path path of the entry being parsed
     * @param raw the text as the source wrote it
     * @param source where the entry came from
     * @return the symbolic value, or the syntax problem
     * @since 1.0
     */
    public static ConfigResult<ConfigValue> parse(ConfigPath path, String raw, ConfigSource source) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(source, "source");
        if (!raw.contains(OPEN)) {
            return ConfigResult.value(ConfigString.of(raw)); // with no `${` there is nothing to do
        }
        if (raw.contains(NO_ESCAPE)) {
            return invalid(path, raw, source, "`$${` is NOT an escape - there is no escape");
        }
        List<ConfigExpression.Content> parts = new ArrayList<>();
        int position = 0;
        while (position < raw.length()) {
            int open = raw.indexOf(OPEN, position);
            if (open < 0) {
                parts.add(new ConfigExpression.Text(raw.substring(position)));
                break;
            }
            if (open > position) {
                parts.add(new ConfigExpression.Text(raw.substring(position, open)));
            }
            int close = raw.indexOf('}', open + OPEN.length());
            if (close < 0) {
                return invalid(path, raw, source, "the substitution `${` has no closing `}`");
            }
            String content = raw.substring(open + OPEN.length(), close);
            if (content.indexOf('{') >= 0 || content.indexOf('}') >= 0) {
                return invalid(path, raw, source, "the body of a substitution carries no braces - "
                    + "there is no nesting");
            }
            ConfigResult<ConfigExpression.Content> symbolic = symbolic(path, raw, source, content);
            if (symbolic instanceof ConfigResult.ErrorResult<ConfigExpression.Content> bad) {
                return ConfigResult.error(bad.error());
            }
            parts.add(((ConfigResult.ValueResult<ConfigExpression.Content>) symbolic).value());
            position = close + 1;
        }
        return assemble(path, raw, source, parts);
    }

    private static ConfigResult<ConfigExpression.Content> symbolic(ConfigPath path, String raw,
                                                                  ConfigSource source, String content) {
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            return invalid(path, raw, source, "an empty substitution `${}` means nothing");
        }
        if (trimmed.startsWith(OPTIONAL)) {
            String target = trimmed.substring(1).trim();
            if (target.isEmpty()) {
                return invalid(path, raw, source, "`${?}` carries no path - there is nothing to use");
            }
            if (target.contains(FALLBACK)) {
                return invalid(path, raw, source, "`${?" + target + "}` says the same thing twice: `?` means "
                    + "\"use it if it exists\", and `??` already handles that");
            }
            return switch (reference(path, raw, source, target, true)) {
                case ConfigResult.ValueResult<ConfigReference> ok ->
                    ConfigResult.value(new ConfigExpression.Ref(ok.value()));
                case ConfigResult.ErrorResult<ConfigReference> bad -> ConfigResult.error(bad.error());
            };
        }
        if (trimmed.contains(FALLBACK)) {
            String[] sides = trimmed.split(Pattern.quote(FALLBACK), -1);
            for (String side : sides) {
                if (side.trim().isEmpty()) {
                    return invalid(path, raw, source, "`??` carries an empty side - every side has to exist");
                }
            }
            ConfigValue chain = null;
            for (int index = sides.length - 1; index >= 0; index--) {
                ConfigResult<ConfigValue> side = operand(path, raw, source, sides[index].trim());
                if (side instanceof ConfigResult.ErrorResult<ConfigValue> bad) {
                    return ConfigResult.error(bad.error());
                }
                ConfigValue target = ((ConfigResult.ValueResult<ConfigValue>) side).value();
                chain = chain == null ? target : new ConfigCoalesce(target, chain);
            }
            return ConfigResult.value(new ConfigExpression.Coalesce((ConfigCoalesce) chain));
        }
        return switch (reference(path, raw, source, trimmed, false)) {
            case ConfigResult.ValueResult<ConfigReference> ok ->
                ConfigResult.value(new ConfigExpression.Ref(ok.value()));
            case ConfigResult.ErrorResult<ConfigReference> bad -> ConfigResult.error(bad.error());
        };
    }


    /**
     * ONE operand of `??`. Its **form** decides what it is, and with that its type:
     *
     * <pre>
     *   9999        -> a literal `int`      (a number states its type by being a number)
     *   "9999"      -> a literal `str`      (quotes are the form of text)
     *   true        -> a literal `bool`
     *   fallback    -> a REFERENCE          (a bare word is an address, as `${extra ?? fallback}` documents)
     * </pre>
     *
     * <p>A literal has no declaration behind it, so the form is the only thing that can speak — exactly as the
     * form of a YAML node speaks for a key outside the schema, and the form of a member speaks for the elements
     * of a collection. Quotes are KEPT in the literal's text: the pair (text, quotes) IS the form, and the
     * resolver strips them when the value is delivered.
     *
     * <p>Nothing else is a literal: a duration or a URI is not read from its form, because `30s` and
     * `scheme://host` are perfectly good names for an address. A token that should be a literal of such a type
     * is written as a reference to a value of that type.
     */
    private static ConfigResult<ConfigValue> operand(ConfigPath path, String raw, ConfigSource source,
                                                     String text) {
        if (isQuoted(text) || isLiteralForm(text)) {
            return ConfigResult.value(ConfigString.of(text));
        }
        return switch (reference(path, raw, source, text, false)) {
            case ConfigResult.ValueResult<ConfigReference> ok -> ConfigResult.value(ok.value());
            case ConfigResult.ErrorResult<ConfigReference> bad -> ConfigResult.error(bad.error());
        };
    }

    /** A quoted text: the form of text, kept WITH its quotes. */
    private static boolean isQuoted(String text) {
        return text.length() > 1 && text.startsWith("\"") && text.endsWith("\"");
    }

    /**
     * The type a literal states by its form: a whole number is `int`, one with a point or an exponent is
     * `float`, and plain `true`/`false` are `bool` — the same shapes our number notation recognises.
     *
     * @param text the text of an operand, exactly as written
     * @return the type the form states
     */
    static ConfigType literalType(String text) {
        if (isQuoted(text)) {
            return ConfigType.STR;
        }
        if (INTEGER_FORM.matcher(text).matches()) {
            return ConfigType.INT;
        }
        if (FLOAT_FORM.matcher(text).matches()) {
            return ConfigType.FLOAT;
        }
        return ConfigType.BOOL;
    }

    /** A whole number in any shape our notation reads: `9999`, `-1`, `010`, `0x1F`, `1_000`. */
    private static final Pattern INTEGER_FORM = Pattern.compile("[-+]?(\\d[\\d_]*|0[xX][0-9a-fA-F_]+|0[bB][01_]+)");

    /** A number with a point or an exponent: `1.5`, `2.0`, `1e6`, `1.5e-3`. */
    private static final Pattern FLOAT_FORM =
        Pattern.compile("[-+]?(\\d[\\d_]*\\.\\d[\\d_]*([eE][-+]?\\d+)?|\\d[\\d_]*[eE][-+]?\\d+)");

    /** Whether the form of the text is a literal at all — a number, a quoted text or a boolean. */
    private static boolean isLiteralForm(String text) {
        return INTEGER_FORM.matcher(text).matches() || FLOAT_FORM.matcher(text).matches()
            || text.equals("true") || text.equals("false");
    }

    private static ConfigResult<ConfigReference> reference(ConfigPath path, String raw, ConfigSource source,
                                                          String target, boolean optional) {
        try {
            return ConfigResult.value(optional ? ConfigReference.optional(target) : ConfigReference.to(target));
        } catch (IllegalArgumentException notAPath) {
            return ConfigResult.error(new ConfigError.InvalidSyntax(path,
                raw + " - `" + target + "` is not a reference path: " + notAPath.getMessage(), source));
        }
    }

    // One part is a SYMBOLIC value, not an expression: `${a}` with or without quotes is still a
    // `ConfigReference`. An expression appears when there are at least two parts, or at least one literal.
    private static ConfigResult<ConfigValue> assemble(ConfigPath path, String raw, ConfigSource source,
                                                      List<ConfigExpression.Content> parts) {
        if (parts.size() == 1) {
            return switch (parts.get(0)) {
                case ConfigExpression.Ref ref -> ConfigResult.value(ref.reference());
                case ConfigExpression.Coalesce coalesce -> ConfigResult.value(coalesce.coalesce());
                case ConfigExpression.Text literal -> ConfigResult.value(ConfigString.of(literal.text()));
            };
        }
        return ConfigResult.value(ConfigExpression.of(parts));
    }

    private static <T> ConfigResult<T> invalid(ConfigPath path, String raw, ConfigSource source, String reason) {
        return ConfigResult.error(new ConfigError.InvalidSyntax(path, raw + " - " + reason, source));
    }

    // --- STATIC ANALYSIS --------------------------------------------------------------------------

    /**
     * Returns the paths this value **refers to** - from references, from both sides of `??`, from the parts
     * of an expression and from the inside of containers.
     *
     * <p>Computed **statically**, on the symbolic tree, so the result does not depend on which branch of `??`
     * was the one that fired.
     *
     * @param value the symbolic value to inspect
     * @return an immutable list of referenced paths, in the order they appear
     * @since 1.0
     */
    public static List<ConfigPath> references(ConfigValue value) {
        List<ConfigPath> found = new ArrayList<>();
        collect(value, found);
        return List.copyOf(found);
    }

    private static void collect(ConfigValue value, List<ConfigPath> found) {
        switch (value) {
            case ConfigReference reference -> found.add(reference.target());
            case ConfigCoalesce coalesce -> {
                collect(coalesce.left(), found);
                collect(coalesce.right(), found);
            }
            case ConfigExpression expression ->
                expression.symbolics().forEach(symbolic -> collect(symbolic, found));
            case ConfigSeq seq -> seq.elements().forEach(element -> collect(element, found));
            case ConfigMap map -> map.entries().values().forEach(element -> collect(element, found));
            case ConfigString ignored -> {
            }
            case ConfigInt ignored -> {
            }
            case ConfigFloat ignored -> {
            }
            case ConfigBool ignored -> {
            }
            case ConfigTimestamp ignored -> {
            }
            case ConfigBinary ignored -> {
            }
        }
    }

    // --- RESOLVER ---------------------------------------------------------------------------------

    /** Reads the value under a path against this snapshot - what `Config.get` does. */
    static Outcome lookup(Config view, ConfigPath path) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(path, "path");
        Resolver resolver = new Resolver(view, view.definition());
        return resolver.lookup(path);
    }

    /**
     * Resolves the value under a path against **this** snapshot and returns it together with the trace of
     * `??` decisions. Called on demand, for diagnostics and the `debug` level.
     *
     * @param config the snapshot to resolve against
     * @param path path of the entry to resolve
     * @return the resolved value and its decision trace, or the problem that prevented resolution
     * @since 1.0
     */
    public static ConfigResult<Resolved> resolve(Config config, ConfigPath path) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(path, "path");
        Resolver resolver = new Resolver(config, config.definition());
        Outcome outcome = resolver.lookup(path);
        return switch (outcome) {
            case Outcome.Value value -> ConfigResult.value(new Resolved(value.value(), resolver.trace()));
            case Outcome.YieldsAbsent ignored -> ConfigResult.error(new ConfigError.Absent(path));
            case Outcome.Problem problem -> ConfigResult.error(problem.error());
        };
    }

    /**
     * The resolver of a single read: it carries the memo and the resolution stack. The memo is shared across
     * the whole chain, so `a -> b -> c` is computed once, and one procedure detects cycles.
     */
    static final class Resolver {

        private final Config view;
        private final ConfigDefinition definition;
        private final Map<ConfigPath, Outcome> memo = new LinkedHashMap<>();
        private final List<ConfigPath> stack = new ArrayList<>();
        private final List<Decision> trace = new ArrayList<>();

        Resolver(Config view) {
            this(view, null);
        }

        /**
         * A resolver that knows the DECLARATIONS: `??` then checks that every operand fits the type of the
         * value. Without the definition (a bare read of an unparsed tree) the check cannot run - the read stays
         * exactly as before.
         */
        Resolver(Config view, ConfigDefinition definition) {
            this.view = view;
            this.definition = definition;
        }

        List<Decision> trace() {
            return List.copyOf(trace);
        }

        /**
         * Where the value at this address came from - material for a message.
         *
         * <p>An address that has no entry of its own takes the source of the **nearest address that has one**:
         * an element of a collection (`ports.1`) or a nested value (`server.tls.ciphers.0`) lives inside a value
         * that some layer stated, and that layer is where it came from. Without this walk the report pointed at
         * `CUSTOM:application code` - a file's problem reported as the application's, with the wrong exit code.
         *
         * <p>The walk ends at the declaration's own layer for a declared address (the root layer holds the
         * defaults), and at {@link ConfigSource#application()} when nothing states the address at all - that is
         * the honest answer for a value no layer brought in.
         */
        ConfigSource sourceOf(ConfigPath path) {
            ConfigPath at = path;
            while (at != null) {
                Optional<Config.Entry> entry = view.entry(at);
                if (entry.isPresent()) {
                    return entry.get().source();
                }
                at = parentOf(at);
            }
            return ConfigSource.application();
        }

        /** The address one level up, or {@code null} at the root - the chain of addresses a value lives in. */
        private static ConfigPath parentOf(ConfigPath path) {
            List<String> segments = path.segments();
            return segments.size() <= 1 ? null : ConfigPath.of(segments.subList(0, segments.size() - 1));
        }

        /**
         * Lookup along the chain: the first hit gives the value, and a layer whose expression **contributed
         * nothing** does not block the one below - exactly as if that layer were not there.
         */
        Outcome lookup(ConfigPath path) {
            Outcome known = memo.get(path);
            if (known != null) {
                return known;
            }
            Outcome found = walk(path);
            memo.put(path, found);
            return found;
        }

        private Outcome walk(ConfigPath path) {
            boolean skippedOne = false;
            for (Config config = view; config != null; config = config.parent().orElse(null)) {
                Config.SlotView slot = config.slotAt(path);
                if (slot == null) {
                    continue;
                }
                if (skippedOne) {
                    skippedOne = false; // this layer was just removed by a `reset`
                    continue;
                }
                if (slot.reset()) {
                    skippedOne = true;
                    continue;
                }
                if (slot.nothing()) {
                    // "nothing delivered" does NOT descend further.
                    return new Outcome.Problem(new ConfigError.NullValue(path, slot.source()));
                }
                if (slot.value().isEmpty()) {
                    // `dropped`: the layer brought in no value (`${?x}` with no target, `??` with both sides
                    // contributing nothing), so the read goes BELOW it - exactly as if that layer had not
                    // mentioned the path. With nothing below, the walk ends as `Absent`.
                    continue;
                }
                Outcome resolved = value(slot.value().orElseThrow(), path);
                if (!(resolved instanceof Outcome.YieldsAbsent)) {
                    return resolved;
                }
            }
            return nested(path).orElseGet(Outcome.YieldsAbsent::new);
        }

        // Descending into a container element: `ports.2` is an element of a list, not an entry of its own.
        private Optional<Outcome> nested(ConfigPath path) {
            List<String> segments = path.segments();
            for (int split = segments.size() - 1; split >= 1; split--) {
                ConfigPath parent = ConfigPath.of(segments.subList(0, split));
                Outcome container = lookup(parent);
                String last = segments.get(segments.size() - 1);
                if (container instanceof Outcome.Value value && value.value() instanceof ConfigMap map) {
                    ConfigValue element = map.entries().get(last);
                    return element == null ? Optional.empty() : Optional.of(value(element, path));
                }
                if (container instanceof Outcome.Value value && value.value() instanceof ConfigSeq seq
                    && last.chars().allMatch(Character::isDigit)) {
                    // The ADDRESS, not the position: an element that vanished left its address empty, and a
                    // reference to it must lead NOWHERE — never to the neighbour that took its place.
                    Optional<ConfigValue> element = seq.at(Integer.parseInt(last));
                    return Optional.of(element.isPresent() ? value(element.get(), path)
                        : new Outcome.Problem(new ConfigError.UnknownPath(path, sourceOf(parent))));
                }
            }
            return Optional.empty();
        }

        // Resolves a value: text is parsed, symbols are walked to their values. Text reaches this method only
        // when the interpolation step has not run (the tree then holds raw text), which keeps `get` correct
        // for an unparsed tree instead of silently empty.
        Outcome value(ConfigValue value, ConfigPath path) {
            return switch (value) {
                case ConfigString text -> fromText(text, path);
                case ConfigReference reference -> target(reference, path, !reference.optional());
                case ConfigCoalesce coalesce -> fallback(coalesce, path);
                case ConfigExpression expression -> expression(expression, path);
                case ConfigSeq seq -> sequence(seq, path);
                case ConfigMap map -> mapping(map, path);
                case ConfigInt ignored -> new Outcome.Value(value);
                case ConfigFloat ignored -> new Outcome.Value(value);
                case ConfigBool ignored -> new Outcome.Value(value);
                case ConfigTimestamp ignored -> new Outcome.Value(value);
                case ConfigBinary ignored -> new Outcome.Value(value);
            };
        }

        private Outcome fromText(ConfigString text, ConfigPath path) {
            ConfigResult<ConfigValue> parsed = parse(path, text.value(), sourceOf(path));
            if (parsed instanceof ConfigResult.ErrorResult<ConfigValue> bad) {
                return new Outcome.Problem(bad.error());
            }
            ConfigValue value = ((ConfigResult.ValueResult<ConfigValue>) parsed).value();
            return value.equals(text) ? new Outcome.Value(text) : value(value, path);
        }

        // `${x}`: a missing target is an error. `${?x}` AND every side of `??`: a missing target means
        // "contributed nothing" - in `??` the left-hand side is optional by definition, because otherwise the
        // operator would have no use.
        private Outcome target(ConfigReference reference, ConfigPath path, boolean required) {
            ConfigPath goal = reference.target();
            int cycleStart = stack.indexOf(goal);
            if (cycleStart >= 0) {
                return cycle(cycleStart, goal);
            }
            stack.add(goal);
            Outcome found = lookup(goal);
            stack.remove(stack.size() - 1);
            if (found instanceof Outcome.YieldsAbsent && required) {
                return new Outcome.Problem(new ConfigError.UnresolvedReference(path, goal, sourceOf(path)));
            }
            return found;
        }

        /** A side of `??` - a missing value on either side is a normal state here, not an error. */
        private Outcome side(ConfigValue value, ConfigPath path) {
            if (value instanceof ConfigReference reference) {
                return target(reference, path, false);
            }
            // A LITERAL from the form of the operand: it carries its text as written, so the quotes of a text
            // literal are stripped here and the digits of a number go as they are - the cast of the field reads
            // them with the notation the declaration states.
            String text = value.display();
            return new Outcome.Value(ConfigString.of(isQuoted(text) ? text.substring(1, text.length() - 1) : text));
        }

        /**
         * The type the result of `??` must have: the declaration of the path in code, or - for a key outside the
         * schema - the type its statement gives. A path with neither has no expected type, and then there is
         * nothing to compare against (the read stays as it was before).
         */
        private Optional<ConfigType> expectedType(ConfigPath path) {
            if (definition != null) {
                Optional<ConfigField> field = definition.field(path);
                if (field.isPresent()) {
                    return Optional.of(field.get().type());
                }
            }
            return view.entry(path).flatMap(entry -> entry.declaration().type());
        }

        /**
         * The type of ONE operand. A literal states it by its form; an address takes it from the declaration in
         * code, and for a key outside the schema from its statement - and `str` when it states nothing, because
         * that is what a key outside the schema is.
         */
        private ConfigType typeOf(ConfigValue operand) {
            if (operand instanceof ConfigReference reference) {
                if (definition != null) {
                    Optional<ConfigField> field = definition.field(reference.target());
                    if (field.isPresent()) {
                        return field.get().type();
                    }
                }
                return view.entry(reference.target())
                    .flatMap(entry -> entry.declaration().type()).orElse(ConfigType.STR);
            }
            return literalType(operand.display());
        }

        /** Whether the operand is an address that NOTHING states - then it can never deliver a value. */
        private boolean leadsNowhere(ConfigValue operand) {
            if (!(operand instanceof ConfigReference reference)) {
                return false;
            }
            ConfigPath target = reference.target();
            boolean declared = definition != null && definition.field(target).isPresent();
            return !declared && view.entry(target).isEmpty();
        }

        /**
         * Every operand of `??` must fit the type of the value, and the fallback must be able to deliver:
         * a fallback of another type (`str` where the field is `int`) or an address that nothing states is an
         * ERROR, because `??` would otherwise hand over the layer below (or nothing) in silence.
         */
        private Optional<ConfigError> unusableFallback(ConfigCoalesce coalesce, ConfigPath path) {
            Optional<ConfigType> expected = expectedType(path);
            if (expected.isEmpty()) {
                return Optional.empty();
            }
            for (ConfigValue operand : new ConfigValue[] {coalesce.left(), coalesce.right()}) {
                ConfigType given = typeOf(operand);
                if (given != expected.get()) {
                    String why = leadsNowhere(operand)
                        ? "it is an address nothing states - its type is `" + given.yamlName()
                            + "`, and this value is `" + expected.get().yamlName() + "`"
                        : operand instanceof ConfigReference
                            ? "it is an address of type `" + given.yamlName() + "`, and this value is `"
                                + expected.get().yamlName() + "`"
                            : "its form makes it `" + given.yamlName() + "`, and this value is `"
                                + expected.get().yamlName() + "`";
                    return Optional.of(new ConfigError.UnusableFallback(path, operand.display(), why,
                        sourceOf(path)));
                }
            }
            return Optional.empty();
        }

        // A cycle: the error carries ALL its paths, and the report shows it ONCE - one mistake, however many
        // participants it has.
        //
        // Paths are stored canonically: the cycle is rotated to its smallest path, because the same cycle
        // discovered from another participant is the same cycle. Otherwise `[a, b, a]` and `[b, a, b]` would
        // be two different problems and the deduplication in the report would have nothing to catch.
        private Outcome cycle(int cycleStart, ConfigPath goal) {
            List<ConfigPath> paths = new ArrayList<>(stack.subList(cycleStart, stack.size()));
            paths.add(goal);
            paths = canonical(paths);
            ConfigError.Cycle problem = new ConfigError.Cycle(paths, sourceOf(goal));
            for (ConfigPath participant : paths) {
                memo.put(participant, new Outcome.Problem(problem));
            }
            return new Outcome.Problem(problem);
        }

        /** Rotates the cycle so that it starts at the smallest path - the order within the cycle is kept. */
        private static List<ConfigPath> canonical(List<ConfigPath> cycle) {
            List<ConfigPath> members = cycle.subList(0, cycle.size() - 1);
            int start = 0;
            for (int index = 1; index < members.size(); index++) {
                if (members.get(index).canonical().compareTo(members.get(start).canonical()) < 0) {
                    start = index;
                }
            }
            List<ConfigPath> rotated = new ArrayList<>();
            for (int offset = 0; offset <= members.size(); offset++) {
                rotated.add(members.get((start + offset) % members.size()));
            }
            return rotated;
        }

        // `??` descends ONLY on `Absent`/"contributed nothing"; an error on the left-hand side propagates as an
        // error, because swallowing it would turn a typo into a silent default value.
        private Outcome fallback(ConfigCoalesce coalesce, ConfigPath path) {
            Optional<ConfigError> unusable = unusableFallback(coalesce, path);
            if (unusable.isPresent()) {
                return new Outcome.Problem(unusable.get());
            }
            Outcome left = side(coalesce.left(), path);
            if (left instanceof Outcome.Value value) {
                trace.add(new Decision(coalesce, left, Optional.empty(), Side.LEFT,
                    "the left-hand side exists (`" + value.value().display() + "`) -> the right-hand side was not checked"));
                return left;
            }
            if (left instanceof Outcome.Problem problem) {
                trace.add(new Decision(coalesce, left, Optional.empty(), Side.LEFT,
                    "the left-hand side is an error (`" + problem.error().kind() + "`) -> `??` does NOT swallow it"));
                return problem;
            }
            Outcome right = side(coalesce.right(), path);
            if (right instanceof Outcome.Value value) {
                trace.add(new Decision(coalesce, left, Optional.of(right), Side.RIGHT,
                    "there was NO left-hand side (`Absent`) -> the right-hand side was taken (`" + value.value().display() + "`)"));
                return right;
            }
            // The right-hand side was TAKEN and delivered nothing: a fallback that cannot deliver is not a
            // fallback, and answering with the layer below (or with the default from the code) would hide a
            // typo in a name. It is an error - for `str` too, where "it fits the type" is not enough.
            ConfigError nothing = new ConfigError.UnusableFallback(path, coalesce.right().display(),
                "it was taken and delivered nothing - a fallback must deliver a value", sourceOf(path));
            trace.add(new Decision(coalesce, left, Optional.of(right), Side.RIGHT,
                "the fallback was taken and delivered nothing -> an ERROR (a fallback must deliver)"));
            return new Outcome.Problem(nothing);
        }

        /** An expression: the parts in order; an optional part with no target makes the WHOLE value "nothing". */
        private Outcome expression(ConfigExpression expression, ConfigPath path) {
            StringBuilder text = new StringBuilder();
            Outcome absent = null;
            for (ConfigExpression.Content content : contents(expression)) {
                if (content instanceof ConfigExpression.Text literal) {
                    text.append(literal.text());
                    continue;
                }
                Outcome found = content instanceof ConfigExpression.Ref ref
                    ? value(ref.reference(), path)
                    : value(((ConfigExpression.Coalesce) content).coalesce(), path);
                if (found instanceof Outcome.Value value) {
                    text.append(value.value().display());
                } else if (found instanceof Outcome.Problem problem) {
                    return problem;
                } else {
                    absent = found; // an optional part with no target: the value is missing as a whole
                }
            }
            return absent == null ? new Outcome.Value(ConfigString.of(text.toString())) : absent;
        }

        private List<ConfigExpression.Content> contents(ConfigExpression expression) {
            List<ConfigExpression.Content> contents = new ArrayList<>();
            expression.parts().values().forEach(part -> contents.add(part.content()));
            return contents;
        }

        /** A list: an element that contributes nothing VANISHES; its address stays empty. */
        private Outcome sequence(ConfigSeq seq, ConfigPath path) {
            java.util.Map<Integer, ConfigValue> kept = new java.util.LinkedHashMap<>();
            Outcome vanished = null;
            for (int address : seq.addresses()) {
                ConfigPath elementPath = path.child(String.valueOf(address));
                Outcome found = value(seq.at(address).orElseThrow(), elementPath);
                if (found instanceof Outcome.Problem problem) {
                    return problem;
                }
                if (found instanceof Outcome.Value resolved) {
                    kept.put(address, resolved.value());
                } else {
                    // An optional element with no target: it is not there, and its ADDRESS stays empty —
                    // nothing shifts, so `l1.3` is still `l1.3` after `l1.2` vanished.
                    vanished = found;
                }
            }
            if (kept.isEmpty() && vanished != null) {
                // Everything vanished: the whole value contributes nothing, exactly as an expression with no
                // parts left does — the read descends, a default (or a layer below) answers.
                return vanished;
            }
            return new Outcome.Value(ConfigSeq.of(kept));
        }

        /**
         * A map: a key may simply not exist, so "contributed nothing" removes the ENTRY. When EVERY entry went
         * away, the whole value contributes nothing rather than turning into an empty map — the same rule a list
         * follows ({@code ConfigSeq}) and the same one an expression follows when no part is left.
         */
        private Outcome mapping(ConfigMap map, ConfigPath path) {
            Map<String, ConfigValue> entries = new LinkedHashMap<>();
            Outcome vanished = null;
            for (Map.Entry<String, ConfigValue> entry : map.entries().entrySet()) {
                ConfigPath entryPath = path.child(entry.getKey());
                Outcome found = value(entry.getValue(), entryPath);
                if (found instanceof Outcome.Problem problem) {
                    return problem;
                }
                if (found instanceof Outcome.Value resolved) {
                    entries.put(entry.getKey(), resolved.value());
                } else {
                    vanished = found;
                }
            }
            if (entries.isEmpty() && vanished != null) {
                // Every entry contributed nothing: the value contributes nothing (NOT an empty map — an empty
                // map would be a statement, and emptiness is an error).
                return vanished;
            }
            return new Outcome.Value(ConfigMap.of(entries));
        }
    }
}
