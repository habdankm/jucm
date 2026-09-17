package dev.jucm.core;

import java.util.List;
import java.util.Objects;

/**
 * Exit codes for configuration errors — `XY`.
 *
 * <pre>
 * X = decade = the layer's position in the CURRENT pipeline (1 = CompiledDefaults, 2 = the first
 *     sourced one, 3 = the second, ... 9 = the eighth and every later)  -> 51 = cast error in the TUI
 *     (5th layer)
 * Y = category of the cause: 0 parse/input, 1 conversion, 2 resolution, 3 validation,
 *     4 missing required, 5 path/model, 6 (reserved), 7 I/O, 9 other
 * </pre>
 *
 * <p><b>The zero decade belongs to the application, not to us</b>: single-digit codes `1..9`
 * are left to whoever
 * writes an application, so a failure in `CompiledDefaults` starts at `10`, not at `01`. The practical
 * benefit: **a one-digit code is never ours**.
 *
 * <p><b>One code when there are many errors</b>: the **lowest `XY`** wins — the earliest
 * layer, and within the same layer the lowest kind. Thanks to that the code does not depend on the
 * order of detection, and `0` means exactly "zero problems". The rule applies **only inside the
 * configuration error class**: an engine failure (`100-124`) does not compete with configuration for
 * the code.
 *
 * <p>The layer number is **positional**, so it depends on the pipeline order; a layer replaced
 * by the application shifts the numbers, which is why messages carry {@link Layer} as well, since it is
 * stable.
 */
public final class ConfigExitCode {

    /** CLI usage error (picocli) — not a configuration error. */
    public static final int USAGE_ERROR = 125;

    /** No problems — exactly "zero errors". */
    public static final int OK = 0;

    private ConfigExitCode() {
    }

    /**
     * The layer pipeline in order — the decade is computed from it. Standard: compiled, YAML, ENV, CLI,
     * TUI; an application may replace the order and the numbers shift with it.
     *
     * @param layers the layers in the order they contribute; never null, never empty
     */
    public record Pipeline(List<Layer> layers) {

        /**
         * The pipeline every application gets by default: one entry per layer kind an entry can come
         * from, in the order the toolbox assembles them. {@code CUSTOM} is part of it on purpose —
         * an application may contribute values programmatically or through its own source, and a layer
         * that is missing here has no position to be numbered with, so an error from it could not be
         * reported at all. It sits below {@code TUI}, the window, which is the user's last word.
         */
        public static final Pipeline STANDARD = new Pipeline(
            List.of(Layer.COMPILED, Layer.YAML, Layer.ENV, Layer.CLI, Layer.CUSTOM, Layer.TUI));

        /** A pipeline without layers has nothing to number, so it is rejected. */
        public Pipeline {
            Objects.requireNonNull(layers, "layers");
            if (layers.isEmpty()) {
                throw new IllegalArgumentException("a pipeline without layers has nothing to number");
            }
            layers = List.copyOf(layers);
        }

        /**
         * The layer position (1-based) saturated at 9 — the number does not grow forever.
         *
         * @param layer the layer to locate; must be one of {@link #layers()}
         * @return the position, at most 9 even for the ninth and every later layer
         * @throws IllegalArgumentException if the layer does not occur in this pipeline
         */
        public int positionOf(Layer layer) {
            int index = layers.indexOf(layer);
            if (index < 0) {
                throw new IllegalArgumentException("layer `" + layer
                    + "` does not occur in this pipeline: " + layers);
            }
            return Math.min(index + 1, 9);
        }
    }

    /**
     * The `XY` code for a single problem.
     *
     * @param error    the problem to translate; must not be null
     * @param pipeline the pipeline the layer positions come from; must not be null
     * @return the code, its `X` decade taken from the layer of the error's source
     */
    public static int of(ConfigError error, Pipeline pipeline) {
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(pipeline, "pipeline");
        return pipeline.positionOf(layerOf(error)) * 10 + categoryOf(error.kind());
    }

    /**
     * One code for the whole report: the lowest `XY`, and {@link #OK} when there are no problems.
     *
     * @param errors   the problems of one report; must not be null, may be empty
     * @param pipeline the pipeline the layer positions come from; must not be null
     * @return the lowest code, or {@link #OK} for an empty list
     */
    public static int lowestOf(List<ConfigError> errors, Pipeline pipeline) {
        Objects.requireNonNull(errors, "errors");
        return errors.stream().mapToInt(error -> of(error, pipeline)).min().orElse(OK);
    }

    /**
     * The layer the problem concerns. `Absent` has no source — no layer contributed anything — so it
     * counts as missing in the **declaration** layer (the root), because that is where the field
     * declaration lives.
     */
    private static Layer layerOf(ConfigError error) {
        if (error instanceof ConfigError.Absent) {
            return Layer.COMPILED;
        }
        return error.source().layer();
    }

    /**
     * Category `Y` — we classify **by cause**, not by Java class. The range `0..9`
     * is closed: a new error kind does not get a new code, only the nearest category.
     */
    private static int categoryOf(ConfigError.Kind kind) {
        return switch (kind) {
            case PARSE_FAILED, NULL_VALUE, INVALID_SYNTAX, TRANSFORM_FAILED -> 0;
            case CAST_FAILED -> 1;
            case UNRESOLVED_REFERENCE, UNRESOLVED_EXPRESSION, CYCLE -> 2;
            // A MODEL error goes to the validation family: a script must see that the configuration did
            // NOT pass, and the distinction "this is the declaration's fault, not the data's" lives in
            // the message, not in the code.
            case VALIDATION_FAILED, UNUSED_AUXILIARY -> 3;
            // A MODEL error (a rule contradicting the declaration) goes to the `5 = Path / Model`
            // family, not to validation. The `X` decade stays with the entry's layer, because in our
            // case such an error HAS a source.
            case RULE_MISMATCH -> 5;
            case ABSENT -> 4;
            case UNKNOWN_PATH -> 5;
            // Y = 6 is reserved for future layer composition errors; Y = 8 does not exist
            case FILE_NOT_EXIST -> 7;
        };
    }
}
