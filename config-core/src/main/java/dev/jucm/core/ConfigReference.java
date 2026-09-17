package dev.jucm.core;

import java.util.Objects;

/**
 * A reference to another path, such as {@code ${a}}.
 *
 * <p>The value of a reference is not copied: reading the field resolves the reference against the
 * current configuration and returns the target's value. A reference is therefore never equal to the
 * value it points at.
 *
 * <p>{@code optional} marks the form {@code ${?a}}, which yields no value instead of an error when
 * the target is absent.
 *
 * @param target   the path this reference points at; never null
 * @param optional whether an absent target yields no value instead of an error
 */
public record ConfigReference(ConfigPath target, boolean optional) implements ConfigValue {

    /** Validates the components — every one of them is required. */
    public ConfigReference {
        Objects.requireNonNull(target, "target");
    }

    /**
     * Creates a reference whose target must exist.
     *
     * @param canonicalTarget canonical path of the target, for example {@code server.port}
     * @return the reference
     */
    public static ConfigReference to(String canonicalTarget) {
        return new ConfigReference(ConfigPath.of(canonicalTarget), false);
    }

    /**
     * Creates a reference whose target may be absent.
     *
     * @param canonicalTarget canonical path of the target, for example {@code server.port}
     * @return the reference
     */
    public static ConfigReference optional(String canonicalTarget) {
        return new ConfigReference(ConfigPath.of(canonicalTarget), true);
    }

    /**
     * Returns the type of this value.
     *
     * @throws UnsupportedOperationException always; the type is the target's type, which is only known
     *         after the reference is resolved by reading the configuration through
     *         {@link Config#get}
     */
    @Override
    public ConfigType type() {
        throw new UnsupportedOperationException("ConfigReference has no type before resolution — "
            + "references are resolved by the INTERPOLATION step (`ConfigPipeline.interpolation`)");
    }

    /**
     * Returns the notation of this reference.
     *
     * @return {@code ${target}} or {@code ${?target}} for an optional reference
     */
    @Override
    public String display() {
        return "${" + (optional ? "?" : "") + target.canonical() + "}";
    }
}
