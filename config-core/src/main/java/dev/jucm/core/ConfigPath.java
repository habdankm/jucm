package dev.jucm.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Canonical path — the address of a value in the tree. A separate immutable value object, not a
 * {@code String}.
 *
 * <p>Grammar: segments separated by a dot, so a dot cannot occur inside a segment. **{@code __} and
 * {@code ___} belong to the engine** — a key containing them is an error. A single
 * {@code _} stays an ordinary character and is often part of a name.
 *
 * <p>The "lower case keys" convention is **not** enforced here — it is a recommendation, not a rule.
 *
 * <p>Container element indices ({@code ports[2]} in diagnostics) arrive together with element
 * validation — we do not guess their shape here.
 */
public final class ConfigPath {

    private static final String HIERARCHY = "__";
    private static final String TYPE_MARKER = "___";
    private static final String SEPARATOR = "\\.";

    private final List<String> segments;
    private final String canonical;

    private ConfigPath(List<String> segments) {
        this.segments = List.copyOf(segments);
        this.canonical = String.join(".", this.segments);
    }


    /**
     * The order of addresses: **by value, not by text**.
     *
     * <p>A segment that is a number compares as a <b>number</b>, every other segment as text. That is what keeps
     * {@code ports.2} before {@code ports.10} and a key {@code 9} before {@code 10}: sorting the canonical TEXT
     * would say {@code "10" < "2"} — the same trap as {@code "11" < "2"} in a rule, and it would make an output
     * that is "repeatable" yet wrong about order.
     *
     * <p>Every output that has to be repeatable uses this: reports, drafts, generated samples. A number that is
     * longer in digits is larger (no parsing, so no overflow), and leading zeros are ignored for the comparison.
     *
     * @since 1.0
     */
    public static final java.util.Comparator<ConfigPath> BY_VALUE = (left, right) -> {
        int common = Math.min(left.segments.size(), right.segments.size());
        for (int index = 0; index < common; index++) {
            int compared = compareSegment(left.segments.get(index), right.segments.get(index));
            if (compared != 0) {
                return compared;
            }
        }
        // A prefix comes before what extends it: `a` before `a.0`.
        return Integer.compare(left.segments.size(), right.segments.size());
    };

    /** A number compares with a number, everything else as text. */
    private static int compareSegment(String left, String right) {
        if (!isNumber(left) || !isNumber(right)) {
            return left.compareTo(right);
        }
        String one = withoutLeadingZeros(left);
        String other = withoutLeadingZeros(right);
        return one.length() == other.length() ? one.compareTo(other) : Integer.compare(one.length(), other.length());
    }

    private static boolean isNumber(String segment) {
        return !segment.isEmpty() && segment.chars().allMatch(Character::isDigit);
    }

    private static String withoutLeadingZeros(String digits) {
        int at = 0;
        while (at < digits.length() - 1 && digits.charAt(at) == '0') {
            at++;
        }
        return digits.substring(at);
    }

    /**
     * A path from the canonical notation, e.g. {@code "server.port"}.
     *
     * @param canonical the canonical notation; must not be null or empty
     * @return the path
     * @throws IllegalArgumentException if the notation is empty or a segment carries an engine marker
     */
    public static ConfigPath of(String canonical) {
        Objects.requireNonNull(canonical, "canonical");
        if (canonical.isEmpty()) {
            throw new IllegalArgumentException("empty path");
        }
        String[] parts = canonical.split(SEPARATOR, -1);
        for (String part : parts) {
            validateSegment(part);
        }
        return new ConfigPath(List.of(parts));
    }

    /**
     * A path from ready-made segments — e.g. from the ENV adapter, split on the {@code __} separator.
     *
     * @param segments the segments from the root downwards; must not be null or empty
     * @return the path
     * @throws IllegalArgumentException if the list is empty or a segment carries an engine marker
     */
    public static ConfigPath of(List<String> segments) {
        Objects.requireNonNull(segments, "segments");
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("empty path");
        }
        segments.forEach(ConfigPath::validateSegment);
        return new ConfigPath(segments);
    }

    private static void validateSegment(String segment) {
        if (segment.isEmpty()) {
            throw new IllegalArgumentException("empty path segment");
        }
        // Order matters: `___` contains `__`, so the type marker is checked first, so that the
        // message names the real cause.
        if (segment.contains(TYPE_MARKER)) {
            throw new IllegalArgumentException("`" + TYPE_MARKER
                + "` belongs to the engine (type marker) — use a single `_` in the name");
        }
        if (segment.contains(HIERARCHY)) {
            throw new IllegalArgumentException("`" + HIERARCHY
                + "` belongs to the engine (hierarchy separator) — use a single `_` in the name");
        }
    }

    /**
     * Segments from the root downwards.
     *
     * @return an immutable list of segments, never empty
     */
    public List<String> segments() {
        return segments;
    }

    /**
     * Canonical notation — exactly the one {@link #of(String)} accepts.
     *
     * @return the canonical notation, never null
     */
    public String canonical() {
        return canonical;
    }

    /**
     * A path one segment deeper.
     *
     * @param segment the segment to append; must not be null, empty, or carry an engine marker
     * @return a new path; this one is unchanged
     * @throws IllegalArgumentException if the segment is empty or carries an engine marker
     */
    public ConfigPath child(String segment) {
        validateSegment(segment);
        List<String> deeper = new ArrayList<>(segments);
        deeper.add(segment);
        return new ConfigPath(deeper);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ConfigPath path && canonical.equals(path.canonical);
    }

    @Override
    public int hashCode() {
        return canonical.hashCode();
    }

    @Override
    public String toString() {
        return canonical;
    }
}
