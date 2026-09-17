package dev.jucm.core;

/**
 * The standard configuration pipeline — **what makes sense ships by default**, and the layer order
 * remains the application's contract.
 *
 * <p>We do two things, and only two:
 *
 * <ol>
 *   <li>we merge the layers **in the order given by the caller** — the library does not know the set of
 *       sources (the core knows nothing about formats, let alone that YAML comes
 *       "before" ENV);</li>
 *   <li>we run the standard pipeline: interpolation -> transformation -> cast -> constraints,
 *       because that is exactly what "a standard that makes sense" means.</li>
 * </ol>
 *
 * <p>When a **different order** makes sense, the layers are given differently — e.g. a
 * configuration-driven application, for which a file is the source of truth rather than code, calls
 * {@code of(definition, registry, cli, yaml)} and then the file beats the command line. When the **order
 * of steps or the set of steps** differs, the caller composes the pipeline from the public factories
 * ({@link ConfigPipeline#interpolation}, {@link ConfigPipeline#transformation}, {@link ConfigPipeline#cast},
 * {@link ConfigPipeline#constraints}) — this helper is not the only way.
 *
 * <p>Validation is a separate, explicit step ({@link ConfigValidator#build}), because a verdict is
 * diagnostics, not configuration building.
 */
public final class StandardPipeline {

    private StandardPipeline() {
    }

    /**
     * Merges the layers in the given order and runs the standard pipeline.
     *
     * @param definition    the application model the config is built from; must not be null
     * @param registry      the transformer registry the notation comes from; must not be null
     * @param layersInOrder layers in the order they CONTRIBUTE: the last one has the highest priority.
     *                      An empty layer ({@code ConfigPatch.empty()}) is allowed and means
     *                      "this source contributed nothing"
     * @return the resolved config; the layers passed in are not modified
     */
    public static Config of(ConfigDefinition definition, TransformerRegistry registry,
                            ConfigPatch... layersInOrder) {
        Config config = Config.root(definition);
        for (ConfigPatch layer : layersInOrder) {
            config = config.apply(layer);
        }
        return ConfigPipeline.all(definition, registry).apply(config);
    }
}
