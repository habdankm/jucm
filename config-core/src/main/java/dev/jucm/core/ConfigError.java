package dev.jucm.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A configuration problem: a closed set of kinds. Every error answers five questions - <b>what</b>
 * failed ({@link #kind()}), <b>where</b> ({@link #source()}), <b>which path</b>, <b>what value</b> and
 * <b>what message</b> ({@link #message()}).
 *
 * <p>Path and value are not universal - {@link ParseFailed} does not concern a path, because a source
 * that cannot be read is never read as a whole - so {@link #pathOf} and {@link #valueOf} ask about them
 * with an exhaustive {@code switch} over the closed set. Adding an error kind therefore **will not
 * compile** until it is decided what that error has to say about path and value.
 *
 * <p>Neither {@code null} nor a blank value is ever used as a failure signal: an absence is
 * {@link Absent}, an emptiness is {@link NullValue}, and the two never have to be told apart by the
 * shape of a carried value.
 *
 * @since 1.0
 */
public sealed interface ConfigError permits
    ConfigError.Absent, ConfigError.UnresolvedReference, ConfigError.UnresolvedExpression,
    ConfigError.Cycle, ConfigError.CastFailed, ConfigError.ValidationFailed, ConfigError.ParseFailed,
    ConfigError.InvalidSyntax, ConfigError.UnusableStatement, ConfigError.UnknownPath, ConfigError.NullValue,
    ConfigError.FileNotExist, ConfigError.UnusedAuxiliary, ConfigError.TransformFailed, ConfigError.RuleMismatch,
    ConfigError.UnusableRule, ConfigError.UnusableFallback {

    /**
     * The problem kind: what failed. A closed set - a new constant is added together with the decision
     * on what that error says about path and value.
     *
     * @since 1.0
     */
    enum Kind {
        /** No value in any layer. */
        ABSENT,
        /** A reference points at a path that leads to no value. */
        UNRESOLVED_REFERENCE,
        /** An expression cannot be computed. */
        UNRESOLVED_EXPRESSION,
        /** References form a loop. */
        CYCLE,
        /** The value cannot be cast onto the declared type. */
        CAST_FAILED,
        /** The value breaks a rule declared for the field. */
        VALIDATION_FAILED,
        /** A source cannot be read. */
        PARSE_FAILED,
        /** Broken interpolation syntax. */
        INVALID_SYNTAX,
        /** The path cannot be followed. */
        UNKNOWN_PATH,
        /** A source delivered nothing, or delivered an empty value. */
        NULL_VALUE,
        /** An unreadable binary source. */
        FILE_NOT_EXIST,
        /** A key outside the schema that nothing leads to. */
        UNUSED_AUXILIARY,
        /** The stated notation does not match the value. */
        TRANSFORM_FAILED,
        /** A rule does not apply to the type of the field. */
        RULE_MISMATCH
    }

    /**
     * Returns the kind of this problem.
     *
     * @return the kind, never {@code null}
     */
    Kind kind();

    /**
     * Returns where the problem happened: the layer and the address within it.
     *
     * @return the origin of the value, never {@code null}
     */
    ConfigSource source();

    /**
     * Returns a message for a human. The path, the value and the source are not part of it - they are
     * added by {@link #report()}.
     *
     * @return the message, never {@code null}
     */
    String message();

    /**
     * Returns the path this problem concerns.
     *
     * @param error problem to inspect; must not be {@code null}
     * @return the path, or empty when the problem does not concern a single path (for example a whole
     *         source that cannot be read)
     */
    static Optional<ConfigPath> pathOf(ConfigError error) {
        return switch (error) {
            case Absent e -> Optional.of(e.path());
            case UnresolvedReference e -> Optional.of(e.path());
            case UnresolvedExpression e -> Optional.of(e.path());
            case Cycle e -> Optional.of(e.paths().get(0));
            case CastFailed e -> Optional.of(e.path());
            case ValidationFailed e -> Optional.of(e.path());
            case InvalidSyntax e -> Optional.of(e.path());
            case UnusableStatement e -> Optional.of(e.path());
            case UnusableRule e -> Optional.of(e.path());
            case UnusableFallback e -> Optional.of(e.path());
            case NullValue e -> Optional.of(e.path());
            case UnknownPath e -> Optional.of(e.path());
            case FileNotExist e -> Optional.of(e.path());
            case UnusedAuxiliary e -> Optional.of(e.path());
            case TransformFailed e -> Optional.of(e.path());
            case RuleMismatch e -> Optional.of(e.path());
            case ParseFailed e -> Optional.empty();
        };
    }

    /**
     * Returns the value that caused this problem.
     *
     * @param error problem to inspect; must not be {@code null}
     * @return the offending value, or empty for the kinds where no single value is the culprit
     */
    static Optional<ConfigValue> valueOf(ConfigError error) {
        return switch (error) {
            case CastFailed e -> Optional.of(e.actual());
            case ValidationFailed e -> Optional.of(e.actual());
            case TransformFailed e -> Optional.of(e.actual());
            default -> Optional.empty();
        };
    }

    /**
     * Returns the full report as one string: kind, message, path, value, source.
     *
     * @return a line for a human; the path and the value appear only when this error carries them
     */
    default String report() {
        StringBuilder text = new StringBuilder(kind().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'))
            .append(": ").append(message());
        pathOf(this).ifPresent(found -> text.append(" [path `").append(found.canonical()).append("`]"));
        valueOf(this).ifPresent(raw -> text.append(" [value `").append(raw.display()).append("`]"));
        return text.append(" [source ").append(source().display()).append("]").toString();
    }

    /**
     * A constraint rule makes no sense for the field type - a DECLARATION error, not a configuration
     * one.
     *
     * <p>An ordering condition ({@code <20}, a range in a chain) compares a typed value. If it compared
     * the notation instead, the result would be silently inverted, because {@code "10" < "2"} is true
     * for text. So a rule that has nothing to compare is reported as a contradiction in the declaration
     * instead of producing an answer nobody can trust - and that is the only way such a mistake becomes
     * visible at all.
     *
     * @param path path of the field whose declaration is contradictory
     * @param rule the rule as it was stated in the declaration
     * @param given type of the value the rule was applied to
     * @param source where that value came from
     * @since 1.0
     */
    record RuleMismatch(ConfigPath path, String rule, ConfigType given, ConfigSource source)
        implements ConfigError {

        /** Validates the components — every one of them is required. */
        public RuleMismatch {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(rule, "rule");
            Objects.requireNonNull(given, "given");
            Objects.requireNonNull(source, "source");
        }

        @Override
        public Kind kind() {
            return Kind.RULE_MISMATCH;
        }

        @Override
        public String message() {
            return "rule `" + rule + "` does not apply to type `" + given.yamlName()
                + "` - this is a DECLARATION error of the field, not a configuration value";
        }
    }

    /**
     * A rule that cannot judge this field — an operand of a type the field can never have.
     *
     * <p>The kind is {@link Kind#RULE_MISMATCH}, because the rule and the declaration contradict each other.
     * The message says <b>which operand and why</b>: {@code in[http,https]} on an {@code int}, or a parameter
     * that is a {@code str} where the field is an {@code int}, compares apples with cars — even when its text
     * happens to fit ({@code 9000} written in a {@code str}).
     *
     * @param path path of the field the rule stands on
     * @param rule the rule as it was written
     * @param why what makes the rule unusable for this type
     * @param source where the rule came from
     * @since 1.0
     */
    record UnusableRule(ConfigPath path, String rule, String why, ConfigSource source) implements ConfigError {

        /** Validates the components — every one of them is required. */
        public UnusableRule {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(rule, "rule");
            Objects.requireNonNull(why, "why");
            Objects.requireNonNull(source, "source");
        }

        @Override
        public Kind kind() {
            return Kind.RULE_MISMATCH;
        }

        @Override
        public String message() {
            return "rule `" + rule + "` cannot judge this field: " + why;
        }
    }


    /**
     * An operand of `??` that cannot do its job: it does not fit the type of the value, or it was taken as the
     * fallback and delivered NOTHING.
     *
     * <p>Without this check `??` hands over whatever the layer below says - or nothing at all - in silence, and
     * a typo in a name passes for a default. Its kind is {@link Kind#RULE_MISMATCH}, because the operand and the
     * declaration contradict each other.
     *
     * @param path path of the value that carries `??`
     * @param fallback the operand, exactly as the source wrote it
     * @param why what is wrong with it, in words
     * @param source where the value came from
     * @since 1.0
     */
    record UnusableFallback(ConfigPath path, String fallback, String why, ConfigSource source)
        implements ConfigError {

        /** Validates the components — every one of them is required. */
        public UnusableFallback {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(fallback, "fallback");
            Objects.requireNonNull(why, "why");
            Objects.requireNonNull(source, "source");
        }

        @Override
        public Kind kind() {
            return Kind.RULE_MISMATCH;
        }

        @Override
        public String message() {
            return "fallback `" + fallback + "` cannot be used here - " + why;
        }
    }

    /**
     * No value in any layer.
     *
     * <p>The source is {@link ConfigSource#application()}, because no layer contributed the value and
     * therefore there is nothing to point at.
     *
     * @param path path of the missing value
     * @since 1.0
     */
    record Absent(ConfigPath path) implements ConfigError {
        /** Validates the components — every one of them is required. */
        public Absent {
            Objects.requireNonNull(path, "path");
        }

        @Override
        public Kind kind() {
            return Kind.ABSENT;
        }

        @Override
        public ConfigSource source() {
            return ConfigSource.application();
        }

        @Override
        public String message() {
            return "no value in any layer";
        }
    }

    /**
     * The value exists, but it cannot be cast onto the declared type.
     *
     * <p>{@code detail} carries the reason from the **type's default notation** - when that notation was
     * the one that refused to read the value, for example {@code 1,000} without a stated locale. Without
     * it the message only says "wrong type" and the user does not know what is missing. The kind stays
     * {@link Kind#CAST_FAILED}, because the default notation is **cast** behaviour, so its failure
     * belongs here too.
     *
     * @param path path of the value that could not be cast
     * @param expected type declared for the field
     * @param actual the value as it arrived
     * @param source where that value came from
     * @param detail reason given by the type's default notation; empty when the reason is in the type
     *        itself
     * @since 1.0
     */
    record CastFailed(ConfigPath path, ConfigType expected, ConfigValue actual, ConfigSource source,
                      Optional<String> detail) implements ConfigError {

        /** Validates the components — every one of them is required. */
        public CastFailed {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(expected, "expected");
            Objects.requireNonNull(actual, "actual");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(detail, "detail");
        }

        /**
         * Creates a failure without a detail from the transformer.
         *
         * @param path path of the value that could not be cast
         * @param expected type declared for the field
         * @param actual the value as it arrived
         * @param source where that value came from
         */
        public CastFailed(ConfigPath path, ConfigType expected, ConfigValue actual, ConfigSource source) {
            this(path, expected, actual, source, Optional.empty());
        }

        @Override
        public Kind kind() {
            return Kind.CAST_FAILED;
        }

        @Override
        public String message() {
            return detail.map(reason -> baseMessage() + " - " + reason).orElseGet(this::baseMessage);
        }

        private String baseMessage() {
            // The notation is strict: the `int`/`float` asymmetry is named plainly, because it is the most
            // common mistake and the message must say what is expected - not just "wrong type".
            if (expected == ConfigType.FLOAT && actual instanceof ConfigInt) {
                return "expected a notation with a decimal point, e.g. `1.0` - not `1`";
            }
            if (expected == ConfigType.INT && actual instanceof ConfigFloat) {
                return "expected an integer notation, e.g. `2` - not `2.0`";
            }
            if (expected == ConfigType.TIMESTAMP) {
                return "expected the format `" + ConfigTimestamp.DEFAULT_FORMAT
                    + "` (or a form with an explicit offset) - another format needs a formatter in the declaration";
            }
            // A reference has no type before resolution, so `type()` must not be called here.
            String actualType = actual instanceof ConfigReference ? "reference" : actual.type().yamlName();
            return "expected type `" + expected.yamlName() + "`, but the incoming value has type `"
                + actualType + "`";
        }
    }

    /**
     * The type matches, the constraint does not.
     *
     * <p>It carries the value, because on a rule violation the value is the culprit - without it the
     * message says "does not satisfy the rule" and leaves the user guessing which value was checked.
     *
     * @param path path of the value that broke the rule
     * @param rule the rule as it was stated in the declaration
     * @param actual the value that was checked
     * @param source where that value came from
     * @since 1.0
     */
    record ValidationFailed(ConfigPath path, String rule, ConfigValue actual, ConfigSource source)
        implements ConfigError {

        /** Validates the components — every one of them is required. */
        public ValidationFailed {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(rule, "rule");
            Objects.requireNonNull(actual, "actual");
            Objects.requireNonNull(source, "source");
        }

        @Override
        public Kind kind() {
            return Kind.VALIDATION_FAILED;
        }

        @Override
        public String message() {
            return "does not satisfy the rule: " + rule;
        }
    }

    /**
     * The source cannot be read.
     *
     * <p>There is no path, because the problem concerns the whole source rather than one value in it.
     *
     * @param source the source that could not be read
     * @param cause reason reported while reading it
     * @since 1.0
     */
    record ParseFailed(ConfigSource source, String cause) implements ConfigError {
        /** Validates the components — every one of them is required. */
        public ParseFailed {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(cause, "cause");
        }

        @Override
        public Kind kind() {
            return Kind.PARSE_FAILED;
        }

        @Override
        public String message() {
            return "the source cannot be read: " + cause;
        }
    }

    /**
     * Broken {@code ${...}} syntax.
     *
     * @param path path of the entry holding the broken notation
     * @param expression the offending text
     * @param source where that entry came from
     * @since 1.0
     */
    record InvalidSyntax(ConfigPath path, String expression, ConfigSource source) implements ConfigError {
        /** Validates the components — every one of them is required. */
        public InvalidSyntax {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(source, "source");
        }

        @Override
        public Kind kind() {
            return Kind.INVALID_SYNTAX;
        }

        @Override
        public String message() {
            return "broken interpolation syntax: `" + expression + "`";
        }
    }

    /**
     * A statement from a source that cannot be used for the entry it was made about — and the message says
     * <b>why</b>.
     *
     * <p>The kind is {@link Kind#INVALID_SYNTAX}, so the code of the run does not change; what changes is the
     * text. Blaming the interpolation syntax for a rule or a notation would send the reader to a place where
     * nothing is broken.
     *
     * @param path path of the entry the statement speaks about
     * @param statement the statement as it was written (for example {@code ___TCONF=upper})
     * @param why what makes it unusable (for example a type with no notation)
     * @param source where the statement came from
     * @since 1.0
     */
    record UnusableStatement(ConfigPath path, String statement, String why, ConfigSource source)
        implements ConfigError {

        /** Validates the components — every one of them is required. */
        public UnusableStatement {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(statement, "statement");
            Objects.requireNonNull(why, "why");
            Objects.requireNonNull(source, "source");
        }

        @Override
        public Kind kind() {
            return Kind.INVALID_SYNTAX;
        }

        @Override
        public String message() {
            return "the statement `" + statement + "` cannot be used here: " + why;
        }
    }

    /**
     * {@code ${x}} points at something that is in no layer.
     *
     * @param path path of the entry holding the reference
     * @param target the referenced path that leads to no value
     * @param source where the referring entry came from
     * @since 1.0
     */
    record UnresolvedReference(ConfigPath path, ConfigPath target, ConfigSource source)
        implements ConfigError {

        /** Validates the components — every one of them is required. */
        public UnresolvedReference {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(source, "source");
        }

        @Override
        public Kind kind() {
            return Kind.UNRESOLVED_REFERENCE;
        }

        @Override
        public String message() {
            return "reference `${" + target.canonical() + "}` leads to no value";
        }
    }

    /**
     * An expression cannot be computed; it does not occur in the default pipeline of this version.
     *
     * @param path path of the entry holding the expression
     * @param expression the offending text
     * @param source where that entry came from
     * @since 1.0
     */
    record UnresolvedExpression(ConfigPath path, String expression, ConfigSource source)
        implements ConfigError {

        /** Validates the components — every one of them is required. */
        public UnresolvedExpression {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(source, "source");
        }

        @Override
        public Kind kind() {
            return Kind.UNRESOLVED_EXPRESSION;
        }

        @Override
        public String message() {
            return "the expression cannot be computed: `" + expression + "`";
        }
    }

    /**
     * References in a loop.
     *
     * <p>The error carries **all** paths of the cycle, and the report shows the cycle **once** - however
     * many participants it has, it is one mistake. The list is immutable.
     *
     * <p>An empty list of paths is rejected: a cycle without paths says nothing.
     *
     * @param paths the paths forming the cycle, in cycle order; must not be empty
     * @param source where the reference that closed the cycle came from
     * @since 1.0
     */
    record Cycle(List<ConfigPath> paths, ConfigSource source) implements ConfigError {
        /**
         * Validates the components — the list of paths is required and must not be empty.
         */
        public Cycle {
            Objects.requireNonNull(paths, "paths");
            if (paths.isEmpty()) {
                throw new IllegalArgumentException("a cycle without paths says nothing");
            }
            paths = List.copyOf(paths);
            Objects.requireNonNull(source, "source");
        }

        @Override
        public Kind kind() {
            return Kind.CYCLE;
        }

        @Override
        public String message() {
            return "references in a loop: " + paths.stream().map(ConfigPath::canonical).toList();
        }
    }

    /**
     * The source stated there is no value: an explicitly empty argument ({@code --host ""}), an empty
     * collection, or an adapter that declared it delivered nothing at all. A variable that is present with
     * no value is NOT this case — the environment source ignores it and the layers below answer.
     *
     * <p><b>Empty means an error, with no exception for the notation format</b>: an empty list
     * ({@code []}) and an empty map ({@code {}}) are the same kind of problem as an empty scalar. The
     * kind is one, but the message **MUST** say that it is about a **collection** - otherwise the user
     * looks for an empty scalar that is not there, and does not know that "I want no elements" is
     * expressed by omitting the key. That is what {@link Shape} is for.
     *
     * @param path path of the value that is empty
     * @param source where that empty value came from
     * @param shape form that was empty, so that the message can name it
     * @since 1.0
     */
    record NullValue(ConfigPath path, ConfigSource source, Shape shape) implements ConfigError {

        /**
         * Which form can be empty - the message depends on it, not the error kind.
         *
         * @since 1.0
         */
        enum Shape {
            VALUE("empty value", "remove the key/variable or provide a value"),
            LIST("empty list", "remove the key or provide elements"),
            MAP("empty map", "remove the key or provide elements");

            private final String what;
            private final String how;

            Shape(String what, String how) {
                this.what = what;
                this.how = how;
            }

            String what() {
                return what;
            }

            String how() {
                return how;
            }
        }

        /** Validates the components — every one of them is required. */
        public NullValue {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(shape, "shape");
        }

        /**
         * Creates an empty-scalar error - the most common case: an explicitly empty argument
         * ({@code --host ""}), an empty collection, a key with no value in the file.
         *
         * @param path path of the value that is empty
         * @param source where that empty value came from
         */
        public NullValue(ConfigPath path, ConfigSource source) {
            this(path, source, Shape.VALUE);
        }

        @Override
        public Kind kind() {
            return Kind.NULL_VALUE;
        }

        @Override
        public String message() {
            return shape.what() + " - " + shape.how();
        }
    }

    /**
     * The path cannot be followed - for example an index beyond the end of a sequence.
     *
     * @param path the path that leads nowhere
     * @param source where the container it was looked up in came from
     * @since 1.0
     */
    record UnknownPath(ConfigPath path, ConfigSource source) implements ConfigError {
        /** Validates the components — every one of them is required. */
        public UnknownPath {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(source, "source");
        }

        @Override
        public Kind kind() {
            return Kind.UNKNOWN_PATH;
        }

        @Override
        public String message() {
            return "unknown path";
        }
    }

    /**
     * An unreadable binary source.
     *
     * @param path path of the value the source was meant to provide
     * @param source the source that cannot be read
     * @param reason reason for the failure, when it can be determined
     * @since 1.0
     */
    record FileNotExist(ConfigPath path, ConfigSource source, String reason) implements ConfigError {
        /** Validates the components — every one of them is required. */
        public FileNotExist {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public Kind kind() {
            return Kind.FILE_NOT_EXIST;
        }

        @Override
        public String message() {
            return "unreadable binary source: " + reason;
        }
    }

    /**
     * A key outside the schema that nothing leads to; an error in {@code strict} mode.
     *
     * @param path path of the unused key
     * @param source where that key came from
     * @since 1.0
     */
    record UnusedAuxiliary(ConfigPath path, ConfigSource source) implements ConfigError {
        /** Validates the components — every one of them is required. */
        public UnusedAuxiliary {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(source, "source");
        }

        @Override
        public Kind kind() {
            return Kind.UNUSED_AUXILIARY;
        }

        @Override
        public String message() {
            return "a key outside the schema that nothing leads to";
        }
    }

    /**
     * The stated notation does not match the value: the transformation could not be carried out, so the
     * value will never become typed.
     *
     * <p><b>A kind of its own rather than {@link ParseFailed}</b>: {@link ParseFailed} describes a source
     * that cannot be read and by design has no path, while here the problem concerns **one value under a
     * known path** - a message without a path does not say what to fix. {@link CastFailed} does not fit
     * either: the type matches, the **written notation** failed, and that is a different pipeline stage.
     *
     * <p>The error carries **everything needed to fix it**: the transformer name, its configuration, the
     * raw value, the reason from the transformer, the path and the source. Without the name and the
     * configuration the user hears "I do not understand this value" and does not know which notation was
     * expected.
     *
     * @param path path of the value that did not survive the transformation
     * @param transformer name of the transformer that refused the value
     * @param transformerConfig configuration the transformer was given; empty when it took none
     * @param actual the value as it arrived
     * @param reason reason reported by the transformer
     * @param source where that value came from
     * @since 1.0
     */
    record TransformFailed(ConfigPath path, String transformer, Optional<String> transformerConfig,
                           ConfigValue actual, String reason, ConfigSource source) implements ConfigError {

        /** Validates the components — every one of them is required. */
        public TransformFailed {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(transformer, "transformer");
            Objects.requireNonNull(transformerConfig, "transformerConfig");
            Objects.requireNonNull(actual, "actual");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(source, "source");
        }

        /**
         * Creates a failure of a transformer that took no configuration - the most common case, since
         * the transformer then works in its default notation.
         *
         * @param path path of the value that did not survive the transformation
         * @param transformer name of the transformer that refused the value
         * @param actual the value as it arrived
         * @param reason reason reported by the transformer
         * @param source where that value came from
         */
        public TransformFailed(ConfigPath path, String transformer, ConfigValue actual, String reason,
                               ConfigSource source) {
            this(path, transformer, Optional.empty(), actual, reason, source);
        }

        @Override
        public Kind kind() {
            return Kind.TRANSFORM_FAILED;
        }

        @Override
        public String message() {
            StringBuilder text = new StringBuilder("transformer `").append(transformer).append('`');
            transformerConfig.ifPresent(config -> text.append(" (config `").append(config).append("`)"));
            return text.append(" did not let the value through: ").append(reason).toString();
        }
    }
}
