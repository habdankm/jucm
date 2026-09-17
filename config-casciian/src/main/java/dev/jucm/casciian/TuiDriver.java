package dev.jucm.casciian;

import casciian.TApplication;
import casciian.TButton;
import casciian.TField;
import casciian.TKeypress;
import casciian.TLabel;
import casciian.TWidget;
import casciian.TWindow;
import casciian.backend.Backend;
import casciian.event.TKeypressEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Drives the TUI from a test — the counterpart of a browser driver for a terminal interface.
 *
 * <p>casciian has no such driver, but it has the seams one can be built on:
 *
 * <ul>
 *   <li>{@code application.postEvent(...)} accepts events ({@code TKeypressEvent}, {@code TPasteEvent}),
 *       so input is injected without a terminal;</li>
 *   <li>{@code HeadlessBackend} provides a screen without a terminal, and casciian's own tests run the
 *       application in a thread and end it with {@code exit()};</li>
 *   <li>typing into a field goes through the field setter ({@code TField.setText}), because this
 *       casciian version has no paste event (a paste event exists only in a newer casciian than the one
 *       this module compiles against), and the keyboard path would need character synthesis;</li>
 *   <li>{@code TWidget.getChildren()} exposes the widget tree, so widgets can be found by label without
 *       touching casciian internals — and the dependency stays a dependency, not a fork.</li>
 * </ul>
 *
 * <p>What casciian lacks and what is added here: widget identifiers ({@code TWidget} knows neither
 * {@code setId} nor {@code getId}), lookup by name, {@code click}/{@code fill} actions, and waiting with
 * a readable message.
 *
 * <p>Instances are not thread-safe. An instance owns the application thread it started and releases it
 * in {@link #close()}.
 *
 * @since 1.0
 */
public final class TuiDriver implements AutoCloseable {

    /** Waiting deadline: long enough for a slow machine, but a failure always ends with a message, never in silence. */
    private static final long TIMEOUT_MS = 5_000;

    private final TApplication application;
    private final Backend backend;
    private final Map<String, TWidget> byId = new LinkedHashMap<>();
    private final Thread loop;

    private TuiDriver(TApplication application, Backend backend) {
        this.application = application;
        this.backend = backend;
        this.loop = new Thread(application::run, "jucm-tui-test");
    }

    /**
     * Starts the application loop on its own thread.
     *
     * @param application application to run; must not be {@code null}
     * @param backend backend the application draws on; must not be {@code null}
     * @return a driver owning the started thread; close it with {@link #close()}
     * @since 1.0
     */
    public static TuiDriver start(TApplication application, Backend backend) {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(backend, "backend");
        TuiDriver driver = new TuiDriver(application, backend);
        driver.loop.start();
        return driver;
    }

    /**
     * Registers a widget under an id. The identifier belongs to the driver, because {@code TWidget} has
     * none of its own.
     *
     * @param id identifier to register under; must not be {@code null} or blank; an earlier registration
     *        under the same id is replaced
     * @param widget widget to register; must not be {@code null}
     * @return this driver, for chaining
     * @since 1.0
     */
    public TuiDriver id(String id, TWidget widget) {
        byId.put(Objects.requireNonNull(id, "id"), Objects.requireNonNull(widget, "widget"));
        return this;
    }

    /**
     * Returns the widget registered under an id, cast to the requested type.
     *
     * @param <T> widget type expected by the caller
     * @param id identifier given to {@link #id}
     * @param type type to cast to; must not be {@code null}
     * @return the registered widget, never {@code null}
     * @throws IllegalArgumentException if no widget is registered under {@code id}; the message lists the
     *         registered identifiers
     * @throws ClassCastException if the registered widget is not of {@code type}
     * @since 1.0
     */
    public <T extends TWidget> T getById(String id, Class<T> type) {
        TWidget found = byId.get(id);
        if (found == null) {
            throw new IllegalArgumentException("no widget registered under id `" + id + "`; registered: "
                + byId.keySet());
        }
        return type.cast(found);
    }

    /**
     * Returns the first button whose label matches, ignoring case and mnemonic markers.
     *
     * @param label button label as shown, without the {@code &} marker; must not be {@code null}
     * @return the matching button, never {@code null}
     * @throws AssertionError if the widget tree holds no such button
     * @since 1.0
     */
    public TButton findButton(String label) {
        for (TWidget widget : tree()) {
            if (widget instanceof TButton button && matches(label, button.getMnemonic().getRawLabel())) {
                return button;
            }
        }
        throw notFound("button", label);
    }

    /**
     * Returns the first field described by a label matching the given text — {@code TLabel.getLabelFor()}
     * says which widget the label belongs to, so "Username" is enough and no field names have to be
     * invented.
     *
     * @param label text of the describing label; must not be {@code null}
     * @return the field the matching label points at, never {@code null}
     * @throws AssertionError if the widget tree holds no such field
     * @since 1.0
     */
    public TField findField(String label) {
        for (TWidget widget : tree()) {
            if (widget instanceof TLabel<?> caption && matches(label, caption.getLabel())
                && caption.getLabelFor() instanceof TField field) {
                return field;
            }
        }
        throw notFound("field labelled", label);
    }

    /**
     * Returns the first label whose text matches — for asserting on what the user sees as a description.
     *
     * @param text label text; must not be {@code null}
     * @return the matching label, never {@code null}
     * @throws AssertionError if the widget tree holds no such label
     * @since 1.0
     */
    public TLabel<?> findLabel(String text) {
        for (TWidget widget : tree()) {
            if (widget instanceof TLabel<?> label && matches(text, label.getLabel())) {
                return label;
            }
        }
        throw notFound("label", text);
    }

    /**
     * Clicks a button by dispatching the widget's own activation path, not by synthesising mouse events.
     *
     * @param button button to click; must not be {@code null}
     * @since 1.0
     */
    public void click(TButton button) {
        Objects.requireNonNull(button, "button").dispatch();
    }

    /**
     * Types text into a field.
     *
     * <p>The text goes through the field setter, because this casciian version has no paste event and the
     * keyboard path would need character synthesis. If a test ever has to prove that text travels along
     * the KEYBOARD path, that synthesis is added separately.
     *
     * @param field field to fill; must not be {@code null}
     * @param text text to put in the field; must not be {@code null}; may be empty
     * @throws AssertionError if the field does not show the text before the deadline
     * @since 1.0
     */
    public void fill(TField field, String text) {
        Objects.requireNonNull(field, "field");
        field.setText(text);
        await(() -> text.equals(field.getText()), "field shows", "`" + text + "`");
    }

    /**
     * Sends a key to the application — this drives shortcuts and quits the application when the default
     * exit path is enabled.
     *
     * @param key key to send; must not be {@code null}
     * @since 1.0
     */
    public void sendKey(TKeypress key) {
        application.postEvent(new TKeypressEvent(backend, Objects.requireNonNull(key, "key")));
    }

    /**
     * Waits until a condition holds, up to the deadline.
     *
     * @param condition condition to wait for; must not be {@code null}
     * @param what what was waited for, in the caller's words; must not be {@code null}
     * @param expected expected value, in the caller's words; must not be {@code null}
     * @throws AssertionError if the condition is still false at the deadline — the message names what was
     *         waited for and the value expected, so a failure cannot be mistaken for a generic timeout
     * @since 1.0
     */
    public void await(BooleanSupplier condition, String what, String expected) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            sleep();
        }
        if (!condition.getAsBoolean()) {
            throw new AssertionError("timed out after " + TIMEOUT_MS + " ms: " + what + " = " + expected);
        }
    }

    /**
     * Closes the driver: ends the application and waits for its thread.
     *
     * <p>A failed run has no right to leave a thread (or a process) behind, so the thread is joined with
     * the same deadline and a thread that will not stop is reported as an error.
     *
     * @throws IllegalStateException if the application loop has not ended within the deadline
     * @since 1.0
     */
    @Override
    public void close() {
        application.exit();
        try {
            loop.join(TIMEOUT_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (loop.isAlive()) {
            throw new IllegalStateException("the TUI loop did not stop within " + TIMEOUT_MS + " ms");
        }
    }

    /**
     * Returns whether the application loop is still running — needed when a test asserts that a key really
     * ends the application.
     *
     * @return {@code true} while the loop thread is alive
     * @since 1.0
     */
    public boolean isRunning() {
        return loop.isAlive();
    }

    /** The widget tree: the application's windows and their descendants. */
    private List<TWidget> tree() {
        List<TWidget> all = new ArrayList<>();
        for (TWindow window : application.getAllWindows()) {
            collect(window, all);
        }
        return all;
    }

    private static void collect(TWidget widget, List<TWidget> all) {
        all.add(widget);
        for (TWidget child : widget.getChildren()) {
            collect(child, all);
        }
    }

    /** Label matching: mnemonic markers ({@code &}) stripped, case ignored. */
    private static boolean matches(String expected, String actual) {
        return actual != null && actual.replace("&", "").trim().equalsIgnoreCase(expected.trim());
    }

    private static AssertionError notFound(String what, String label) {
        return new AssertionError("no " + what + " `" + label + "` in the widget tree");
    }

    private static void sleep() {
        try {
            Thread.sleep(1);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
