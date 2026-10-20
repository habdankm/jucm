package dev.jucm.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jucm.core.Config;
import dev.jucm.core.ConfigDefinition;
import dev.jucm.core.ConfigField;
import dev.jucm.core.ConfigPatch;
import dev.jucm.core.ConfigResult;
import dev.jucm.core.ConfigType;
import dev.jucm.core.ConfigValue;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * THE VAULT AS A LAYER (owner, 21 X 2026): it supplies values for the fields the CONFIGURATION marked sensitive, and for nothing else. From a
 * consumer's side a password then is simply configuration.
 */
@DisplayName("the vault as a configuration layer")
class SecretsLayerTest {

  private static final ConfigDefinition DEFINITION = ConfigDefinition.of(
      ConfigField.optional("db.password", ConfigType.STR).sensitive(),
      ConfigField.optional("db.user", ConfigType.STR),
      ConfigField.optional("db.url", ConfigType.STR));

  @Test
  @DisplayName("a sensitive field takes its value from the vault, with a source that names the vault")
  void aSensitiveFieldComesFromTheVault(@TempDir Path home) {
    SecretVault vault = SecretVault.open(home.resolve("secrets"));
    vault.put("db.password", "s3cret");

    ConfigPatch patch = new SecretsLayer(vault).patch(DEFINITION);
    Config config = Config.root(DEFINITION).apply(patch);

    assertThat(text(config, "db.password")).isEqualTo("s3cret");
  }

  @Test
  @DisplayName("a field NOT marked sensitive is never taken from the vault, even when an entry exists")
  void theMarkIsThePermission(@TempDir Path home) {
    SecretVault vault = SecretVault.open(home.resolve("secrets"));
    vault.put("db.user", "app_rw"); // an entry for a field that is not sensitive

    ConfigPatch patch = new SecretsLayer(vault).patch(DEFINITION);

    Config config = Config.root(DEFINITION).apply(patch);
    assertThat(text(config, "db.user")).isEqualTo("<absent>");
  }

  @Test
  @DisplayName("a vault entry that cannot be decrypted fails the layer instead of reading as an empty password")
  void aBrokenEntryFailsTheLayer(@TempDir Path home) throws Exception {
    Path directory = home.resolve("secrets");
    SecretVault vault = SecretVault.open(directory);
    vault.put("db.password", "s3cret");
    java.nio.file.Files.write(directory.resolve("vault.key"), new byte[32]);

    SecretRefused refused = org.junit.jupiter.api.Assertions.assertThrows(SecretRefused.class,
        () -> new SecretsLayer(SecretVault.open(directory)).patch(DEFINITION));
    assertThat(refused.getMessage()).contains("db.password");
  }

  private static String text(Config config, String path) {
    return switch (config.get(path)) {
      case ConfigResult.ValueResult<ConfigValue> found -> found.value().display();
      case ConfigResult.ErrorResult<ConfigValue> missing -> "<absent>";
    };
  }
}
