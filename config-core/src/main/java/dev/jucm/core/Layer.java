package dev.jucm.core;

/**
 * The kind of layer that contributed a value.
 *
 * <p>A layer is not identified by its origin: a file, an environment variable and a command-line
 * option may all contribute to the same layer kind. The order in which layers are applied is
 * configured by the application (see {@link StandardPipeline}) and is not fixed by this library.
 */
public enum Layer {

    /** Values materialised from the field declarations of the application model. */
    COMPILED,

    /** Values read from a YAML source. */
    YAML,

    /** Values read from environment variables. */
    ENV,

    /** Values read from command-line arguments. */
    CLI,

    /** Values edited in the configuration UI. */
    TUI,

    /** Values contributed by application code with {@link ConfigSource#application()}. */
    CUSTOM
}
