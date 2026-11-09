package dev.jucm.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A SECRET MUST NOT SIT IN A SETTINGS FILE AS ITSELF (owner, 21 X 2026) - a SEALED one may (owner, 31 X 2026, when `!decrypt` replaced the separate vault). The mark
 * on the declaration is what makes this checkable: without `sensitive`, a validator sees a string; with it, it sees a password that has no business in a file people
 * copy and commit.
 */
@DisplayName("a sensitive value arriving from a settings file")
class SensitiveFromFileTest {

  private static final ConfigDefinition DEFINITION = ConfigDefinition.of(
      ConfigField.optional("db.password", ConfigType.STR).sensitive(),
      ConfigField.optional("db.user", ConfigType.STR));

  @Test
  @DisplayName("as PLAINTEXT from YAML it is an ERROR that names the field and does not print the value")
  void plaintextFromYamlIsRefused() {
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
  @DisplayName("SEALED from YAML it is accepted - that is what `!decrypt` is for")
  void sealedFromYamlIsAccepted() {
    Config config = Config.root(DEFINITION).apply(ConfigPatch.empty()
        .with("db.password", new ConfigSealed(Sealed.seal("hunter2", "a-key"), "a-key"),
            ConfigSource.of(Layer.YAML, "maswerk.yaml:12")));

    assertThat(ConfigValidator.build(DEFINITION, config).hasErrors()).isFalse();
  }

  @Test
  @DisplayName("from the environment it is accepted")
  void fromEnvItIsAccepted() {
    Config fromEnv = Config.root(DEFINITION).apply(ConfigPatch.empty()
        .with("db.password", ConfigString.of("hunter2"), ConfigSource.of(Layer.ENV, "DB_PASSWORD")));

    assertThat(ConfigValidator.build(DEFINITION, fromEnv).hasErrors()).isFalse();
  }

  @Test
  @DisplayName("an ordinary field may of course come from the file")
  void anOrdinaryFieldIsFine() {
    Config config = Config.root(DEFINITION).apply(ConfigPatch.empty()
        .with("db.user", ConfigString.of("app_rw"), ConfigSource.of(Layer.YAML, "maswerk.yaml:13")));
    assertThat(ConfigValidator.build(DEFINITION, config).hasErrors()).isFalse();
  }
}
