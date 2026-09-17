package dev.jucm.casciian;

import casciian.TApplication;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.charset.StandardCharsets;
import casciian.TKeypress;
import casciian.backend.Backend;
import casciian.event.TKeypressEvent;

/**
 * The JUCM TUI application with a <strong>default exit path</strong>.
 *
 * <p>The reason is practical: a caller, human or agent, MUST be able to close a running application.
 * Without an exit path a failed run leaves a process behind and a test has no way to end the loop —
 * so {@code ESC} quits from the first run onwards, and the configuration can hide it if it gets in the
 * way.
 *
 * <p>The hook lives in this subclass because casciian declares {@code TApplication.onKeypress} as
 * {@code protected}: the exit path is an extension point, not a plugin, and no casciian change is
 * needed for it.
 *
 * @since 1.0
 */
public class TuiApplication extends TApplication {

    private boolean quitOnEscape = true;
    private final AtomicInteger repaints = new AtomicInteger();

    /**
     * Creates an application on a caller-supplied backend.
     *
     * @param backend backend the application draws on; must not be {@code null}
     * @since 1.0
     */
    public TuiApplication(Backend backend) {
        super(backend);
    }

    /**
     * Creates an application on a real terminal of the given type (XTERM/ECMA48 is what runs a TUI for
     * a human); tests pass a backend instead.
     *
     * @param type terminal backend type; must not be {@code null}
     * @throws java.io.UnsupportedEncodingException if the terminal encoding cannot be set up — there
     *         are then no conditions for an interactive interface
     * @since 1.0
     */
    public TuiApplication(BackendType type) throws java.io.UnsupportedEncodingException {
        super(type);
    }

    /**
     * Returns an application on a real terminal with a window title — the single entry point for
     * callers that are not meant to know casciian: the backend type and {@code setTitle} are details of
     * this module, not of theirs.
     *
     * @param title window title; must not be {@code null}
     * @return the application, not yet running
     * @throws java.io.UnsupportedEncodingException if the terminal encoding cannot be set up
     * @since 1.0
     */
    public static TuiApplication onTerminal(String title) throws java.io.UnsupportedEncodingException {
        TuiApplication application = new TuiApplication(BackendType.XTERM);
        application.getBackend().setTitle(title);
        return application;
    }

    /**
     * Sets whether {@code ESC} quits the application.
     *
     * @param enabled {@code true} (the default) to quit on {@code ESC}; {@code false} to leave the key
     *        to the widgets
     * @return this application, for chaining
     * @since 1.0
     */
    public TuiApplication withQuitOnEscape(boolean enabled) {
        this.quitOnEscape = enabled;
        return this;
    }

    /**
     * Returns how many times a full repaint has been requested.
     *
     * <p>The value is a counter, not a flag: a test that polls with {@code await} asks repeatedly, so a
     * getter with a side effect of its own (clearing the flag) would report wrongly.
     *
     * @return the number of repaints requested so far, never reset
     * @since 1.0
     */
    public int repaints() {
        return repaints.get();
    }

    /**
     * Runs the configurator on a real terminal and returns the exit code — the single entry point for
     * callers that are not meant to know casciian or its life cycle.
     *
     * @param definition declarations the configuration is built from; must not be {@code null}
     * @param registry transformers available to the configuration; must not be {@code null}
     * @param layers configuration layers, lowest first; must not be {@code null}
     * @param targets where the save actions write; must not be {@code null}
     * @param program program name, used in the window title; must not be {@code null}
     * @return {@link dev.jucm.core.ConfigExitCode#OK} once the loop ends;
     *         {@link dev.jucm.core.ConfigExitCode#USAGE_ERROR} when the terminal encoding cannot be set
     *         up — a missing condition for running the interface rather than a configuration failure
     * @since 1.0
     */
    public static int runConfigurator(dev.jucm.core.ConfigDefinition definition,
                                      dev.jucm.core.TransformerRegistry registry,
                                      dev.jucm.core.ConfigPatch[] layers,
                                      Configurator.Targets targets, String program) {
        return runConfigurator(definition, registry, layers, targets, program, view -> { });
    }

