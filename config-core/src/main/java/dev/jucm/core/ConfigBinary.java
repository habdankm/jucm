package dev.jucm.core;

import java.net.URI;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * A binary value, held in memory as a byte array.
 *
 * <p>A URI is only an instruction how to obtain the value: the value itself is the blob. The scheme
 * is mandatory, because a bare payload is ambiguous. Of the schemes accepted by the signature, only
 * {@code base64://} is supported — {@code file://} and {@code https://} need to read an external
 * resource and are rejected until a source adapter can do that.
 *
 * <p>Instances are immutable; {@link #blob()} returns a copy, so callers cannot change the stored
 * bytes.
 */
public final class ConfigBinary implements ConfigValue {

    private static final String BASE64_PREFIX = "base64://";
    private static final String FILE_PREFIX = "file://";
    private static final String HTTPS_PREFIX = "https://";

    private final byte[] blob;

    private ConfigBinary(byte[] blob) {
        this.blob = blob.clone();
    }

    /**
     * Creates a binary value from an already encoded blob.
     *
     * @param blob the blob; must not be {@code null}, copied on construction
     * @return the binary value
     */
    public static ConfigBinary of(byte[] blob) {
        Objects.requireNonNull(blob, "blob");
        return new ConfigBinary(blob);
    }

    /**
     * Creates a binary value from a URI.
     *
     * @param uri URI with a scheme; must not be {@code null}
     * @return the decoded blob for a {@code base64://} URI
     * @throws UnsupportedOperationException if the scheme is {@code file://} or {@code https://}
     * @throws IllegalArgumentException if the scheme is missing, or the payload is not valid base64
     */
    public static ConfigBinary fromUri(URI uri) {
        Objects.requireNonNull(uri, "uri");
        String text = uri.toString();
        if (text.startsWith(BASE64_PREFIX)) {
            return new ConfigBinary(decode(text.substring(BASE64_PREFIX.length())));
        }
        if (text.startsWith(FILE_PREFIX) || text.startsWith(HTTPS_PREFIX)) {
            throw new UnsupportedOperationException("scheme `" + schemeOf(text)
                + "` needs reading a resource — it will arrive with a source adapter");
        }
        throw new IllegalArgumentException("missing scheme in `" + text
            + "` — allowed schemes: base64, file, https");
    }

    private static String schemeOf(String text) {
        return text.substring(0, text.indexOf("://"));
    }

    private static byte[] decode(String payload) {
        try {
            return Base64.getDecoder().decode(payload.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid base64 in `" + BASE64_PREFIX + "`", e);
        }
    }

    /**
     * Returns the blob.
     *
     * @return a copy of the stored bytes
     */
    public byte[] blob() {
        return blob.clone();
    }

    /**
     * Returns the size of the blob in bytes.
     *
     * @return the number of stored bytes
     */
    public int size() {
        return blob.length;
    }

    @Override
    public ConfigType type() {
        return ConfigType.BINARY;
    }

    /** Returns the size of the blob, never its content. */
    @Override
    public String display() {
        return "binary[" + blob.length + " B]";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ConfigBinary binary && Arrays.equals(blob, binary.blob);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(blob);
    }

    @Override
    public String toString() {
        return display();
    }
}
