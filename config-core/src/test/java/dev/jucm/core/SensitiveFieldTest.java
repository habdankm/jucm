package dev.jucm.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `sensitive` as an ATTRIBUTE (owner, 21 X 2026): a field says it carries a secret, the type still says what the value is, and nothing else has to
 * learn a second vocabulary.
 */
@DisplayName("a field marked sensitive")
class SensitiveFieldTest {

  @Test
  @DisplayName("the mark is carried, survives a copy that changes something else, and stays absent otherwise")
  void theMarkIsCarried() {
    ConfigField secret = ConfigField.optional("db.password", ConfigType.STR).sensitive();
    assertThat(secret.isSensitive()).isTrue();
    assertThat(secret.type()).isEqualTo(ConfigType.STR);

    ConfigField described = secret.described("the password of the database");
    assertThat(described.isSensitive()).isTrue();

    ConfigField plain = ConfigField.optional("db.url", ConfigType.STR);
    assertThat(plain.isSensitive()).isFalse();
  }

  @Test
  @DisplayName("it is metadata, so an existing reader sees the same field with one more key")
  void itIsMetadataNotAType() {
    ConfigField secret = ConfigField.optional("api.token", ConfigType.STR).sensitive();
    assertThat(secret.metadata()).containsEntry("sensitive", "true");
    assertThat(secret.path().canonical()).isEqualTo("api.token");
  }
}
