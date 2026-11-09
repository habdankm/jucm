package dev.jucm.yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.SequenceNode;
import org.snakeyaml.engine.v2.nodes.Tag;
import org.snakeyaml.engine.v2.schema.FailsafeSchema;

/**
 * {@code !include} - one document spliced into another, at COMPOSE level.
 *
 * <p>WHY IT IS POSSIBLE AT ALL, measured 20 X 2026: {@code YamlSource} composes with the low-level
 * {@link Compose}, so a tag the schema has no constructor for is not an error - it survives as a {@link ScalarNode} whose tag is the
 * literal {@code !include} and whose value is the path. That is why no schema change and no custom constructor are needed: the tree is
 * walked after composition and the tag is replaced.
 *
 * <p>TWO POSITIONS, and they mean different things:
 *
 * <pre>{@code
 *   sub: !include inner.yaml          # a VALUE: the included document replaces this node
 *   !include outer.yaml:              # a KEY:   the included mapping is MERGED into this one,
 *   own: 1                            #          and a key written HERE wins over the same key there
 * }</pre>
 *
 * <p>THE MERGE DIRECTION IS DELIBERATE: the file that names the include keeps its own keys. An include is therefore "bring these defaults
 * in", never "let another file silently decide what I said".
 *
 * <p>CYCLES ARE REFUSED with the chain spelled out: a file that reaches itself through includes would otherwise compose until the stack
 * ends, and the failure would name a file the reader never wrote.
 *
 * <p>RELATIVE PATHS resolve against the file that NAMES the include, not against the process's working directory - measured lesson from this
 * project's own history: a relative path resolved against the CWD puts files where nobody expects them.
 */
public final class Includes {

    /** The tag as it survives composition. */
    static final Tag INCLUDE = new Tag("!include");

    private Includes() {
    }

    /** A problem with an include: a missing file, an unreadable one, a cycle, or a document that is not a mapping where one is required. */
    public static final class IncludeProblem extends Exception {
        IncludeProblem(String message) {
            super(message);
        }
    }

    /**
     * Splices every include in the tree, recursively.
     *
     * @param root    the composed document
     * @param baseDir the directory of the file that produced it
     * @return the same shape with every {@code !include} resolved
     * @throws IncludeProblem when an include cannot be honoured
     */
    public static Node splice(Node root, Path baseDir) throws IncludeProblem {
        List<Path> chain = new ArrayList<>();
        return splice(root, baseDir.toAbsolutePath().normalize(), chain);
    }

    private static Node splice(Node node, Path dir, List<Path> chain) throws IncludeProblem {
        if (node instanceof MappingNode mapping) {
            return spliceMapping(mapping, dir, chain);
        }
        if (node instanceof SequenceNode sequence) {
            List<Node> elements = new ArrayList<>();
            for (Node element : sequence.getValue()) {
                elements.add(splice(element, dir, chain));
            }
            return new SequenceNode(sequence.getTag(), elements, sequence.getFlowStyle());
        }
        return node;
    }

    private static Node spliceMapping(MappingNode mapping, Path dir, List<Path> chain) throws IncludeProblem {
        // Keys merged in from an include come first, then the mapping's OWN keys - so an own key replaces an included one.
        Map<String, NodeTuple> merged = new LinkedHashMap<>();
        List<NodeTuple> own = new ArrayList<>();

        for (NodeTuple tuple : mapping.getValue()) {
            if (isInclude(tuple.getKeyNode()) && isNull(tuple.getValueNode())) {
                MappingNode included = includedMapping(pathOf(tuple.getKeyNode()), dir, chain);
                for (NodeTuple fromIncluded : included.getValue()) {
                    String includedKey = keyText(fromIncluded.getKeyNode());
                    NodeTuple already = merged.get(includedKey);
                    if (already != null && already.getValueNode() instanceof MappingNode under
                            && fromIncluded.getValueNode() instanceof MappingNode above) {
                        // TWO INCLUDES THAT BOTH NAME `storage` MERGE TOO (the same rule as an included-versus-own collision): a document that splices two files
                        // describing different stores must end up with both, not with the last one read.
                        merged.put(includedKey, new NodeTuple(fromIncluded.getKeyNode(), mergeDeep(under, above)));
                    } else {
                        merged.put(includedKey, fromIncluded);
                    }
                }
            } else {
                own.add(tuple);
            }
        }
        for (NodeTuple tuple : own) {
            String key = keyText(tuple.getKeyNode());
            NodeTuple fromIncluded = merged.get(key);
            if (fromIncluded != null && fromIncluded.getValueNode() instanceof MappingNode base
                    && tuple.getValueNode() instanceof MappingNode over) {
                // DEEP MERGE WHEN BOTH SIDES ARE MAPPINGS (measured 1 XI 2026): two files that both spell `storage` describe DIFFERENT stores, and a shallow replace
                // dropped the included half - a `multi` install whose core file named the user file could not open its index, because the core's `storage` mapping
                // replaced the user's `storage.workspace_index` whole. On a true collision a leaf from the NAMING file still wins.
                merged.put(key, new NodeTuple(tuple.getKeyNode(), mergeDeep(base, over)));
                continue;
            }
            merged.put(key, tuple);
        }

        List<NodeTuple> resolved = new ArrayList<>();
        for (NodeTuple tuple : merged.values()) {
            Node value = isInclude(tuple.getValueNode())
                ? includeAsValue(pathOf(tuple.getValueNode()), dir, chain)
                : splice(tuple.getValueNode(), dir, chain);
            resolved.add(new NodeTuple(splice(tuple.getKeyNode(), dir, chain), value));
        }
        return new MappingNode(mapping.getTag(), resolved, mapping.getFlowStyle());
    }

