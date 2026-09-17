package dev.jucm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The type of an element is **part of the declaration**: a collection says what it holds, and it cannot be
 * declared without saying it (`ConfigField.seq`, `ConfigField.map`).
 */
class ConfigFieldTest {

    @Test
    @DisplayName("`seq(path, INT)` and `map(path, INT)` carry the element type — there is no other way to say it")
    void collectionsCarryTheirElementType() {
        ConfigField seq = ConfigField.seq("ports", ConfigType.INT);
        ConfigField map = ConfigField.map("weight", ConfigType.STR, ConfigType.INT);

        assertThat(seq.type()).isEqualTo(ConfigType.SEQ);
        assertThat(seq.elementType()).contains(ConfigType.INT);
        assertThat(map.type()).isEqualTo(ConfigType.MAP);
        assertThat(map.elementType())
            .as("a map's elements are its VALUES — keys are text everywhere")
            .contains(ConfigType.INT);
        assertThat(map.keyType())
            .as("a map states BOTH types: without the key type `{10: a, 9: b}` would order as text")
            .contains(ConfigType.STR);
        assertThat(ConfigField.optionalSeq("extra", ConfigType.STR).elementType()).contains(ConfigType.STR);
        assertThat(ConfigField.optionalMap("labels", ConfigType.STR, ConfigType.STR).elementType())
            .contains(ConfigType.STR);
        assertThat(ConfigField.optionalMap("labels", ConfigType.STR, ConfigType.STR).keyType())
            .contains(ConfigType.STR);
        assertThat(ConfigField.seq("ports", ConfigType.INT, ConfigSeq.of(List.of(ConfigInt.of(80))))
            .defaultValue()).isPresent();
    }

    @Test
    @DisplayName("a collection WITHOUT an element type is a model error — the declaration must say what it holds")
    void aCollectionMustStateItsElementType() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> ConfigField.required("ports", ConfigType.SEQ))
            .as("a `seq` without the type of its elements")
            .withMessageContaining("states no element type").withMessageContaining("ConfigField.seq");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> ConfigField.optional("weights", ConfigType.MAP))
            .as("a `map` without the types of its keys and values")
            .withMessageContaining("states no element type").withMessageContaining("ConfigField.map");
    }

    @Test
    @DisplayName("a SCALAR cannot carry an element type — that declaration contradicts itself")
    void aScalarCannotCarryAnElementType() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new ConfigField(ConfigPath.of("port"), ConfigType.INT, true, Optional.empty(), "",
                List.of(), Map.of(), List.of(), Optional.empty(), Optional.empty(), Optional.of(ConfigType.INT),
                Optional.empty()))
            .withMessageContaining("element type belongs to a `seq` or a `map`");
    }

    @Test
    @DisplayName("a KEY type on anything but a `map` is refused — a `seq` has elements, not keys")
    void aKeyTypeBelongsToAMapOnly() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new ConfigField(ConfigPath.of("ports"), ConfigType.SEQ, true, Optional.empty(), "",
                List.of(), Map.of(), List.of(), Optional.empty(), Optional.empty(), Optional.of(ConfigType.INT),
                Optional.of(ConfigType.STR)))
            .withMessageContaining("only a `map` has keys");
    }

    @Test
    @DisplayName("the builders keep the element type: a description or an alias does not lose it on the way")
    void buildersKeepTheElementType() {
        ConfigField described = ConfigField.seq("ports", ConfigType.INT)
            .described("ports to listen on")
            .aliased("p");

        assertThat(described.elementType()).contains(ConfigType.INT);
        assertThat(described.description()).isEqualTo("ports to listen on");
        assertThat(described.aliases()).containsExactly("p");
    }
}
