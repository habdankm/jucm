package dev.jucm.core;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * Cast — **one operation for all sources**: the value delivered by a source, onto the type
 * declared in the model. A failed cast means error and stop; we do not repair, we do not guess intent.
 *
 * <p>Adapters do not cast — they **transport**. That is why the raw value enters here
 * together with a {@link ConfigSource}, and either a typed value or a {@link ConfigError.CastFailed}
 * leaves, carrying the path, the raw value and the source. That is the whole diagnostic contract.
 *
 * <p><b>Notation is handed to transformers</b>: the cast stops being the
 * place where "how to read a notation" lives and becomes **single-form** — it receives a value in the
 * canonical notation. `int`/`float` -> `number`, `timestamp` -> `date` (`TransformerRegistry`
 * `defaultFor`); the remaining types have no default notation, so they go as before. Those contracts
 * **stay**: the default timestamp format is `2026-01-01T12:00:00Z`, a bare date is rejected, and error
 * messages change nothing.
 *
 * <p>Notation rules are **strict**: on an `int` field only an integer literal, on a
 * `float` only one with a decimal point. No widening `int` -> `float`, no narrowing.
 *
 * <p>`NaN` and `Infinity` are rejected, `null` does not exist.
 */
public final class ConfigCast {

    private ConfigCast() {
    }

    /**
     * Casts the value onto the declared type, with the JUCM default notation.
     * Value types: `str`, `int`, `float`, `bool`, `timestamp`, `seq`, `map`, `binary`; cast targets for
     * declared fields: `duration`, `uri`.
     *
     * @param delivered the value as the source delivered it; must not be null
     * @param declared  the type the model declares for that path; must not be null
     * @param path      the path the value belongs to, for the message; must not be null
     * @param source    where the value came from, for the message; must not be null
     * @return the value in the declared type, or a {@link ConfigError.CastFailed}
     */
    public static ConfigResult<ConfigValue> to(ConfigValue delivered, ConfigType declared,
                                               ConfigPath path, ConfigSource source) {
        return to(delivered, declared, path, source, TransformerRegistry.defaults());
    }

    /**
     * Same as {@link #to(ConfigValue, ConfigType, ConfigPath, ConfigSource)}, but with the application's
     * registry: swapping the registry must change the notation wherever the declaration did not
     * state it — and not only where the declaration looks for it.
     *
     * @param delivered the value as the source delivered it; must not be null
     * @param declared  the type the model declares for that path; must not be null
     * @param path      the path the value belongs to, for the message; must not be null
     * @param source    where the value came from, for the message; must not be null
     * @param registry  the registry the default notation comes from; must not be null
     * @return the value in the declared type, or a {@link ConfigError.CastFailed}
     */
    public static ConfigResult<ConfigValue> to(ConfigValue delivered, ConfigType declared,
                                               ConfigPath path, ConfigSource source,
                                               TransformerRegistry registry) {
        Objects.requireNonNull(delivered, "delivered");
        Objects.requireNonNull(declared, "declared");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(registry, "registry");

        if (delivered instanceof ConfigReference reference) {
            // A reference is resolved by the interpolation step, BEFORE the cast. Only a caller
            // that skipped that step arrives here — and then it is true that the reference leads nowhere.
            return ConfigResult.error(new ConfigError.UnresolvedReference(path, reference.target(), source));
        }
        if (delivered instanceof ConfigExpression expression) {
            return ConfigResult.error(new ConfigError.UnresolvedExpression(path, expression.display(), source));
        }
        if (delivered instanceof ConfigCoalesce coalesce) {
            return ConfigResult.error(new ConfigError.UnresolvedExpression(path, coalesce.display(), source));
        }

        Optional<ConfigValue> cast = switch (declared) {
            case STR -> asText(delivered);
            case INT -> asInt(delivered, registry);
            case FLOAT -> asFloat(delivered, registry);
            case BOOL -> asBool(delivered);
            case TIMESTAMP -> asTimestamp(delivered, registry);
            case SEQ -> delivered instanceof ConfigSeq ? Optional.of(delivered) : Optional.empty();
            case MAP -> delivered instanceof ConfigMap ? Optional.of(delivered) : Optional.empty();
            case BINARY -> asBinary(delivered);
            case DURATION, URI -> asCanonicalText(delivered, declared, registry);
        };

        return cast.<ConfigResult<ConfigValue>>map(ConfigResult::value)
            .orElseGet(() -> failed(path, declared, delivered, source, notationProblem(delivered, declared, registry)));
    }

    private static ConfigResult<ConfigValue> failed(ConfigPath path, ConfigType declared, ConfigValue delivered,
                                                    ConfigSource source, Optional<String> detail) {
        return ConfigResult.error(new ConfigError.CastFailed(path, declared, delivered, source, detail));
    }