    /**
     * TWO MAPPINGS, ONE - the included one's keys first, the naming file's keys on top, and recursion where BOTH carry a mapping.
     *
     * @param base what the include brought
     * @param over what the naming file says
     * @return the merged mapping, with the naming file winning on any leaf it names
     */
    private static MappingNode mergeDeep(MappingNode base, MappingNode over) {
        Map<String, NodeTuple> merged = new LinkedHashMap<>();
        for (NodeTuple tuple : base.getValue()) {
            merged.put(keyText(tuple.getKeyNode()), tuple);
        }
        for (NodeTuple tuple : over.getValue()) {
            String key = keyText(tuple.getKeyNode());
            NodeTuple existing = merged.get(key);
            if (existing != null && existing.getValueNode() instanceof MappingNode under
                    && tuple.getValueNode() instanceof MappingNode above) {
                merged.put(key, new NodeTuple(tuple.getKeyNode(), mergeDeep(under, above)));
            } else {
                merged.put(key, tuple);
            }
        }
        return new MappingNode(over.getTag(), new ArrayList<>(merged.values()), over.getFlowStyle());
    }

    private static Node includeAsValue(String path, Path dir, List<Path> chain) throws IncludeProblem {
        return read(path, dir, chain);
    }

    private static MappingNode includedMapping(String path, Path dir, List<Path> chain) throws IncludeProblem {
        Node included = read(path, dir, chain);
        if (included instanceof MappingNode mapping) {
            return mapping;
        }
        throw new IncludeProblem("`!include " + path + "` is used as a KEY, so it must name a document whose root is a mapping, and "
            + path + " has a " + included.getNodeType());
    }

    /** Composes one file and splices ITS includes relative to ITS OWN directory. */
    private static Node read(String path, Path dir, List<Path> chain) throws IncludeProblem {
        Path file = dir.resolve(path).normalize();
        if (chain.stream().anyMatch(each -> each.equals(file))) {
            List<String> names = new ArrayList<>();
            chain.forEach(each -> names.add(each.getFileName().toString()));
            names.add(file.getFileName().toString());
            throw new IncludeProblem("the includes form a cycle: " + String.join(" -> ", names));
        }
        if (!Files.isRegularFile(file)) {
            throw new IncludeProblem("`!include " + path + "` names " + file + ", which does not exist");
        }
        String text;
        try {
            text = Files.readString(file);
        } catch (java.io.IOException broken) {
            throw new IncludeProblem("`!include " + path + "` could not read " + file + ": " + broken.getMessage());
        }
        Node composed;
        try {
            composed = new Compose(LoadSettings.builder().setSchema(new FailsafeSchema()).setParseComments(true).build())
                .composeString(text).orElse(null);
        } catch (RuntimeException broken) {
            throw new IncludeProblem("`!include " + path + "` is not readable YAML (" + file + "): " + broken.getMessage());
        }
        if (composed == null) {
            throw new IncludeProblem("`!include " + path + "` names " + file + ", which is an empty document");
        }
        chain.add(file);
        try {
            return splice(composed, file.getParent(), chain);
        } finally {
            chain.remove(chain.size() - 1);
        }
    }

    private static boolean isInclude(Node node) {
        return node instanceof ScalarNode scalar && INCLUDE.equals(scalar.getTag());
    }

    private static boolean isNull(Node node) {
        return node instanceof ScalarNode scalar && scalar.getTag().equals(Tag.NULL);
    }

    private static String pathOf(Node node) {
        String value = ((ScalarNode) node).getValue();
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("an `!include` with no path names nothing");
        }
        return value.trim();
    }

    private static String keyText(Node key) {
        return key instanceof ScalarNode scalar ? scalar.getValue() : key.toString();
    }
}
