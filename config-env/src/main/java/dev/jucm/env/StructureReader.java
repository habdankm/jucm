package dev.jucm.env;

import dev.jucm.core.ConfigError;
import dev.jucm.core.ConfigResult;
import dev.jucm.core.ConfigSource;
import dev.jucm.core.ConfigValue;
import dev.jucm.core.Layer;

/**
 * A reader of <b>flow literals</b> ({@code [a, b]}, {@code {a: 1}}) — the only place where text becomes
 * a structure.
 *
 * <p>The interface lives in the ENV module while the implementation is <b>injected by the
 * application</b>. That is not decoration: source modules do not know one another, so {@code config-env}
 * <b>must not</b> depend on {@code config-yaml}. And still there has to be one place that turns text
 * into a structure, because otherwise a list from ENV and a list from YAML would diverge at the first
 * comma or quote inside a value.
 *
 * <p>The seam is thin: the application passes a reference to the file reader
 * ({@code EnvSource.of(env, prefix, YamlSource::value)}), and nobody's dependency changes.
 *
 * @since 1.0
 */
@FunctionalInterface
public interface StructureReader {

    /**
     * Reads text into a structure ({@code ConfigSeq}/{@code ConfigMap}), or an error with the reason.
     *
     * @param text text of the value to read; must not be {@code null}
     * @return the structure, or a parse error saying why the text is not readable
     * @since 1.0
     */
    ConfigResult<ConfigValue> read(String text);

    /**
     * No reader: a flow literal becomes an error with a reason instead of a silent string.
     *
     * <p>A value that looks like a structure and has nothing to be read with MUST be visible —
     * otherwise {@code [1, 2]} would enter the configuration as text and only surface at the cast, far
     * away from its cause.
     *
     * @return a reader that rejects every text, including a well-formed flow literal
     * @since 1.0
     */
    static StructureReader none() {
        return text -> ConfigResult.error(new ConfigError.ParseFailed(
            ConfigSource.of(Layer.ENV, "env"), "the value `" + text + "` is a flow literal, and the adapter"
                + " has no structure reader injected"));
    }
}
