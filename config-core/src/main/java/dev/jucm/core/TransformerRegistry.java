package dev.jucm.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The transformer registry — **explicit and immutable once built**, passed wherever it is
 * used. No global mutable singleton: two applications in one JVM must not see each other, and the
 * registration order must not change behaviour.
 *
 * <pre>
 *   TransformerRegistry registry = TransformerRegistry.defaults()
 *       .register("my-date", MyDateTransformer::new);
 * </pre>
 *
 * <p>The name is **WHAT**, the transformer configuration is **HOW**: one name serves many
 * configurations, and a transformation is not bound to a path — the path is the address of a value, not
 * a description of its interpretation.
 *
 * <p>Transformers provided by JUCM (the name is what the declaration writes):
 *
 * <pre>
 *   number    YAML notation: `010` = 8, `0x1F` = 31, `1_000` = 1000, trimming          (int, float)
 *   date      canonical notation `2026-01-01T12:00:00Z` (+ explicit offset); with a pattern
 *             in the configuration — a format stated by the declaration              (timestamp)
 *   duration  a duration notation (`30s`, `5m`)                                      (—)
 *   uri       validation of scheme and syntax                                        (—)
 *   trim      explicit trimming of spaces — **never automatic**                    (—)
 * </pre>
 *
 * <p>`str` **does not get** a default `trim`: when concatenating, a space can be content, so automatic
 * trimming would destroy data without a trace in the message.
 */
public final class TransformerRegistry {

    private final Map<String, Supplier<? extends Transformer>> factories;

    private TransformerRegistry(Map<String, Supplier<? extends Transformer>> factories) {
        // The registration order is kept (LinkedHashMap): messages and diagnostics must be repeatable,
        // and the list of names must match what the application registered.
        this.factories = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(factories));
    }

    /**
     * Transformers provided by JUCM — the starting point for every application.
     *
     * @return a registry with `number`, `date`, `duration`, `uri`, `trim`, `size` and `percentage`
     */
    public static TransformerRegistry defaults() {
        return new TransformerRegistry(Map.of())
            .register("number", NumberTransformer::new)
            .register("date", DateTransformer::new)
            .register("duration", DurationTransformer::new)
            .register("uri", UriTransformer::new)
            .register("trim", TrimTransformer::new)
            .register("size", SizeTransformer::new)
            .register("percentage", PercentageTransformer::new);
    }

    /**
     * Adds an application transformer; the original registry stays unchanged. A name that is
     * already present is a programmer error, not a silent win of one of them — overriding a registration
     * would change behaviour depending on the order of calls, exactly what a stable registration order
     * forbids. Replacing JUCM's default transformers has its own **explicit** route: {@link #override}.
     *
     * @param name    the name the declaration writes; must not be null or blank
     * @param factory builds the transformer; must not be null
     * @return a new registry; this one is unchanged
     * @throws IllegalArgumentException if the name is blank or already registered
     */
    public TransformerRegistry register(String name, Supplier<? extends Transformer> factory) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(factory, "factory");
        if (name.isBlank()) {
            throw new IllegalArgumentException("an empty transformer name means nothing");
        }
        Map<String, Supplier<? extends Transformer>> extended = new LinkedHashMap<>(factories);
        if (extended.putIfAbsent(name, factory) != null) {
            throw new IllegalArgumentException("transformer `" + name + "` is already registered"
                + " — names MUST be unique, and overriding would change behaviour depending on order."
                + " Replacing a default transformer is `override(...)`");
        }
        return new TransformerRegistry(extended);
    }

    /**
     * Replaces a transformer with that name — the only way an application can take JUCM's default
      * notation away from it (`number` for `int`, `date` for `timestamp`). It is separate and explicit so
      * that a replacement does not happen by accident: `register` on a taken name is an error, and here an
      * **unknown** name is an error — you replace something that exists.
     *
     * @param name    the name to replace; must not be null
     * @param factory builds the replacement; must not be null
     * @return a new registry; this one is unchanged
     * @throws IllegalArgumentException if nothing is registered under that name
     */
    public TransformerRegistry override(String name, Supplier<? extends Transformer> factory) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(factory, "factory");
        if (!factories.containsKey(name)) {
            throw new IllegalArgumentException("there is nothing to replace: transformer `" + name
                + "` is not registered — registered: " + names());
        }
        Map<String, Supplier<? extends Transformer>> extended = new LinkedHashMap<>(factories);
        extended.put(name, factory);
        return new TransformerRegistry(extended);
    }

    /**
      * The transformer with that name. A missing name means **no value**, not "no transformation": a
     * silent degradation on an unknown name is excluded, so the caller is obliged to report it
      * loudly — see {@link #require(String)}.
     *
     * @param name the transformer name; must not be null
     * @return the transformer, or empty when no factory is registered under that name
     */
    public Optional<Transformer> lookup(String name) {
        Objects.requireNonNull(name, "name");
        return Optional.ofNullable(factories.get(name)).map(Supplier::get);
    }

    /**
     * The transformer with that name or a **loud model error**: an unknown name
      * arises before any configuration exists, so it is not a `ConfigError` — it is a mistake in the
      * declaration, and the message lists what to choose from.
     *
     * @param name the transformer name; must not be null
     * @return the transformer registered under that name
     * @throws IllegalArgumentException if nothing is registered under that name, with the known names
     */
    public Transformer require(String name) {
        return lookup(name).orElseThrow(() -> new IllegalArgumentException(
            "unknown transformer `" + name + "` — registered: " + names()));
    }

    /**
      * The default notation for a declared type — the type knows the names
      * ({@link ConfigType#defaultTransformer()}) and the registry resolves them. One place with names, one
      * with factories, and a replacement ({@link #override}) works on both routes.
     *
     * @param type the declared type; must not be null
     * @return the transformer the type points at, or empty when the type has no default notation
     */
    public Optional<Transformer> defaultFor(ConfigType type) {
        Objects.requireNonNull(type, "type");
        return type.defaultTransformer().flatMap(this::lookup);
    }

    /**
     * Names of the registered transformers, in registration order — for messages.
     *
     * @return an immutable, ordered set of names
     */
    public Set<String> names() {
        return java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(factories.keySet()));
    }

    /**
     * The number of registered transformers.
     *
     * @return the number of registrations
     */
    public int size() {
        return factories.size();
    }

    @Override
    public String toString() {
        return "TransformerRegistry" + names();
    }
}
