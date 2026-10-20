package dev.jucm.toolbox;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jucm.core.Config;
import dev.jucm.core.ConfigDefinition;
import dev.jucm.core.ConfigField;
import dev.jucm.core.ConfigType;
import dev.jucm.secrets.SecretVault;
import dev.jucm.secrets.SecretsSettings;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * THE VAULT AS A FRAMEWORK LAYER (owner, 21 X 2026): an application declares a `sensitive` field and gets the store; it does not wire one by hand, and
 * the POSITION of the layer - above the file, below the environment - is the toolbox's decision rather than each application's.
 */
@DisplayName("the secrets vault as a toolbox layer")
class SecretsLayerInToolboxTest {

  private static ConfigDefinition model(Path vaultDirectory) {
    List<ConfigField> fields = new ArrayList<>();
    fields.add(ConfigField.optional("db.user", ConfigType.STR));
    fields.add(ConfigField.optional("db.password", ConfigType.STR).sensitive());
    fields.addAll(List.of(SecretsSettings.definition()));
    return ConfigDefinition.of(fields.toArray(ConfigField[]::new));
  }

  @Test
  @DisplayName("a sensitive field is filled from the vault the file points at, and the layer is reported as CUSTOM")
  void theVaultFillsASensitiveField(@TempDir Path dir) throws Exception {
    Path vaultDirectory = dir.resolve("secrets");
    ConfigDefinition definition = model(vaultDirectory);
    SecretVault.open(vaultDirectory).put("db.password", "from-vault");
    Files.writeString(dir.resolve("application.yaml"),
        "db.user: app_rw\nsecrets:\n  vault_directory: \"" + vaultDirectory + "\"\n");

    Ran ran = run(ConfigTool.of(definition).sources(dir));

    assertThat(ran.code()).isZero();
    assertThat(ConfigTool.display(ran.config(), "db.password")).isEqualTo("from-vault");
    assertThat(ran.out()).contains("layer.db.password=CUSTOM");
  }

  @Test
  @DisplayName("ENV still wins over the vault, which is what keeps CI and a first boot working")
  void environmentWins(@TempDir Path dir) throws Exception {
    Path vaultDirectory = dir.resolve("secrets");
    ConfigDefinition definition = model(vaultDirectory);
    SecretVault.open(vaultDirectory).put("db.password", "from-vault");
    Files.writeString(dir.resolve("application.yaml"),
        "secrets:\n  vault_directory: \"" + vaultDirectory + "\"\n");

    Ran ran = run(ConfigTool.of(definition).sources(dir).env("APP", Map.of("APP__DB__PASSWORD", "from-env")));

    assertThat(ConfigTool.display(ran.config(), "db.password")).isEqualTo("from-env");
    assertThat(ran.out()).contains("layer.db.password=ENV");
  }

  @Test
  @DisplayName("a password written in the FILE is refused, and the report does not print it")
  void aSecretInTheFileIsRefused(@TempDir Path dir) throws Exception {
    Path vaultDirectory = dir.resolve("secrets");
    ConfigDefinition definition = model(vaultDirectory);
    Files.writeString(dir.resolve("application.yaml"), "db.password: hunter2\n");

    Ran ran = run(ConfigTool.of(definition).sources(dir));

    assertThat(ran.code()).isNotZero();
    assertThat(ran.err()).contains("sensitive: a secret cannot be set in a settings file");
    assertThat(ran.err()).doesNotContain("hunter2");
  }

  @Test
  @DisplayName("an application with no sensitive field never has a vault directory created for it")
  void noSensitiveFieldMeansNoVault(@TempDir Path dir) throws Exception {
    ConfigDefinition plain = ConfigDefinition.of(ConfigField.optional("db.user", ConfigType.STR));
    Files.writeString(dir.resolve("application.yaml"), "db.user: app_rw\n");

    Ran ran = run(ConfigTool.of(plain).sources(dir));

    assertThat(ran.code()).isZero();
    assertThat(Files.exists(Path.of(System.getProperty("user.home"), ".jucm", "secrets", "secrets.enc")))
        .as("the layer must not touch a store when nothing is marked").isFalse();
  }

  private record Ran(int code, String out, String err, Config config) {
  }

  private static Ran run(ConfigTool tool, String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    List<Config> seen = new ArrayList<>();
    int code = tool.onRun(seen::add)
        .run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    return new Ran(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8),
        seen.isEmpty() ? null : seen.get(0));
  }
}
