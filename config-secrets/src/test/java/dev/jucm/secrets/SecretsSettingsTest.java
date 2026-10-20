package dev.jucm.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jucm.core.Config;
import dev.jucm.core.ConfigDefinition;
import dev.jucm.core.ConfigPatch;
import dev.jucm.core.ConfigString;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * WHERE THE VAULT LIVES (owner, 21 X 2026): directory and file name are configuration, the directory may be interpolated, and an install with no keys at
 * all still starts in the conventional place.
 */
@DisplayName("the vault's location as configuration")
class SecretsSettingsTest {

  private static ConfigDefinition definition() {
    // `state_dir` is declared BY THE HOST and interpolated by JUCM - the mechanism that exists, instead of a second one in this record.
    java.util.List<dev.jucm.core.ConfigField> fields = new java.util.ArrayList<>();
    fields.add(dev.jucm.core.ConfigField.optional("state_dir", dev.jucm.core.ConfigType.STR));
    fields.addAll(java.util.List.of(SecretsSettings.definition()));
    return ConfigDefinition.of(fields.toArray(dev.jucm.core.ConfigField[]::new));
  }

  @Test
  @DisplayName("a configured directory and file are honoured, and the file really is the one opened")
  void configuredLocationIsHonoured(@TempDir Path home) throws Exception {
    Path directory = home.resolve(".maswerk/secrets");
    SecretsSettings settings = SecretsSettings.from(config(
        "secrets.vault_directory", directory.toString(),
        "secrets.vault_file", "secrets_vault.enc"));

    assertThat(settings.directory()).isEqualTo(directory);
    assertThat(settings.fileName()).isEqualTo("secrets_vault.enc");

    settings.open().put("db.password", "s3cret");
    assertThat(Files.exists(directory.resolve("secrets_vault.enc"))).isTrue();
    assertThat(Files.exists(directory.resolve("vault.key"))).isTrue();
    assertThat(settings.open().get("db.password")).contains("s3cret");
  }

  @Test
  @DisplayName("with no keys the vault lands in the conventional place and uses the conventional name")
  void defaultsApply() {
    SecretsSettings settings = SecretsSettings.from(config());
    assertThat(settings.directory()).isEqualTo(Path.of(System.getProperty("user.home"), ".jucm", "secrets"));
    assertThat(settings.fileName()).isEqualTo(SecretVault.DEFAULT_FILE);
  }

  @Test
  @DisplayName("the directory may be interpolated by JUCM from an address the host declares")
  void theDirectoryMayBeInterpolated(@TempDir Path home) {
    SecretsSettings settings = SecretsSettings.from(config(
        "state_dir", home.toString(),
        "secrets.vault_directory", "${state_dir}/secrets"));
    assertThat(settings.directory()).as("JUCM resolved ${state_dir} before this record saw the value")
        .isEqualTo(home.resolve("secrets"));
  }

  private static Config config(String... pairs) {
    ConfigPatch patch = ConfigPatch.empty();
    for (int index = 0; index + 1 < pairs.length; index += 2) {
      patch = patch.with(pairs[index], ConfigString.of(pairs[index + 1]));
    }
    return Config.root(definition()).apply(patch);
  }
}
