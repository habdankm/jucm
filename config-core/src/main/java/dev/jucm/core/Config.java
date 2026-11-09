package dev.jucm.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * An immutable snapshot of the configuration, linked to its parent as a **chain** (config lineage).
 *
 * <pre>
 *   slots   : only what this layer brought in (storage is sparse)
 *   layer   : the layer this snapshot belongs to
 *   parent  : the previous snapshot - the chain itself is the history
 * </pre>
 *
 * <p>There is no separate change log and no {@code oldValue} field: a second copy of the history
 * would be one more thing to keep in sync, while a chain of immutable snapshots is a history by
 * construction. Origin is **per value** - carried by {@link Entry} - because without it neither
 * {@code application.yaml:12} nor {@code ENV:AIMENAGERIE__SERVER__PORT} could be shown.
 *
 * <p>Sparseness is deliberate: a node holds only what it brought in, and the only dense thing is the
 * lookup - the semantic view sees every declared field, including the ones no source has ever
 * mentioned (those fall back to {@code defaultValue} or end up as {@code Absent}). A value of type
 * {@code Unset}/{@code NotProvided} does **not exist** in the tree.
 *
 * <p><b>No mutation, and verdicts belong to steps.</b> Nothing is rearranged in place: a
 * {@link ConfigStep} returns a **new** variant in which entries carry that step's verdict
 * ({@link Verdict}). Re-running a step is therefore free (entries that already carry a verdict are
 * skipped), {@code build()} does not have to check anything twice, and an older snapshot keeps its
 * own image.
 *
 * <p><b>Two operations, two roles.</b> {@link #with(ConfigPath, ConfigValue)} grows the **current**
 * layer (the parent is untouched), while {@link #apply(ConfigPatch)} **closes** the layer - the
 * previous variant becomes the layer underneath. There is no {@code seal()} and no separate builder.
 *
 * @since 1.0
 */
public final class Config {

    /**
     * A step's verdict on an entry: the step examined the value and either let it through or said
     * why not. **A missing verdict is not an error** - it means the step has not been here yet.
     *
     * <p>{@code FAILED} does **not** remove the entry: the value stays in the variant together with
     * the problem, so the report ({@code build()}) reproduces it, and a later step in the flow does
     * not process that entry again - one mistake yields one problem, not two.
     *
     * @since 1.0
     */
    public sealed interface Verdict permits Verdict.Passed, Verdict.Failed {

        /** The step let the value through. */
        record Passed() implements Verdict {
        }

        /**
         * The step rejected the value; the problem carries the reason.
         *
         * @param error the problem that stopped the value; never null
         */
        record Failed(ConfigError error) implements Verdict {
        /** Validates the components — every one of them is required. */
            public Failed {
                Objects.requireNonNull(error, "error");
            }
        }

        /**
         * Returns a passing verdict.
         *
         * @return a verdict that lets the value through
         */
        static Verdict passed() {
            return new Passed();
        }

        /**
         * Returns a failing verdict.
         *
         * @param error the problem that stopped the value; must not be {@code null}
         * @return a verdict carrying that problem
         */
        static Verdict failed(ConfigError error) {
            return new Failed(error);
        }

        /**
         * Returns whether this verdict is a pass.
         *
         * @return true when no problem stopped the value
         */
        default boolean isPassed() {
            return this instanceof Passed;
        }

        /**
         * The problem that stopped the value.
         *
         * @return the problem, or empty when the step let the value through
         */
        default Optional<ConfigError> problem() {
            return this instanceof Failed failed ? Optional.of(failed.error()) : Optional.empty();
        }
    }

    /**
     * A value under a path together with its origin and step verdicts - the unit of diagnostics and
     * of the TUI.
     *
     * <p>The verdict map is unmodifiable, so an entry cannot change under a read.
     *
     * @param value       the value; must not be {@code null}
     * @param source      where the value came from; must not be {@code null}
     * @param verdicts    verdicts of the steps that have seen this value; must not be {@code null}
     * @param declaration what the source stated about type and notation; must not be {@code null}
     * @since 1.0
     */
    public record Entry(ConfigValue value, ConfigSource source, Map<ConfigStep, Verdict> verdicts,
                        SourceDeclaration declaration) {

        /** Validates the components — every one of them is required. */
        public Entry {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(declaration, "declaration");
            verdicts = Map.copyOf(verdicts);
        }

        /**
         * An entry without verdicts: a value brought in programmatically or fresh from a patch.
         *
         * @param value  the value; must not be {@code null}
         * @param source the origin of the value; must not be {@code null}
         */
        public Entry(ConfigValue value, ConfigSource source) {
            this(value, source, Map.of(), SourceDeclaration.none());
        }

        /**
         * An entry about which the source said nothing on type and notation - what most entries look
         * like.
         *
         * @param value    the value; must not be {@code null}
         * @param source   the origin of the value; must not be {@code null}
         * @param verdicts verdicts of the steps that have seen this value; must not be {@code null}
         */
        public Entry(ConfigValue value, ConfigSource source, Map<ConfigStep, Verdict> verdicts) {
            this(value, source, verdicts, SourceDeclaration.none());
        }

        /**
         * The verdict of one step, if that step has seen this value.
         *
         * @param step the step to look up; must not be {@code null}
         * @return the step's verdict, or empty when the step has not seen this value
         */
        public Optional<Verdict> verdict(ConfigStep step) {
            Objects.requireNonNull(step, "step");
            return Optional.ofNullable(verdicts.get(step));
        }

        /**
         * Returns whether the step has already examined this value - no verdict means "not yet".
         *
         * @param step the step to ask about; must not be {@code null}
         * @return true when that step has already recorded a verdict
         */
        public boolean resolved(ConfigStep step) {
            return verdicts.containsKey(step);
        }

        /**
         * Every problem carried by the verdicts, in {@link ConfigStep} declaration order.
         *
         * @return an unmodifiable list of problems; empty when no step found anything wrong
         */
        public List<ConfigError> problems() {
            List<ConfigError> found = new ArrayList<>();
            for (ConfigStep step : ConfigStep.values()) {
                verdict(step).flatMap(Verdict::problem).ifPresent(found::add);
            }
            return List.copyOf(found);
        }
    }

    /**
     * One step of the history: what a single layer contributed for this path.
     *
     * @param source the origin of the contribution; must not be {@code null}
     * @param value  the value that layer carried, or empty when the layer brought in no value (the
     *               source delivered nothing, the entry was dropped, or it was reset); must not be
     *               {@code null}
     * @param layer  the layer this step belongs to; must not be {@code null}
     * @since 1.0
     */
    public record Step(ConfigSource source, Optional<ConfigValue> value, Layer layer) {
        /** Validates the components — every one of them is required. */
        public Step {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(layer, "layer");
        }

        /**
         * The printable form: {@code YAML:application.yaml:12 = 8080}, or {@code ... = <not
         * provided>} when the layer contributed no value.
         *
         * @return the printable form, never null
         */
        public String display() {
            return source.display() + " = " + value.map(ConfigValue::display).orElse("<not provided>");
        }
    }

    /**
     * Applying one step to one entry - the logic of a step lives in the step, not in the tree.
     * Returning empty means the step does not apply to that entry, so the entry stays **without a
     * verdict** and the step's coverage stays incomplete.
     */
    @FunctionalInterface
    interface EntryStep {

        Optional<Outcome> apply(ConfigPath path, ConfigValue value, ConfigSource source,
                                SourceDeclaration declaration);

        /** What the step did with the entry: the new value (possibly the same one) and the verdict. */
        record Outcome(ConfigValue value, Verdict verdict) {
            public Outcome {
                Objects.requireNonNull(value, "value");
                Objects.requireNonNull(verdict, "verdict");
            }
        }
    }

    /**
     * A step that **walks the tree** - for steps that must see **other entries**. Interpolation is
     * the only such step: resolving {@code ${a}} means looking under {@code a}, and catching a cycle
     * means walking the whole chain. Per-entry steps stay with {@link EntryStep}.
     */
    @FunctionalInterface
    interface ValueStep {

        /**
         * @param view the same variant for every entry (the view **before** the step), so the
         *             outcome does not depend on the order in which the tree is walked
         * @return the new form of the entry; empty means the step does not apply to it (the entry
         *         stays without a verdict)
         */
        Optional<Rewrite> apply(Config view, ConfigPath path, ConfigValue value, ConfigSource source);
    }

    /** What a walk did with an entry: it replaced the value (with a verdict), or the entry ceased to exist. */
    sealed interface Rewrite permits Rewrite.Replaced, Rewrite.Dropped {

        record Replaced(ConfigValue value, Verdict verdict) implements Rewrite {
            public Replaced {
                Objects.requireNonNull(value, "value");
                Objects.requireNonNull(verdict, "verdict");
            }
        }

        /**
         * The expression **yielded nothing** ({@code ${?x}} with no target): the layer brings in no
         * value, so the entry disappears and the lookup goes lower - an expression that produced no
         * value does not block a lower fallback. This is not recorded as a state, because a state is
         * not a value.
         */
        record Dropped() implements Rewrite {
        }
    }

    /** An entry in a node: a value, or a marker that removes or disables an override. */
    private sealed interface Slot permits ValueSlot, ResetSlot, NullSlot, DroppedSlot {
    }

    /**
     * The view of one entry for the **resolver** - as much as reference resolution needs: the value, the
     * origin and the facts that come without a value ({@code null} / {@code reset} / {@code dropped}).
     * The resolver must not see {@code Slot}, because that is a detail of the tree, but it must be able
     * to walk the chain exactly like a lookup does.
     *
     * <p>The two flags are not a way of saying "no value": {@code nothing} is the source stating it
     * delivered **nothing** ({@code null}), which **stops** the read; {@code reset} skips exactly the
     * layer below; and an entry with neither flag and no value is {@code dropped} - the layer brought in
     * nothing, so the read continues **below** it, as if that layer had not mentioned the path at all.
     */
    record SlotView(Optional<ConfigValue> value, ConfigSource source, boolean nothing, boolean reset) {

        SlotView {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(source, "source");
        }
    }

    /** The entry in **this** node (not in the whole chain), or {@code null} when this node does not have it. */
    SlotView slotAt(ConfigPath path) {
        Slot slot = slots.get(path);
        return switch (slot) {
            case null -> null;
            case ValueSlot value -> new SlotView(Optional.of(value.value()), value.source(), false, false);
            case NullSlot nothing -> new SlotView(Optional.empty(), nothing.source(), true, false);
            case ResetSlot reset -> new SlotView(Optional.empty(), reset.source(), false, true);
            // `dropped`: this layer contributes NO value, so the read goes below it - the same shape as
            // "this layer does not have the path at all". The source is kept for the diagnostics.
            case DroppedSlot dropped -> new SlotView(Optional.empty(), dropped.source(), false, false);
        };
    }

    /** The source delivered nothing - in the tree that is a fact, not a value. */
    private record NullSlot(ConfigSource source) implements Slot {
        NullSlot {
            Objects.requireNonNull(source, "source");
        }
    }

    private record ValueSlot(ConfigValue value, ConfigSource source, Map<ConfigStep, Verdict> verdicts,
                             SourceDeclaration declaration) implements Slot {

        ValueSlot {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(declaration, "declaration");
            verdicts = Map.copyOf(verdicts);
        }
    }

    /**
     * The {@code dropped} marker: a step decided that the entry ceases to exist. The entry is **not**
     * deleted - the marker keeps the layer from contributing a value (so the read continues below it,
     * exactly as if that layer had not mentioned the address), and the declarations of lower layers stay
     * untouched. That is what lets {@code history} show WHERE the expression yielded nothing and in
     * which step.
     */
    private record DroppedSlot(ConfigSource source, ConfigStep step, Verdict verdict) implements Slot {

        DroppedSlot {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(step, "step");
            Objects.requireNonNull(verdict, "verdict");
        }
    }

    /** The {@code reset} marker: it takes the override off the layer directly below. */
    private record ResetSlot(ConfigSource source) implements Slot {
        ResetSlot {
            Objects.requireNonNull(source, "source");
        }
    }

    private final Map<ConfigPath, Slot> slots;
    private final Layer layer;
    private final Config parent;
    private final ConfigDefinition definition;

    private Config(Map<ConfigPath, Slot> slots, Layer layer, Config parent, ConfigDefinition definition) {
        this.slots = Map.copyOf(slots);
        this.layer = layer;
        this.parent = parent;
        this.definition = definition;
    }

    /** The definition of the snapshot, or {@code null} for a chain built without one. */
    private static ConfigDefinition definitionOf(Config snapshot) {
        return snapshot == null ? null : snapshot.definition;
    }

    /**
     * The model this chain was built from, or {@code null} when no declaration was ever handed in (a chain of
     * hand-built values). Steps and reads use it to judge a value against its DECLARATION - `??` checks the
     * types of its operands there, because a fallback that does not fit the declaration must be an error and
     * not a silent answer from the layer below.
     *
     * @return the definition, or {@code null} when there is none
     * @since 1.0
     */
    public ConfigDefinition definition() {
        return definition;
    }

    /**
     * Returns an empty snapshot - the start of a hand-built chain.
     *
     * @return a snapshot on {@link Layer#CUSTOM}, with no entries and no parent
     */
    public static Config empty() {
        return new Config(Map.of(), Layer.CUSTOM, null, null);
    }

    /**
     * Returns the root layer, **materialised from the declaration**. The value comes from
     * {@code defaultValue}, and a field without a default **does not get a placeholder** - it ends
     * up as {@code Absent}.
     *
     * @param definition the declaration to materialise; must not be {@code null}
     * @return a snapshot on {@link Layer#COMPILED}, with no parent
     */
    public static Config root(ConfigDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        Map<ConfigPath, Slot> defaults = new LinkedHashMap<>();
        for (ConfigField field : definition.fieldsWithDefault()) {
            defaults.put(field.path(), new ValueSlot(field.defaultValue().orElseThrow(),
                ConfigSource.declaration(field.path()), Map.of(), SourceDeclaration.none()));
        }
        return new Config(defaults, Layer.COMPILED, null, definition);
    }

    /**
     * Applies a patch and returns a **new** snapshot; this object is left unchanged. An empty patch
     * does not create a new layer - there is nothing to bring in.
     *
     * <p>The patch must belong to a **single** layer: the layer of the snapshot is derived from its
     * entries (mixing layers in one patch is a programmer error, not a configuration state).
     *
     * @param patch the patch to apply; must not be {@code null}
     * @return a new snapshot with the patch as its top layer, or this snapshot when the patch is
     *         empty
     * @throws IllegalArgumentException if the patch mixes layers
     */
    public Config apply(ConfigPatch patch) {
        Objects.requireNonNull(patch, "patch");
        if (patch.isEmpty()) {
            return this;
        }
        Map<ConfigPath, Slot> brought = new LinkedHashMap<>();
        Layer patchLayer = null;
        for (Map.Entry<ConfigPath, ConfigPatch.Operation> entry : patch.operations().entrySet()) {
            ConfigPatch.Operation operation = entry.getValue();
            ConfigSource source = sourceOf(operation);
            if (patchLayer == null) {
                patchLayer = source.layer();
            } else if (patchLayer != source.layer()) {
                throw new IllegalArgumentException("patch mixes layers: `" + patchLayer + "` and `"
                    + source.layer() + "` - a single patch brings in a single layer");
            }
            brought.put(entry.getKey(), slotOf(operation, source));
        }
        return new Config(brought, patchLayer, this, definitionOf(this));
    }

    private static ConfigSource sourceOf(ConfigPatch.Operation operation) {
        if (operation instanceof ConfigPatch.SetValue set) {
            return set.source();
        }
        if (operation instanceof ConfigPatch.Reset reset) {
            return reset.source();
        }
        return ((ConfigPatch.NullDelivered) operation).source();
    }

    private static Slot slotOf(ConfigPatch.Operation operation, ConfigSource source) {
        if (operation instanceof ConfigPatch.SetValue set) {
            return new ValueSlot(set.value(), source, Map.of(), set.declaration());
        }
        if (operation instanceof ConfigPatch.NullDelivered) {
            return new NullSlot(source);
        }
        return new ResetSlot(source);
    }

    /**
     * Grows the **current** layer: a new variant with one more entry and the same {@code parent}.
     *
     * <p>A value brought in programmatically carries **no verdicts**, and that is a feature rather
     * than a problem: it shows exactly which values have not gone through the steps yet, and the
     * next walk processes each of them exactly **once**. That is why {@code with} does not create a
     * link of the chain - only {@link #apply(ConfigPatch)} does.
     *
     * @param path  the path to set; must not be {@code null}
     * @param value the value to put there; must not be {@code null}
     * @return a new snapshot with the entry added to the current layer
     */
    public Config with(ConfigPath path, ConfigValue value) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(value, "value");
        Map<ConfigPath, Slot> extended = new LinkedHashMap<>(slots);
        extended.put(path, new ValueSlot(value, ConfigSource.application(), Map.of(), SourceDeclaration.none()));
        return new Config(extended, layer, parent, definitionOf(parent));
    }

    /**
     * Convenience form of {@link #with(ConfigPath, ConfigValue)}, for example
     * {@code config.with("server.port", ConfigInt.of(9000))}.
     *
     * @param canonicalPath the path in canonical notation; must not be {@code null} or empty
     * @param value         the value to put there; must not be {@code null}
     * @return a new snapshot with the entry added to the current layer
     * @throws IllegalArgumentException if {@code canonicalPath} is not a valid canonical path
     */
    public Config with(String canonicalPath, ConfigValue value) {
        return with(ConfigPath.of(canonicalPath), value);
    }

    /**
     * Runs **one step** of the flow over this chain: returns a new variant in which entries that had
     * no verdict from that step now have one. This object is left unchanged.
     *
     * <p>The step skips entries that already carry their verdict (a repeat is free), as well as the
     * ones stopped by an earlier step - one mistake is one problem, not two. When nothing changed,
     * the **same** variant is returned.
     */
    Config advance(ConfigStep step, EntryStep entryStep) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(entryStep, "entryStep");
        Config below = parent == null ? null : parent.advance(step, entryStep);
        Map<ConfigPath, Slot> updated = new LinkedHashMap<>();
        for (Map.Entry<ConfigPath, Slot> entry : slots.entrySet()) {
            updated.put(entry.getKey(), advanced(entry.getKey(), entry.getValue(), step, entryStep));
        }
        if (below == parent && updated.equals(slots)) {
            return this;
        }
        return new Config(updated, layer, below, definitionOf(below));
    }

    /** Whether an earlier step stopped this entry - a later step then has nothing to check. */
    private static boolean stopped(ValueSlot slot) {
        return slot.verdicts().values().stream().anyMatch(verdict -> !verdict.isPassed());
    }

    private static Slot advanced(ConfigPath path, Slot slot, ConfigStep step, EntryStep entryStep) {
        if (!(slot instanceof ValueSlot value) || value.verdicts().containsKey(step) || stopped(value)) {
            return slot;
        }
        Optional<EntryStep.Outcome> outcome = entryStep.apply(path, value.value(), value.source(),
            value.declaration());
        if (outcome.isEmpty()) {
            return slot;
        }
        EntryStep.Outcome resolved = outcome.get();
        Map<ConfigStep, Verdict> verdicts = new LinkedHashMap<>(value.verdicts());
        verdicts.put(step, resolved.verdict());
        return new ValueSlot(resolved.value(), value.source(), verdicts, value.declaration());
    }

    /**
     * Runs a step that **walks the tree** - like {@link #advance}, but the step also receives a
     * **view of the whole**. The outcome of resolving {@code ${a}} therefore does not depend on the
     * order in which the tree is walked, and a cycle is detected by one walk instead of several
     * independent reads.
     *
     * <p>Entries that already carry this step's verdict, and entries stopped earlier, are skipped -
     * one mistake is one problem. An entry the step declared {@link Rewrite.Dropped} **disappears**
     * from this layer.
     */
    Config resolve(ConfigStep step, ValueStep valueStep) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(valueStep, "valueStep");
        return resolve(step, this, valueStep);
    }

    private Config resolve(ConfigStep step, Config view, ValueStep valueStep) {
        return rewriteForStep(step, view, valueStep, new LinkedHashSet<>());
    }

    /**
     * The step decides the **winning** entries and writes the outcome into the layer of that entry -
     * it does not spread it over the whole chain. {@code claimed} carries the paths already decided
     * higher up, so every path belongs to exactly one layer: the first one from the top that carries
     * it.
     *
     * <p>As a result, lower layers keep their declarations, so {@code history} stops lying. The
     * disappearance of an entry is a marker, not a deletion - otherwise the value would fall back to
     * a lower layer.
     */
    private Config rewriteForStep(ConfigStep step, Config view, ValueStep valueStep,
                                  Set<ConfigPath> claimed) {
        Map<ConfigPath, Slot> updated = new LinkedHashMap<>();
        for (Map.Entry<ConfigPath, Slot> entry : slots.entrySet()) {
            ConfigPath path = entry.getKey();
            Slot slot = entry.getValue();
            if (claimed.contains(path) || slot instanceof ResetSlot) {
                updated.put(path, slot);   // `reset` does not claim the path: it only skips the layer below
                continue;
            }
            if (!(slot instanceof ValueSlot value)) {
                claimed.add(path);         // `null` / `dropped` - the path is decided
                updated.put(path, slot);
                continue;
            }
            if (value.verdicts().containsKey(step) || stopped(value)) {
                claimed.add(path);
                updated.put(path, slot);
                continue;
            }
            Optional<Rewrite> outcome = valueStep.apply(view, path, value.value(), value.source());
            claimed.add(path);
            if (outcome.isEmpty()) {
                updated.put(path, slot);   // the step does not apply to this entry - it stays without a verdict
                continue;
            }
            updated.put(path, switch (outcome.get()) {
                case Rewrite.Dropped ignored -> new DroppedSlot(value.source(), step, Verdict.passed());
                case Rewrite.Replaced replaced -> {
                    Map<ConfigStep, Verdict> verdicts = new java.util.EnumMap<>(ConfigStep.class);
                    verdicts.putAll(value.verdicts());
                    verdicts.put(step, replaced.verdict());
                    yield new ValueSlot(replaced.value(), value.source(), verdicts, value.declaration());
                }
            });
        }
        Config below = parent == null ? null : parent.rewriteForStep(step, view, valueStep, claimed);
        if (below == parent && updated.equals(slots)) {
            return this;
        }
        return new Config(updated, layer, below, definitionOf(below));
    }

    /**
     * The coverage of the walk: the steps that have gone through **every entry carrying a value** in
     * this chain. A step with incomplete coverage is simply not reported.
     *
     * <p>Computed from the entries rather than stored - just like {@link #history(ConfigPath)}:
     * there is nothing to keep in sync, so the answer is always true. {@code NullDelivered} and
     * {@code reset} carry no value, so they have nothing to process and do not hold coverage back.
     *
     * @return an unmodifiable set of steps with full coverage; empty when the chain carries no value
     */
    public Set<ConfigStep> steps() {
        List<ValueSlot> entries = effectiveSlots();
        if (entries.isEmpty()) {
            return Set.of();
        }
        Set<ConfigStep> complete = new LinkedHashSet<>();
        for (ConfigStep step : ConfigStep.values()) {
            if (entries.stream().allMatch(slot -> slot.verdicts().containsKey(step))) {
                complete.add(step);
            }
        }
        return Collections.unmodifiableSet(complete);
    }

    /**
     * The **winning** entries - one per path: the first one from the top, until the path is decided
     * (a {@code null}/{@code dropped} marker) or skipped ({@code reset} does not stop the path).
     *
     * <p>After {@code resolve} only the winning layer carries step verdicts, so a step's coverage is
     * counted on **these** entries - otherwise a step that handled every value would look incomplete
     * merely because lower layers still have their own declarations.
     */
    private List<ValueSlot> effectiveSlots() {
        List<ValueSlot> found = new ArrayList<>();
        Set<ConfigPath> claimed = new LinkedHashSet<>();
        for (Config config = this; config != null; config = config.parent) {
            for (Map.Entry<ConfigPath, Slot> entry : config.slots.entrySet()) {
                if (claimed.contains(entry.getKey()) || entry.getValue() instanceof ResetSlot) {
                    continue;
                }
                claimed.add(entry.getKey());
                if (entry.getValue() instanceof ValueSlot value) {
                    found.add(value);
                }
            }
        }
        return found;
    }

    /**
     * Returns the layer of this snapshot.
     *
     * @return the layer this snapshot was built from
     */
    public Layer layer() {
        return layer;
    }

    /**
     * Returns the previous snapshot of the chain, or empty when this is its root.
     *
     * @return the parent snapshot, or empty for the root
     */
    public Optional<Config> parent() {
        return Optional.ofNullable(parent);
    }

    /**
     * The paths brought in by **this** node - not by the whole chain (storage is sparse).
     *
     * @return the paths this node carries, in insertion order, never null
     */
    public List<ConfigPath> ownPaths() {
        return List.copyOf(slots.keySet());
    }

    /**
     * The **symbolic** view: the entry that wins in the chain, together with its origin and verdicts.
     * Unlike {@link #get(ConfigPath)} it does not resolve interpolation.
     *
     * <p>A layer whose expression yielded nothing ({@code dropped}) **does not answer**: the view
     * continues below it, because that is where the read finds its value too. The symbolic view and the
     * read therefore report the same fact about the same entry.
     *
     * @param path the path to look up; must not be {@code null}
     * @return the winning entry, or empty when no layer in the chain carries this path
     */
    public Optional<Entry> entry(ConfigPath path) {
        Objects.requireNonNull(path, "path");
        boolean skippedOne = false;
        for (Config config = this; config != null; config = config.parent) {
            Slot slot = config.slots.get(path);
            if (slot == null) {
                continue;
            }
            if (skippedOne) {
                skippedOne = false; // this layer was just taken off by a `reset`
                continue;
            }
            if (slot instanceof ValueSlot value) {
                return Optional.of(new Entry(value.value(), value.source(), value.verdicts(),
                    value.declaration()));
            }
            if (slot instanceof NullSlot nothing) {
                return Optional.empty(); // nothing was delivered - there is no value to show
            }
            if (slot instanceof ResetSlot) {
                skippedOne = true; // `reset`: skip the override from the layer directly below
                continue;
            }
            // `dropped`: this layer contributes no value - the view goes on below it, exactly like a read
        }
        return Optional.empty();
    }

    /**
     * A **resolved** read: the first hit walking from the newest node down to the root.
     *
     * <pre>
     *   no entry in this layer (Missing)           -&gt; continue in the parent
     *   entry present and it yields a value        -&gt; return it (after resolving `${...}`)
     *   the expression YIELDED NOTHING (`${?x}`)   -&gt; continue in the parent, as if there were no entry
     *   the source delivered nothing (NullValue)   -&gt; an ERROR, without going lower
     *   `reset`                                    -&gt; skip the override of the layer below
     *   end of the chain                           -&gt; Absent
     * </pre>
     *
     * <p>Resolution is **lazy and belongs to the read**: the tree keeps symbols, so the same
     * {@code Config.with("a", 20)} yields a different {@code c = ${a}} without touching {@code c}.
     * The symbolic view of the same entry comes from {@link #entry(ConfigPath)} - both are needed at
     * once.
     *
     * <p>{@code NullValue} does **not** fall through: empty means an error, not a missing value.
     *
     * @param path the path to read; must not be {@code null}
     * @return the resolved value, or an error saying why there is none
     */
    public ConfigResult<ConfigValue> get(ConfigPath path) {
        Objects.requireNonNull(path, "path");
        return switch (Interpolation.lookup(this, path)) {
            case Interpolation.Outcome.Value value -> opened(value.value(), path);
            case Interpolation.Outcome.YieldsAbsent ignored -> ConfigResult.error(new ConfigError.Absent(path));
            case Interpolation.Outcome.Problem problem -> ConfigResult.error(problem.error());
        };
    }

    /**
     * A SEALED VALUE OPENS HERE - AT THE READ, not at the parse (owner, 31 X 2026: „vault_key wsadzić też jako base64 do głównej konfiguracji. potem interpolować tę
     * wartość w miejscach”). The `key` member may be `${vault_key}`, and `${…}` is resolved against ONE snapshot at read time; opening the value while parsing would
     * freeze the key to the literal text and fail.
     *
     * @param value what the entry holds
     * @param path  where it stands, for the sentence when it will not open
     * @return the plain value, or the refusal
     */
    private ConfigResult<ConfigValue> opened(ConfigValue value, ConfigPath path) {
        if (!(value instanceof ConfigSealed sealed)) {
            return ConfigResult.value(value);
        }
        ConfigSource here = ConfigSource.of(Layer.CUSTOM, "decrypt:" + path.canonical());
        try {
            return ConfigResult.value(ConfigString.of(Sealed.open(sealed.ciphertext(), keyOf(sealed, path, here))));
        } catch (IllegalArgumentException refused) {
            return ConfigResult.error(new ConfigError.ParseFailed(here, refused.getMessage()));
        }
    }

    /**
     * THE KEY THE VALUE WAS SEALED WITH: a literal, or ONE `${…}` reference resolved against this snapshot.
     *
     * <p>A key made of several references (`"${a}-${b}"`) is REFUSED rather than quietly resolved: a secret whose key is assembled from several values has no single
     * place a person can check, and a typo there would look like a wrong password.
     */
    private String keyOf(ConfigSealed sealed, ConfigPath path, ConfigSource source) {
        String written = sealed.key();
        if (!written.contains("${")) {
            return written;
        }
        ConfigValue parsed = switch (Interpolation.parse(path, written, source)) {
            case ConfigResult.ValueResult<ConfigValue> ok -> ok.value();
            case ConfigResult.ErrorResult<ConfigValue> bad -> throw new IllegalArgumentException(bad.error().message());
        };
        if (!(parsed instanceof ConfigReference reference)) {
            throw new IllegalArgumentException("the `!decrypt` key `" + written
                + "` is not a single ${reference}: a key assembled from several values has no one place a person can check");
        }
        return switch (get(reference.target())) {
            case ConfigResult.ValueResult<ConfigValue> found -> found.value() instanceof ConfigString text ? text.value() : found.value().display();
            case ConfigResult.ErrorResult<ConfigValue> missing -> throw new IllegalArgumentException("the `!decrypt` key names `"
                + reference.target().canonical() + "`, which this configuration does not carry: " + missing.error().message());
        };
    }

    /**
     * Convenience read taking a canonical path.
     *
     * @param canonicalPath the path in canonical notation; must not be {@code null}
     * @return the resolved value, or an error saying why there is none
     */
    public ConfigResult<ConfigValue> get(String canonicalPath) {
        return get(ConfigPath.of(canonicalPath));
    }

    /**
     * Returns whether the whole chain has a value for this path, without reporting {@code Absent} as
     * an error.
     *
     * @param path the path to check; must not be {@code null}
     * @return true when some snapshot in the chain carries a value for that path
     */
    public boolean has(ConfigPath path) {
        return get(path).isValue();
    }

    /**
     * The history of a value: what each layer contributed, from the newest to the oldest. It is
     * **never stored** - it is computed by walking the chain.
     *
     * <p><b>Boundary:</b> on a chain **after** the {@code resolve} step each layer carries its
     * current, materialised state - so {@code history} then shows the state of a layer rather than
     * its declaration. When you need "what this layer said", read a snapshot taken **before** the
     * pipeline; that is what the {@code config-validation} report does with its {@code previous:}
     * row.
     *
     * @param path the path to trace; must not be {@code null}
     * @return an unmodifiable list of steps, newest layer first; empty when no layer ever mentioned
     *         this path
     */
    public List<Step> history(ConfigPath path) {
        Objects.requireNonNull(path, "path");
        List<Step> steps = new ArrayList<>();
        for (Config config = this; config != null; config = config.parent) {
            Slot slot = config.slots.get(path);
            if (slot instanceof ValueSlot value) {
                steps.add(new Step(value.source(), Optional.of(value.value()), config.layer));
            } else if (slot instanceof NullSlot nothing) {
                steps.add(new Step(nothing.source(), Optional.empty(), config.layer));
            } else if (slot instanceof DroppedSlot dropped) {
                steps.add(new Step(dropped.source(), Optional.empty(), config.layer));
            } else if (slot instanceof ResetSlot reset) {
                steps.add(new Step(reset.source(), Optional.empty(), config.layer));
            }
        }
        return List.copyOf(steps);
    }

    /**
     * The state as of a layer: the chain cut off at that layer, as if the layers above did not
     * exist. It is the materialisation computed for **that** layer at that moment - read values from
     * it as from an ordinary configuration ({@code get}), and {@code history} shows what each layer
     * up to it contributed.
     *
     * <p>Computed by walking the chain; nothing is stored and there is no cache to invalidate - the
     * mechanism is diagnostic. Empty when the layer is not part of this chain: the question then has
     * no answer and none is made up.
     *
     * <p><b>Boundary:</b> this is a view of the chain cut at the layer, so the values computed by
     * steps are the ones the steps left behind - and steps compute once, with the widest view. When
     * you need a state computed **in the context** of that layer (for example a {@code uri} whose
     * port comes only from the layers up to it), run the flow over the prefix of the patches; that
     * is what {@code config-validation-with-layers} does.
     *
     * @param layer the layer to cut at; must not be {@code null}
     * @return the snapshot of that layer, or empty when the layer is not in this chain
     */
    public Optional<Config> stateAsOf(Layer layer) {
        Objects.requireNonNull(layer, "layer");
        for (Config node = this; node != null; node = node.parent) {
            if (node.layer == layer) {
                return Optional.of(node);
            }
        }
        return Optional.empty();
    }

    /**
     * All paths present in the chain, without duplicates - the input for auxiliary analysis.
     *
     * @return the paths of every snapshot in the chain, in chain order
     */
    public List<ConfigPath> pathsInChain() {
        LinkedHashSet<ConfigPath> all = new LinkedHashSet<>();
        for (Config config = this; config != null; config = config.parent) {
            all.addAll(config.slots.keySet());
        }
        return List.copyOf(all);
    }

    /**
     * The depth of the chain: how many layers went in, the root included.
     *
     * @return the number of snapshots in the chain, at least 1
     */
    public int depth() {
        int depth = 0;
        for (Config config = this; config != null; config = config.parent) {
            depth++;
        }
        return depth;
    }

    @Override
    public String toString() {
        return "Config[layer " + layer + ", " + slots.size() + " own entries, depth " + depth() + "]";
    }
}
