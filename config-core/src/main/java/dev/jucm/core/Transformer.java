package dev.jucm.core;

import java.util.Optional;

/**
 * Transformer — the SPI for normalising the **input notation**. Its job is neither
 * validation nor presentation, but bringing a raw value from a source into the **canonical notation**, so
 * that the cast can be strict and single-form.
 *
 * <pre>
 *   text "2026-01-01 12:00:00"  +  configuration "yyyy-MM-dd HH:mm:ss"  ->  2026-01-01T12:00:00Z
 * </pre>
 *
 * <p><b>What a transformer does not know</b>: {@link ConfigField}, the path, the source or
 * any other values. The **caller** adds the path and the source — that is why a transformer is testable
 * in isolation, and yet the diagnostics still has the complete picture.
 *
 * <p><b>What the transformer does not do</b>: semantic validation (that is constraints, after the cast), I/O
 * (that is the source/loader) and nothing that depends on time or state — a transformation **MUST** be
 * deterministic and stateless, so one transformer serves many fields at the same time.
 *
 * <p>A configuration is **opaque** to us: the transformer interprets it, and it validates its
 * content **loudly** — a rejection with a reason, never a silent degradation.
 */
public interface Transformer {

    /**
     * Normalises the value. The result is a value in canonical notation **or a rejection reason** —
     * never an exception: the reason is a separate case, because without it the message would say
     * "bad value" without saying which notation was expected.
     *
     * @param raw    the value as the source delivered it; must not be null
     * @param config the notation configuration from the declaration or from the source marker; empty
     *               when neither stated one
     * @return the normalised value, or the reason it was rejected; never null
     */
    Outcome normalize(ConfigValue raw, Optional<String> config);

    /**
     * Checks **its own configuration** before any value reaches it. Empty = the configuration is fine.
     * Separate from {@link #normalize}, because a bad configuration is a **model** error (the declaration
     * or the source marker), not a value problem: without that distinction a nonsense
     * `transformerConfig(...)` would travel as `TransformFailed`, that is, blame the data — while the
     * fault is in the model.
     *
     * @param config the notation configuration; empty when none was stated
     * @return the problem in the configuration, or empty when it is acceptable
     */
    default Optional<String> configProblem(Optional<String> config) {
        return Optional.empty();
    }

    /**
     * The declaration types on which this transformer makes sense — **empty = no restriction of its own**.
     * Checked at first use, because a mismatch is a model error: a numeric transformer on a `str` field
     * would lie about intent, and its "conversion" would do nothing.
     *
     * @return the accepted types, or an empty set when the transformer accepts any type
     */
    default java.util.Set<ConfigType> allowedTypes() {
        return java.util.Set.of();
    }

    /** The normalisation result — a closed set: it succeeded, or we know why not. */
    sealed interface Outcome permits Outcome.Normalized, Outcome.Rejected {

        /**
         * The value in canonical notation; this is what travels on, to the cast.
         *
         * @param value the normalised value; never null
         */
        record Normalized(ConfigValue value) implements Outcome {
            /** Validates the component — it is required. */
            public Normalized {
                java.util.Objects.requireNonNull(value, "value");
            }
        }

        /**
         * The rejection reason — a sentence for a human, without path and source (the caller adds those).
         *
         * @param reason why the value was rejected; never null
         */
        record Rejected(String reason) implements Outcome {
            /** Validates the component — it is required. */
            public Rejected {
                java.util.Objects.requireNonNull(reason, "reason");
            }
        }
    }

    /**
     * A successful normalisation.
     *
     * @param value the value in canonical notation; must not be null
     * @return the outcome carrying that value
     */
    static Outcome normalized(ConfigValue value) {
        return new Outcome.Normalized(value);
    }

    /**
     * A rejected value.
     *
     * @param reason why the value was rejected; must not be null
     * @return the outcome carrying that reason
     */
    static Outcome rejected(String reason) {
        return new Outcome.Rejected(reason);
    }
}
