package dev.jucm.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jucm.casciian.DraftWriter;
import dev.jucm.core.ConfigDefinition;
import dev.jucm.core.ConfigDraft;
import dev.jucm.core.ConfigField;
import dev.jucm.core.ConfigPatch;
import dev.jucm.core.ConfigPath;
import dev.jucm.core.ConfigResult;
import dev.jucm.core.ConfigSource;
import dev.jucm.core.ConfigString;
import dev.jucm.core.ConfigType;
import dev.jucm.core.Layer;
import dev.jucm.core.TransformerRegistry;
import dev.jucm.picocli.CliSource;
import dev.jucm.picocli.CliSpec;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * The loop closed between the view and the command line: what the configurator prints as `--` flags is
 * **arguments of the same application**.
 *
 * <p>The test lives in the consumer, because both adapters meet here (TUI and CLI) — `config-casciian`
 * does not know the CLI and is not meant to: source modules do not know one another.
 */
class CommandLineRoundTripTest {

    private static final TransformerRegistry REGISTRY = TransformerRegistry.defaults();

    private static ConfigDefinition model() {
        return ConfigDefinition.of(
            ConfigField.required("host", ConfigType.STR, ConfigString.of("localhost")),
            ConfigField.required("port", ConfigType.INT, ConfigString.of("8080")),
            ConfigField.required("uri", ConfigType.STR, ConfigString.of("http://${host}:${port}/api")));
    }

    @Test
    @DisplayName("the `CLI` line from the configurator comes back as the same configuration through our parser")
    void thePrintedCommandLineIsParseable() {
        ConfigDraft draft = ConfigDraft.of(model(), REGISTRY,
            ConfigPatch.set(ConfigPath.of("host"), ConfigString.of("example.org"),
                ConfigSource.of(Layer.YAML, "application.yaml")));
        draft.edit(ConfigPath.of("port"), ConfigString.of("7000"));

        String line = DraftWriter.asCommandLine(model(), draft, "uri-app");
        String[] args = Arrays.copyOfRange(line.split(" "), 1, line.split(" ").length);

        CommandLine parsed = new CommandLine(CliSpec.of(model()));
        parsed.setUnmatchedArgumentsAllowed(true);
        parsed.parseArgs(args);

        var patch = (ConfigResult.ValueResult<ConfigPatch>) CliSource.read(parsed.getParseResult());
        assertThat(((ConfigPatch.SetValue) patch.value().operation("port").orElseThrow()).value().display())
            .as("the printed flags read back as the same value").isEqualTo("7000");
        assertThat(((ConfigPatch.SetValue) patch.value().operation("host").orElseThrow()).value().display())
            .isEqualTo("example.org");
    }
}
