// MinimalApp.java — THE SHORTEST FORM OF AN APPLICATION ON JUCM. This is an EXAMPLE, not a test.
//
// WHAT IT SHOWS — this file has NO:
//   package, class declaration, `public`, `static`, `String[] args`, nor a single JDK import
//   (the default class sees `java.base` — hence `Path` — and `IO` — hence `IO.println`)
//
// RUNNING IT (two ways, neither requires building this file):
//   java MinimalApp.java                  # a compact file: compiles and starts in memory
//   ./gradlew :examples:minimal-app:run   # the same through Gradle, with the module classpath
//
// And the configurator window (`tui`) is BUILT INTO the toolbox — it is not here, not even one line:
//   java MinimalApp.java tui
//
// CUSTOM HELP: `-help` (one dash — the engine's options are single-dash) is generated from the model,
// but the application may add its own — one extension point (`.help(...)`) that receives the generated
// help and keeps writing. See below.

import dev.jucm.core.ConfigDefinition;
import dev.jucm.core.ConfigField;
import dev.jucm.core.ConfigInt;
import dev.jucm.core.ConfigString;
import dev.jucm.core.ConfigType;
import dev.jucm.toolbox.ConfigTool;
import module java.base;

/**
 * The whole application: the model plus one lambda. Nothing here starts a process — the toolbox does
 * the reading, the help, the actions and the exit code.
 *
 * @param args the command line: `--field value` for configuration, a bare word for an action
 */
void main(String[] args) {
    var app = ConfigTool.of(ConfigDefinition.of(
            ConfigField.required("server.host", ConfigType.STR, ConfigString.of("localhost"))
                .described("Service host."),
            ConfigField.required("server.port", ConfigType.INT, ConfigInt.of(8080))
                .described("Service port.")))
        .program("minimal-app")
        .sources(Path.of("."))
        .env("MINIMAL")
        // EXTENSION POINT: custom help. `generated` is the help generated from the MODEL (what we would
        // print without this point), and `help` is a stream — you may precede it, add to it or print
        // only your own. Without this, `-help` shows only what follows from the declarations.
        .help((generated, help) -> {
            help.print(generated);
            help.println();
            help.println("MINIMAL-APP — a JUCM example");
            help.println("  minimal-app        prints host:port (ENV, file, default values)");
            help.println("  minimal-app tui    configurator window (built into the toolbox)");
        })
        .onRun(config -> IO.println("start: " + ConfigTool.display(config, "server.host")
            + ":" + ConfigTool.display(config, "server.port")));

    System.exit(app.run(args));
}
