package dev.jucm.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A configuration change to be applied on top of a snapshot. Immutable and **sparse**.
 *
 * <p>Operations: {@code set(path, value)} and {@code reset(path)}. There is no {@code remove} in
 * this version, so there is nothing that could mask a value from a lower layer;
 * {@link #nullDelivered(ConfigPath, ConfigSource)} is the way to state that a source delivered
 * nothing.
 *
 * <p>Sparseness is the correctness test here: a patch contains **only what the source really
 * delivered** - no placeholders such as {@code server = NotProvided}. A missing entry means "this
 * source knows nothing about it", and a value of type {@code Unset}/{@code NotProvided} **does not
 * exist** in the model.
 *
 * <p>A patch never mutates a configuration: {@code Config + ConfigPatch -> new Config}. It is the
 * only way into the tree for values coming from external sources.
 *
 * <p><b>Convenience entry points:</b> the methods also take a canonical path as a {@code String} and
 * the bare value, without building a {@link SetValue} by hand. The string is **only** a convenience
 * entry point: a {@link ConfigPath} is always created inside, so key grammar validation works the
 * same way, and the equality of patches does not depend on which route built them.
 *
 * <pre>{@code
 * patch.with("server.port", ConfigInt.of(8080))                 // convenience
 * patch.with(ConfigPath.of("server.port"), ConfigInt.of(8080));  // the same, explicit
 * }</pre>
 *
 * @since 1.0
 */
public final class ConfigPatch {

    /**
     * An operation - a closed set: set a value, take off one's own override, or state that the
     * source **delivered no value**.
     *
     * @since 1.0
     */
    public sealed interface Operation permits SetValue, Reset, NullDelivered {
    }

    /**
     * Sets a value under a path, **together with its origin**. A source adapter passes its own
     * source; a patch built by hand gets the application source.
     *
     * <p>The origin is **not part** of equality - only the value is compared, so two patches with
     * the same effect are equal even when they came from different layers. The source stays
     * available separately, for diagnostics.
     *
     * <p><b>The source statement ({@code declaration}) IS part of equality</b>, and that is not an
     * inconsistency: {@code ___TYPE}/{@code ___TCONF} change the **effect** of the value (its type
     * and notation) whereas the source does not - two patches differing in the statement yield
     * different configurations, so they are not the same patch.
     *
     * @param value       the value to set; must not be {@code null}
     * @param source      the origin of the value; must not be {@code null}
     * @param declaration what the source stated about type and notation; must not be {@code null}
     * @since 1.0
     */
    public record SetValue(ConfigValue value, ConfigSource source, SourceDeclaration declaration)
        implements Operation {

        /** Validates the components — every one of them is required. */
        public SetValue {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(declaration, "declaration");
        }

        /**
         * A value from a hand-built patch - the origin is the application code.
         *
         * @param value the value to set; must not be {@code null}
         */
        public SetValue(ConfigValue value) {
            this(value, ConfigSource.application(), SourceDeclaration.none());
        }

        /**
         * A value from an adapter that said nothing about type or notation - the typical case.
         *
         * @param value  the value to set; must not be {@code null}
         * @param source the origin of the value; must not be {@code null}
         */
        public SetValue(ConfigValue value, ConfigSource source) {
            this(value, source, SourceDeclaration.none());
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof SetValue set && value.equals(set.value)
                && declaration.equals(set.declaration);
        }

        @Override
        public int hashCode() {
            return Objects.hash(value, declaration);
        }

        @Override
        public String toString() {
            return "SetValue[" + value.display() + " @ " + source.display()
                + (declaration.isNone() ? "" : " + " + declaration) + "]";
        }
    }

    /**
     * Takes off one's own override, so the lookup continues down the chain.
     *
     * @param source the origin of the reset; must not be {@code null}
     * @since 1.0
     */
    public record Reset(ConfigSource source) implements Operation {

        /** Validates the components — every one of them is required. */
        public Reset {
            Objects.requireNonNull(source, "source");
        }

        /** A reset from a hand-built patch - the origin is the application code. */
        public Reset() {
            this(ConfigSource.application());
        }

        @Override
        public String toString() {
            return "Reset[" + source.display() + "]";
        }
    }


    /**
     * The source **delivered no value**: a loader could not obtain the value of a key it knew about, and
     * the adapter says so instead of staying silent. An adapter hands this fact over the way it received
     * it - nothing is corrected.
     *
     * <p><b>This is the adapter's own statement</b>, and it is the only path that reaches
     * {@code NullValue} without a value: the library's own adapters do not use it, because each of them
     * handles the same situation its own way. An ENV variable that is absent is not in the patch at all,
     * and an EMPTY one is ignored (see {@code EnvSource}); a command-line option with no parameter is
     * never a value - it is a USAGE error for EVERY name, declared or not (see {@code CliSource}), while
     * an option WITH a value stays an auxiliary key even when its name is unknown; a missing YAML key
     * states no address. What remains is an adapter that knows the value exists and cannot obtain it.
     *
     * <p>The validator is strict and turns it into {@link ConfigError.NullValue}, exactly as it does
     * with an empty string: **empty means an error**.
     *
     * <p><b>Why this exists when empty is an error anyway:</b> it is a seam for a **source failure**.
     * When a loader (for example of binaries over {@code file://}) cannot obtain the value, the
     * reason travels through here - there is room for it here, and not in an exception thrown in the
     * middle of a walk, because an interrupted validator would not collect the remaining problems.
     * This path will be empty most of the time, and that is fine: an empty seam is cheaper than
     * building one when a loader first throws.
     *
     * @param source the origin of the missing value; must not be {@code null}
     * @since 1.0
     */
    public record NullDelivered(ConfigSource source) implements Operation {
        /** Validates the components — every one of them is required. */
        public NullDelivered {
            Objects.requireNonNull(source, "source");
        }

        @Override
        public String toString() {
            return "NullDelivered[" + source.display() + "]";
        }
    }

    private static final ConfigPatch EMPTY = new ConfigPatch(Map.of());

    private final Map<ConfigPath, Operation> operations;

    private ConfigPatch(Map<ConfigPath, Operation> operations) {
        this.operations = Map.copyOf(operations);
    }

    /**
     * Returns an empty patch - applying it changes nothing.
     *
     * @return a patch that carries no operation
     */
    public static ConfigPatch empty() {
        return EMPTY;
    }

    /**
     * Returns a patch that sets a value under a path, with the application as its origin.
     *
     * @param path  the path to set; must not be {@code null}
     * @param value the value to set; must not be {@code null}
     * @return a patch carrying a single {@link SetValue}
     */
    public static ConfigPatch set(ConfigPath path, ConfigValue value) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(value, "value");
        return new ConfigPatch(Map.of(path, new SetValue(value)));
    }

    /**
     * As {@link #set(ConfigPath, ConfigValue)}, but with an origin coming from a source adapter.
     *
     * @param path   the path to set; must not be {@code null}
     * @param value  the value to set; must not be {@code null}
     * @param source the origin of the value; must not be {@code null}
     * @return a patch carrying a single {@link SetValue}
     */
    public static ConfigPatch set(ConfigPath path, ConfigValue value, ConfigSource source) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(value, "value");
        return new ConfigPatch(Map.of(path, new SetValue(value, source)));
    }

    /**
     * As {@link #set(ConfigPath, ConfigValue, ConfigSource)}, but the source also stated its type
     * or notation.
     *
     * @param path        the path to set; must not be {@code null}
     * @param value       the value to set; must not be {@code null}
     * @param source      the origin of the value; must not be {@code null}
     * @param declaration what the source stated about type and notation; must not be {@code null}
     * @return a patch carrying a single {@link SetValue}
     */
    public static ConfigPatch set(ConfigPath path, ConfigValue value, ConfigSource source,
                                  SourceDeclaration declaration) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(declaration, "declaration");
        return new ConfigPatch(Map.of(path, new SetValue(value, source, declaration)));
    }

    /**
     * {@link #set(ConfigPath, ConfigValue, ConfigSource, SourceDeclaration)} with a canonical path.
     *
     * @param canonicalPath the path in canonical notation; must not be {@code null}
     * @param value         the value to set; must not be {@code null}
     * @param source        the origin of the value; must not be {@code null}
     * @param declaration   what the source stated about type and notation; must not be {@code null}
     * @return a patch carrying a single {@link SetValue}
     */
    public static ConfigPatch set(String canonicalPath, ConfigValue value, ConfigSource source,
                                  SourceDeclaration declaration) {
        return set(ConfigPath.of(canonicalPath), value, source, declaration);
    }

    /**
     * {@link #set(ConfigPath, ConfigValue)} with a canonical path - the string is only an entry
     * point.
     *
     * @param canonicalPath the path in canonical notation; must not be {@code null}
     * @param value         the value to set; must not be {@code null}
     * @return a patch carrying a single {@link SetValue}
     */
    public static ConfigPatch set(String canonicalPath, ConfigValue value) {
        return set(ConfigPath.of(canonicalPath), value);
    }

    /**
     * Returns a patch that takes the override off a path, so the lookup continues down the chain.
     *
     * @param path the path to reset; must not be {@code null}
     * @return a patch carrying a single {@link Reset}
     */
    public static ConfigPatch reset(ConfigPath path) {
        Objects.requireNonNull(path, "path");
        return new ConfigPatch(Map.of(path, new Reset()));
    }

    /**
     * {@link #reset(ConfigPath)} with a canonical path.
     *
     * @param canonicalPath the path in canonical notation; must not be {@code null}
     * @return a patch carrying a single {@link Reset}
     */
    public static ConfigPatch reset(String canonicalPath) {
        return reset(ConfigPath.of(canonicalPath));
    }

    /**
     * A reset with an explicit origin - this is what the TUI and the API use, since a reset is an
     * interactive operation.
     *
     * @param path   the path to reset; must not be {@code null}
     * @param source the origin of the reset; must not be {@code null}
     * @return a patch carrying a single {@link Reset}
     */
    public static ConfigPatch reset(ConfigPath path, ConfigSource source) {
        Objects.requireNonNull(path, "path");
        return new ConfigPatch(Map.of(path, new Reset(source)));
    }

    /**
     * {@link #reset(ConfigPath, ConfigSource)} with a canonical path.
     *
     * @param canonicalPath the path in canonical notation; must not be {@code null}
     * @param source        the origin of the reset; must not be {@code null}
     * @return a patch carrying a single {@link Reset}
     */
    public static ConfigPatch reset(String canonicalPath, ConfigSource source) {
        return reset(ConfigPath.of(canonicalPath), source);
    }

    /**
     * The source delivered no value - the validator turns this into {@code NullValue}. It is also
     * the seam for the reason a loader failed; see {@link NullDelivered}.
     *
     * @param path   the path the source said nothing about; must not be {@code null}
     * @param source the origin of the missing value; must not be {@code null}
     * @return a patch carrying a single {@link NullDelivered}
     */
    public static ConfigPatch nullDelivered(ConfigPath path, ConfigSource source) {
        Objects.requireNonNull(path, "path");
        return new ConfigPatch(Map.of(path, new NullDelivered(source)));
    }

    /**
     * {@link #nullDelivered(ConfigPath, ConfigSource)} with a canonical path.
     *
     * @param canonicalPath the path in canonical notation; must not be {@code null}
     * @param source        the origin of the missing value; must not be {@code null}
     * @return a patch carrying a single {@link NullDelivered}
     */
    public static ConfigPatch nullDelivered(String canonicalPath, ConfigSource source) {
        return nullDelivered(ConfigPath.of(canonicalPath), source);
    }

    /**
     * This patch with a value added under a path - no explicit {@link SetValue} needed. This patch is
     * left unchanged.
     *
     * @param path  the path to set; must not be {@code null}
     * @param value the value to set; must not be {@code null}
     * @return a new patch carrying that value in addition to the existing operations
     */
    public ConfigPatch with(ConfigPath path, ConfigValue value) {
        Objects.requireNonNull(value, "value");
        return with(path, new SetValue(value));
    }

    /**
     * As {@link #with(ConfigPath, ConfigValue)}, but with an origin coming from a source adapter.
     *
     * @param path   the path to set; must not be {@code null}
     * @param value  the value to set; must not be {@code null}
     * @param source the origin of the value; must not be {@code null}
     * @return a new patch carrying that value in addition to the existing operations
     */
    public ConfigPatch with(ConfigPath path, ConfigValue value, ConfigSource source) {
        Objects.requireNonNull(value, "value");
        return with(path, new SetValue(value, source));
    }

    /**
     * {@link #with(ConfigPath, ConfigValue, ConfigSource)} with a canonical path.
     *
     * @param canonicalPath the path in canonical notation; must not be {@code null}
     * @param value         the value to set; must not be {@code null}
     * @param source        the origin of the value; must not be {@code null}
     * @return a new patch carrying that value in addition to the existing operations
     */
    public ConfigPatch with(String canonicalPath, ConfigValue value, ConfigSource source) {
        return with(ConfigPath.of(canonicalPath), value, source);
    }

    /**
     * {@link #with(ConfigPath, ConfigValue)} with a canonical path - the string is only an entry
     * point.
     *
     * @param canonicalPath the path in canonical notation; must not be {@code null}
     * @param value         the value to set; must not be {@code null}
     * @return a new patch carrying that value in addition to the existing operations
     */
    public ConfigPatch with(String canonicalPath, ConfigValue value) {
        return with(ConfigPath.of(canonicalPath), value);
    }

    /**
     * As {@link #with(ConfigPath, ConfigValue, ConfigSource)}, but the source also stated a type
     * and/or a notation ({@code ___TYPE}, {@code ___TCONF}).
     *
     * @param path        the path to set; must not be {@code null}
     * @param value       the value to set; must not be {@code null}
     * @param source      the origin of the value; must not be {@code null}
     * @param declaration what the source stated about type and notation; must not be {@code null}
     * @return a new patch carrying that value in addition to the existing operations
     */
    public ConfigPatch with(ConfigPath path, ConfigValue value, ConfigSource source,
                            SourceDeclaration declaration) {
        Objects.requireNonNull(value, "value");
        return with(path, new SetValue(value, source, declaration));
    }

    /**
     * {@link #with(ConfigPath, ConfigValue, ConfigSource, SourceDeclaration)} with a canonical path.
     *
     * @param canonicalPath the path in canonical notation; must not be {@code null}
     * @param value         the value to set; must not be {@code null}
     * @param source        the origin of the value; must not be {@code null}
     * @param declaration   what the source stated about type and notation; must not be {@code null}
     * @return a new patch carrying that value in addition to the existing operations
     */
    public ConfigPatch with(String canonicalPath, ConfigValue value, ConfigSource source,
                            SourceDeclaration declaration) {
        return with(ConfigPath.of(canonicalPath), value, source, declaration);
    }

    /**
     * Adds an operation directly ({@link SetValue}, {@link Reset} or {@link NullDelivered}) - this is
     * the primitive form.
     *
     * @param path      the path the operation applies to; must not be {@code null}
     * @param operation the operation; must not be {@code null}
     * @return a new patch carrying that operation in addition to the existing ones
     */
    public ConfigPatch with(ConfigPath path, Operation operation) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(operation, "operation");
        Map<ConfigPath, Operation> merged = new LinkedHashMap<>(operations);
        merged.put(path, operation);
        return new ConfigPatch(merged);
    }

    /**
     * Merges two patches: for the same path the operation from the **later** patch wins, that is,
     * from the layer later in the chain. The application decides the order of merging.
     *
     * @param later the patch merged on top of this one; must not be {@code null}
     * @return a new patch with the operations of this patch, overridden by those of {@code later}
     */
    public ConfigPatch merge(ConfigPatch later) {
        Objects.requireNonNull(later, "later");
        Map<ConfigPath, Operation> merged = new LinkedHashMap<>(operations);
        merged.putAll(later.operations);
        return new ConfigPatch(merged);
    }

    /**
     * Returns whether this patch carries no operation at all.
     *
     * @return true when there is nothing to apply
     */
    public boolean isEmpty() {
        return operations.isEmpty();
    }

    /**
     * Returns the number of paths this patch carries an operation for.
     *
     * @return the number of operations
     */
    public int size() {
        return operations.size();
    }

    /**
     * The operations by path - an unmodifiable view.
     *
     * @return the operations keyed by path
     */
    public Map<ConfigPath, Operation> operations() {
        return operations;
    }

    /**
     * The paths this patch carries an operation for.
     *
     * @return an unmodifiable list of paths
     */
    public List<ConfigPath> paths() {
        return List.copyOf(operations.keySet());
    }

    /**
     * The operation carried under a path, if this patch carries one.
     *
     * @param path the path to look up; must not be {@code null}
     * @return the operation, or empty when this patch says nothing about that path
     */
    public Optional<Operation> operation(ConfigPath path) {
        return Optional.ofNullable(operations.get(path));
    }

    /**
     * {@link #operation(ConfigPath)} with a canonical path.
     *
     * @param canonicalPath the path in canonical notation; must not be {@code null}
     * @return the operation, or empty when this patch says nothing about that path
     */
    public Optional<Operation> operation(String canonicalPath) {
        return operation(ConfigPath.of(canonicalPath));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ConfigPatch patch && operations.equals(patch.operations);
    }

    @Override
    public int hashCode() {
        return operations.hashCode();
    }

    @Override
    public String toString() {
        return "ConfigPatch" + operations;
    }
}
