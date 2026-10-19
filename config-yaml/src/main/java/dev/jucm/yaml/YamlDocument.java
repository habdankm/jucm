package dev.jucm.yaml;

import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.StreamDataWriter;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.snakeyaml.engine.v2.common.ScalarStyle;
import org.snakeyaml.engine.v2.emitter.Emitter;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.Tag;
import org.snakeyaml.engine.v2.schema.CoreSchema;
import org.snakeyaml.engine.v2.serializer.Serializer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A YAML document AS A TREE, for the one job an application has on somebody else's configuration file: adding or
 * removing an entry without rewriting the file a person maintains.
 *
 * <p>WHY A TREE AND NOT A MAP: a configuration carries COMMENTS, ordering and formatting that mean something to the
 * operator who wrote them, and a parse-into-a-Map-then-dump path destroys all three. This class composes with
 * snakeyaml-engine's {@code compose()} (comments included), changes only the nodes it was asked to change, and emits
 * with comments enabled - the same reasoning that made {@link YamlSource} read with {@code compose()} rather than
 * {@code load()}.
 *
 * <p>WHAT IT DOES NOT PROMISE: byte-identical formatting of nodes it did not touch. Emitting re-renders the document
 * (indentation, quoting, where an inline comment sits). That is why {@link #writeIfChanged} compares the emitted text
 * with what is on disk and DOES NOT TOUCH THE FILE when the document did not change - an untouched file is never
 * rewritten, so the guarantee that matters ("my file looks like I left it") holds for every file this class was not
 * asked to modify.
 *
 * <p>THE WRITE IS ATOMIC AND KEEPS A COPY: the new text goes to a sibling temporary file which is then moved over the
 * target, and the version being replaced is copied to {@code <name>.bak-<timestamp>} first. A configuration file that
 * can be left half-written by an interrupted write is worse than no writer at all.
 */
public final class YamlDocument {

    private static final int INDENT = 2;

    /**
     * HOW MANY REPLACED VERSIONS ARE KEPT. A backup per change is what the owner asked for ("always, just in case"); an
     * unbounded number of them turns a settings directory into an archive nobody prunes, so the newest ten stay and the rest
     * go - newest ten is enough to undo a mistake made this week.
     */
    private static final int KEEP_BACKUPS = 10;

    /** The root mapping. A document whose root is not a mapping is refused: configuration is addresses, not a list. */
    private final MappingNode root;

    private YamlDocument(MappingNode root) {
        this.root = root;
    }

    public static YamlDocument parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("a YAML document needs at least an empty mapping");
        }
        Optional<Node> composed = new Compose(settings()).composeString(text);
        Node node = composed.orElseThrow(() -> new IllegalArgumentException("the text is not a YAML document"));
        if (!(node instanceof MappingNode mapping)) {
            throw new IllegalArgumentException("a configuration document must have a MAPPING at its root, and this one has "
                + node.getNodeType());
        }
        return new YamlDocument(mapping);
    }

    public static YamlDocument read(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    // ── reading: the two questions a caller needs ───────────────────────────────────────────

    public boolean has(String... path) {
        return find(path) != null;
    }

    /** The scalar at that address, or empty when the address is absent or does not hold a scalar. */
    public Optional<String> scalar(String... path) {
        Node node = find(path);
        return node instanceof ScalarNode scalar ? Optional.of(scalar.getValue()) : Optional.empty();
    }

    /** Every key of the mapping at that address, in document order - the list a caller will append to. */
    public List<String> keys(String... path) {
        Node node = path.length == 0 ? root : find(path);
        if (!(node instanceof MappingNode mapping)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (NodeTuple tuple : mapping.getValue()) {
            if (tuple.getKeyNode() instanceof ScalarNode key) {
                names.add(key.getValue());
            }
        }
        return names;
    }

    // ── writing: only what a host settings-file needs ────────────────────────────────────────

    /** Sets a scalar at that address, creating the mappings on the way when they are missing. Returns this document. */
    public YamlDocument putScalar(String value, String... path) {
        if (path.length == 0) {
            throw new IllegalArgumentException("an address is required: a value at the root would replace the document");
        }
        MappingNode parent = mappingAt(path, path.length - 1, true);
        String key = path[path.length - 1];
        NodeTuple existing = tuple(parent, key);
        Node valueNode = new ScalarNode(Tag.STR, value, ScalarStyle.PLAIN);
        if (existing == null) {
            parent.getValue().add(new NodeTuple(new ScalarNode(Tag.STR, key, ScalarStyle.PLAIN), valueNode));
        } else {
            replaceValue(parent, existing, valueNode);
        }
        return this;
    }

    /** Ensures an empty mapping exists at that address (the parent of an entry about to be added). */
    public YamlDocument putMapping(String... path) {
        mappingAt(path, path.length, true);
        return this;
    }

    /** Removes the entry at that address, if it is there. Absent is not an error: the caller wanted it gone. */
    public YamlDocument remove(String... path) {
        if (path.length == 0) {
            throw new IllegalArgumentException("an address is required: the root is not removable");
        }
        MappingNode parent = mappingAt(path, path.length - 1, false);
        if (parent == null) {
            return this;
        }
        parent.getValue().removeIf(tuple -> tuple.getKeyNode() instanceof ScalarNode key
            && key.getValue().equals(path[path.length - 1]));
        return this;
    }

    /** The document as text, comments included, long scalars NOT wrapped: a URI must stay on one line. */
    public String toYaml() {
        DumpSettings settings = DumpSettings.builder()
            .setDumpComments(true)
            .setIndent(INDENT)
            .setIndentWithIndicator(true)
            .setSplitLines(false)
            .setWidth(Integer.MAX_VALUE)
            .build();
        StringBuilder out = new StringBuilder();
        Emitter emitter = new Emitter(settings, new StreamDataWriter() {
            @Override
            public void write(String text) {
                out.append(text);
            }

            @Override
            public void write(String text, int offset, int length) {
                out.append(text, offset, offset + length);
            }
        });
        Serializer serializer = new Serializer(settings, emitter);
        serializer.emitStreamStart();
        serializer.serializeDocument(root);
        serializer.emitStreamEnd();
        return out.toString();
    }

    /**
     * Writes the document ONLY when its text differs from what the file holds, atomically, keeping the replaced version as
     * {@code .bak-<timestamp>}. Returns whether anything was written - which is the answer a caller reports, because "I saved"
     * and "nothing needed saving" are different facts.
     */
    public boolean writeIfChanged(Path file) throws IOException {
        String rendered = toYaml();
        if (Files.isRegularFile(file) && rendered.equals(Files.readString(file, StandardCharsets.UTF_8))) {
            return false;
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.isRegularFile(file)) {
            Files.copy(file, file.resolveSibling(file.getFileName() + ".bak-" + Instant.now().toString().replace(':', '-')),
                StandardCopyOption.REPLACE_EXISTING);
        }
        Path temporary = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, rendered, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException noAtomic) {
                // A filesystem that cannot move atomically still gets a REPLACE, and the copy above already protects the old text.
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        pruneBackups(file);
        return true;
    }

    /**
     * Keeps the newest {@link #KEEP_BACKUPS} copies and deletes older ones. BEST EFFORT ON PURPOSE: a failed prune must never
     * turn a successful write into an exception, because the file has already been changed and an operator reading "failed" would
     * go looking for damage that is not there.
     */
    private static void pruneBackups(Path file) {
        Path directory = file.toAbsolutePath().getParent();
        if (directory == null) {
            return;
        }
        String prefix = file.getFileName() + ".bak-";
        try (var listing = Files.list(directory)) {
            List<Path> backups = listing
                .filter(path -> path.getFileName().toString().startsWith(prefix))
                .sorted(java.util.Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                .toList();
            for (Path older : backups.subList(Math.min(KEEP_BACKUPS, backups.size()), backups.size())) {
                Files.deleteIfExists(older);
            }
        } catch (Exception ignored) {
            // Said here rather than hidden: the copies that could not be pruned stay, which is the harmless direction.
        }
    }

    // ── the tree, walked one address segment at a time ──────────────────────────────────────

    private static LoadSettings settings() {
        return LoadSettings.builder()
            // NOT FailsafeSchema, and this was measured: with Failsafe EVERY scalar is a string, so the emitter must QUOTE
            // `2500` to keep it one - and a settings file comes back with `'2500'` where the operator wrote `2500`. CoreSchema
            // resolves the YAML 1.2 types, so numbers and booleans are emitted as they were written; JUCM still reads every
            // value through its own schema, so nothing about the configuration's meaning changes.
            .setSchema(new CoreSchema())
            // COMMENTS ARE THE POINT: without this the tree carries no comments and the emitted file loses what the
            // operator wrote about their own configuration.
            .setParseComments(true)
            .build();
    }

    /** The node at that address, or null. Anchors are followed, because an alias is not a different value. */
    private Node find(String... path) {
        Node current = root;
        for (String segment : path) {
            if (!(current instanceof MappingNode mapping)) {
                return null;
            }
            NodeTuple tuple = tuple(mapping, segment);
            if (tuple == null) {
                return null;
            }
            current = tuple.getValueNode();
        }
        return current;
    }

    /**
     * The mapping at that address, created down to {@code depth} when {@code create} is set. A node that is there and is NOT a
     * mapping is refused rather than replaced: overwriting somebody's scalar with a map is how a settings file loses a value that
     * was working.
     */
    private MappingNode mappingAt(String[] path, int depth, boolean create) {
        MappingNode current = root;
        for (int index = 0; index < depth; index++) {
            String segment = path[index];
            NodeTuple tuple = tuple(current, segment);
            if (tuple == null) {
                if (!create) {
                    return null;
                }
                MappingNode created = new MappingNode(Tag.MAP, new ArrayList<>(), FlowStyle.BLOCK);
                current.getValue().add(new NodeTuple(new ScalarNode(Tag.STR, segment, ScalarStyle.PLAIN), created));
                current = created;
                continue;
            }
            Node value = tuple.getValueNode();
            if (!(value instanceof MappingNode mapping)) {
                throw new IllegalStateException("the configuration holds a scalar at " + String.join(".", path)
                    + " where a mapping is needed: a value that is already there is never overwritten by a structure");
            }
            current = mapping;
        }
        return current;
    }

    private static NodeTuple tuple(MappingNode mapping, String key) {
        for (NodeTuple tuple : mapping.getValue()) {
            if (tuple.getKeyNode() instanceof ScalarNode scalar && scalar.getValue().equals(key)) {
                return tuple;
            }
        }
        return null;
    }

    private static void replaceValue(MappingNode parent, NodeTuple existing, Node valueNode) {
        List<NodeTuple> tuples = parent.getValue();
        for (int index = 0; index < tuples.size(); index++) {
            if (tuples.get(index) == existing) {
                tuples.set(index, new NodeTuple(existing.getKeyNode(), valueNode));
                return;
            }
        }
    }
}
