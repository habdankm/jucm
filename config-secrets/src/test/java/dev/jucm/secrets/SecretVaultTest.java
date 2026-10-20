package dev.jucm.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.jucm.secrets.SecretRefused;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * THE VAULT (owner, 20-21 X 2026): AES-256-GCM, a key apart from the values, and failures that NAME the entry instead of pretending it is missing.
 *
 * <p>Each test is one of the ways a secret store usually goes wrong: plaintext on disk, a key that travels with the values, a wrong key that reads as
 * "no password", and ciphertext moved between names.
 */
@DisplayName("the secrets vault")
class SecretVaultTest {

  @Test
  @DisplayName("a secret comes back, and the file on disk holds no plaintext of it")
  void roundTripAndNoPlaintext(@TempDir Path home) throws Exception {
    SecretVault vault = SecretVault.open(home.resolve("secrets"));
    vault.put("db.prod.password", "hunter2-very-secret");

    assertEquals(Optional.of("hunter2-very-secret"), vault.get("db.prod.password"));
    assertTrue(vault.contains("db.prod.password"));
    assertEquals(List.of("db.prod.password"), vault.names());

    String onDisk = Files.readString(home.resolve("secrets/secrets.enc"), StandardCharsets.UTF_8);
    assertFalse(onDisk.contains("hunter2"), "the value must never be written in the clear: " + onDisk);
    assertTrue(onDisk.contains("db.prod.password"), "the NAME is in the clear on purpose - a list must be readable without the key");
  }

  @Test
  @DisplayName("the key lives beside the vault, owner-only, and a second open reads the same values")
  void keyIsSeparateAndOwnerOnly(@TempDir Path home) throws Exception {
    Path directory = home.resolve("secrets");
    SecretVault first = SecretVault.open(directory);
    first.put("store.user", "app_rw");

    Path key = directory.resolve("vault.key");
    assertTrue(Files.isRegularFile(key), "the key is a file, not a value inside secrets.enc");
    assertEquals(32, Files.size(key), "AES-256 means 32 bytes");
    assertEquals(java.util.Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        Files.getPosixFilePermissions(key), "a vault key readable by others is not a key");
    assertEquals(java.util.Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        Files.getPosixFilePermissions(directory.resolve("secrets.enc")));

    SecretVault second = SecretVault.open(directory);
    assertEquals(Optional.of("app_rw"), second.get("store.user"));
  }

  @Test
  @DisplayName("a MISSING secret is empty; a secret that cannot be decrypted is an ERROR naming it")
  void missingIsEmptyButBrokenIsSecretRefused(@TempDir Path home) throws Exception {
    Path directory = home.resolve("secrets");
    SecretVault vault = SecretVault.open(directory);
    vault.put("a", "value-a");
    vault.put("b", "value-b");

    assertEquals(Optional.empty(), vault.get("nobody-set-this"));

    // the key is replaced: every value is now undecryptable, and that must NOT read as "no password"
    Files.write(directory.resolve("vault.key"), new byte[32]);
    SecretVault wrongKey = SecretVault.open(directory);
    SecretRefused refused = assertThrows(SecretRefused.class, () -> wrongKey.get("a"));
    assertTrue(refused.getMessage().contains("`a`"), refused.getMessage());
    assertTrue(refused.getMessage().contains("NOT treated as missing"), refused.getMessage());
    assertEquals(2, wrongKey.names().size(), "the NAMES still list - listing never decrypts");
  }

  @Test
  @DisplayName("ciphertext moved to another name fails, because the name is part of what was sealed")
  void ciphertextIsBoundToItsName(@TempDir Path home) throws Exception {
    Path directory = home.resolve("secrets");
    SecretVault vault = SecretVault.open(directory);
    vault.put("prod", "prod-secret");

    Path file = directory.resolve("secrets.enc");
    String line = Files.readAllLines(file).stream().filter(each -> each.startsWith("prod=")).findFirst().orElseThrow();
    String value = line.substring("prod=".length());
    Files.writeString(file, "# edited\nstaging=" + value + "\n");

    SecretRefused refused = assertThrows(SecretRefused.class, () -> vault.get("staging"));
    assertTrue(refused.getMessage().contains("`staging`"), refused.getMessage());
  }

  @Test
  @DisplayName("a line that is not `name = value` is refused, never skipped")
  void aBrokenLineIsSecretRefused(@TempDir Path home) throws Exception {
    Path directory = home.resolve("secrets");
    SecretVault.open(directory).put("x", "1");
    Files.writeString(directory.resolve("secrets.enc"), "this line has no equals sign\n");

    SecretRefused refused = assertThrows(SecretRefused.class, () -> SecretVault.open(directory).names());
    assertTrue(refused.getMessage().contains("without `name = value`"), refused.getMessage());
  }

  @Test
  @DisplayName("a name is required, and deleting what is not there says so")
  void namesAndDeletion(@TempDir Path home) {
    SecretVault vault = SecretVault.open(home.resolve("secrets"));
    assertThrows(SecretRefused.class, () -> vault.put("", "x"));
    vault.put("gone", "x");
    vault.delete("gone");
    assertFalse(vault.contains("gone"));
    SecretRefused refused = assertThrows(SecretRefused.class, () -> vault.delete("gone"));
    assertTrue(refused.getMessage().contains("holds no secret called `gone`"), refused.getMessage());
  }
}