    /**
     * The reason for rejecting the **type's default notation** — an error message must say WHY (not
     * just "wrong type"). Reached only when the cast failed, so the success path pays nothing.
     */
    private static Optional<String> notationProblem(ConfigValue delivered, ConfigType declared,
                                                    TransformerRegistry registry) {
        return registry.defaultFor(declared)
            .flatMap(transformer -> switch (transformer.normalize(delivered, Optional.empty())) {
                case Transformer.Outcome.Normalized ok -> wrongFamily(declared, ok.value());
                case Transformer.Outcome.Rejected rejected -> Optional.of(rejected.reason());
            });
    }

    /**
     * The notation read the value, but as a **different family** than the field (`1e6` on `int`) —
     * without this sentence the message would say "the incoming value has type `str`", that is about the
     * form, not about the reason.
     */
    private static Optional<String> wrongFamily(ConfigType declared, ConfigValue produced) {
        if (declared == ConfigType.INT && produced instanceof ConfigFloat fractional) {
            return Optional.of("the notation read this as a floating-point number (`"
                + fractional.display() + "`), but the field is `int`");
        }
        if (declared == ConfigType.FLOAT && produced instanceof ConfigInt integral) {
            return Optional.of("the notation read this as an integer (`"
                + integral.display() + "`), but the field is `float`");
        }
        return Optional.empty();
    }

    /** `str`: scalars are shown as a string; an object or an array is an error. */
    private static Optional<ConfigValue> asText(ConfigValue delivered) {
        if (delivered instanceof ConfigString || delivered instanceof ConfigInt
            || delivered instanceof ConfigFloat || delivered instanceof ConfigBool
            || delivered instanceof ConfigTimestamp) {
            return Optional.of(delivered instanceof ConfigString text ? text : ConfigString.of(delivered.display()));
        }
        return Optional.empty();
    }

    /** Integer literal — the notation is `number`, the strictness ("no decimal point") stays here. */
    private static Optional<ConfigValue> asInt(ConfigValue delivered, TransformerRegistry registry) {
        if (delivered instanceof ConfigInt number) {
            return Optional.of(number);
        }
        return normalized(delivered, ConfigType.INT, registry)
            .filter(ConfigInt.class::isInstance);
    }

    /** `float` only with a decimal point — the notation comes from `number`, the form requirement stays here. */
    private static Optional<ConfigValue> asFloat(ConfigValue delivered, TransformerRegistry registry) {
        if (delivered instanceof ConfigFloat number) {
            return Optional.of(number);
        }
        return normalized(delivered, ConfigType.FLOAT, registry)
            .filter(ConfigFloat.class::isInstance);
    }

    /** `timestamp` — the notation (default format, pattern from the declaration) lives in the `date` transformer. */
    private static Optional<ConfigValue> asTimestamp(ConfigValue delivered, TransformerRegistry registry) {
        if (delivered instanceof ConfigTimestamp timestamp) {
            return Optional.of(timestamp);
        }
        return normalized(delivered, ConfigType.TIMESTAMP, registry)
            .filter(ConfigTimestamp.class::isInstance);
    }

    private static Optional<ConfigValue> asBool(ConfigValue delivered) {
        if (delivered instanceof ConfigBool flag) {
            return Optional.of(flag);
        }
        if (delivered instanceof ConfigString text) {
            try {
                return Optional.of(ConfigBool.parseStrict(text.value()));
            } catch (IllegalArgumentException rejected) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Passes the value through the type's default transformer. A rejection and a missing transformer
     * both give the same `CastFailed` — the rejected notation is mentioned in the message, and a
     * separate error kind belongs to the transformation step, where the caller has path and source.
     */
    private static Optional<ConfigValue> normalized(ConfigValue delivered, ConfigType declared,
                                                    TransformerRegistry registry) {
        return registry.defaultFor(declared).flatMap(transformer ->
            switch (transformer.normalize(delivered, Optional.empty())) {
                case Transformer.Outcome.Normalized ok -> Optional.of(ok.value());
                case Transformer.Outcome.Rejected rejected -> Optional.empty();
            });
    }

    /**
     * Types identified by **us** (`duration`, `uri`): their notation is their definition, so the
     * cast passes the value through the type's transformer and keeps the **canonical form as a string**
     * (`90m`, `https://x`). The value in the tree remains a YAML dictionary type — `str` —
     * which is why `ConfigValue` does not grow new variants.
     */
    private static Optional<ConfigValue> asCanonicalText(ConfigValue delivered, ConfigType declared,
                                                         TransformerRegistry registry) {
        return normalized(delivered, declared, registry).filter(ConfigString.class::isInstance);
    }

    private static Optional<ConfigValue> asBinary(ConfigValue delivered) {
        if (delivered instanceof ConfigBinary blob) {
            return Optional.of(blob);
        }
        if (delivered instanceof ConfigString text) {
            try {
                return Optional.of(ConfigBinary.fromUri(URI.create(text.value().trim())));
            } catch (IllegalArgumentException | UnsupportedOperationException rejected) {
                // missing scheme / bad base64 / a scheme that needs I/O — all of it is CastFailed,
                // so that validation continues and collects ALL problems at once.
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
