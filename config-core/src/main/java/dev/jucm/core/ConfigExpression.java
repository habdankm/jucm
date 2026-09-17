package dev.jucm.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An expression: a text built from literals and symbolic values.
 *
 * <pre>{@code
 *   "http://${host}:${port}"  ->  [ Text("http://"), Ref(host), Text(":"), Ref(port) ]
 *   "${year}-${month}"        ->  [ Ref(year), Text("-"), Ref(month) ]
 * }</pre>
 *
 * <p><b>The shape is an ORDERED MAP of parts, not a list</b>: an ordered map can always act as a list
 * (iteration in order), while a list gives no <b>addressability</b> of parts — without a key there is
 * no way to say which part took part and **from where**. The key has the form
 * {@code "<position>:<kind>"} ({@code "0:text"}, {@code "1:ref"}, {@code "2:coalesce"}): the position
 * guarantees uniqueness ({@code "${a}${a}"} is two different parts) and the kind reads well in a
 * debugger.
 *
 * <p><b>Every part knows its WHOLE expression</b> ({@link Part#owner()}) — without that an error shows
 * only a fragment, and then a problem with a **space** inside one part would go unnoticed. That is a
 * deliberately **cyclic** structure (part -> whole -> parts), and therefore:
 *
 * <pre>
 *   1. the reference to the whole does NOT enter equals/hashCode — otherwise the comparison would
 *      recurse
 *   2. the reference is NOT traversed by equality, hashing, serialisation or tree printing — it serves
 *      diagnostics ONLY
 *   3. traversal goes ONE way: whole -> parts; never part -> whole -> part
 * </pre>
 *
 * <p>The type of an expression is always {@code str}: concatenation is an operation on text, and a
 * value of any other type is rendered in its canonical form ({@link ConfigValue#display()}).
 *
 * @since 1.0
 */
public final class ConfigExpression implements ConfigValue {

    /**
     * A part of an expression: its content plus a **reference to the whole** (outside equality and
     * hashing).
     *
     * @since 1.0
     */
    public static final class Part {

        private final String key;
        private final Content content;
        private ConfigExpression owner;

        private Part(String key, Content content) {
            this.key = key;
            this.content = content;
        }

        /**
         * Returns the key of this part.
         *
         * @return the key, of the form {@code "<position>:<kind>"}; never null
         */
        public String key() {
            return key;
        }

        /**
         * Returns the content: a literal ({@link Text}) or a symbolic value ({@link Ref}, {@link Coalesce}).
         *
         * @return the content of this part, never null
         */
        public Content content() {
            return content;
        }

        /**
         * Returns the whole expression this part comes from — <b>for diagnostics only</b>. It is set
         * once, while the expression is built, and it is not part of the identity of a part.
         *
         * @return the whole expression this part belongs to, never null
         */
        public ConfigExpression owner() {
            return owner;
        }

        /**
         * Returns the notation of this part for diagnostics: a literal in quotes, a symbolic value as
         * written.
         *
         * @return the notation, never null
         */
        public String display() {
            return switch (content) {
                case Text text -> "\"" + text.text() + "\"";
                case Ref ref -> ref.reference().display();
                case Coalesce coalesce -> coalesce.coalesce().display();
            };
        }

        @Override
        public String toString() {
            return key + " -> " + display();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Part part && key.equals(part.key) && content.equals(part.content);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, content);
        }
    }

    /**
     * The content of a part — a literal or a symbolic value; nothing else.
     *
     * @since 1.0
     */
    public sealed interface Content permits Text, Ref, Coalesce {
    }

    /**
     * A literal: the characters that stand next to substitutions in a value (a space is content too).
     *
     * @param text the literal text; must not be {@code null}
     * @since 1.0
     */
    public record Text(String text) implements Content {
        /** Validates the components — every one of them is required. */
        public Text {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * A reference, required or optional ({@code ${x}} / {@code ${?x}}).
     *
     * @param reference the resolved reference; must not be {@code null}
     * @since 1.0
     */
    public record Ref(ConfigReference reference) implements Content {
        /** Validates the components — every one of them is required. */
        public Ref {
            Objects.requireNonNull(reference, "reference");
        }
    }

    /**
     * A fallback {@code a ?? b} as a part of an expression ({@code "${a ?? b}-suffix"}).
     *
     * @param coalesce the fallback; must not be {@code null}
     * @since 1.0
     */
    public record Coalesce(ConfigCoalesce coalesce) implements Content {
        /** Validates the components — every one of them is required. */
        public Coalesce {
            Objects.requireNonNull(coalesce, "coalesce");
        }
    }

    private final Map<String, Part> parts;

    private ConfigExpression(Map<String, Part> parts) {
        this.parts = Collections.unmodifiableMap(parts);
    }

    /**
     * Builds an expression from parts in the given order. Keys are assigned in <b>one place</b>, so the
     * {@code "<position>:<kind>"} convention cannot drift between callers.
     *
     * @param contents the parts, in the order in which they are written; must not be {@code null}
     * @return an immutable expression whose parts are ordered as supplied and addressable by key
     * @throws NullPointerException if the list or any of its elements is {@code null}
     * @throws IllegalArgumentException if there are fewer than two parts — one part is a
     *     {@link ConfigReference} or a {@link ConfigCoalesce}, not an expression
     * @since 1.0
     */
    public static ConfigExpression of(List<Content> contents) {
        Objects.requireNonNull(contents, "contents");
        if (contents.size() < 2) {
            throw new IllegalArgumentException("an expression makes sense from TWO parts — a single "
                + "part is a ConfigReference or a ConfigCoalesce, not an expression");
        }
        Map<String, Part> parts = new LinkedHashMap<>();
        ConfigExpression expression = new ConfigExpression(parts);
        for (int position = 0; position < contents.size(); position++) {
            Content content = Objects.requireNonNull(contents.get(position), "part " + position);
            Part part = new Part(position + ":" + kindOf(content), content);
            part.owner = expression; // the only place where the part -> whole reference is created
            parts.put(part.key(), part);
        }
        return expression;
    }

    private static String kindOf(Content content) {
        return switch (content) {
            case Text ignored -> "text";
            case Ref ignored -> "ref";
            case Coalesce ignored -> "coalesce";
        };
    }

    /**
     * Returns the parts in notation order, as an immutable map from {@code "<position>:<kind>"} to the
     * part.
     *
     * @return an immutable, ordered view of the parts
     */
    public Map<String, Part> parts() {
        return parts;
    }

    /**
     * Returns the symbolic values present in the expression, in notation order — the input for the
     * resolver and for reachability analysis. Literals refer to nothing and are skipped.
     *
     * @return the symbolic values in notation order; empty when the expression is a single literal
     */
    public List<ConfigValue> symbolics() {
        List<ConfigValue> found = new ArrayList<>();
        for (Part part : parts.values()) {
            switch (part.content()) {
                case Ref ref -> found.add(ref.reference());
                case Coalesce coalesce -> found.add(coalesce.coalesce());
                case Text ignored -> {
                    // a literal refers to nothing
                }
            }
        }
        return List.copyOf(found);
    }

    /**
     * Returns {@link ConfigType#STR}: concatenation yields text, and that is why there is no exception
     * here.
     */
    @Override
    public ConfigType type() {
        return ConfigType.STR;
    }

    /**
     * Returns the notation, which can be parsed back: literals as they stand, symbolic values as in
     * the source.
     */
    @Override
    public String display() {
        StringBuilder text = new StringBuilder();
        for (Part part : parts.values()) {
            switch (part.content()) {
                case Text literal -> text.append(literal.text());
                case Ref ref -> text.append(ref.reference().display());
                case Coalesce coalesce -> text.append(coalesce.coalesce().display());
            }
        }
        return text.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ConfigExpression expression && parts.equals(expression.parts);
    }

    @Override
    public int hashCode() {
        return parts.hashCode();
    }

    @Override
    public String toString() {
        return "ConfigExpression[" + display() + ", parts: " + parts.size() + "]";
    }
}
