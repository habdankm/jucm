package dev.jucm.secrets;


import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * THE SECRETS OF ONE OWNER, ENCRYPTED AT REST: AES-256-GCM, a key generated once per vault and kept APART from the values, and no plaintext anywhere.
 *
 * <p>WHAT IT IS FOR (owner, 20-21 X 2026): the password of a database the MCP tool may touch, the password of a component store, and the credential
 * of the HTTP surface - all things that must NOT sit in `maswerk.yaml`, which is a file people edit, copy and commit.
 *
 * <p>THE KEY IS NOT IN THE CONFIGURATION AND NOT IN THE BINARY. It is a file beside the vault, readable only by its owner (0600), generated the
 * first time the vault is opened. Losing it means losing the secrets - that is stated rather than softened, because a recovery path that quietly
 * rewrites the key would be a recovery path that quietly loses the data.
 *
 * <p>EACH ENTRY IS ENCRYPTED WITH ITS OWN NAME AS ADDITIONAL DATA, so ciphertext moved from one entry to another fails to decrypt instead of
 * silently becoming a secret under the wrong name. The stored form is `base64(nonce || ciphertext || tag)`, one per line, `name = value`.
 *
 * <p>A VAULT THAT CANNOT BE READ IS NOT AN EMPTY VAULT: a wrong key, a truncated line or a tampered value each produce a sentence naming the ENTRY.
 * The failure mode this class exists to prevent is "the password is missing, so we tried an empty one".
 */
public final class SecretVault {

    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String CIPHER = "AES/GCM/NoPadding";

    private final Path directory;
    private final SecretKeySpec key;

    private SecretVault(Path directory, SecretKeySpec key) {
        this.directory = directory;
        this.key = key;
    }

    /**
     * OPENS (or founds) the vault in a directory: the key is created on first use and read afterwards.
     *
     * @param directory where the key and the values live - `~/.maswerk/secrets` for this install
     * @return the vault
     * @throws SecretRefused when the directory or the key cannot be created or read
     */
    public static SecretVault open(Path directory) {
        try {
            Files.createDirectories(directory);
            Path keyFile = directory.resolve("vault.key");
            byte[] material;
            if (Files.isRegularFile(keyFile)) {
                material = Files.readAllBytes(keyFile);
                if (material.length != KEY_BYTES) {
                    throw new SecretRefused("the vault key " + keyFile.toAbsolutePath() + " is " + material.length
                        + " bytes and a key is " + KEY_BYTES + ": refusing to guess, because a guessed key would silently fail every entry");
                }
            } else {
                material = new byte[KEY_BYTES];
                new SecureRandom().nextBytes(material);
                Path temporary = directory.resolve("vault.key.tmp");
                Files.write(temporary, material);
                restrict(temporary);
                Files.move(temporary, keyFile, StandardCopyOption.REPLACE_EXISTING);
                restrict(keyFile);
            }
            return new SecretVault(directory, new SecretKeySpec(material, "AES"));
        } catch (SecretRefused refused) {
            throw refused;
        } catch (Exception broken) {
            throw new SecretRefused("the vault at " + directory.toAbsolutePath() + " could not be opened ("
                + broken.getClass().getSimpleName() + ": " + (broken.getMessage() == null ? "no message" : broken.getMessage()) + ")");
        }
    }

    /** Where this vault lives, for a message that has to say it. */
    public Path directory() {
        return directory;
    }

    /** Stores or replaces one secret, and says nothing about it beyond that. */
    public void put(String name, String value) {
        requireName(name);
        if (value == null) {
            throw new SecretRefused("the secret `" + name + "` cannot be stored as null: use delete to remove one");
        }
        Map<String, String> entries = readAllRaw();
        entries.put(name, encrypt(name, value));
        writeAll(entries);
    }

    /** One secret, or empty when the vault holds no such name. A BROKEN entry is an exception, never an empty answer. */
    public Optional<String> get(String name) {
        requireName(name);
        String encoded = readAllRaw().get(name);
        return encoded == null ? Optional.empty() : Optional.of(decrypt(name, encoded));
    }

