package dev.jucm.core;

/**
 * It demonstrates the rule "Vavr inside, plain Java outside".
 *
 * <p>Vavr is the type of a <b>private field</b>. The public method returns a {@link java.util.List}, so a
 * consumer does not need Vavr on the classpath and will not see it in a signature. This class is also the
 * Vavr "carrier" in the native binary — it is the proof that Vavr works in Native Image.
 *
 * <p>Order: the list keeps layers <b>from the lowest to the highest</b> — exactly as it returns them and
 * as it prints them. No reversing in the getter, so that there are not two truths about the order.
 *
 */
public final class Provenance {

    private final io.vavr.collection.List<String> layers;

    private Provenance(io.vavr.collection.List<String> layers) {
        this.layers = layers;
    }

    /**
     * Layers in order from the lowest to the highest.
     *
     * @param layers the layer names, lowest first
     * @return a provenance keeping that order
     */
    public static Provenance of(String... layers) {
        return new Provenance(io.vavr.collection.List.ofAll(java.util.List.of(layers)));
    }

    /**
     * Public view: JDK types, never Vavr.
     *
     * @return an immutable list, from the lowest layer to the highest
     */
    public java.util.List<String> layers() {
        return layers.toJavaList();
    }

    /**
     * A new layer above the current one — a new object, no mutation.
     *
     * @param layer the layer name to append on top
     * @return a new provenance; this one is unchanged
     */
    public Provenance above(String layer) {
        return new Provenance(layers.append(layer));
    }

    /**
     * The highest layer — the one that "wins".
     *
     * @return the last layer, or an empty string when there is none
     */
    public String top() {
        return layers.isEmpty() ? "" : layers.last();
    }

    @Override
    public String toString() {
        return String.join(" > ", layers.toJavaList());
    }
}
