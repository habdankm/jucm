package dev.jucm.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A working copy of the configuration - the core of the configurator and of every tool that "edits
 * and checks": it holds the input layers, accepts changes and after **every** one exposes a new
 * snapshot plus the list of fields whose **resolved value** changed.
 *
 * <p>Why a separate object instead of "a text field with a value": with {@code b = ${a}} a change to
 * {@code a} changes {@code b} as well, so the view **MUST** know about fields **distant from the
 * cause**. Computing that in the view would drift from what the pipeline will really produce - which
 * is why the single source of truth is the snapshot and the view is its projection.
 *
 * <p>A change goes on top of the layers as {@link Layer#TUI}: it is a user edit, so it beats the
 * file, the environment and the command line - until it is removed with {@link #reset()}.
 *
 * @since 1.0
 */
public final class ConfigDraft {

    private final ConfigDefinition definition;
    private final TransformerRegistry registry;
    private final List<ConfigPatch> layers;
    private final Map<ConfigPath, ConfigPatch> edits = new LinkedHashMap<>();

    private ConfigDraft(ConfigDefinition definition, TransformerRegistry registry, List<ConfigPatch> layers) {
        this.definition = definition;
        this.registry = registry;
        this.layers = layers;
    }

    /**
     * Creates a draft over the given layers.
     *
     * @param definition the field declaration; must not be {@code null}
     * @param registry   the transformer registry; must not be {@code null}
     * @param layers     input layers in contribution order (as in {@link StandardPipeline#of});
     *                   edits always go on top of them; must not be {@code null} and must not
     *                   contain {@code null}
     * @return a draft with no edits yet
     */
    public static ConfigDraft of(ConfigDefinition definition, TransformerRegistry registry,
                                 ConfigPatch... layers) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(registry, "registry");
        return new ConfigDraft(definition, registry, List.of(layers));
    }

    /**
     * The source statement for a field, taken from the same layer as {@link #written(ConfigPath)}
     * (the last one wins). Needed wherever state is saved: a rule ({@code CONST}) and a notation
     * ({@code TCONF}) must survive a save. {@link #patch()} carries **only** session edits, so a
     * rule coming from a file would be left out of the save.
     *
     * @param path the field to look up
     * @return the statement carried by the winning layer, or empty when that layer stated nothing
     */
    public java.util.Optional<SourceDeclaration> writtenDeclaration(ConfigPath path) {
        List<ConfigPatch> layers = all();
        for (int index = layers.size() - 1; index >= 0; index--) {
            var operation = layers.get(index).operation(path);
            if (operation.isPresent()) {
                return java.util.Optional.of(((ConfigPatch.SetValue) operation.get()).declaration());
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * The **written** form of a field - what somebody really typed: the last layer (or edit), and
     * when nobody typed anything, the default value from the declaration.
     *
     * <p>This cannot be read through {@link Config#get}, not even from a "raw" tree, because value
     * interpolation is **lazy** - reading resolves {@code ${...}}. That is why the written form comes
     * from the layer material, not from the tree.
     *
     * @param path the field to look up
     * @return the text as written, or the empty string when neither a layer nor the declaration gives
     *         the field a value
     */
    public String written(ConfigPath path) {
        List<ConfigPatch> layers = all();
        for (int index = layers.size() - 1; index >= 0; index--) {
            var operation = layers.get(index).operation(path);
            if (operation.isPresent()) {
                return ((ConfigPatch.SetValue) operation.get()).value().display();
            }
        }
        for (ConfigField field : definition.fields()) {
            if (field.path().equals(path)) {
                return field.defaultValue().map(ConfigValue::display).orElse("");
            }
        }
        return "";
    }

    /**
     * The current state: layers plus edits, run through the standard pipeline.
     *
     * @return a fresh snapshot; the draft itself is unchanged
     */
    public Config snapshot() {
        return StandardPipeline.of(definition, registry, all().toArray(ConfigPatch[]::new));
    }

    /** Input layers in contribution order, with user edits at the end. */
    private List<ConfigPatch> all() {
        List<ConfigPatch> all = new ArrayList<>(layers);
        all.addAll(edits.values());
        return all;
    }

    /**
     * The accumulated editor change: all session edits merged together, ready to be saved as a
     * single node. Empty when nothing was edited.
     *
     * @return one patch carrying every session edit, or an empty patch
     */
    public ConfigPatch patch() {
        ConfigPatch patch = ConfigPatch.empty();
        for (ConfigPatch edit : edits.values()) {
            patch = patch.merge(edit);
        }
        return patch;
    }

    /**
     * Replaces the value and returns **all** fields whose resolved value changed because of it -
     * including those nobody touched ({@code b} when {@code a} changes). The view redraws exactly
     * that list.
     *
     * @param path  the field to change; must not be {@code null}
     * @param value the new value; must not be {@code null}
     * @return the changes to redraw, ordered by canonical path; empty when nothing changed
     */
    public List<Change> edit(ConfigPath path, ConfigValue value) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(value, "value");
        Config before = snapshot();
        edits.put(path, ConfigPatch.set(path, value, ConfigSource.of(Layer.TUI, path.canonical())));
        return diff(before, snapshot());
    }

    /**
     * An edit with an explicit origin - when the change did not come from the user but from a
     * generator.
     *
     * @param path   the field to change; must not be {@code null}
     * @param value  the new value; must not be {@code null}
     * @param source the origin of the edit; must not be {@code null}
     * @return the changes to redraw, ordered by canonical path; empty when nothing changed
     */
    public List<Change> edit(ConfigPath path, ConfigValue value, ConfigSource source) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(source, "source");
        Config before = snapshot();
        edits.put(path, ConfigPatch.set(path, value, source));
        return diff(before, snapshot());
    }

    /**
     * An edit with an explicit origin and a **source statement** (type, notation, rule) - for the
     * configurator: the {@code CONST} field next to a field writes a rule, and a rule coming from a
     * source SUPERSEDES rules from code.
     *
     * @param path        the field to change; must not be {@code null}
     * @param value       the new value; must not be {@code null}
     * @param source      the origin of the edit; must not be {@code null}
     * @param declaration what the source stated about type and notation; must not be {@code null}
     * @return the changes to redraw, ordered by canonical path; empty when nothing changed
     */
    public List<Change> edit(ConfigPath path, ConfigValue value, ConfigSource source,
                             SourceDeclaration declaration) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(declaration, "declaration");
        Config before = snapshot();
        edits.put(path, ConfigPatch.set(path, value, source, declaration));
        return diff(before, snapshot());
    }

    /** Takes off **all** edits: the state returns exactly to the input layers. */
    public void reset() {
        edits.clear();
    }

    /**
     * Returns whether the user changed anything - only then does a cancel make sense.
     *
     * @return true when at least one edit is pending
     */
    public boolean isDirty() {
        return !edits.isEmpty();
    }

    /**
     * The verdicts for the current state - the same validator that runs when the application starts.
     *
     * @return the report for a snapshot of the current state
     */
    public ConfigValidator.Report report() {
        return ConfigValidator.build(definition, snapshot());
    }

    /**
     * The fields whose value changed between two snapshots, compared by {@code display()}, that is,
     * by what is visible.
     *
     * <p>The order is **part of the contract**: changes are sorted by canonical path, so the view
     * redraws repeatably. Java's immutable collections have an iteration order that varies between
     * JVM runs, so that order is never relied upon here.
     */
    private List<Change> diff(Config before, Config after) {
        List<Change> changes = new ArrayList<>();
        for (ConfigField field : definition.fields()) {
            String was = display(before, field.path());
            String now = display(after, field.path());
            if (!Objects.equals(was, now)) {
                changes.add(new Change(field.path(), was, now));
            }
        }
        // BY VALUE, not by text: a numeric segment (a list address, a numeric key) compares as a number.
        changes.sort((one, other) -> ConfigPath.BY_VALUE.compare(one.path(), other.path()));
        return changes;
    }

    /** The value as the view sees it: {@code display()}, or {@code null} when the field is absent (absence is not emptiness). */
    private static String display(Config config, ConfigPath path) {
        return config.get(path) instanceof ConfigResult.ValueResult<ConfigValue> value
            ? value.value().display()
            : null;
    }

    /**
     * A change as the view sees it: what it was ({@code null} means absent) and what it is.
     *
     * @param path   the field that changed; must not be {@code null}
     * @param before the display form before the change, or {@code null} when the field had no value
     * @param after  the display form after the change; {@code null} when the field now has no value
     * @since 1.0
     */
    public record Change(ConfigPath path, String before, String after) {

        /**
         * Returns whether the field had no value before this change.
         *
         * @return true when there was no value before
         */
        public boolean isNew() {
            return before == null;
        }
    }
}
