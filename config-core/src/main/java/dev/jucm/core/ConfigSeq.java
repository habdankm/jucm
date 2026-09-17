package dev.jucm.core;

import io.vavr.collection.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * An ordered sequence of values — with <b>addresses</b> for its elements.
 *
 * <p>Instances are immutable. Elements are not typed: declared element types are checked when the
 * configuration is validated, not when the value is created.
 *
 * <p><b>An element has an ADDRESS, not only a position.</b> Addresses are normally {@code 0..n-1}, but an
 * element that contributes nothing (an optional reference with no target) <b>vanishes</b> and leaves its
 * address EMPTY: the content stays dense, the addressing becomes sparse. That is why {@code ports.2} addresses
 * the same element after {@code ports.1} vanished, and why a reference to a vanished address leads NOWHERE
 * instead of pointing at the neighbour that took its place.
 *
 * <p>Addresses are the business of ADDRESSING only ({@link #addresses()}, {@link #at(int)}, see
 * {@code Interpolation.lookup}). {@link #equals(Object)}, {@link #hashCode()}, {@link #size()},
 * {@link #elements()} and {@link #display()} speak about the CONTENT — the elements that are there, in
 * address order — so two lists with the same content are equal, however either of them was written.
 *
 * <p>An empty sequence is a valid instance but an invalid configuration value; validation rejects it,
 * because "no elements" is expressed by omitting the key.
 */
public final class ConfigSeq implements ConfigValue {

    /** Address → element. Sparse: a vanished element leaves its address empty. Ordered by address. */
    private final SortedMap<Integer, ConfigValue> elements;

    private ConfigSeq(SortedMap<Integer, ConfigValue> elements) {
        this.elements = elements;
    }

    /**
     * Creates a sequence whose addresses are {@code 0..n-1} — the form every source writes.
     *
     * @param elements elements of the sequence, in order; must not be {@code null} and must not
     *        contain {@code null}
     * @return the sequence
     * @throws IllegalArgumentException if any element is {@code null}
     */
    public static ConfigSeq of(java.util.List<ConfigValue> elements) {
        Objects.requireNonNull(elements, "elements");
        SortedMap<Integer, ConfigValue> addressed = new TreeMap<>();
        int address = 0;
        for (ConfigValue element : elements) {
            require(element);
            addressed.put(address++, element);
        }
        return new ConfigSeq(addressed);
    }

    /**
     * Creates a sequence with addresses stated explicitly — for a list in which an optional element vanished,
     * so the elements that stayed keep the addresses they had.
     *
     * @param elements address → element; addresses must be non-negative, values must not be {@code null}
     * @return the sequence
     * @throws IllegalArgumentException if an address is negative or a value is {@code null}
     */
    public static ConfigSeq of(Map<Integer, ConfigValue> elements) {
        Objects.requireNonNull(elements, "elements");
        SortedMap<Integer, ConfigValue> addressed = new TreeMap<>();
        for (Map.Entry<Integer, ConfigValue> entry : elements.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "address");
            if (entry.getKey() < 0) {
                throw new IllegalArgumentException("a `seq` address cannot be negative: " + entry.getKey());
            }
            require(entry.getValue());
            addressed.put(entry.getKey(), entry.getValue());
        }
        return new ConfigSeq(addressed);
    }

    /**
     * An empty sequence value.
     *
     * @return a sequence with no elements
     */
    public static ConfigSeq empty() {
        return new ConfigSeq(new TreeMap<>());
    }

    /** Validates one element — a sequence never carries {@code null}. */
    private static void require(ConfigValue element) {
        if (element == null) {
            throw new IllegalArgumentException("a `seq` element cannot be `null` — `null` does not exist in the model");
        }
    }

    /**
     * Returns the addresses of the elements that are there, in ascending order.
     *
     * @return the addresses; unmodifiable
     */
    public java.util.List<Integer> addresses() {
        return java.util.List.copyOf(elements.keySet());
    }

    /**
     * Returns the element under this address.
     *
     * @param address the address, as written in a reference ({@code ports.2} → address {@code 2})
     * @return the element, or empty when that address is empty (the element vanished, or was never there)
     */
    public Optional<ConfigValue> at(int address) {
        return Optional.ofNullable(elements.get(address));
    }

    /**
     * Returns the elements that are there, in address order.
     *
     * @return an unmodifiable copy; changing it does not affect this value
     */
    public java.util.List<ConfigValue> elements() {
        return java.util.List.copyOf(elements.values());
    }

    /**
     * Returns the number of elements that are there.
     *
     * @return the number of elements
     */
    public int size() {
        return elements.size();
    }

    /**
     * Returns whether no element is there.
     *
     * @return true when there are no elements
     */
    public boolean isEmpty() {
        return elements.isEmpty();
    }

    @Override
    public ConfigType type() {
        return ConfigType.SEQ;
    }

    /**
     * Returns the sequence in YAML notation, for example {@code [1, 2]}.
     *
     * <p>Content only: a vanished element is not shown (it is not there), and the notation keeps the shape, so
     * a list can always be told apart from a map with positional keys.
     */
    @Override
    public String display() {
        return List.ofAll(elements.values()).map(ConfigValue::display).mkString("[", ", ", "]");
    }

    /**
     * Compares the CONTENT — the elements that are there, in address order.
     *
     * <p>Not the addresses: {@code [1, 3]} and {@code [1, ${?x}, 3]} with {@code x} absent are equal, because
     * they say the same thing about the configuration. Addressing is a different question, and different
     * answers belong to it (see {@link #at(int)}).
     *
     * <p>The comparison runs on {@link #elements()} — a COPY — and never on {@code map.values()}: the view a
     * {@link SortedMap} hands out asks its own map about KEYS when tested with {@code contains}, so comparing
     * views would compare addresses while pretending to compare elements (measured: two lists with equal
     * content came out unequal).
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof ConfigSeq seq && elements().equals(seq.elements());
    }

    @Override
    public int hashCode() {
        return elements().hashCode();
    }

    @Override
    public String toString() {
        return display();
    }
}
