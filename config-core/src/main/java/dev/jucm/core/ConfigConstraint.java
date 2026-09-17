package dev.jucm.core;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A rule imposed on a value **after a successful cast**.
 *
 * <p>The two levels of validation are separated on purpose: {@link ConfigError.CastFailed} means "the
 * value does not fit the declared type", and {@link ConfigError.ValidationFailed} means "the type
 * matches, the rule does not". A constraint therefore receives an **already typed** value.
 *
 * <p><b>There is one form: a CHAIN OF CONDITIONS IN A SINGLE TEXT.</b> The same string is written in
 * Java, in ENV, in CLI and in YAML:
 *
 * <pre>{@code
 * ConfigConstraint.Const("<2&>0")
 * }</pre>
 *
 * <p>Conditions are **recognised by their symbol** (one {@code switch}) and are never built from
 * expressions — there is no expression parser, no arithmetic and no grouping brackets. Evaluation runs
 * <b>strictly from left to right</b>: <b>there is no precedence</b>, so {@code '<2&>0' | '!=7'} means
 * {@code ((<2 & >0) | !=7)}; an operator is both the **separator** and the decision about the logic.
 * Every element after the first therefore <b>MUST</b> start with {@code &} or {@code |}.
 *
 * <p><b>The dictionary.</b> An operator has an EXPLICIT symbol — a list literal is not a condition,
 * because otherwise a list could not be compared with a list:
 *
 * <pre>{@code
 * <20  <=20  >1  >=1            order — the value is TYPED (numbers numerically, moments chronologically)
 * <'2030-01-01T00:00:00Z'       order on time: the operand in single quotes, canonical form
 * =x   !=x                      equality of the NOTATION (display()); '=a b' keeps spaces
 * =[1,2,3]   !=[1,2,3]          the value is a LIST of that shape
 * ={a:1}     !={a:1}            the value is a MAP of that shape
 * in[1,3,5]  !in[1,3,5]         one of the list / none of the list
 * c[1,3,5]   !c[1,3,5]          the value CONTAINS those elements (a sequence) or those keys (a map)
 * c{a:1}     !c{a:1}            the map CONTAINS those entries
 * keys[1,3]  values[1,3]        the keys of a map are a SUBSET of the list / every value is IN the list
 * ~`body`   !~`body`            a regular expression / it is not true that it matches (the backtick closes the body)
 * }</pre>
 *
 * <p>The notation {@code \<char>} protects a character inside an operand ({@code '=a\&b'} is a single
 * comparison).
 *
 * <p><b>A comparison runs on the TYPE OF THE VALUE</b> ({@code 11 > 2}, but {@code "11" < "2"}): order
 * reads a number as a number and a moment as a moment, never the notation. That is why {@code int},
 * {@code float} and {@code timestamp} are separate value types.
 *
 * <p><b>A rule belongs to a TYPE</b> — the type of the field it stands on, taken from the DECLARATION (the
 * code is the source of truth). {@link #forType(ConfigType)} binds a rule to that type, and then every
 * operand is checked against it: an operand that can never be a value of that type has nothing to compare,
 * so it is refused — that is how "apples with cars" stops being compared as text. A list or a map obliges its
 * <b>elements</b>, never the collection, because a list compared with a single value is nonsense. A rule
 * parsed WITHOUT a type is checked for SYNTAX only: that mode is for editors and for reading a source before
 * the type is known. An operand for order on time is written in the canonical form, because a rule does not
 * know the field's notation.
 *
 * <p><b>Two views on a value</b>: conditions of {@link Look#SHAPE} compare the **notation**
 * ({@link ConfigValue#display()}), and {@link Look#ORDER} compares the **typed value**. Order on text
 * is a **model error** ({@link ConfigError.RuleMismatch}), because on text {@code "10" < "2"} is true
 * and on numbers it is false. The same holds for {@code duration} and {@code uri}: after the cast they
 * are strings in canonical form, and a string has no order ({@code "10s"} &lt; {@code "9s"}, although
 * 10 s > 9 s) — so order on them is a model error, not a silent comparison of text.
 *
 * @since 1.0
 */
public sealed interface ConfigConstraint permits ConfigConstraint.Const {

    /**
     * How a condition reads a value — and what it requires of the value to make sense.
     *
     * @since 1.0
     */
    enum Look {
        /** Compares the NOTATION: lists, patterns, textual equality. Works for every type. */
        SHAPE,
        /** Compares the TYPED value — requires a number or a moment, because text would invert the result. */
        ORDER,
        /** Requires a collection: a sequence or a map (a list literal, "contains"). */
        COLLECTION,
        /** Requires a map: keys, values, entries. */
        MAP
    }

    /**
     * Returns the description of the rule for a message — the user has to know what is allowed.
     *
     * @return the chain exactly as it was written
     * @since 1.0
     */
    String rule();

    /**
     * Returns the references in the PARAMETERS of the rule ({@code <${min_port}}) — to be resolved by
     * the **same resolver** as values. Empty when the rule has no parameters.
     *
     * @return the references of the parameters, in order of appearance, without duplicates
     * @since 1.0
     */
    default java.util.List<String> parameters() {
        return java.util.List.of();
    }

    /**
     * Returns the rule with its parameters substituted by values — the template becomes a rule.
     * Returns {@code this} when nothing had to be substituted.
     *
     * @param resolved the parameter values, keyed by reference
     * @return a rule with no unresolved parameter, or {@code this}
     * @throws IllegalArgumentException if a substituted value does not form a valid operand (for
     *     example text where a number is expected); the caller reports this as an ENTRY error
     * @since 1.0
     */
    default ConfigConstraint withParameters(java.util.Map<String, String> resolved) {
        return this;
    }

    /**
     * Returns whether this value (already of the declared type) satisfies the rule.
     *
     * <p>Ask {@link #appliesTo} first: {@link Const} reads only the types its conditions can read, and
     * calling this for any other type raises a declaration error instead of returning {@code false}.
     *
     * @param value the typed value to check; must not be {@code null}
     * @return {@code true} when the value satisfies the rule
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalStateException if the type of the value is not one this rule reads (see
     *     {@link #appliesTo}), or when the rule still is an unresolved template
     * @since 1.0
     */
    boolean accepts(ConfigValue value);

    /**
     * Returns how the rule reads a value — the strictest requirement in the chain (see {@link Look}).
     *
     * @return the view of {@link Look} this rule needs
     * @since 1.0
     */
    Look look();

    /**
     * Returns whether the rule makes any sense for this value. {@code false} is a **model error**, not
     * a configuration one: the declaration contradicts the type of the field (for example {@code <20}
     * on a {@code str} field), so there is nothing to compare. A validator reports that as
     * {@link ConfigError.RuleMismatch} and checks no further.
     *
     * <p>The gate is computed for <b>all</b> conditions before the result is computed — otherwise
     * short-circuited {@code &&}/{@code ||} would hide a declaration error further along the chain.
     *
     * @param value the typed value to inspect; must not be {@code null}
     * @return {@code true} when the rule can read this value
     * @since 1.0
     */
    default boolean appliesTo(ConfigValue value) {
        return switch (look()) {
            case SHAPE -> true;
            case ORDER -> value instanceof ConfigInt || value instanceof ConfigFloat
                || value instanceof ConfigTimestamp;
            case COLLECTION -> value instanceof ConfigSeq || value instanceof ConfigMap;
            case MAP -> value instanceof ConfigMap;
        };
    }

    /**
     * Returns a constraint built from a chain of conditions ({@code "<2&>0"},
     * {@code "~`^https?://`&!=http://x"}).
     *
     * <p>The chain is parsed here, so a broken chain fails at the declaration and not at the first
     * value.
     *
     * @param chain the chain of conditions; must not be {@code null} or blank
     * @return a parsed constraint
     * @throws NullPointerException if {@code chain} is {@code null}
     * @throws IllegalArgumentException if the chain is empty, cannot be parsed, or breaks the rule
     *     "every element after the first starts with {@code &} or {@code |}"
     * @since 1.0
     */
    static Const Const(String chain) {
        return new Const(chain);
    }

    /**
     * Returns a constraint built from a chain **for a field of the given type** — the type is what makes the
     * chain usable or not, so it is part of the constraint and not something discovered later.
     *
     * <p>Every operand is checked against that type here: an operand that can never be a value of that type
     * (an order comparison on a `str`, {@code in[http,https]} on an `int`) is refused, instead of passing as
     * text and turning into a comparison of apples with cars. The type comes from the **declaration in code**,
     * which is the source of truth for a declared key.
     *
     * @param chain the chain of conditions; must not be {@code null} or blank
     * @param type the type the rule will judge; must not be {@code null}
     * @return a parsed constraint, bound to that type
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if the chain is broken, or an operand cannot be a value of that type —
     *     the message names the operand and says why
     * @since 1.0
     */
    static Const Const(String chain, ConfigType type) {
        return new Const(chain, type);
    }

    /**
     * Returns **why** this rule cannot judge a field of this type — empty when it can.
     *
     * <p>A rule always speaks about a value OF A TYPE: an operand that cannot be of that type has nothing to
     * compare. The reason is a sentence for the user, not a code — it names the operand and the type.
     *
     * @param type the type of the field the rule stands on; must not be {@code null}
     * @return the reason, or empty when the rule can work with that type
     * @since 1.0
     */
    default java.util.Optional<String> unusableFor(ConfigType type) {
        return java.util.Optional.empty();
    }

    /**
     * Returns whether the given parameter fills a **list** ({@code in[${allowed}]}) rather than standing for a
     * single value ({@code <${min}}). A list parameter brings the ELEMENTS, so what has to match the type are
     * its elements — a list is never compared with a single value.
     *
     * @param reference a reference from {@link #parameters()}; must not be {@code null}
     * @return true when the reference stands inside a list body
     * @since 1.0
     */
    default boolean fillsAList(String reference) {
        return false;
    }

    /**
     * Returns the same rule **bound to the type it will judge** — the type belongs to the rule, so a rule
     * without one is not usable in a typed model (see {@link #Const(String, ConfigType)}).
     *
     * @param type the type of the field the rule stands on; must not be {@code null}
     * @return a rule bound to that type
     * @throws IllegalArgumentException if an operand cannot be a value of that type
     * @since 1.0
     */
    default ConfigConstraint forType(ConfigType type) {
        return this;
    }

    /**
     * A chain of conditions: the list of recognised elements plus the operators that join them.
     *
     * <p>Parsing happens in the constructor, so a broken chain is a declaration error rather than a
     * surprise at the first value. The parser is flat: it reads an element (symbol + operand), then a
     * separator, and so on to the end — no tree, no grammar. Instances are immutable and equal when
     * their chains are equal.
     *
     * @since 1.0
     */
    final class Const implements ConfigConstraint {

        /** A parameter inside an operand: {@code ${reference}} — the whole operand, not a fragment. */
        private static final java.util.regex.Pattern PARAMETER =
            java.util.regex.Pattern.compile("\\$\\{([^{}]+)}");

        /** The logic joining the result so far with the next element. */
        private enum Logic {
            AND, OR
        }

        /** Order — {@code <}, {@code <=}, {@code >}, {@code >=}. Shared by numbers and time. */
        private enum Ord {
            LT("<"), LE("<="), GT(">"), GE(">=");

            private final String symbol;

            Ord(String symbol) {
                this.symbol = symbol;
            }

            boolean holds(int comparison) {
                return switch (this) {
                    case LT -> comparison < 0;
                    case LE -> comparison <= 0;
                    case GT -> comparison > 0;
                    case GE -> comparison >= 0;
                };
            }
        }

        /**
         * A single condition — recognised by its symbol, never built as a tree. The types are private:
         * only the chain exists outside, because the chain is the notation contract.
         */
        private sealed interface Element permits Element.Num, Element.Time, Element.Param, Element.Text,
            Element.Literal, Element.Membership, Element.Contains, Element.ContainsEntries, Element.Rx {

            Look look();

            boolean appliesTo(ConfigValue value);

            boolean accepts(ConfigValue value);

            String text();

            /**
             * Returns why this operand cannot be a value of the given type — empty when it can. An operand
             * that can never be such a value has nothing to compare, so the rule must not be built at all.
             *
             * @param type the type of the field the rule stands on; must not be {@code null}
             * @return the reason, or empty
             */
            default java.util.Optional<String> unusableFor(ConfigType type) {
                return java.util.Optional.empty();
            }

            /** Order on a NUMBER: {@code <20} and the like. Takes the typed value, never the notation. */
            record Num(Ord ord, BigDecimal bound) implements Element {

                @Override
                public java.util.Optional<String> unusableFor(ConfigType field) {
                    return field == ConfigType.INT || field == ConfigType.FLOAT ? java.util.Optional.empty()
                        : java.util.Optional.of("order condition `" + symbol() + "` compares numbers, and the "
                            + "field is `" + field.yamlName() + "` — there is nothing to compare");
                }

                @Override
                public Look look() {
                    return Look.ORDER;
                }

                @Override
                public boolean appliesTo(ConfigValue value) {
                    return value instanceof ConfigInt || value instanceof ConfigFloat;
                }

                @Override
                public boolean accepts(ConfigValue value) {
                    BigDecimal other = switch (value) {
                        case ConfigInt integer -> BigDecimal.valueOf(integer.value());
                        case ConfigFloat real -> BigDecimal.valueOf(real.value());
                        default -> throw new IllegalStateException(
                            "order condition `" + symbol() + "` on a value of type " + value.type()
                                + " is a DECLARATION error — ask `appliesTo` before `accepts`");
                    };
                    return ord.holds(other.compareTo(bound));
                }

                private String symbol() {
                    return ord.symbol + bound.toPlainString();
                }

                @Override
                public String text() {
                    return symbol();
                }
            }

            /**
             * Order on TIME: {@code <'2030-01-01T00:00:00Z'}. Compares a moment with a moment, not
             * text — {@code 2026-01-01T12:00:00Z} and {@code 2026-01-01T13:00:00+02:00} are the same
             * moment, though different text. The operand stands in single quotes, in the canonical
             * form: a rule does not know the notation of the field.
             */
            record Time(Ord ord, Instant bound) implements Element {

                @Override
                public java.util.Optional<String> unusableFor(ConfigType field) {
                    return field == ConfigType.TIMESTAMP ? java.util.Optional.empty()
                        : java.util.Optional.of("order condition `" + symbol() + "` compares moments, and the "
                            + "field is `" + field.yamlName() + "` — there is nothing to compare");
                }

                @Override
                public Look look() {
                    return Look.ORDER;
                }

                @Override
                public boolean appliesTo(ConfigValue value) {
                    return value instanceof ConfigTimestamp;
                }

                @Override
                public boolean accepts(ConfigValue value) {
                    Instant other = switch (value) {
                        case ConfigTimestamp moment -> moment.value();
                        default -> throw new IllegalStateException(
                            "order condition `" + text() + "` on a value of type " + value.type()
                                + " is a DECLARATION error — ask `appliesTo` before `accepts`");
                    };
                    return ord.holds(other.compareTo(bound));
                }

                private String symbol() {
                    return ord.symbol + "'" + bound + "'";
                }

                @Override
                public String text() {
                    return symbol();
                }
            }

            /**
             * A parameter TO BE RESOLVED: {@code <${min_port}} (order) and {@code =${limit}} (equality).
             *
             * <p>The parameter <b>MAY</b> be an interpolation, resolved by the same resolver as values.
             * A template cannot compute — {@link #accepts} throws until the parameter is substituted
             * through {@link #withParameters}. Without this element {@code <${min_port}} would be a
             * SYNTAX error at the declaration, although it is a valid notation.
             */
            record Param(Ord ord, boolean equal, String reference) implements Element {

                @Override
                public Look look() {
                    return ord == null ? Look.SHAPE : Look.ORDER;
                }

                @Override
                public boolean appliesTo(ConfigValue value) {
                    return true;   // only the resolved rule can decide
                }

                @Override
                public boolean accepts(ConfigValue value) {
                    throw new IllegalStateException("parameter `${" + reference + "}` is not resolved — "
                        + "this is a TEMPLATE; fill it in with `withParameters` before evaluating");
                }

                @Override
                public String text() {
                    return (ord == null ? (equal ? "=" : "!=") : ord.symbol) + "${" + reference + "}";
                }
            }

            /** Equality / inequality of the NOTATION ({@code =x}, {@code !=x}) — {@code 010} and {@code 8} are different text for it. */
            record Text(boolean equal, String literal) implements Element {

                @Override
                public java.util.Optional<String> unusableFor(ConfigType field) {
                    return readableAs(literal, field) ? java.util.Optional.empty()
                        : java.util.Optional.of("`" + literal + "` is not a value of type `" + field.yamlName()
                            + "` (" + notationOf(field) + "), so `" + text() + "` would compare apples with cars");
                }

                @Override
                public Look look() {
                    return Look.SHAPE;
                }

                @Override
                public boolean appliesTo(ConfigValue value) {
                    return true;
                }

                @Override
                public boolean accepts(ConfigValue value) {
                    return equal == literal.equals(value.display());
                }

                @Override
                public String text() {
                    return (equal ? "=" : "!=") + literal;
                }
            }

            /**
             * Equality with a COLLECTION LITERAL: {@code =[1,2,3]}, {@code !={a:1}}.
             *
             * <p>Compares the canonical form of BOTH sides, not the text as typed: {@code display()} of
             * a sequence is {@code [1, 2, 3]} — with a space after each comma — so {@code =[1,2,3]}
             * keeps its meaning although it looks different. Shape stays shape: {@code =[010]} does not
             * match the value {@code 8}, just as {@code =010} does not match {@code 8}.
             */
            record Literal(boolean equal, boolean map, String canonical) implements Element {

                @Override
                public java.util.Optional<String> unusableFor(ConfigType field) {
                    ConfigType needed = map ? ConfigType.MAP : ConfigType.SEQ;
                    return field == needed ? java.util.Optional.empty()
                        : java.util.Optional.of("`" + text() + "` compares a whole "
                            + (map ? "map" : "list") + ", and the field is `" + field.yamlName() + "`");
                }

                @Override
                public Look look() {
                    return Look.COLLECTION;
                }

                @Override
                public boolean appliesTo(ConfigValue value) {
                    return map ? value instanceof ConfigMap : value instanceof ConfigSeq;
                }

                @Override
                public boolean accepts(ConfigValue value) {
                    return equal == canonical.equals(value.display());
                }

                @Override
                public String text() {
                    return (equal ? "=" : "!=") + canonical;
                }
            }

            /**
             * Membership: {@code in[1,3,5]} / {@code !in[1,3,5]} — the value must be one of the list
             * (or none of it).
             *
             * <p>On a map the words have their own meaning: the keys of the map are a subset of the list
             * ({@code keys[...]}) or every value of the map is in the list ({@code values[...]}) —
             * whichever {@code target} says.
             */
            record Membership(boolean negated, List<String> allowed, Target target) implements Element {

                /**
                 * What the list refers to. Field values are the default case ({@code in[...]}); maps
                 * have their own words ({@code keys}/{@code values}), because keys have to be told
                 * apart from values.
                 */
                enum Target {
                    VALUE, KEYS, VALUES
                }

                @Override
                public java.util.Optional<String> unusableFor(ConfigType field) {
                    if (target != Target.VALUE) {
                        // `keys[…]`/`values[…]` speak about a MAP — and about its keys or its values, never
                        // about the map as a whole.
                        return field == ConfigType.MAP ? java.util.Optional.empty()
                            : java.util.Optional.of("`" + text() + "` compares the "
                                + (target == Target.KEYS ? "keys" : "values") + " of a map, and the field is `"
                                + field.yamlName() + "`");
                    }
                    for (String one : allowed) {
                        if (isParameter(one)) {
                            // Not yet an operand: the parameter is resolved later, and the FILLED chain is
                            // bound to the type again — the elements it brings are what must match the type.
                            continue;
                        }
                        if (!readableAs(one, field)) {
                            // The obligation is on the ELEMENT of the list, never on the list: `in[http,https]`
                            // on an `int` compares apples with cars.
                            return java.util.Optional.of("`" + one + "` in `" + text()
                                + "` is not a value of type `" + field.yamlName() + "` (" + notationOf(field)
                                + ")");
                        }
                    }
                    return java.util.Optional.empty();
                }

                @Override
                public Look look() {
                    return target == Target.VALUE ? Look.SHAPE : Look.MAP;
                }

                @Override
                public boolean appliesTo(ConfigValue value) {
                    return target == Target.VALUE || value instanceof ConfigMap;
                }

                @Override
                public boolean accepts(ConfigValue value) {
                    boolean inside = switch (target) {
                        case VALUE -> allowed.contains(value.display());
                        case KEYS -> ((ConfigMap) value).entries().keySet().stream()
                            .allMatch(allowed::contains);
                        case VALUES -> ((ConfigMap) value).entries().values().stream()
                            .allMatch(element -> allowed.contains(element.display()));
                    };
                    return negated != inside;
                }

                @Override
                public String text() {
                    String prefix = negated ? "!" : "";
                    String word = switch (target) {
                        case VALUE -> "in";
                        case KEYS -> "keys";
                        case VALUES -> "values";
                    };
                    return prefix + word + "[" + String.join(",", allowed) + "]";
                }
            }

            /**
             * "Contains": {@code c[1,3,5]} / {@code !c[1,3,5]} — the value must contain <b>at least all</b>
             * of the listed elements (it may hold more).
             *
             * <p>On a sequence: every listed element is one of its elements. On a map: every listed text
             * is a KEY of the map. The comparison runs on {@code display()} of the elements, so it works
             * regardless of their types.
             */
            record Contains(boolean negated, List<String> wanted) implements Element {

                @Override
                public java.util.Optional<String> unusableFor(ConfigType field) {
                    return field == ConfigType.SEQ || field == ConfigType.MAP ? java.util.Optional.empty()
                        : java.util.Optional.of("`" + text() + "` needs a list or a map to contain anything, "
                            + "and the field is `" + field.yamlName() + "`");
                }

                @Override
                public Look look() {
                    return Look.COLLECTION;
                }

                @Override
                public boolean appliesTo(ConfigValue value) {
                    return value instanceof ConfigSeq || value instanceof ConfigMap;
                }

                @Override
                public boolean accepts(ConfigValue value) {
                    boolean inside = switch (value) {
                        case ConfigSeq seq -> seq.elements().stream()
                            .map(ConfigValue::display)
                            .collect(java.util.stream.Collectors.toSet())
                            .containsAll(wanted);
                        case ConfigMap map -> map.entries().keySet().containsAll(wanted);
                        default -> throw new IllegalStateException(
                            "the \"contains\" condition `" + text() + "` on a value of type " + value.type()
                                + " is a DECLARATION error — ask `appliesTo` before `accepts`");
                    };
                    return negated != inside;
                }

                @Override
                public String text() {
                    return (negated ? "!" : "") + "c[" + String.join(",", wanted) + "]";
                }
            }

            /**
             * "Contains entries": {@code c{a:1}} / {@code !c{a:1}} — the map must contain the listed
             * entries (key + value), not necessarily only those. For keys alone there is
             * {@link Contains} ({@code c[key]}).
             */
            record ContainsEntries(boolean negated, Map<String, String> wanted) implements Element {

                @Override
                public java.util.Optional<String> unusableFor(ConfigType field) {
                    return field == ConfigType.MAP ? java.util.Optional.empty()
                        : java.util.Optional.of("`" + text() + "` needs a map to contain entries, and the field "
                            + "is `" + field.yamlName() + "`");
                }

                @Override
                public Look look() {
                    return Look.MAP;
                }

                @Override
                public boolean appliesTo(ConfigValue value) {
                    return value instanceof ConfigMap;
                }

                @Override
                public boolean accepts(ConfigValue value) {
                    if (!(value instanceof ConfigMap map)) {
                        throw new IllegalStateException(
                            "the \"contains entries\" condition `" + text() + "` on a value of type "
                                + value.type()
                                + " is a DECLARATION error — ask `appliesTo` before `accepts`");
                    }
                    for (Map.Entry<String, String> pair : wanted.entrySet()) {
                        ConfigValue actual = map.entries().get(pair.getKey());
                        if (actual == null || !actual.display().equals(pair.getValue())) {
                            return negated;
                        }
                    }
                    return !negated;
                }

                @Override
                public String text() {
                    StringBuilder text = new StringBuilder(negated ? "!c{" : "c{");
                    boolean first = true;
                    for (Map.Entry<String, String> pair : wanted.entrySet()) {
                        if (!first) {
                            text.append(", ");
                        }
                        text.append(pair.getKey()).append(": ").append(pair.getValue());
                        first = false;
                    }
                    return text.append('}').toString();
                }
            }

            /** A regular expression, written {@code ~`body`}, or its negation {@code !~`body`}. */
            record Rx(boolean negated, String regex) implements Element {

                @Override
                public Look look() {
                    return Look.SHAPE;
                }

                @Override
                public boolean appliesTo(ConfigValue value) {
                    return true;
                }

                @Override
                public boolean accepts(ConfigValue value) {
                    return negated != value.display().matches(regex);
                }

                @Override
                public String text() {
                    return (negated ? "!~" : "~") + "`" + regex + "`";
                }
            }
        }

        private final String chain;
        private final List<Element> elements;
        private final List<Logic> logic;
        /** The type the chain judges — {@code null} when it was parsed for SYNTAX only. */
        private final ConfigType type;

        /**
         * Creates a constraint by parsing a chain of conditions — **without a type**. The chain is checked for
         * SYNTAX only; use {@link #Const(String, ConfigType)} (or {@link #forType}) whenever the type is known,
         * because only then can the engine refuse an operand that can never be a value of that type.
         *
         * @param chain the chain of conditions; must not be {@code null} or blank
         * @throws NullPointerException if {@code chain} is {@code null}
         * @throws IllegalArgumentException if the chain is empty, cannot be parsed, or breaks the rule
         *     "every element after the first starts with {@code &} or {@code |}"
         */
        public Const(String chain) {
            this(chain, null);
        }

        /**
         * Creates a constraint by parsing a chain of conditions **for a field of the given type**: every
         * operand is checked against it, so a rule that compares apples with cars cannot be built.
         *
         * @param chain the chain of conditions; must not be {@code null} or blank
         * @param type the type the chain will judge; {@code null} means "syntax only"
         * @throws NullPointerException if {@code chain} is {@code null}
         * @throws IllegalArgumentException if the chain is empty, cannot be parsed, or an operand cannot be a
         *     value of that type — the message names the operand and says why
         */
        public Const(String chain, ConfigType type) {
            Objects.requireNonNull(chain, "chain");
            if (chain.isBlank()) {
                throw new IllegalArgumentException("a constraint without conditions means nothing");
            }
            this.chain = chain;

            List<Element> parsed = new ArrayList<>();
            List<Logic> joiners = new ArrayList<>();
            int at = 0;
            while (true) {
                at = skipSpaces(chain, at);
                if (!parsed.isEmpty()) {
                    // An element boundary MUST be explicit: the operator is the separator and the logic.
                    char separator = at < chain.length() ? chain.charAt(at) : '\0';
                    if (separator != '&' && separator != '|') {
                        throw error(chain, at, "every element after the first must start with `&` or `|`");
                    }
                    joiners.add(separator == '&' ? Logic.AND : Logic.OR);
                    at++;
                }
                at = skipSpaces(chain, at);
                if (at >= chain.length()) {
                    throw error(chain, at, "the chain ends on an operator — a condition is missing");
                }
                Parsed parsedElement = parseElement(chain, at);
                parsed.add(parsedElement.element());
                at = parsedElement.next();
                at = skipSpaces(chain, at);
                if (at >= chain.length()) {
                    break;
                }
            }
            this.elements = List.copyOf(parsed);
            this.logic = List.copyOf(joiners);
            this.type = type;
            if (type != null) {
                // The type is checked HERE, when the rule is built for a field: an operand that can never be a
                // value of that type has nothing to compare, and passing it as text is how apples end up
                // compared with cars.
                java.util.Optional<String> reason = unusableFor(type);
                if (reason.isPresent()) {
                    throw new IllegalArgumentException(reason.get());
                }
            }
        }

        @Override
        public ConfigConstraint forType(ConfigType type) {
            Objects.requireNonNull(type, "type");
            return this.type == type ? this : new Const(chain, type);
        }

        @Override
        public java.util.Optional<String> unusableFor(ConfigType field) {
            Objects.requireNonNull(field, "field");
            for (Element element : elements) {
                java.util.Optional<String> reason = element.unusableFor(field);
                if (reason.isPresent()) {
                    return reason;
                }
            }
            return java.util.Optional.empty();
        }

        @Override
        public boolean fillsAList(String reference) {
            Objects.requireNonNull(reference, "reference");
            String needle = "${" + reference + "}";
            int at = chain.indexOf('[');
            while (at >= 0) {
                int close = chain.indexOf(']', at);
                String body = close < 0 ? chain.substring(at) : chain.substring(at, close);
                if (body.contains(needle)) {
                    return true;
                }
                at = chain.indexOf('[', at + 1);
            }
            return false;
        }

        /**
         * Whether a written operand can be a value of that type. A rule is written by a human, so this is the
         * CANONICAL form of the type ({@code int} → a whole number, {@code timestamp} → a moment in the
         * canonical form), not every notation a source might accept.
         */
        private static boolean readableAs(String operand, ConfigType type) {
            return switch (type) {
                case INT -> operand.matches("-?[0-9]+");
                case FLOAT -> operand.matches("-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?");
                case BOOL -> "true".equals(operand) || "false".equals(operand);
                case TIMESTAMP -> isMoment(operand);
                default -> true;
            };
        }

        private static boolean isMoment(String operand) {
            try {
                java.time.Instant.parse(operand);
                return true;
            } catch (java.time.format.DateTimeParseException notAMoment) {
                return false;
            }
        }

        /** Whether the operand is still a PLACEHOLDER ({@code ${x}}) — resolved later, judged then. */
        private static boolean isParameter(String operand) {
            return PARAMETER.matcher(operand).matches();
        }

        /** How a value of that type is written — for the message that refuses an operand. */
        private static String notationOf(ConfigType type) {
            return switch (type) {
                case INT -> "a whole number";
                case FLOAT -> "a number";
                case BOOL -> "`true` or `false`";
                case TIMESTAMP -> "a moment in the canonical form, e.g. `2026-01-01T12:00:00Z`";
                case SEQ -> "a list";
                case MAP -> "a map";
                default -> "text";
            };
        }

        @Override
        public String rule() {
            return chain;
        }

        @Override
        public Look look() {
            // The strictest requirement in the chain: map > collection > order > shape.
            Look summary = Look.SHAPE;
            for (Element element : elements) {
                switch (element.look()) {
                    case MAP -> {
                        return Look.MAP;
                    }
                    case COLLECTION -> summary = Look.COLLECTION;
                    case ORDER -> {
                        if (summary == Look.SHAPE) {
                            summary = Look.ORDER;
                        }
                    }
                    case SHAPE -> {
                        // nothing tightens it
                    }
                }
            }
            return summary;
        }

        @Override
        public boolean appliesTo(ConfigValue value) {
            Objects.requireNonNull(value, "value");
            // For ALL elements, before the result is computed (short-circuited `&&`/`||` would hide an error).
            for (Element element : elements) {
                if (!element.appliesTo(value)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean accepts(ConfigValue value) {
            Objects.requireNonNull(value, "value");
            boolean result = elements.getFirst().accepts(value);
            for (int index = 1; index < elements.size(); index++) {
                boolean next = elements.get(index).accepts(value);
                result = logic.get(index - 1) == Logic.AND ? result && next : result || next;
            }
            return result;
        }

        /**
         * The conditions in order, for reports and tests — the chain stays the notation contract.
         *
         * @return the text of each condition, in order of appearance
         */
        public List<String> conditions() {
            return elements.stream().map(Element::text).toList();
        }

        /**
         * The operators joining the conditions, one between each pair of neighbours.
         *
         * @return {@code "&"} or {@code "|"} for every gap between conditions
         */
        public List<String> operators() {
            return logic.stream().map(operator -> operator == Logic.AND ? "&" : "|").toList();
        }

        /**
         * The values admitted by the (first) <b>{@code in[...]}</b> element — for a view that asks the
         * CONSTRAINT what may be chosen instead of guessing from the rule text.
         *
         * <p>Empty when there is no such element. In particular <b>{@code !in[...]} gives no list to
         * choose from</b>: the negation admits every other text, so a list of choices would be a lie.
         * The same holds for lists about a map ({@code keys[...]}, {@code values[...]}) — they do not
         * describe the value of the field.
         *
         * @return the allowed values in notation order, or empty
         */
        public java.util.Optional<java.util.List<String>> allowedValues() {
            for (Element element : elements) {
                if (element instanceof Element.Membership membership && !membership.negated()
                    && membership.target() == Element.Membership.Target.VALUE) {
                    return java.util.Optional.of(java.util.List.copyOf(membership.allowed()));
                }
            }
            return java.util.Optional.empty();
        }

        /**
         * Returns the chain exactly as it was written.
         *
         * @return the chain, unchanged
         */
        public String chain() {
            return chain;
        }

        @Override
        public java.util.List<String> parameters() {
            return parametersOf(chain);
        }

        @Override
        public ConfigConstraint withParameters(java.util.Map<String, String> resolved) {
            String filled = chain;
            for (java.util.Map.Entry<String, String> value : resolved.entrySet()) {
                filled = filled.replace("${" + value.getKey() + "}", escape(value.getValue()));
            }
            return filled.equals(chain) ? this
                : (type == null ? new Const(filled) : new Const(filled, type));
        }

        /**
         * The references {@code ${...}} in a chain — but NOT inside backticks (a regex body is
         * literal), and {@code \${} escapes one. Substitution is TEXTUAL, so {@code in[${x}]} works
         * too: the list receives a ready element.
         */
        private static java.util.List<String> parametersOf(String chain) {
            java.util.List<String> found = new java.util.ArrayList<>();
            boolean backtick = false;
            for (int index = 0; index < chain.length(); index++) {
                char character = chain.charAt(index);
                if (character == '\\') {
                    index++;
                    continue;
                }
                if (character == '`') {
                    backtick = !backtick;
                    continue;
                }
                if (!backtick && character == '$' && index + 1 < chain.length()
                    && chain.charAt(index + 1) == '{') {
                    int close = chain.indexOf('}', index);
                    if (close > 0) {
                        String reference = chain.substring(index + 2, close).strip();
                        if (!reference.isEmpty() && !found.contains(reference)) {
                            found.add(reference);
                        }
                        index = close;
                    }
                }
            }
            return java.util.List.copyOf(found);
        }

        /** The value substituted for an operand: separators, brackets and quotes MUST be protected. */
        private static String escape(String value) {
            StringBuilder text = new StringBuilder();
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (character == '&' || character == '|' || character == ')' || character == '\\'
                    || character == '`' || character == '\'') {
                    text.append('\\');
                }
                text.append(character);
            }
            return text.toString();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Const constraint && chain.equals(constraint.chain);
        }

        @Override
        public int hashCode() {
            return chain.hashCode();
        }

        @Override
        public String toString() {
            return "Const[" + chain + "]";
        }

        // ── parser: symbol + operand, flat ────────────────────────────────────────────────────────

        private record Parsed(Element element, int next) {
        }

        /** The body of a bracket (a list {@code [...]} or a map {@code {...}}) + the position after the closing bracket. */
        private record Body(String raw, int open, int next) {
        }

        private static Parsed parseElement(String chain, int at) {
            // 1. words: `in[...]`, `keys[...]`, `values[...]`, `c[...]`, `c{...}`
            if (chain.startsWith("in[", at)) {
                return parseList(chain, at + 3, Element.Membership.Target.VALUE, false, "in[");
            }
            if (chain.startsWith("keys[", at)) {
                return parseList(chain, at + 5, Element.Membership.Target.KEYS, false, "keys[");
            }
            if (chain.startsWith("values[", at)) {
                return parseList(chain, at + 7, Element.Membership.Target.VALUES, false, "values[");
            }
            if (chain.startsWith("c[", at) || chain.startsWith("c{", at)) {
                return parseContains(chain, at, false);
            }
            // 2. negations: `!=`, `!in[`, `!c[`, `!c{`, `!~`
            if (chain.charAt(at) == '!') {
                int next = at + 1;
                if (next >= chain.length()) {
                    throw error(chain, at, "`!` with nothing to negate — a negation must have its subject");
                }
                if (chain.startsWith("!in[", at)) {
                    return parseList(chain, at + 4, Element.Membership.Target.VALUE, true, "!in[");
                }
                if (chain.startsWith("!c[", at) || chain.startsWith("!c{", at)) {
                    return parseContains(chain, at + 1, true);
                }
                return switch (chain.charAt(next)) {
                    case '=' -> equality(chain, next, false);
                    case '~' -> parseRx(chain, next, true);
                    case '[' -> throw error(chain, at + 1,
                        "`![...]` used to mean \"not one of the list\", but `[...]` is now a LIST "
                            + "LITERAL — write `!in[...]` (none of the list)");
                    default -> throw error(chain, at + 1,
                        "unknown negation `!" + chain.charAt(next) + "` — allowed are `!=`, `!in[`, "
                            + "`!c[`, `!c{`, `!~`");
                };
            }
            // 3. the remaining symbols
            if (chain.charAt(at) == '=') {
                return equality(chain, at, true);
            }
            if (chain.charAt(at) == '[' || chain.charAt(at) == '{') {
                throw error(chain, at, "a collection literal is not a condition — write `in[...]` (one "
                    + "of the list), `c[...]` (contains elements), `c{...}` (contains entries) or "
                    + "`=[...]` (equals the list)");
            }
            if (chain.charAt(at) == '~') {
                return parseRx(chain, at, false);
            }
            // Order symbols from the LONGEST — otherwise `<` would swallow `<=` and the operand would be `=20`.
            for (Ord ord : new Ord[] {Ord.LE, Ord.GE, Ord.LT, Ord.GT}) {
                if (chain.startsWith(ord.symbol, at)) {
                    return order(chain, at, ord);
                }
            }
            throw error(chain, at, "unknown condition symbol — the dictionary is: `<` `<=` `>` `>=` "
                + "`=` `!=` `in[` `!in[` `c[` `c{` `keys[` `values[` `~` `!~`");
        }

        /** {@code =x} / {@code !=x} — and when the operand is {@code [...]} or {@code {...}}, a collection literal. */
        private static Parsed equality(String chain, int symbolAt, boolean equal) {
            int from = symbolAt + 1;
            int at = skipSpaces(chain, from);
            if (at < chain.length() && (chain.charAt(at) == '[' || chain.charAt(at) == '{')) {
                boolean map = chain.charAt(at) == '{';
                Body body = readBody(chain, at + 1, map ? '{' : '[', map ? '}' : ']', map ? "={" : "=[");
                String canonical = map ? canonicalMap(chain, body) : canonicalList(chain, body);
                if (canonical.isEmpty()) {
                    throw error(chain, at, (map ? "a map" : "a list") + " in an equality literal means nothing");
                }
                return new Parsed(new Element.Literal(equal, map, canonical), body.next());
            }
            String text = operand(chain, from, symbolAt);
            java.util.regex.Matcher parameter = PARAMETER.matcher(text);
            if (parameter.matches()) {
                return new Parsed(new Element.Param(null, equal, parameter.group(1).strip()),
                    afterOperand(chain, from));
            }
            return new Parsed(new Element.Text(equal, stripQuotes(text)), afterOperand(chain, from));
        }

        /** {@code <20} / {@code <'2030-01-01T00:00:00Z'} — the operand decides whether numbers or moments are compared. */
        private static Parsed order(String chain, int at, Ord ord) {
            int from = at + ord.symbol.length();
            String text = operand(chain, from, at);
            java.util.regex.Matcher parameter = PARAMETER.matcher(text);
            if (parameter.matches()) {
                return new Parsed(new Element.Param(ord, false, parameter.group(1).strip()),
                    afterOperand(chain, from));
            }
            if (text.startsWith("'")) {
                if (!text.endsWith("'") || text.length() < 2) {
                    throw error(chain, at, "the operand of order on time MUST be in single quotes on both "
                        + "sides, and it is `" + text + "`");
                }
                String moment = text.substring(1, text.length() - 1);
                try {
                    return new Parsed(new Element.Time(ord, Instant.parse(moment)),
                        afterOperand(chain, from));
                } catch (DateTimeParseException notATime) {
                    throw error(chain, from, "order on time requires the canonical form (for example "
                        + "`<'2030-01-01T00:00:00Z'`), but got `" + moment + "` — after the cast a "
                        + "duration and a URI are STRINGS (`90m`, `https://x`), so they have no order: "
                        + "use `in[...]`, `c[...]`, `c{...}` or a `~` regex");
                }
            }
            BigDecimal bound;
            try {
                bound = new BigDecimal(text);
            } catch (NumberFormatException notANumber) {
                // The most common mistake: a missing separator, so the second condition ran into the operand.
                String hint = text.indexOf('<') >= 0 || text.indexOf('>') >= 0
                    ? "looks like a missing separator — every element after the first must start with "
                        + "`&` or `|` (the operand came out as `" + text + "`)"
                    : "requires a number or a moment in single quotes, but got `" + text + "`";
                throw error(chain, from, "order condition `" + ord.symbol + "` " + hint);
            }
            return new Parsed(new Element.Num(ord, bound), afterOperand(chain, from));
        }

        /** The list {@code [a,b,c]} — commas separate, {@code \,} protects a comma, {@code ]} ends it. */
        private static Parsed parseList(String chain, int at, Element.Membership.Target target, boolean negated,
                                        String opening) {
            List<String> values = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            int index = at;
            while (true) {
                if (index >= chain.length()) {
                    throw error(chain, at - 1, "list `" + opening + "` is not closed");
                }
                char character = chain.charAt(index);
                if (character == '\\') {
                    if (index + 1 >= chain.length()) {
                        throw error(chain, index, "`\\` at the end of a list protects nothing");
                    }
                    current.append(chain.charAt(index + 1));
                    index += 2;
                    continue;
                }
                if (character == ']') {
                    values.add(scalarOf(current.toString()));
                    index++;
                    break;
                }
                if (character == ',') {
                    values.add(scalarOf(current.toString()));
                    current.setLength(0);
                    index++;
                    continue;
                }
                current.append(character);
                index++;
            }
            if (values.stream().anyMatch(String::isEmpty)) {
                throw error(chain, at - 1, "list `" + opening + "` with an empty element means nothing");
            }
            return new Parsed(new Element.Membership(negated, List.copyOf(values), target), index);
        }

        /** {@code c[...]}: contains elements (a sequence) or keys (a map); {@code c{...}}: contains map entries. */
        private static Parsed parseContains(String chain, int at, boolean negated) {
            int open = at + 1;
            char bracket = chain.charAt(open);
            boolean map = bracket == '{';
            Body body = readBody(chain, open + 1, bracket, map ? '}' : ']', negated ? "!c" : "c");
            if (body.raw().isBlank()) {
                throw error(chain, open, "`c" + bracket + (map ? "}" : "]") + "` without content means nothing");
            }
            if (map) {
                Map<String, String> pairs = pairs(chain, body);
                return new Parsed(new Element.ContainsEntries(negated, pairs), body.next());
            }
            List<String> wanted = new ArrayList<>();
            for (String piece : splitTopLevel(body.raw())) {
                if (piece.indexOf('[') >= 0 || piece.indexOf('{') >= 0) {
                    throw error(chain, open, "an element of a `c[...]` list cannot be a collection — use "
                        + "`c{...}` (map entries) or separate conditions");
                }
                wanted.add(scalarOf(piece));
            }
            if (wanted.stream().anyMatch(String::isEmpty)) {
                throw error(chain, open, "`c[...]` without elements means nothing");
            }
            return new Parsed(new Element.Contains(negated, List.copyOf(wanted)), body.next());
        }

        /** The entries of a map from a {@code {...}} body — key and value in canonical form (as in {@code display()}). */
        private static Map<String, String> pairs(String chain, Body body) {
            Map<String, String> wanted = new LinkedHashMap<>();
            for (String piece : splitTopLevel(body.raw())) {
                int colon = topLevelColon(piece);
                if (colon < 0) {
                    throw error(chain, body.open, "a map entry MUST have the form `key: value`, and it is `"
                        + piece.strip() + "`");
                }
                String key = scalarOf(piece.substring(0, colon));
                String value = canonical(chain, piece.substring(colon + 1));
                if (key.isEmpty()) {
                    throw error(chain, body.open, "a map entry without a key means nothing");
                }
                wanted.put(key, value);
            }
            if (wanted.isEmpty()) {
                throw error(chain, body.open, "`c{...}` without entries means nothing");
            }
            return Map.copyOf(wanted);
        }

        /** A regular expression: {@code ~} plus a body in backticks. */
        private static Parsed parseRx(String chain, int at, boolean negated) {
            int body = at + 1;
            if (body >= chain.length() || chain.charAt(body) != '`') {
                throw error(chain, at, "the body of a regular expression MUST stand in backticks: `` ~`body` ``");
            }
            StringBuilder regex = new StringBuilder();
            int index = body + 1;
            while (true) {
                if (index >= chain.length()) {
                    throw error(chain, body, "the body of a regular expression is not closed with a backtick");
                }
                char character = chain.charAt(index);
                if (character == '`') {
                    index++;
                    break;
                }
                if (character == '\\' && index + 1 < chain.length() && chain.charAt(index + 1) == '`') {
                    // only the delimiter comes out of the escape; `\d`, `\.`, `\\` go to the engine as they are
                    regex.append('`');
                    index += 2;
                    continue;
                }
                regex.append(character);
                index++;
            }
            if (regex.isEmpty()) {
                throw error(chain, at, "an empty regular expression means nothing");
            }
            return new Parsed(new Element.Rx(negated, regex.toString()), index);
        }

        /**
         * A text operand: up to the nearest unescaped separator ({@code &}, {@code |}) or to the close
         * of a notation section ({@code )}), with spaces trimmed. Single quotes are NOT removed here —
         * the symbol decides whether the operand is text or a MOMENT ({@code <'2030-01-01T00:00:00Z'}).
         */
        private static String operand(String chain, int from, int symbolAt) {
            StringBuilder text = new StringBuilder();
            int index = from;
            while (index < chain.length()) {
                char character = chain.charAt(index);
                if (character == '\\') {
                    if (index + 1 >= chain.length()) {
                        throw error(chain, index, "`\\` at the end of an operand protects nothing");
                    }
                    text.append(character).append(chain.charAt(index + 1));
                    index += 2;
                    continue;
                }
                if (character == '&' || character == '|' || character == ')') {
                    break;
                }
                text.append(character);
                index++;
            }
            String trimmed = unescape(text.toString()).strip();
            if (trimmed.isEmpty()) {
                throw error(chain, symbolAt, "a condition without an operand means nothing");
            }
            return trimmed;
        }

        private static int afterOperand(String chain, int from) {
            int index = from;
            while (index < chain.length()) {
                char character = chain.charAt(index);
                if (character == '\\') {
                    index += 2;
                    continue;
                }
                if (character == '&' || character == '|' || character == ')') {
                    break;
                }
                index++;
            }
            return index;
        }

        // ── collection literals: reading the body and the canonical form ──────────────────────────

        /** Reads a body up to the MATCHING bracket; nested brackets of the same family are counted. */
        private static Body readBody(String chain, int from, char opening, char closing, String where) {
            StringBuilder raw = new StringBuilder();
            int depth = 0;
            int index = from;
            while (index < chain.length()) {
                char character = chain.charAt(index);
                if (character == '\\') {
                    if (index + 1 >= chain.length()) {
                        throw error(chain, index, "`\\` at the end of " + where + " protects nothing");
                    }
                    raw.append(character).append(chain.charAt(index + 1));
                    index += 2;
                    continue;
                }
                if (character == opening) {
                    depth++;
                } else if (character == closing) {
                    if (depth == 0) {
                        return new Body(raw.toString(), from, index + 1);
                    }
                    depth--;
                }
                raw.append(character);
                index++;
            }
            throw error(chain, from - 1, "bracket `" + where + "` is not closed");
        }

        /** Splits a body on top-level commas (a comma inside nested brackets does not separate). */
        private static List<String> splitTopLevel(String raw) {
            List<String> pieces = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            int depth = 0;
            for (int index = 0; index < raw.length(); index++) {
                char character = raw.charAt(index);
                if (character == '\\') {
                    current.append(character);
                    if (index + 1 < raw.length()) {
                        current.append(raw.charAt(index + 1));
                        index++;
                    }
                    continue;
                }
                if (character == '[' || character == '{') {
                    depth++;
                } else if (character == ']' || character == '}') {
                    depth--;
                } else if (character == ',' && depth == 0) {
                    pieces.add(current.toString());
                    current.setLength(0);
                    continue;
                }
                current.append(character);
            }
            pieces.add(current.toString());
            return pieces;
        }

        /** The canonical form of a list — as in {@code ConfigSeq.display()}: {@code [1, 2, 3]}. */
        private static String canonicalList(String chain, Body body) {
            List<String> rendered = new ArrayList<>();
            for (String piece : splitTopLevel(body.raw())) {
                String element = canonical(chain, piece);
                if (element.isEmpty()) {
                    throw error(chain, body.open, "a list in an equality literal has an empty element");
                }
                rendered.add(element);
            }
            return "[" + String.join(", ", rendered) + "]";
        }

        /** The canonical form of a map — as in {@code ConfigMap.display()}: {@code {a: 1, b: 2}}. */
        private static String canonicalMap(String chain, Body body) {
            StringBuilder text = new StringBuilder("{");
            for (Map.Entry<String, String> pair : pairs(chain, body).entrySet()) {
                if (text.length() > 1) {
                    text.append(", ");
                }
                text.append(pair.getKey()).append(": ").append(pair.getValue());
            }
            return text.append('}').toString();
        }

        /** A literal value: a nested collection stays a collection, a scalar goes through as text. */
        private static String canonical(String chain, String raw) {
            String trimmed = raw.strip();
            if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
                boolean map = trimmed.charAt(0) == '{';
                char closing = map ? '}' : ']';
                if (trimmed.length() < 2 || trimmed.charAt(trimmed.length() - 1) != closing) {
                    throw error(chain, 0, "nested literal `" + trimmed + "` is not closed");
                }
                Body inner = new Body(trimmed.substring(1, trimmed.length() - 1), 0, 0);
                return map ? canonicalMap(chain, inner) : canonicalList(chain, inner);
            }
            return scalarOf(trimmed);
        }

        /** The position of the top-level colon in a map entry; {@code \:} protects one. */
        private static int topLevelColon(String piece) {
            int depth = 0;
            for (int index = 0; index < piece.length(); index++) {
                char character = piece.charAt(index);
                if (character == '\\') {
                    index++;
                    continue;
                }
                if (character == '[' || character == '{') {
                    depth++;
                } else if (character == ']' || character == '}') {
                    depth--;
                } else if (character == ':' && depth == 0) {
                    return index;
                }
            }
            return -1;
        }

        /** A literal scalar: removes escapes, surrounding spaces and a pair of single quotes. */
        private static String scalarOf(String raw) {
            return stripQuotes(unescape(raw).strip());
        }

        /** {@code \x} means {@code x} — without it neither a comma nor a colon could be written into an element. */
        private static String unescape(String raw) {
            StringBuilder text = new StringBuilder();
            for (int index = 0; index < raw.length(); index++) {
                char character = raw.charAt(index);
                if (character == '\\' && index + 1 < raw.length()) {
                    text.append(raw.charAt(index + 1));
                    index++;
                    continue;
                }
                text.append(character);
            }
            return text.toString();
        }

        private static String stripQuotes(String text) {
            if (text.length() >= 2 && text.startsWith("'") && text.endsWith("'")) {
                return text.substring(1, text.length() - 1);
            }
            return text;
        }

        private static int skipSpaces(String chain, int at) {
            int index = at;
            while (index < chain.length() && Character.isWhitespace(chain.charAt(index))) {
                index++;
            }
            return index;
        }

        /** Builds the failure message with the position of the problem inside the chain. */
        private static IllegalArgumentException error(String chain, int at, String reason) {
            return new IllegalArgumentException(
                "constraint chain `" + chain + "` (position " + (at + 1) + "): " + reason);
        }
    }
}
