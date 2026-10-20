package dev.jucm.secrets;

import dev.jucm.core.Config;
import dev.jucm.core.ConfigField;
import dev.jucm.core.ConfigResult;
import dev.jucm.core.ConfigType;
import dev.jucm.core.ConfigValue;

import java.nio.file.Path;

/**
 * WHERE THE VAULT LIVES, as configuration - so an install can put it beside its state, on an encrypted volume, or anywhere the operator decides, without
 * a rebuild (owner, 21 X 2026).
 *
 * <p>TWO KEYS AND NOT ONE PATH: a directory and a file name, because they answer different questions - where the volume is mounted, and what the file is
 * called - and an operator who moves the vault between disks should not have to retype a name, nor rename it to move it.
 *
 * <p>THE DIRECTORY MAY BE INTERPOLATED BY JUCM, NOT BY THIS RECORD: `${state_dir}/secrets` resolves because `state_dir` is an ADDRESS the host declares,
 * and the layer that resolves `${...}` runs before this one is read. Measured 21 X 2026: `${user.home}` does NOT work, because `user.home` is a JVM
 * property and not a configuration address - and inventing a second interpolation here would be a second syntax for the same idea.
 *
 * @param directory where the key and the values live
 * @param fileName  the values file inside it
 */
public record SecretsSettings(Path directory, String fileName) {

    /** The keys this record reads, so a host can compose them into its own definition instead of retyping the names. */
    public static ConfigField[] definition() {
        return new ConfigField[] {
            ConfigField.optional("secrets.vault_directory", ConfigType.STR)
                .described("The directory holding the vault key and values. May use JUCM's interpolation, e.g. ${state_dir}/secrets."),
            ConfigField.optional("secrets.vault_file", ConfigType.STR)
                .described("The values file inside that directory. Default " + SecretVault.DEFAULT_FILE + ".")
        };
    }

    /**
     * READS THE SETTINGS. Absent keys mean the conventional place under the user's home, so a first start works with no configuration at all.
     *
     * @param config the resolved configuration
     * @return where the vault is
     */
    public static SecretsSettings from(Config config) {
        return from(config, Path.of(System.getProperty("user.home", "."), ".jucm", "secrets"));
    }

    /**
     * THE SAME, with the HOST'S conventional directory - because "where this application keeps its vault" is the host's convention, not this library's: a
     * configuration key wins when it is set, and otherwise the caller's own place is used.
     *
     * @param config            the resolved configuration
     * @param conventionalPlace where the host keeps a vault when nothing is configured
     * @return where the vault is
     */
    public static SecretsSettings from(Config config, Path conventionalPlace) {
        String directory = text(config, "secrets.vault_directory");
        String file = text(config, "secrets.vault_file");
        Path path = directory == null || directory.isBlank() ? conventionalPlace : Path.of(directory);
        return new SecretsSettings(path, file == null || file.isBlank() ? SecretVault.DEFAULT_FILE : file);
    }

    /** Opens the vault these settings describe, founding it when it does not exist yet. */
    public SecretVault open() {
        return SecretVault.open(directory, fileName);
    }

    private static String text(Config config, String path) {
        return switch (config.get(path)) {
            case ConfigResult.ValueResult<ConfigValue> found -> found.value().display();
            case ConfigResult.ErrorResult<ConfigValue> missing -> null;
        };
    }
}