    /**
     * Runs the configurator on a real terminal, with an <strong>extension point</strong>: the caller
     * adds its own widgets to the same window.
     *
     * <p>Two things happen here and nowhere else: the window that writes takes the source directory
     * from {@link Configurator.Targets}, and after the loop ends the terminal is restored to its state
     * from before the run.
     *
     * @param definition declarations the configuration is built from; must not be {@code null}
     * @param registry transformers available to the configuration; must not be {@code null}
     * @param layers configuration layers, lowest first; must not be {@code null}
     * @param targets where the save actions write; must not be {@code null}
     * @param program program name, used in the window title; must not be {@code null}
     * @param extension called once with the running configurator, before the loop starts; must not be
     *        {@code null}
     * @return {@link dev.jucm.core.ConfigExitCode#OK} once the loop ends;
     *         {@link dev.jucm.core.ConfigExitCode#USAGE_ERROR} when the terminal encoding cannot be set up
     * @since 1.0
     */
    public static int runConfigurator(dev.jucm.core.ConfigDefinition definition,
                                      dev.jucm.core.TransformerRegistry registry,
                                      dev.jucm.core.ConfigPatch[] layers,
                                      Configurator.Targets targets, String program,
                                      java.util.function.Consumer<Configurator> extension) {
        try {
            TuiApplication application = onTerminal(program + " - configurator (JUCM)");
            Configurator.open(application, definition,
                dev.jucm.core.ConfigDraft.of(definition, registry, layers), targets, program, extension);
            application.run();
            return dev.jucm.core.ConfigExitCode.OK;
        } catch (java.io.UnsupportedEncodingException broken) {
            System.err.println("error=TUI message=" + broken.getMessage());
            return dev.jucm.core.ConfigExitCode.USAGE_ERROR;
        }
    }

    @Override
    public void doRepaint() {
        // The superclass constructor calls doRepaint() before this class's fields are initialised, so
        // the counter is still null at that point. Counting is therefore limited to the calls made
        // after construction — without this check, creating the application would throw.
        AtomicInteger counter = repaints;
        if (counter != null) {
            counter.incrementAndGet();
        }
        super.doRepaint();
    }

    /**
     * Restores the terminal after the loop ends, in two steps that are both needed:
     *
     * <ol>
     *   <li>{@code restoreConsole()} (casciian) removes its own traces: raw mode, mouse, palette,
     *       cursor. {@code TApplication.run()} does not call it on a normal exit (only its internal
     *       handles do, and only from a {@code catch}), so without this step the terminal would stay in
     *       raw mode.</li>
     *   <li>the exact terminal state is snapshotted with {@code stty -g} before the run and restored
     *       after it, because {@code restoreConsole()} restores terminal <em>attributes</em>, which are
     *       not the same thing as the state captured by {@code stty -g} — a terminal left in the wrong
     *       input mode shows up as a broken line editor in the shell.</li>
     * </ol>
     *
     * <p>The state is saved before anything is changed and restored in a {@code finally}, so every way
     * out of the loop ({@code ESC}, cancel, window close, exception) ends the same way. On systems
     * without {@code stty} (Windows, pipes) both steps are silent no-ops.
     */
    @Override
    public void run() {
        String saved = sttySnapshot();
        try {
            super.run();
        } finally {
            restoreConsole();
            restoreStty(saved);
        }
    }

    /** Exact terminal state ({@code stty -g}), or {@code null} when there is no terminal or no {@code stty}. */
    private static String sttySnapshot() {
        return shell("stty -g");
    }

    /** Restores a saved state; {@code stty -g} yields a string of digits and colons. */
    private static void restoreStty(String saved) {
        if (saved != null && !saved.isBlank()) {
            shell("stty '" + saved + "'");
        }
    }

    private static String shell(String command) {
        try {
            Process process = new ProcessBuilder("/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor() == 0 ? output : null;
        } catch (IOException unavailable) {
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Override
    protected boolean onKeypress(TKeypressEvent event) {
        if (quitOnEscape && event.matchesKey(TKeypress.kbEsc)) {
            exit();
            return true;
        }
        return super.onKeypress(event);
    }
}
