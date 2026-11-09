package dev.jucm.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * ONE VALUE, SEALED WITH A KEY THAT TRAVELS WITH IT - the machinery behind the `!decrypt` tag.
 *
 * <p>WHY THIS LIVES IN CORE AND NOT IN A VAULT MODULE (owner, 31 X 2026): the value is a CONFIGURATION VALUE, so the type that reads it at read time is the same one
 * that reads references - and a sealed value whose key is `${vault_key}` cannot be opened by a module that sees the tree only after the YAML is parsed. The
 * encryption is a plain-JDK construction, so core keeps its promise of no third-party dependencies.
 *
 * <p>THE SHAPE A PERSON WRITES:
 * <pre>
 *   vault_key: "bXktc2VjcmV0LWtleQ=="
 *   storage:
 *     corgimq:
 *       password: !decrypt
 *         ciphertext: "9mXk…"          # base64(nonce || ciphertext || tag)
 *         key: ${vault_key}             # the value, interpolated where it is used
 * </pre>
 *
 * <p>AES-256-GCM, a fresh 12-byte nonce per seal, a 128-bit tag, stored as `base64(nonce || ciphertext || tag)`.
 *
 * <p>THE KEY IS TEXT, AND ITS 32 BYTES ARE SHA-256 OF THAT TEXT: any passphrase works, nothing has to be base64, and there is no second rule to remember. The
 * hash is a derivation, not a password check - a weak passphrase is still a weak passphrase, and this class says so rather than pretending.
 *
 * <p>NO VALUE EVER APPEARS IN AN EXCEPTION: a wrong key and a corrupt token both answer "cannot be opened", with no fragment of either.
 */
public final class Sealed {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String CIPHER = "AES/GCM/NoPadding";

    /**
     * Seals a value for one key.
     *
     * @param plaintext what to protect
     * @param key       the key text (any length; 32 bytes are derived from it)
     * @return `base64(nonce || ciphertext || tag)`, the text a configuration file carries
     */
    public static String seal(String plaintext, String key) {
        if (plaintext == null) {
            throw new IllegalArgumentException("nothing to seal: the value is null");
        }
        requireKey(key);
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, keyOf(key), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] token = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, token, 0, nonce.length);
            System.arraycopy(sealed, 0, token, nonce.length, sealed.length);
            return Base64.getEncoder().encodeToString(token);
        } catch (Exception broken) {
            throw new IllegalArgumentException("the value could not be sealed: " + broken.getClass().getSimpleName());
        }
    }

    /**
     * Opens a sealed value with its key.
     *
     * @param token `base64(nonce || ciphertext || tag)`, as the configuration carries it
     * @param key   the key text the value was sealed with
     * @return the plain value
     * @throws IllegalArgumentException when the token is not base64, is truncated, or does not open with this key - NEVER with the value in the sentence
     */
    public static String open(String token, String key) {
        requireKey(key);
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("a `!decrypt` value with no ciphertext: there is nothing to open");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(token.trim());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalArgumentException("the `!decrypt` ciphertext is not base64");
        }
        if (raw.length <= NONCE_BYTES + TAG_BITS / 8) {
            throw new IllegalArgumentException("the `!decrypt` ciphertext is too short to carry a nonce, a tag and a value");
        }
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, keyOf(key), new GCMParameterSpec(TAG_BITS, raw, 0, NONCE_BYTES));
            return new String(cipher.doFinal(raw, NONCE_BYTES, raw.length - NONCE_BYTES), StandardCharsets.UTF_8);
        } catch (Exception wrongKey) {
            throw new IllegalArgumentException("the value cannot be opened with this key: it was sealed with another one, or the text was changed");
        }
    }

    private static SecretKeySpec keyOf(String key) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }

    private static void requireKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("a `!decrypt` value with no key: the `key` member is the key the value was sealed with");
        }
    }

    private Sealed() {
    }
}
