package dev.jucm.yaml;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jucm.core.Config;
import dev.jucm.core.ConfigDefinition;
import dev.jucm.core.ConfigField;
import dev.jucm.core.ConfigPatch;
import dev.jucm.core.ConfigResult;
import dev.jucm.core.ConfigType;
import dev.jucm.core.TransformerRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code !include}, measured at the level a consumer feels it: a file is read, the include is resolved, and the resulting CONFIGURATION
 * has the values - or the read fails with a sentence. The mechanism (a tag surviving composition) is an implementation detail; these tests
 * are about the contract.
 */
@DisplayName("`!include` in a YAML source")
class IncludeTest {

  private static final TransformerRegistry REGISTRY = TransformerRegistry.defaults();

  @Test
  @DisplayName("an include in VALUE position becomes the included document")
  void valueIncludeIsSpliced(@TempDir Path dir) throws IOException {
    Files.writeString(dir.resolve("inner.yaml"), "retries: 7\nname: from-inner\n");
    Files.writeString(dir.resolve("main.yaml"), "server:\n  sub: !include inner.yaml\n");

    Config config = read(dir.resolve("main.yaml"),
        ConfigField.optional("server.sub.retries", ConfigType.INT),
        ConfigField.optional("server.sub.name", ConfigType.STR));

    assertThat(text(config, "server.sub.name")).isEqualTo("from-inner");
    assertThat(text(config, "server.sub.retries")).isEqualTo("7");
  }

  @Test
  @DisplayName("an include in KEY position MERGES, and the file that names it keeps its own keys")
  void keyIncludeMergesAndTheIncludingFileWins(@TempDir Path dir) throws IOException {
    Files.writeString(dir.resolve("defaults.yaml"), "port: 1111\nhost: from-defaults\n");
    Files.writeString(dir.resolve("main.yaml"), "!include defaults.yaml:\nhost: from-main\n");

    Config config = read(dir.resolve("main.yaml"),
        ConfigField.optional("port", ConfigType.INT),
        ConfigField.optional("host", ConfigType.STR));

    assertThat(text(config, "port")).isEqualTo("1111");
    assertThat(text(config, "host")).isEqualTo("from-main");
  }

  @Test
  @DisplayName("a missing include is a PARSE-GRADE failure naming the file, not a crash")
  void missingIncludeIsReported(@TempDir Path dir) throws IOException {
    Files.writeString(dir.resolve("main.yaml"), "a: !include nowhere.yaml\n");

    ConfigResult<YamlSource> opened = YamlSource.of(dir.resolve("main.yaml"));
    assertThat(opened).isInstanceOf(ConfigResult.ValueResult.class);
    ConfigDefinition definition = ConfigDefinition.of(ConfigField.optional("a", ConfigType.STR));
    ConfigResult<ConfigPatch> result = ((ConfigResult.ValueResult<YamlSource>) opened).value()
        .patch(Config.root(definition), definition, REGISTRY);

    assertThat(result).isInstanceOf(ConfigResult.ErrorResult.class);
    assertThat(((ConfigResult.ErrorResult<ConfigPatch>) result).error().toString()).contains("nowhere.yaml");
  }

  @Test
  @DisplayName("a cycle is refused with the chain spelled out")
  void cycleIsRefused(@TempDir Path dir) throws IOException {
    Files.writeString(dir.resolve("a.yaml"), "x: !include b.yaml\n");
    Files.writeString(dir.resolve("b.yaml"), "y: !include a.yaml\n");

    ConfigResult<YamlSource> opened = YamlSource.of(dir.resolve("a.yaml"));
    ConfigDefinition empty = ConfigDefinition.of();
    ConfigResult<ConfigPatch> result = ((ConfigResult.ValueResult<YamlSource>) opened).value()
        .patch(Config.root(empty), empty, REGISTRY);

    assertThat(result).isInstanceOf(ConfigResult.ErrorResult.class);
    assertThat(((ConfigResult.ErrorResult<ConfigPatch>) result).error().toString()).contains("cycle");
  }

  /** Reads one file into a validated configuration, which is how a consumer sees the result. */
  private static Config read(Path file, ConfigField... fields) {
    ConfigDefinition definition = ConfigDefinition.of(fields);
    ConfigResult<YamlSource> opened = YamlSource.of(file);
    assertThat(opened).isInstanceOf(ConfigResult.ValueResult.class);
    ConfigResult<ConfigPatch> result = ((ConfigResult.ValueResult<YamlSource>) opened).value()
        .patch(Config.root(definition), definition, REGISTRY);
    assertThat(result).as("expected entries: " + result).isInstanceOf(ConfigResult.ValueResult.class);
    return Config.root(definition).apply(((ConfigResult.ValueResult<ConfigPatch>) result).value());
  }

  private static String text(Config config, String path) {
    return switch (config.get(path)) {
      case ConfigResult.ValueResult<dev.jucm.core.ConfigValue> found -> found.value().display();
      case ConfigResult.ErrorResult<dev.jucm.core.ConfigValue> missing -> "<absent>";
    };
  }
}
