package dev.jucm.core;

import java.util.Objects;

/**
 * A SEALED VALUE, KEPT SYMBOLIC SO ITS KEY CAN BE AN INTERPOLATION.
 *
 * <p>`!decrypt` with `ciphertext` and `key` becomes this, not a plain string: the key may be `${vault_key}`, and JUCM resolves `${…}` **at the read**, against one
 * snapshot (owner, 31 X 2026: „vault_key wsadzić też jako base64 do głównej konfiguracji. potem interpolować tę wartość w miejscach”). Opening it at parse time would
 * freeze the key to the literal text `${vault_key}` and fail.
 *
 * <p>THE OPENING HAPPENS IN {@link Config#get(ConfigPath)}: the key is resolved like any other reference, then {@link Sealed#open} produces the value. A sealed value
 * therefore behaves like a string EVERYWHERE it is read, and never appears as itself in a rendering - {@link #display()} counts, it does not show.
 *
 * @param ciphertext `base64(nonce || ciphertext || tag)`
 * @param key        the key as the file wrote it: a literal, or a `${…}` expression to resolve at the read
 * @since 1.0
 */
public record ConfigSealed(String ciphertext, String key) implements ConfigValue {

    /** Validates the components: both are required, and neither may be blank. */
    public ConfigSealed {
        Objects.requireNonNull(ciphertext, "sealed: ciphertext");
        Objects.requireNonNull(key, "sealed: key");
        if (ciphertext.isBlank()) {
            throw new IllegalArgumentException("sealed: the ciphertext is blank, so there is nothing to open");
        }
    }

    @Override
    public ConfigType type() {
        return ConfigType.STR;
    }

    /** A sealed value is a text once opened, and its own notation NEVER shows the ciphertext or the key. */
    @Override
    public String display() {
        return "sealed[" + ciphertext.length() + " chars]";
    }
}
