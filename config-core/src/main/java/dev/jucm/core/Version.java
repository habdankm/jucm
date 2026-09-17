package dev.jucm.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The version of this library, read from a resource filled in at build time.
 */
public final class Version {

    private static final String UNKNOWN = "unknown";

    private Version() {
    }

    /**
     * Returns the library version.
     *
     * @return the version string, or {@code "unknown"} when the build resource is missing
     */
    public static String get() {
        try (InputStream in = Version.class.getResourceAsStream("/version.properties")) {
            if (in == null) {
                return UNKNOWN;
            }
            Properties props = new Properties();
            props.load(in);
            return props.getProperty("version", UNKNOWN);
        } catch (IOException e) {
            return UNKNOWN;
        }
    }
}
