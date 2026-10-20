package dev.jucm.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jucm.core.Config;
import dev.jucm.core.ConfigDefinition;
import dev.jucm.core.ConfigField;
import dev.jucm.core.ConfigPatch;
import dev.jucm.core.ConfigSource;
import dev.jucm.core.ConfigString;
import dev.jucm.core.ConfigType;
import dev.jucm.core.ConfigValidator;
import dev.jucm.core.Layer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A SECRET CANNOT COME FROM A SETTINGS FILE (owner, 21 X 2026). The mark on the declaration is what makes this checkable: without `sensitive`, a validator
 * sees a string; with it, it sees a password that has no business in a file people copy and commit.
 */
@DisplayName("a sensitive value arriving from a settings file")
class SensitiveFromFileTest {

  private static final ConfigDefinition DEFINITION = ConfigDefinition.of(
      ConfigField.optional("db.password", ConfigType.STR).sensitive(),
      ConfigField.optional("db.user", ConfigType.STR));

  @Test
  @DisplayName("from YAML it is an ERROR that names the field and does not print the value")
  void fromYamlItIsRefused() {
    Config config = Config.root(DEFINITION).apply(ConfigPatch.empty()
        .with("db.password", ConfigString.of("hunter2"), ConfigSource.of(Layer.YAML, "maswerk.yaml:12")));

    ConfigValidator.Report report = ConfigValidator.build(DEFINITION, config);

    assertThat(report.hasErrors()).isTrue();
    String described = report.describe();
    assertThat(described).contains("db.password");
    assertThat(described).contains("sensitive: a secret cannot be set in a settings file");
    assertThat(described).doesNotContain("hunter2", "the report must not print the secret it refuses");
  }

  @Test
  @DisplayName("from the environment and from the vault it is accepted")
  void fromEnvOrVaultItIsAccepted() {
    Config fromEnv = Config.root(DEFINITION).apply(ConfigPatch.empty()
        .with("db.password", ConfigString.of("hunter2"), ConfigSource.of(Layer.ENV, "DB_PASSWORD")));
    assertThat(ConfigValidator.build(DEFINITION, fromEnv).hasErrors()).isFalse();

    Config fromVault = Config.root(DEFINITION).apply(ConfigPatch.empty()
        .with("db.password", ConfigString.of("hunter2"), ConfigSource.of(Layer.CUSTOM, "vault:secrets_vault.enc:db.password")));
    assertThat(ConfigValidator.build(DEFINITION, fromVault).hasErrors()).isFalse();
  }

  @Test
  @DisplayName("an ordinary field may of course come from the file")
  void anOrdinaryFieldIsFine() {
    Config config = Config.root(DEFINITION).apply(ConfigPatch.empty()
        .with("db.user", ConfigString.of("app_rw"), ConfigSource.of(Layer.YAML, "maswerk.yaml:13")));
    assertThat(ConfigValidator.build(DEFINITION, config).hasErrors()).isFalse();
  }
}
