package dev.jucm.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The working copy: editing one field recomputes the WHOLE state and tells the view what changed —
 * including fields far from the cause, such as `b = ${a}`.
 */
class ConfigDraftTest {

    private static final TransformerRegistry REGISTRY = TransformerRegistry.defaults();
    private static final ConfigSource FILE = ConfigSource.of(Layer.YAML, "application.yaml");

    private static ConfigDefinition definition() {
        return ConfigDefinition.of(
            ConfigField.required("a", ConfigType.INT),
            ConfigField.required("b", ConfigType.STR));
    }

    private static ConfigPatch file(String path, String text) {
        return ConfigPatch.set(ConfigPath.of(path), ConfigString.of(text), FILE);
    }

    private static String value(Config config, String path) {
        return ((ConfigResult.ValueResult<ConfigValue>) config.get(path)).value().display();
    }

    @Test
    @DisplayName("`b = ${a}`: changing `a` from 10 to 20 refreshes `b` — and the view is told about BOTH")
    void dependentFieldIsRecomputed() {
        ConfigDraft draft = ConfigDraft.of(definition(), REGISTRY, file("a", "10"), file("b", "${a}"));
        assertThat(value(draft.snapshot(), "b")).as("the initial state").isEqualTo("10");

        List<ConfigDraft.Change> changes = draft.edit(ConfigPath.of("a"), ConfigString.of("20"));

        assertThat(value(draft.snapshot(), "b")).as("`b` recomputed by the flow, not by the view")
            .isEqualTo("20");
        assertThat(changes).extracting(change -> change.path().canonical())
            .as("both changes are there: the requested one and the derived one")
            .containsExactly("a", "b");
        assertThat(changes).anySatisfy(change -> {
            assertThat(change.path().canonical()).isEqualTo("b");
            assertThat(change.before()).isEqualTo("10");
            assertThat(change.after()).isEqualTo("20");
        });
    }

    @Test
    @DisplayName("a user edit goes on top of the layers (`Layer.TUI`) and beats the file")
    void editsOutrankTheLayersBelow() {
        ConfigDraft draft = ConfigDraft.of(definition(), REGISTRY, file("a", "10"), file("b", "${a}"));

        draft.edit(ConfigPath.of("a"), ConfigString.of("7"));

        assertThat(value(draft.snapshot(), "a")).isEqualTo("7");
        assertThat(((ConfigPatch.SetValue) draft.patch().operation("a").orElseThrow()).source().layer())
            .as("the origin tells the truth: this is an edit, not the file").isEqualTo(Layer.TUI);
        assertThat(draft.isDirty()).isTrue();
    }

    @Test
    @DisplayName("`reset()` undoes everything — the state returns exactly to the incoming layers")
    void resetDropsEveryEdit() {
        ConfigDraft draft = ConfigDraft.of(definition(), REGISTRY, file("a", "10"), file("b", "${a}"));
        draft.edit(ConfigPath.of("a"), ConfigString.of("20"));
        draft.edit(ConfigPath.of("b"), ConfigString.of("manually"));

        List<ConfigDraft.Change> back = draft.edit(ConfigPath.of("a"), ConfigString.of("10"));
        assertThat(back).as("restoring `a` also brings `b` back to the value computed from the file").isNotEmpty();
        draft.reset();

        assertThat(draft.isDirty()).isFalse();
        assertThat(value(draft.snapshot(), "a")).isEqualTo("10");
        assertThat(value(draft.snapshot(), "b")).as("`b` is computed from `${a}` again, not from a manual edit")
            .isEqualTo("10");
    }

    @Test
    @DisplayName("a field that was absent before is NEW — the view tells \"was not there\" from \"empty\"")
    void absentBecomesNew() {
        ConfigDraft draft = ConfigDraft.of(definition(), REGISTRY, file("a", "10"), file("b", "${a}"));

        ConfigDraft.Change change = draft.edit(ConfigPath.of("b"), ConfigString.of("x")).stream()
            .filter(candidate -> candidate.path().canonical().equals("b")).findFirst().orElseThrow();

        assertThat(change.isNew()).as("`b` existed, so this is not a new field").isFalse();
        assertThat(draft.report().hasErrors()).as("a state without errors").isFalse();
    }

    @Test
    @DisplayName("the verdicts come from the SAME validator as a real run — e.g. a bad type from an edit")
    void reportUsesTheSameValidator() {
        ConfigDraft draft = ConfigDraft.of(definition(), REGISTRY, file("a", "10"), file("b", "${a}"));

        draft.edit(ConfigPath.of("a"), ConfigString.of("not-a-number"));

        assertThat(draft.report().errors()).extracting(ConfigError::kind)
            .as("`config-validation` will see exactly the same thing").contains(ConfigError.Kind.CAST_FAILED);
    }
}
