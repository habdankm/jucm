package dev.jucm.picocli;

import dev.jucm.core.Config;
import dev.jucm.core.ConfigDefinition;
import dev.jucm.core.ConfigPatch;
import dev.jucm.core.ConfigResult;
import dev.jucm.core.Provenance;
import dev.jucm.core.TransformerRegistry;
import dev.jucm.core.Version;
import dev.jucm.env.EnvVars;
import dev.jucm.yaml.YamlSource;
import java.io.PrintStream;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

/**
 * A proof that the modules compose into one chain — picocli + the YAML loader + ENV + Vavr.
 *
 * <p><b>This is not the CLI adapter.</b> The adapter is {@link CliSpec} plus {@link CliSource}; the
 * tools (`config-validation`, `config-generate`) are actions of the application. This class is
 * temporary and exists so that the integration of the modules is checked by a test instead of by
 * belief. There is no {@code main()} — a library is not an application.
 *
 * <p>The spec is built <b>programmatically</b> ({@link CommandSpec}/{@link OptionSpec}), without
 * annotations — that is how the adapter works as well. A side effect: no annotation processor and no
 * reflect-config of our own are needed.
 */
public final class WiringSmoke {

    static final String PREFIX = "JUCM";

    private WiringSmoke() {
    }

    /**
     * Builds a spec with two options and returns the result in one line.
     *
     * @param args the command line to parse
     * @param out  where the result line is printed
     * @return the exit code of the command line
     */
    public static int run(String[] args, PrintStream out) {
        CommandSpec spec = CommandSpec.create().name("jucm-smoke");
        // The engine's own options take ONE dash, exactly as in the CLI adapter (`CliSpec`): `-help` and
        // `-version`. picocli's `mixinStandardHelpOptions` would bring `-h`/`-V` and a `--help` instead —
        // and there are no guessed short names here: a short name exists only when it is DECLARED.
        spec.addOption(OptionSpec.builder("-help")
                .usageHelp(true)
                .description("prints this help and exits")
                .build());
        spec.version(Version.get());
        spec.addOption(OptionSpec.builder("-version")
                .versionHelp(true)
                .description("prints the version and exits")
                .build());
        spec.addOption(OptionSpec.builder("--yaml")
                .paramLabel("<text>")
                .type(String.class)
                .description("A YAML document; we read the raw scalar of its first key.")
                .build());
        spec.addOption(OptionSpec.builder("--env")
                .paramLabel("<name>")
                .type(String.class)
                .description("Name of the environment variable whose state we report.")
                .build());
        // The execution strategy hangs on the CommandLine, not on the spec (that is how picocli does
        // it — checked with javap, not from memory).
        CommandLine commandLine = new CommandLine(spec);
        commandLine.setExecutionStrategy(parseResult -> {
            out.println(render(parseResult.matchedOptionValue("--yaml", null),
                    parseResult.matchedOptionValue("--env", null)));
            return 0;
        });
        return commandLine.execute(args);
    }

    /**
     * The first entry of the document, read with the **same loader** as the adapter — the smoke test
     * has to prove real composition of the modules, not call a stub.
     */
    private static String firstEntry(String yaml) {
        ConfigDefinition definition = ConfigDefinition.of();
        ConfigResult<ConfigPatch> patch = YamlSource.of("smoke.yaml", yaml)
                .patch(Config.root(definition), definition, TransformerRegistry.defaults());
        if (!(patch instanceof ConfigResult.ValueResult<ConfigPatch> ok) || ok.value().isEmpty()) {
            return "error";
        }
        var path = ok.value().paths().get(0);
        var operation = (ConfigPatch.SetValue) ok.value().operation(path).orElseThrow();
        return path.canonical() + ":" + operation.value().display();
    }

    /** One line of output — a stable format, because a test compares it. */
    static String render(String yaml, String envName) {
        String yamlPart = yaml == null ? "n/a" : firstEntry(yaml);
        String envPart = envName == null
                ? "n/a"
                : envName + ":" + EnvVars.state(envName).name().toLowerCase();
        String layers = Provenance.of("compiled", "yaml").above("env").toString();
        return PREFIX + " " + Version.get()
                + " | yaml=" + yamlPart
                + " | env=" + envPart
                + " | layers=" + layers;
    }
}