    public boolean contains(String name) {
        requireName(name);
        return readAllRaw().containsKey(name);
    }

    /** The NAMES only - reading a list never decrypts anything, so it works even when one entry is broken. */
    public List<String> names() {
        return new ArrayList<>(readAllRaw().keySet());
    }

    public void delete(String name) {
        requireName(name);
        Map<String, String> entries = readAllRaw();
        if (entries.remove(name) == null) {
            throw new SecretRefused("the vault at " + directory.toAbsolutePath() + " holds no secret called `" + name + "`");
        }
        writeAll(entries);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // the file: names and encrypted values, one per line, written atomically and readable only by the owner

    private Path file() {
        return directory.resolve("secrets.enc");
    }

    private Map<String, String> readAllRaw() {
        Map<String, String> entries = new LinkedHashMap<>();
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return entries;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                int equals = line.indexOf('=');
                if (equals <= 0) {
                    throw new SecretRefused("the vault file " + file.toAbsolutePath() + " carries a line without `name = value`, and this build will"
                        + " not skip it: a skipped line is a secret somebody would later believe is stored");
                }
                entries.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
            }
            return entries;
        } catch (SecretRefused refused) {
            throw refused;
        } catch (Exception broken) {
            throw new SecretRefused("the vault file " + file.toAbsolutePath() + " could not be read ("
                + broken.getClass().getSimpleName() + ": " + broken.getMessage() + ")");
        }
    }

    private void writeAll(Map<String, String> entries) {
        Path file = file();
        try {
            StringBuilder text = new StringBuilder("# maswerk secrets: names in the clear, values AES-256-GCM. The key is vault.key beside this file.\n");
            entries.forEach((name, value) -> text.append(name).append('=').append(value).append('\n'));
            Path temporary = directory.resolve("secrets.enc.tmp");
            Files.writeString(temporary, text.toString(), StandardCharsets.UTF_8);
            restrict(temporary);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            restrict(file);
        } catch (Exception broken) {
            throw new SecretRefused("the vault file " + file.toAbsolutePath() + " could not be written ("
                + broken.getClass().getSimpleName() + ": " + broken.getMessage() + "): the secret was NOT stored");
        }
    }

    private String encrypt(String name, String value) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(sealed, 0, combined, nonce.length, sealed.length);
            return java.util.Base64.getEncoder().encodeToString(combined);
        } catch (Exception broken) {
            throw new SecretRefused("the secret `" + name + "` could not be encrypted ("
                + broken.getClass().getSimpleName() + ": " + broken.getMessage() + ")");
        }
    }

    private String decrypt(String name, String encoded) {
        try {
            byte[] combined = java.util.Base64.getDecoder().decode(encoded);
            if (combined.length <= NONCE_BYTES) {
                throw new SecretRefused("the secret `" + name + "` in " + file().toAbsolutePath() + " is truncated: it cannot be decrypted, and it is"
                    + " NOT an empty secret");
            }
            byte[] nonce = java.util.Arrays.copyOfRange(combined, 0, NONCE_BYTES);
            byte[] sealed = java.util.Arrays.copyOfRange(combined, NONCE_BYTES, combined.length);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
        } catch (SecretRefused refused) {
            throw refused;
        } catch (Exception broken) {
            throw new SecretRefused("the secret `" + name + "` in " + file().toAbsolutePath() + " could not be decrypted ("
                + broken.getClass().getSimpleName() + "): the key does not match this value or the value was changed. It is NOT treated as missing");
        }
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new SecretRefused("a secret needs a NAME: the vault is addressed by name, never by position");
        }
    }

    /** Owner-only permissions; a vault readable by others is not a vault. Best effort where POSIX is absent, loud where it exists. */
    private static void restrict(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, java.util.Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException notPosix) {
            // the platform has no POSIX permissions; the installer owns the directory's protection then
        }
    }
}
