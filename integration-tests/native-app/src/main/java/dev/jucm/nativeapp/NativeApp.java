package dev.jucm.nativeapp;

import dev.jucm.core.ConfigConstraint;
import dev.jucm.core.ConfigDefinition;
import dev.jucm.core.ConfigField;
import dev.jucm.core.ConfigInt;
import dev.jucm.core.ConfigString;
import dev.jucm.core.ConfigType;
import casciian.TLabel;
import casciian.TWidget;
import dev.jucm.toolbox.ConfigTool;
// JEP 512/511 (standard in JDK 25, no --enable-preview): one module import instead of
// `java.io.PrintStream` + `java.nio.file.Path` + `java.util.List` - all of them are in `java.base`.
import module java.base;

/**
 * A small consumer application for studying **how JUCM behaves in a native image (GraalVM) and on
 * different platforms** (Linux, macOS, Windows).
 *
 * <p>The configuration is deliberately odd: `uri` is **computed from** `host` and `port`
 * (`http://${host}:${port}/api`), because that is the kind of thing that only shows up in a binary —
 * a different step order, a different way of reading the file, a different time zone or a different
 * console. That is why the output is:
 *
 * <ul>
 *   <li><b>ASCII and deterministic</b> — comparable between platforms without guessing whether the
 *       problem is in the configuration or in the console encoding;</li>
 *   <li><b>with the origin</b> (`layer.*`) — it shows WHICH layer won, so a platform difference
 *       immediately says whether the file was found, whether ENV got through or whether it was an
 *       argument (the printing is done by {@link ConfigTool}, so it is the same in every application
 *       on the toolbox);</li>
 *   <li><b>with an exit code</b> — success and every kind of problem has its own code, so a script on
 *       macOS and PowerShell on Windows check the same thing.</li>
 * </ul>
 *
 * <p>What is left here is what belongs to THIS application: the model and the way the result is
 * printed. Reading the file and the overlay, ENV, `CliSpec` with actions, the pipeline and the
 * verdict, the exit codes, and the `tui` action with its window and its files all live in the library.
 */
public final class NativeApp {

    /** Variable prefix: `URIAPP__HOST=x`, `URIAPP__PORT=9`. */
    private static final String ENV_PREFIX = "URIAPP";

    /** The program name in the `application.sh` script and in the printed command line. */
    private static final String PROGRAM = "uri-app";

    private static final List<String> PRINTED = List.of("host", "port", "uri");

    /**
     * A PUBLIC constructor, although the application never calls it: that is what the JVM launcher
     * needs to run an INSTANCE `main` (Java 25). Without it `./gradlew run` fails with "no non-private
     * zero argument constructor found" — and without `run` there is no way to compare the JVM with the
     * binary. Native Image builds it the same way.
     */
    public NativeApp() {
    }

    /**
     * The application entry point: an **instance main** (JEP 512, standard in JDK 25 — no preview
     * flag). The JDK launcher finds it on its own, just as it would a static one; that is what shows
     * an "application" no longer needs a static `main` to be runnable.
     */
    void main(String[] args) {
        System.exit(run(args, System.out, System.err, Path.of(".")));
    }

    /**
     * All of the work in one method: the tests call it directly, without starting a process.
     *
     * <p>A model declaration plus one lambda — exactly as much as was supposed to stay in the
     * application once the boilerplate went into the toolbox.
     *
     * @param args             the command line as given by the launcher; actions and `--` options as
     *                         in {@link ConfigTool}; must not be {@code null}
     * @param out              the stream the configuration and the origin report go to; must not be
     *                         {@code null}
     * @param err              the stream errors and help go to; must not be {@code null}
     * @param workingDirectory the directory the file sources are resolved against; must not be
     *                         {@code null}
     * @return the exit code: {@code 0} on success, and the code of the first problem kind otherwise
     */
    public static int run(String[] args, PrintStream out, PrintStream err, Path workingDirectory) {
        return ConfigTool.of(model())
            .program(PROGRAM)
            .sources(workingDirectory)
            .env(ENV_PREFIX)
            // An application can add functionality of its own to the TUI: a widget in the SAME window
            // that reads the resolved state from the model (`view.config()`) and reports in the status
            // line. The cost: the application has to know casciian — writing such a widget any other
            // way is impossible, and that is explicit.
            .tui(view -> {
                TLabel<TWidget> echo = view.window().addLabel("", 2, view.freeRow());
                view.window().addButton("Ping", 2, view.freeRow() + 1, () -> {
                    String uri = ConfigTool.display(view.config(), "uri");
                    echo.setLabel("ping: " + uri);
                    view.statusMessage("ping -> " + uri);
                });
            })
            // An extension point for help: the application gets the help generated from the model and
            // adds its OWN. That is how injecting custom help looks — one place, full control over the
            // text.
            .help((generated, help) -> {
                help.print(generated);
                help.println();
                help.println("URI-APP");
                help.println("  uri-app                     prints the configuration and the origin (layer.*)");
                help.println("  uri-app tui                 configurator window (saves to application.applied.yaml)");
                help.println("  uri-app config-validation   checks the configuration without running the application");
                help.println("  uri-app config-generate     a sample YAML file from the model");
            })
            .onRun(config -> {
                for (String field : PRINTED) {
                    out.println(field + "=" + ConfigTool.display(config, field));
                }
            })
            .run(args, out, err);
    }

    /**
     * The application model — one source of metadata (descriptions, types, default values).
     *
     * @return the declaration used by this application; a fresh immutable model on every call
     */
    public static ConfigDefinition model() {
        return ConfigDefinition.of(
            ConfigField.required("host", ConfigType.STR, ConfigString.of("localhost"))
                .described("Service host."),
            // The rule on the port: 8080 (HTTP) or 443 (HTTPS) only — the same chain as
            // `AIMENAGERIE__PORT___CONST='in[8080,443]'`, `--port--const 'in[8080,443]'`
            // and `# CONST(in[8080,443])` in YAML (one syntax in every medium).
            ConfigField.required("port", ConfigType.INT, ConfigInt.of(8080))
                .described("Service port.")
                .constrained(ConfigConstraint.Const("in[8080,443]")),
            ConfigField.required("uri", ConfigType.STR, ConfigString.of("http://${host}:${port}/api"))
                .described("Base address computed from host and port."));
    }
}
