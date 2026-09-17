package dev.jucm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Closed sets: the value types and the layer kinds.
 *
 * <p>This test exists so that adding a type or a layer is a **decision** rather than an accident: changing
 * the number of elements changes the contract, so the test has to fail and force an update.
 */
class ClosedSetsTest {

    @Test
    @DisplayName("the set of types: eight from YAML plus two that WE recognise")
    void typesAreClosedWithTwoFamilies() {
        assertThat(ConfigType.values()).hasSize(10);
        assertThat(Arrays.stream(ConfigType.values()).filter(ConfigType::isYamlValueType)
            .map(ConfigType::yamlName).toList())
            .as("the YAML vocabulary — a value in the tree is one of these eight variants")
            .containsExactly("str", "int", "float", "bool", "timestamp", "seq", "map", "binary");
        assertThat(Arrays.stream(ConfigType.values()).filter(ConfigType::isDeclaredTarget)
            .map(ConfigType::yamlName).toList())
            .as("cast targets for declared fields — YAML does not know them, so we recognise them ourselves")
            .containsExactly("duration", "uri");
    }

    @Test
    @DisplayName("a type carries its own rules: the notation and the default shape")
    void typesCarryTheirOwnRules() {
        assertThat(ConfigType.INT.defaultTransformer()).contains("number");
        assertThat(ConfigType.FLOAT.defaultTransformer()).contains("number");
        assertThat(ConfigType.TIMESTAMP.defaultTransformer()).contains("date");
        assertThat(ConfigType.DURATION.defaultTransformer()).contains("duration");
        assertThat(ConfigType.URI.defaultTransformer()).contains("uri");
        assertThat(ConfigType.STR.defaultTransformer()).as("`str` without a default trim").isEmpty();
        assertThat(ConfigType.BOOL.defaultTransformer()).isEmpty();

        assertThat(ConfigType.URI.defaultConstraint().orElseThrow().rule())
            .as("the default shape rule for `uri` — a regex on the scheme")
            .isEqualTo("~`^[a-z][a-z0-9+.\\-]*://.*`");
        assertThat(ConfigType.DURATION.defaultConstraint())
            .as("the rule for `duration` is the notation (enforced by the transformer), not a shape").isEmpty();
        assertThat(ConfigType.INT.defaultConstraint()).isEmpty();
    }

    @Test
    @DisplayName("a type name outside the set is an error listing the allowed ones — both families named")
    void unknownTypeNameIsRejectedWithList() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> ConfigType.fromYamlName("moment"))
            .withMessageContaining("str, int, float, bool, timestamp, seq, map, binary")
            .withMessageContaining("duration, uri");
    }

    @Test
    @DisplayName("`duration` and `uri` are recognised by name — `___TYPE=duration` means something, not \"unknown\"")
    void ourOwnTypesAreRecognisedByName() {
        assertThat(ConfigType.fromYamlName("duration")).isEqualTo(ConfigType.DURATION);
        assertThat(ConfigType.fromYamlName("uri")).isEqualTo(ConfigType.URI);
        assertThat(ConfigType.URI.isDeclaredTarget()).isTrue();
        assertThat(ConfigType.STR.isDeclaredTarget()).isFalse();
    }

    @Test
    @DisplayName("six layer kinds in `source`")
    void layersAreClosed() {
        assertThat(List.of(Layer.values()))
            .containsExactly(Layer.COMPILED, Layer.YAML, Layer.ENV, Layer.CLI, Layer.TUI, Layer.CUSTOM);
    }
}
