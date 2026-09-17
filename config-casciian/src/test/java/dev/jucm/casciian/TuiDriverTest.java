package dev.jucm.casciian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import casciian.TButton;
import casciian.TField;
import casciian.TKeypress;
import casciian.TWindow;
import casciian.backend.HeadlessBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The TUI driver in action: click, type, assert, exit.
 *
 * <p>The test is also the proof that the casciian seams suffice: input goes in EXCLUSIVELY through
 * {@code postEvent}, the screen is headless, and the application runs in a thread that has to end. This
 * is the basis for every later assertion that needs no window.
 */
class TuiDriverTest {

    /** A fresh screen: a labelled field, a labelled status field and a button that sets the status. */
    private record Screen(HeadlessBackend backend, TuiApplication application, TWindow window,
                          TField username, TField status, TButton login) {

        static Screen of() {
            HeadlessBackend backend = new HeadlessBackend();
            backend.setBackend(backend);   // as in casciian's own tests: the backend points at itself
            TuiApplication application = new TuiApplication(backend);
            TWindow window = application.addWindow("login", 0, 0, 50, 12);
            TField username = window.addField(2, 2, 20, false);
            window.addLabelFor("Username", 2, 1, username);
            TField status = window.addField(2, 5, 30, false);
            window.addLabelFor("Status", 2, 4, status);
            TButton login = window.addButton("Login", 2, 7, () -> status.setText("Logged in"));
            return new Screen(backend, application, window, username, status, login);
        }
    }

    @Test
    @DisplayName("click, type, assert — that is `fill`/`click`/`await` on the casciian seams")
    void drivesAForm() {
        Screen screen = Screen.of();

        try (TuiDriver ui = TuiDriver.start(screen.application(), screen.backend())) {
            ui.id("login", screen.login());

            ui.fill(ui.findField("Username"), "john");
            assertThat(screen.username().getText()).as("`fill` put the text into the field").isEqualTo("john");

            ui.click(ui.findButton("Login"));
            ui.await(() -> "Logged in".equals(screen.status().getText()), "field Status", "`Logged in`");

            assertThat(ui.findLabel("Status").getLabel()).as("the label is readable from the tree").isEqualTo("Status");
            assertThat(ui.getById("login", TButton.class)).as("the id leads to THE SAME widget")
                .isSameAs(ui.findButton("Login"));
        }
    }

    @Test
    @DisplayName("ESC quits the application — the default exit path, so a test never leaves a thread behind")
    void escapeQuitsTheApplication() {
        Screen screen = Screen.of();

        try (TuiDriver ui = TuiDriver.start(screen.application(), screen.backend())) {
            assertThat(ui.isRunning()).as("the loop has started").isTrue();

            ui.sendKey(TKeypress.kbEsc);
            ui.await(() -> !ui.isRunning(), "TUI loop", "finished (ESC)");
        }
    }

    @Test
    @DisplayName("`withQuitOnEscape(false)` hides the exit path — allowed, but the default is to quit")
    void quitCanBeHidden() {
        HeadlessBackend backend = new HeadlessBackend();
        backend.setBackend(backend);
        TuiApplication application = new TuiApplication(backend).withQuitOnEscape(false);
        application.addWindow("no-exit", 0, 0, 40, 8);

        try (TuiDriver ui = TuiDriver.start(application, backend)) {
            ui.sendKey(TKeypress.kbEsc);
            sleep();

            assertThat(ui.isRunning()).as("ESC is switched off, so the loop is still running").isTrue();
        }
    }

    @Test
    @DisplayName("a lookup that misses SAYS what is missing — instead of throwing an NPE somewhere in the middle")
    void missingWidgetSaysSo() {
        Screen screen = Screen.of();

        try (TuiDriver ui = TuiDriver.start(screen.application(), screen.backend())) {
            assertThatExceptionOfType(AssertionError.class)
                .isThrownBy(() -> ui.findButton("NoSuchButton"))
                .withMessageContaining("NoSuchButton");
            assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> ui.getById("no-such", TButton.class))
                .withMessageContaining("no-such");
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
