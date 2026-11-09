package dev.jucm.yaml;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jucm.core.Config;
import dev.jucm.core.ConfigDefinition;
import dev.jucm.core.ConfigError;
import dev.jucm.core.ConfigPatch;
import dev.jucm.core.ConfigResult;
import dev.jucm.core.ConfigValue;
import dev.jucm.core.Sealed;
import dev.jucm.core.TransformerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `!decrypt` — A SEALED VALUE WHERE ITS SETTING LIVES, AND ITS KEY INTERPOLATED FROM THE SAME FILE.
 *
 * <p>Owner, 31 X 2026: „vault_key wsadzić też jako base64 do głównej konfiguracji, potem interpolować tę wartość w miejscach”. Więc plik niesie klucz RAZ, a wartość
 * pod adresem, którego czytelnik oczekuje; otwarcie dzieje się przy ODCZYCIE, bo `${…}` jest w JUCM leniwe.
 */
class DecryptTagTest {

    private static final TransformerRegistry REGISTRY = TransformerRegistry.defaults();
    private static final ConfigDefinition EMPTY = ConfigDefinition.of();

    private static ConfigPatch patch(String text) {
        ConfigResult<ConfigPatch> result = YamlSource.of("application.yaml", text).patch(Config.root(EMPTY), EMPTY, REGISTRY);
        assertThat(result).as("expected entries, but there is an error: " + errorOf(result))
            .isInstanceOf(ConfigResult.ValueResult.class);
        return ((ConfigResult.ValueResult<ConfigPatch>) result).value();
    }

    private static ConfigError parseError(String text) {
        ConfigResult<ConfigPatch> result = YamlSource.of("application.yaml", text).patch(Config.root(EMPTY), EMPTY, REGISTRY);
        assertThat(result).as("expected an error, but there are entries").isInstanceOf(ConfigResult.ErrorResult.class);
        return ((ConfigResult.ErrorResult<ConfigPatch>) result).error();
    }

    private static String errorOf(ConfigResult<?> result) {
        return result instanceof ConfigResult.ErrorResult<?> bad ? bad.error().report() : "-";
    }

    private static Config config(String text) {
        return Config.root(EMPTY).apply(patch(text));
    }

    /** The value AS THE READ SEES IT: opening a sealed value belongs to `Config.get`, not to the adapter. */
    private static String read(Config config, String canonicalPath) {
        return switch (config.get(canonicalPath)) {
            case ConfigResult.ValueResult<ConfigValue> ok -> ok.value().display();
            case ConfigResult.ErrorResult<ConfigValue> bad -> throw new AssertionError(bad.error().report());
        };
    }

    private static String readError(Config config, String canonicalPath) {
        return switch (config.get(canonicalPath)) {
            case ConfigResult.ValueResult<ConfigValue> ok -> throw new AssertionError("expected a refusal, got " + ok.value().display());
            case ConfigResult.ErrorResult<ConfigValue> bad -> bad.error().report();
        };
    }

    private static String sealedBlock(String plain, String keyText) {
        return "a: !decrypt\n  ciphertext: \"" + Sealed.seal(plain, "my-secret-key") + "\"\n  key: " + keyText + "\n";
    }

    @Test
    @DisplayName("a literal key opens the value at the address it stands under")
    void aLiteralKeyOpens() {
        assertThat(read(config(sealedBlock("s3cret", "\"my-secret-key\"")), "a")).isEqualTo("s3cret");
    }

    @Test
    @DisplayName("the key comes from the configuration once, interpolated where it is used")
    void theKeyIsInterpolated() {
        String text = "vault_key: \"my-secret-key\"\n" + sealedBlock("s3cret", "${vault_key}");

        assertThat(read(config(text), "a")).isEqualTo("s3cret");
    }

    @Test
    @DisplayName("a value sealed with another key is REFUSED, and the sentence carries neither the value nor the key")
    void aWrongKeyIsRefused() {
        String text = "a: !decrypt\n  ciphertext: \"" + Sealed.seal("s3cret", "the-right-key") + "\"\n  key: \"another-key\"\n";

        String refused = readError(config(text), "a");

        assertThat(refused).contains("cannot be opened");
        assertThat(refused).doesNotContain("s3cret").doesNotContain("the-right-key").doesNotContain("another-key");
    }

    @Test
    @DisplayName("a key that names something the configuration does not carry says WHICH address is missing")
    void aMissingKeyIsNamed() {
        String text = sealedBlock("s3cret", "${vault_key}");

        assertThat(readError(config(text), "a")).contains("vault_key");
    }

    @Test
    @DisplayName("the WRITER produces the shape the READER understands: `!decrypt` with a ciphertext and an interpolated key")
    void theWriterAndTheReaderAgree() {
        YamlDocument document = YamlDocument.parse("vault_key: \"my-secret-key\"\n");
        document.putSealed(Sealed.seal("s3cret", "my-secret-key"), "${vault_key}", "storage", "corgimq", "password");

        String written = document.toYaml();

        assertThat(written).as("the file a person reads: " + written).contains("!decrypt").contains("ciphertext").contains("${vault_key}");
        assertThat(read(config(written), "storage.corgimq.password")).isEqualTo("s3cret");
    }

    @Test
    @DisplayName("the tag names what it does not know, instead of silently ignoring a member")
    void anUnknownMemberIsRefused() {
        ConfigError refused = parseError("a: !decrypt\n  ciphertext: \"x\"\n  value: \"y\"\n");

        assertThat(refused.message()).contains("knows `ciphertext` and `key`").contains("value");
    }

    @Test
    @DisplayName("a tag on something that is not a mapping says what it takes")
    void aTagOnAScalarIsRefused() {
        ConfigError refused = parseError("a: !decrypt \"not-a-mapping\"\n");

        assertThat(refused.message()).contains("takes a mapping of `ciphertext` and `key`");
    }
}
