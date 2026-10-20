package dev.jucm.secrets;

import dev.jucm.core.ConfigDefinition;
import dev.jucm.core.ConfigField;
import dev.jucm.core.ConfigPath;
import dev.jucm.core.ConfigPatch;
import dev.jucm.core.ConfigSource;
import dev.jucm.core.ConfigString;
import dev.jucm.core.Layer;

import java.util.Optional;

/**
 * THE VAULT AS A CONFIGURATION LAYER: it supplies a value for exactly the fields the CONFIGURATION MARKED `sensitive`, and for nothing else.
 *
 * <p>THIS IS WHERE THE DECISION BELONGS (owner, 21 X 2026): a password is a SETTING, so the machinery that decides where settings come from is the one
 * that should decide where a password comes from. From a consumer's point of view (JIDL, an application) it is then simply configuration - one patch
 * among the layers, with a source that says `vault:secrets.enc` instead of a file name.
 *
 * <p>THE MARK IS THE PERMISSION: a field that is not `sensitive` is NEVER taken from the vault, even when an entry with that address exists. That is
 * what stops a typo in a secret's name from filling some other setting with a password.
 *
 * <p>THE ORDER IS THE OWNER'S: the vault sits ABOVE the YAML file and BELOW the environment - a person's saved secret beats a hand-written file, while
 * ENV still wins for CI and for a first boot where no vault exists yet. The layer is reported as {@link Layer#CUSTOM}, because a source that is neither
 * a file nor the environment must not pretend to be one.
 */
public final class SecretsLayer {

    private final SecretVault vault;

    public SecretsLayer(SecretVault vault) {
        this.vault = vault;
    }

    /**
     * Builds the patch this vault contributes, one entry per sensitive field it holds a value for.
     *
     * @param definition the configuration's declaration - the ONLY place that says which fields may hold a secret
     * @return a patch, empty when the vault holds nothing for those fields
     * @throws SecretRefused when an entry exists but cannot be decrypted: a broken vault is not an empty one
     */
    public ConfigPatch patch(ConfigDefinition definition) {
        ConfigPatch patch = ConfigPatch.empty();
        for (ConfigField field : definition.fields()) {
            if (!field.isSensitive()) {
                continue;
            }
            ConfigPath path = field.path();
            Optional<String> value = vault.get(path.canonical());
            if (value.isPresent()) {
                patch = patch.with(path, ConfigString.of(value.get()), sourceFor(path));
            }
        }
        return patch;
    }

    /** Where the value came from, in the words a diagnostic shows a person: the vault file, never a bare "vault". */
    private ConfigSource sourceFor(ConfigPath path) {
        return ConfigSource.of(Layer.CUSTOM, "vault:" + vault.filePath().getFileName() + ":" + path.canonical());
    }
}
