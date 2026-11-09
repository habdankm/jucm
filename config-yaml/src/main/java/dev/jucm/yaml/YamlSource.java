package dev.jucm.yaml;

import dev.jucm.core.Config;
import dev.jucm.core.ConfigDefinition;
import dev.jucm.core.ConfigError;
import dev.jucm.core.ConfigMap;
import dev.jucm.core.ConfigPatch;
import dev.jucm.core.ConfigPath;
import dev.jucm.core.ConfigPipeline;
import dev.jucm.core.ConfigResult;
import dev.jucm.core.ConfigSealed;
import dev.jucm.core.ConfigSeq;
import dev.jucm.core.ConfigSource;
import dev.jucm.core.ConfigString;
import dev.jucm.core.ConfigType;
import dev.jucm.core.ConfigValue;
import dev.jucm.core.Layer;
import dev.jucm.core.SourceDeclaration;
import dev.jucm.core.TransformerRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.comments.CommentLine;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.common.ScalarStyle;
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException;
import org.snakeyaml.engine.v2.nodes.AnchorNode;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.SequenceNode;
import org.snakeyaml.engine.v2.schema.FailsafeSchema;

/**
 * A YAML source — a <b>file loader</b>: a YAML tree enters the pipeline as a {@link ConfigPatch}.
 *
 * <p>Three things it rests on:
 *
 * <ol>
 *   <li>{@code compose()} + {@code Node}, never {@code load()}: we get the <b>raw scalar</b>
 *       ({@code 2026-09-12} stays text, not a date type), and the field declaration decides the
 *       type.</li>
 *   <li>The {@link FailsafeSchema} schema is set <b>explicitly</b>: nothing is typed but explicit
 *       tags, so {@code ${…}} stays our syntax instead of SnakeYAML's own ENV interpolation (the
 *       {@code !ENV_VARIABLE} tag used by the {@code Json} and {@code Core} schemas).</li>
 *   <li>Interpolated keys are substituted <b>in the tree, on a CLONE</b>: the original stays
 *       untouched, because the layer below has to keep it, while comments and node positions carry
 *       over to the clone.</li>
 * </ol>
 *
 * <p>Comments stay in the tree and <b>do not enter</b> the patch: they are needed by whoever writes
 * the YAML out.
 *
 * <p><b>A mapping is a set of ADDRESSES — until a declaration says otherwise.</b> The form of a node
 * says nothing about its type, so the model decides how a mapping is read: under a field declared as
 * {@code map} the whole mapping is <b>the value of that address</b> ({@code m: {a: 1}} states the value
 * of {@code m}), and everywhere else it stays a set of addresses ({@code server:} states
 * {@code server.host}). A sequence needs no such decision — a list is a value under its key in every
 * case.
 *
 * @since 1.0
 */
public final class YamlSource {

    /** The key pre-parser runs only when the text has something to resolve. */
    private static final String CONTROL = "${";

    /**
     * THE TAG THAT CARRIES A SEALED VALUE: `!decrypt` over a mapping of `ciphertext` and `key`. It is read here, at the one place a node becomes a value, so
     * every consumer gets a plain setting and the encryption never leaks into the model (owner, 31 X 2026).
     */
    private static final String DECRYPT_TAG = "!decrypt";

    /**
     * Technical paths under which the content of keys to resolve is placed. They are not
     * configuration: they live only in a temporary snapshot used to compute keys and never leave the
     * adapter.
     */
    private static final String KEY_SLOT = "jucm_key_slot_";

    private final String origin;
    private final String text;
    /** Where relative `!include` paths resolve; null for text that came from nowhere (a flow literal, the ENV adapter). */
    private final java.nio.file.Path baseDir;

    private YamlSource(String origin, String text) {
        this(origin, text, (java.nio.file.Path) null);
    }

    private YamlSource(String origin, String text, java.nio.file.Path baseDir) {
        this.baseDir = baseDir;
        this.origin = origin;
        this.text = text;
    }

    /**
     * Creates a source from text.
     *
     * @param origin name used in diagnostics, as {@code file:line}; must not be {@code null}
     * @param text the YAML document; must not be {@code null}
     * @return a source over that text
     * @since 1.0
     */
    public static YamlSource of(String origin, String text) {
        return new YamlSource(origin, text);
    }

    /**
     * Creates a source from a file.
     *
     * <p>A missing file is a source error, not a thrown exception in the middle of a pipeline: the
     * caller learns about it the same way as about any other configuration error.
     *
     * @param file path of the YAML file; must not be {@code null}
     * @return the source, or {@link ConfigError.FileNotExist} when the file cannot be read
     * @since 1.0
     */
    public static ConfigResult<YamlSource> of(Path file) {
        try {
            return ConfigResult.value(new YamlSource(file.getFileName().toString(), Files.readString(file),
                file.toAbsolutePath().getParent()));
        } catch (IOException missing) {
            return ConfigResult.error(new ConfigError.FileNotExist(FILE_PATH, source(file.toString()),
                missing.getMessage() == null ? "the file could not be read" : missing.getMessage()));
        }
    }

    /**
     * Reads a single YAML value.
     *
     * <p>This is the seam for the ENV adapter: source modules do not know each other, so the
     * application passes this reader when it builds the ENV adapter
     * ({@code EnvSource.of(env, prefix, YamlSource::value)}). That is why a flow literal has ONE
     * place that turns text into structure, shared with the file.
     *
     * <p>An empty document yields an empty string.
     *
     * @param text YAML text of a single value; must not be {@code null}
     * @return the value, or a parse error when the text is not readable YAML
     * @since 1.0
     */
    public static ConfigResult<ConfigValue> value(String text) {
        YamlSource source = new YamlSource("value", text);
        try {
            Node document = source.compose();
            return document == null ? ConfigResult.value(ConfigString.of(""))
                : ConfigResult.value(source.value(document, document, new LinkedHashSet<>()));
        } catch (MarkedYamlEngineException broken) {
            return ConfigResult.error(source.parseFailed(broken));
        } catch (YamlProblem problem) {
            return ConfigResult.error(problem.error);
        }
    }

    /**
     * Returns the entries of the file.
     *
     * <p>When the text contains a reference in the form {@code ${...}}, keys are resolved first against
     * the declarations <b>from
     * this file and from the layers below</b> (two passes), while VALUES stay symbolic — the
     * interpolation step of the pipeline resolves them, lazily.
     *
     * <p><b>The declaration under an address decides how a MAPPING is read.</b> A field declared as
     * {@code map} takes the whole mapping as <b>its value</b> ({@code m: {a: 1}} states the value of
     * {@code m}); every other address — a field of another type, a key outside the schema — keeps
     * reading a mapping as a set of <b>addresses</b> ({@code server:} states {@code server.host}), and
     * a marker on such a mapping stays an error. Emptiness is untouched: {@code m: {}} is an empty
     * value of that field (an emptiness error, like every empty collection), not an absent one — "the
     * field may have no elements" is expressed by making the field optional.
     *
     * <p>A key outside the schema carries the type of its statement, and the form of the node is where this
     * source states its own: a scalar is {@code str}, a sequence {@code seq}, a mapping {@code map}. Every
     * statement therefore travels on with a type to be read with — and a statement a type cannot use is
     * reported by the flow, not by this reader.
     *
     * @param below configuration of the lower layers, used to resolve the content of keys; must not
     *        be {@code null}
     * @param definition declaration of the model; must not be {@code null}
     * @param registry transformer registry used by the intake of key content; must not be
     *        {@code null}
     * @return the entries of this file, or a parse error carrying the file and line that caused it
     * @since 1.0
     */
    public ConfigResult<ConfigPatch> patch(Config below, ConfigDefinition definition,
                                           TransformerRegistry registry) {
        Node document;
        try {
            document = compose();
        } catch (MarkedYamlEngineException broken) {
            return ConfigResult.error(parseFailed(broken));
        } catch (YamlProblem problem) {
            return ConfigResult.error(problem.error);
        }
        if (document == null) {
            return ConfigResult.value(ConfigPatch.empty());
        }
        try {
            Node working = text.contains(CONTROL)
                ? withResolvedKeys(document, below, definition, registry)
                : document;
            List<Entry> read = entries(working, definition);
            // No guard here for "a marker without a type": every statement gets one (from the form of the
            // node, or `str` as the default for a key outside the schema), so that state does not exist.
            // The guard that used to stand here was UNREACHABLE — a corpse whose comment preached the old
            // rule ("a key outside the schema has no type to take"); see `SourceDeclaration.asField`.
            return ConfigResult.value(patch(read));
        } catch (YamlProblem problem) {
            return ConfigResult.error(problem.error);
        }
    }

    // ── compose ──────────────────────────────────────────────────────────────────────────────

    private Node compose() throws YamlProblem {
        LoadSettings settings = LoadSettings.builder()
            .setSchema(new FailsafeSchema())
            .setParseComments(true)
            .build();
        Node root = new Compose(settings).composeString(text).orElse(null);
        if (root == null || baseDir == null) {
            return root;
        }
        try {
            return Includes.splice(root, baseDir);
        } catch (Includes.IncludeProblem broken) {
            // A MISSING OR CYCLIC INCLUDE IS A PARSE-GRADE FAILURE: it is the file that cannot be read as written, and the reader must hear
            // that in the same voice as a syntax error - with this file as the origin, and the include's own sentence inside.
            throw new YamlProblem(new ConfigError.ParseFailed(source(origin), broken.getMessage()));
        }
    }

    private ConfigError parseFailed(MarkedYamlEngineException broken) {
        String where = broken.getProblemMark()
            .map(mark -> " (" + origin + ":" + (mark.getLine() + 1) + ":" + (mark.getColumn() + 1) + ")")
            .orElse("");
        return new ConfigError.ParseFailed(source(origin), broken.getMessage().replace("\n", " ").trim() + where);
    }

    // ── address space: a mapping inside a mapping is a path ──────────────────────────────────

    /** An entry: address, value, where exactly it came from (diagnostics) and what the SOURCE said. */
    private record Entry(ConfigPath path, ConfigValue value, ConfigSource source,
                         SourceDeclaration declaration) {
    }

    private List<Entry> entries(Node document, ConfigDefinition definition) throws YamlProblem {
        Node root = real(document);
        if (!(root instanceof MappingNode mapping)) {
            throw new YamlProblem(new ConfigError.ParseFailed(source(origin),
                "a YAML document must be a mapping (addresses), but it is " + root.getNodeType()));
        }
        Markers.rejectMisplaced(real(document), this);
        List<Entry> entries = new ArrayList<>();
        addressSpace(mapping, List.of(), entries, new LinkedHashSet<>(), true, definition);
        return entries;
    }

    private void addressSpace(Node node, List<String> parent, List<Entry> entries, Set<Node> branch,
                              boolean readMarkers, ConfigDefinition definition) throws YamlProblem {
        MappingNode mapping = (MappingNode) enter(node, branch);
        Set<String> seen = new LinkedHashSet<>();
        for (NodeTuple tuple : mapping.getValue()) {
            String key = keyText(tuple.getKeyNode());
            List<String> segments = new ArrayList<>(parent);
            segments.add(key);
            if (!seen.add(String.join(".", segments))) {
                throw new YamlProblem(new ConfigError.ParseFailed(source(line(tuple.getKeyNode())),
                    "duplicate key `" + key + "` — YAML keeps BOTH entries, so there is nothing to choose from"));
            }
            Node child = real(tuple.getValueNode());
            if (child instanceof MappingNode nested) {
                // THE VALUE NODE, not the key: the tag `!decrypt` sits on the mapping, and asking the KEY's tag is what made the first version descend into it
                // (measured 31 X 2026: `a.ciphertext` and `a.key` came back as two addresses).
                if (takesMappingAsValue(definition, segments, tuple.getValueNode())) {
                    // A declaration stands under this address: the mapping is ONE value there, so the pairs
                    // inside it are the content of that value, not addresses. A marker inside them would be
                    // a rule that does NOT work (the content is not an entry) — hence loud.
                    if (readMarkers) {
                        Markers.rejectInsideValue(real(tuple.getValueNode()), this);
                    }
                    entries.add(entry(segments, tuple, child, branch, readMarkers));
                    continue;
                }
                if (readMarkers) {
                    // A map value creates no entry (entries come from the pairs inside), so a marker
                    // on its line would have nothing to type — it MUST be loud, not skipped.
                    Markers.rejectOnMap(tuple, this);
                }
                addressSpace(nested, List.copyOf(segments), entries, branch, readMarkers, definition);
            } else {
                entries.add(entry(segments, tuple, child, branch, readMarkers));
            }
        }
        branch.remove(mapping);
    }

    /** One entry from a pair: address, value, origin and — when it is read for real — what the source said. */
    private Entry entry(List<String> segments, NodeTuple tuple, Node value, Set<Node> branch,
                        boolean readMarkers) throws YamlProblem {
        return new Entry(address(segments, tuple.getKeyNode()), value(value, tuple.getValueNode(), branch),
            source(line(tuple.getValueNode())),
            readMarkers ? Markers.of(tuple, this) : SourceDeclaration.none());
    }

    /**
     * Whether a mapping standing under this key is the VALUE of the field declared there, or a set of
     * ADDRESSES.
     *
     * <p>The DECLARATION decides, and ANY declared field takes the whole mapping as its value: the type
     * in the file is just a form, so "this mapping is one value" is a statement of the model, never of the
     * notation. **Any declared field** at that address takes the mapping as its own value — whether the type
     * accepts it is the cast's business, so a list field gets a loud "that is not a list" instead of silence.
     * A key OUTSIDE the schema has no declaration, so it keeps reading the mapping as addresses.
     */
    private boolean takesMappingAsValue(ConfigDefinition definition, List<String> segments, Node at) {
        // `!decrypt` SAYS IT ITSELF (owner, 31 X 2026): the tag marks a mapping as ONE VALUE - `ciphertext` and `key` are its parts, not two addresses under
        // the setting. Without this the walker descended into it and a password became `password.ciphertext` and `password.key`, a shape nobody wrote.
        if (DECRYPT_TAG.equals(real(at).getTag().getValue())) {
            return true;
        }
        return addressOrNone(segments, at)
            .flatMap(definition::field)
            .isPresent();
    }

    /**
     * The address, or nothing when the key cannot be an address (an empty segment, `__`, `___`). Such a
     * key is reported by whoever builds an entry under it — here the answer is only "no declaration".
     */
    private Optional<ConfigPath> addressOrNone(List<String> segments, Node at) {
        try {
            return Optional.of(address(segments, at));
        } catch (YamlProblem notAnAddress) {
            return Optional.empty();
        }
    }

    /** The value under an address: a scalar is text, a sequence is a `ConfigSeq`, a map a `ConfigMap`. */
    private ConfigValue value(Node node, Node position, Set<Node> branch) throws YamlProblem {
        Node real = real(node);
        // `!decrypt` IS READ BEFORE ANYTHING ELSE, because it is the one node whose text is NOT its value (owner, 31 X 2026):
        //
        //     password: !decrypt
        //       ciphertext: "<base64(nonce || ciphertext || tag)>"
        //       key: "the key it was sealed with"
        //
        // The secret then sits EXACTLY where its setting is - the address a reader already knows - instead of in a second file nobody looks at.
        if (DECRYPT_TAG.equals(real.getTag().getValue())) {
            return decrypted(real, position);
        }
        if (real instanceof ScalarNode scalar) {
            return ConfigString.of(scalar.getValue());
        }
        if (real instanceof SequenceNode sequence) {
            enter(sequence, branch);
            List<ConfigValue> elements = new ArrayList<>();
            for (Node element : sequence.getValue()) {
                elements.add(value(element, position, branch));
            }
            branch.remove(sequence);
            return ConfigSeq.of(elements);
        }
        if (real instanceof MappingNode mapping) {
            enter(mapping, branch);
            Map<String, ConfigValue> mapped = new LinkedHashMap<>();
            for (NodeTuple tuple : mapping.getValue()) {
                mapped.put(keyText(tuple.getKeyNode()), value(tuple.getValueNode(), position, branch));
            }
            branch.remove(mapping);
            return ConfigMap.of(mapped);
        }
        throw new YamlProblem(new ConfigError.ParseFailed(source(line(position)),
            "a node of type " + real.getNodeType() + " is not a configuration value"));
    }

    /**
     * OPENS ONE `!decrypt` NODE - a mapping of exactly `ciphertext` and `key`, where the ciphertext is `base64(nonce || ciphertext || tag)` produced by
     * `dev.jucm.secrets.Sealed` (or by `SecretVault`, which uses the same construction).
     *
     * <p>WHY THE ERRORS ARE THIS NARROW: a value that will not open is either a wrong key or a text somebody changed, and neither is a parse problem a reader can
     * solve by looking at the node's shape - so the sentence says what failed and never shows the value, its key, or a fragment of either.
     *
     * @param node     the tagged node
     * @param position where it sits, for the sentence
     * @return the plain value
     * @throws YamlProblem when the node is not the shape the tag describes, or the value does not open
     */
    private ConfigValue decrypted(Node node, Node position) throws YamlProblem {
        if (!(node instanceof MappingNode mapping)) {
            throw failure(position, "`!decrypt` takes a mapping of `ciphertext` and `key`, and this node is a " + node.getNodeType());
        }
        String ciphertext = null;
        String key = null;
        for (NodeTuple tuple : mapping.getValue()) {
            String name = keyText(tuple.getKeyNode());
            Node held = real(tuple.getValueNode());
            if (!(held instanceof ScalarNode scalar)) {
                throw failure(position, "`!decrypt`'s `" + name + "` must be a scalar");
            }
            switch (name) {
                case "ciphertext" -> ciphertext = scalar.getValue();
                case "key" -> key = scalar.getValue();
                default -> throw failure(position, "`!decrypt` knows `ciphertext` and `key`, not `" + name + "`");
            }
        }
        if (ciphertext == null || ciphertext.isBlank()) {
            throw failure(position, "`!decrypt` with no `ciphertext`: there is nothing to open");
        }
        if (key == null || key.isBlank()) {
            throw failure(position, "`!decrypt` with no `key`: the `key` member is the key the value was sealed with");
        }
        // NOT OPENED HERE (owner, 31 X 2026): the key may be `${vault_key}`, and `${…}` is resolved AT THE READ against one snapshot. This node leaves the adapter as
        // a SYMBOLIC value and `Config.get` opens it - see `ConfigSealed`.
        return new ConfigSealed(ciphertext, key);
    }

    private YamlProblem failure(Node position, String sentence) {
        return new YamlProblem(new ConfigError.ParseFailed(source(line(position)), sentence));
    }

    private String keyText(Node key) throws YamlProblem {
        if (!(real(key) instanceof ScalarNode scalar)) {
            throw new YamlProblem(new ConfigError.ParseFailed(source(line(key)),
                "the key is not a scalar — a compound key cannot be turned into an address"));
        }
        return scalar.getValue();
    }

    private ConfigPath address(List<String> segments, Node at) throws YamlProblem {
        try {
            return ConfigPath.of(List.copyOf(segments));
        } catch (IllegalArgumentException broken) {
            throw new YamlProblem(new ConfigError.ParseFailed(source(line(at)),
                "address `" + String.join(".", segments) + "` is not valid: " + broken.getMessage()));
        }
    }

    private ConfigPatch patch(List<Entry> entries) {
        ConfigPatch patch = ConfigPatch.empty();
        for (Entry entry : entries) {
            patch = patch.with(entry.path(), entry.value(), entry.source(), entry.declaration());
        }
        return patch;
    }

    /** A YAML alias is the same node wrapped in an `AnchorNode` — the value sits inside. */
    private Node real(Node node) {
        return node instanceof AnchorNode anchor ? real(anchor.getRealNode()) : node;
    }

    /** A recursive anchor yields an infinite value — it is rejected rather than walked forever. */
    private Node enter(Node node, Set<Node> branch) throws YamlProblem {
        if (!branch.add(node)) {
            throw new YamlProblem(new ConfigError.ParseFailed(source(line(node)),
                "recursive anchor — an infinite value does not express itself as configuration"));
        }
        return node;
    }

    // ── two passes: keys in the tree, on a clone ─────────────────────────────────────────────

    /**
     * PASS 1 collects the declarations of the file, PASS 2 substitutes the keys — on a <b>clone</b>,
     * so the original tree stays untouched.
     *
     * <p>The content of a key is {@code display()} after the <b>full intake</b>, so the order of the
     * steps differs from the value pipeline on purpose: first the declarations of the file are typed
     * (transformation, then cast), then the key content is added and only then is it interpolated —
     * thanks to that {@code ${port}} sees {@code 8} in the key, not the raw {@code 010}. Every step
     * runs <b>once</b>: no double resolution.
     */
    private Node withResolvedKeys(Node document, Config below, ConfigDefinition definition,
                                  TransformerRegistry registry) throws YamlProblem {
        Node working = deepCopy(document, new IdentityHashMap<>());

        List<Entry> declarations = new ArrayList<>();
        Node root = real(working);
        if (root instanceof MappingNode mapping) {
            addressSpace(mapping, List.of(), declarations, new LinkedHashSet<>(), false, definition);
        }

        List<KeySite> sites = new ArrayList<>();
        List<Entry> expressions = new ArrayList<>();
        substitute(working, List.of(), expressions, sites, new LinkedHashSet<>());

        Config canonical = ConfigPipeline.cast(definition, registry).apply(
            ConfigPipeline.transformation(definition, registry).apply(below.apply(patch(declarations))));
        Config resolved = ConfigPipeline.interpolation().apply(canonical.apply(patch(expressions)));

        for (KeySite site : sites) {
            ConfigResult<ConfigValue> found = resolved.get(site.slot());
            if (found instanceof ConfigResult.ErrorResult<ConfigValue> bad) {
                // A key is an ADDRESS, not a value: the kind of problem (a cycle, an unresolved
                // reference) travels in the content, otherwise the report would talk about a
                // technical path instead of the key.
                throw new YamlProblem(new ConfigError.ParseFailed(source(line(site.at())),
                    "the content of key `" + site.expression() + "` could not be resolved: " + bad.error().message()));
            }
            ConfigValue materialized = ((ConfigResult.ValueResult<ConfigValue>) found).value();
            site.substituteWith(materialized.display());
        }
        return working;
    }

    /**
     * A key entry to resolve. The substitution happens <b>in the parent tuple</b>, because a
     * `ScalarNode` has no setter — and the comments of the key MUST carry over to the new node,
     * otherwise a written-out YAML loses them.
     */
    private record KeySite(String slot, MappingNode owner, int index, ScalarNode key, String expression,
                           Node at) {

        void substituteWith(String text) {
            ScalarNode replaced = new ScalarNode(key.getTag(), true, text, key.getScalarStyle(),
                key.getStartMark(), key.getEndMark());
            replaced.setAnchor(key.getAnchor());
            replaced.setBlockComments(comments(key.getBlockComments()));
            replaced.setInLineComments(comments(key.getInLineComments()));
            replaced.setEndComments(comments(key.getEndComments()));
            List<NodeTuple> tuples = new ArrayList<>(owner.getValue());
            tuples.set(index, new NodeTuple(replaced, tuples.get(index).getValueNode()));
            owner.setValue(tuples);
        }
    }

    /** PASS 2: every key with `${…}` gets a slot; values are descended too (maps inside sequences). */
    private void substitute(Node node, List<String> parent, List<Entry> expressions, List<KeySite> sites,
                            Set<Node> branch) throws YamlProblem {
        Node real = real(node);
        if (real instanceof SequenceNode sequence) {
            enter(sequence, branch);
            for (Node element : sequence.getValue()) {
                substitute(element, parent, expressions, sites, branch);
            }
            branch.remove(sequence);
            return;
        }
        if (!(real instanceof MappingNode mapping)) {
            return;
        }
        enter(mapping, branch);
        List<NodeTuple> tuples = mapping.getValue();
        for (int index = 0; index < tuples.size(); index++) {
            NodeTuple tuple = tuples.get(index);
            if (real(tuple.getKeyNode()) instanceof ScalarNode key && key.getValue().contains(CONTROL)) {
                String slot = KEY_SLOT + sites.size();
                sites.add(new KeySite(slot, mapping, index, key, key.getValue(), tuple.getKeyNode()));
                expressions.add(new Entry(ConfigPath.of(List.of(slot)), ConfigString.of(key.getValue()),
                    source(line(tuple.getKeyNode())), SourceDeclaration.none()));
            }
            substitute(tuple.getValueNode(), parent, expressions, sites, branch);
        }
        branch.remove(mapping);
    }

    // ── a clone of the tree (without reflection) ─────────────────────────────────────────────

    /**
     * A faithful clone: tag, text, style, positions, comments, anchor, children. Three traps:
     * the identity map MUST be written before descending into the children (aliases are the same
     * object, and a recursive anchor is accepted by the engine), `SequenceNode` has no setter (the
     * list is built here and filled after the node is registered), and comments are sometimes
     * `null`.
     */
    private Node deepCopy(Node node, Map<Node, Node> memo) {
        Node known = memo.get(node);
        if (known != null) {
            return known;
        }
        Node copy = switch (node) {
            case ScalarNode scalar -> new ScalarNode(scalar.getTag(), true, scalar.getValue(),
                scalar.getScalarStyle(), scalar.getStartMark(), scalar.getEndMark());
            case SequenceNode sequence -> copySequence(sequence, memo);
            case MappingNode mapping -> copyMapping(mapping, memo);
            case AnchorNode anchor -> new AnchorNode(deepCopy(anchor.getRealNode(), memo));
            default -> throw new IllegalStateException("unknown YAML node: " + node.getNodeType());
        };
        memo.put(node, copy);
        copy.setAnchor(node.getAnchor());
        copy.setRecursive(node.isRecursive());
        copy.setBlockComments(comments(node.getBlockComments()));
        copy.setInLineComments(comments(node.getInLineComments()));
        copy.setEndComments(comments(node.getEndComments()));
        return copy;
    }

    private SequenceNode copySequence(SequenceNode sequence, Map<Node, Node> memo) {
        List<Node> elements = new ArrayList<>();
        SequenceNode copy = new SequenceNode(sequence.getTag(), true, elements, sequence.getFlowStyle(),
            sequence.getStartMark(), sequence.getEndMark());
        memo.put(sequence, copy);
        sequence.getValue().forEach(element -> elements.add(deepCopy(element, memo)));
        return copy;
    }

    private MappingNode copyMapping(MappingNode mapping, Map<Node, Node> memo) {
        MappingNode copy = new MappingNode(mapping.getTag(), true, new ArrayList<>(), mapping.getFlowStyle(),
            mapping.getStartMark(), mapping.getEndMark());
        memo.put(mapping, copy);
        List<NodeTuple> tuples = new ArrayList<>();
        for (NodeTuple tuple : mapping.getValue()) {
            tuples.add(new NodeTuple(deepCopy(tuple.getKeyNode(), memo), deepCopy(tuple.getValueNode(), memo)));
        }
        copy.setValue(tuples);
        return copy;
    }

    /** Comments are sometimes `null` — no comments is no list, not an empty list. */
    private static List<CommentLine> comments(List<CommentLine> comments) {
        return comments == null ? null : new ArrayList<>(comments);
    }

    // ── origin and position ──────────────────────────────────────────────────────────────────

    /**
     * A missing file has no address in the configuration: the path is technical, the name travels in
     * the source.
     */
    private static final ConfigPath FILE_PATH = ConfigPath.of(List.of("file"));

    private static ConfigSource source(String origin) {
        return ConfigSource.of(Layer.YAML, origin);
    }

    /** `file:line` from the node marks — positions come from the AST, not from the text. */
    private String line(Node at) {
        return at.getStartMark()
            .map(mark -> origin + ":" + (mark.getLine() + 1))
            .orElse(origin);
    }

    // ── markers in a comment: the `TCONF(…)` and `CONST(…)` sections ─────────────────────────

    /**
     * Markers in a YAML comment.
     *
     * <p><b>THERE IS EXACTLY ONE PLACE FOR THEM</b>: a comment on the SAME LINE as the value of a
     * pair. With {@code setParseComments(true)} such a comment lands as {@code IN_LINE} on the node
     * of the VALUE, and when the value is a collection written in block form — on the node of the
     * KEY. A comment on its own line (a file header, a line above a key, a line between pairs) lands
     * as {@code BLOCK}, and a comment of a sequence element as {@code IN_LINE} on the node of the
     * ELEMENT — so each of those places can be told apart and REJECTED with a reason.
     *
     * <p>Inside a comment we look for WHOLE SECTIONS <b>from its beginning</b>: at most one
     * {@code TCONF(…)} and at most one {@code CONST(…)}, in any order, and after the sections any
     * prose may follow (it is ignored). The payload ends at the FIRST unescaped {@code )} outside
     * backticks — {@code \)} protects the bracket, and inside backticks {@code )} is free, so regex
     * groups work.
     *
     * <p>{@code A DELIBERATE NARROWING}: a section counts only when the comment STARTS with it.
     * Without that, documentation prose quoting {@code CONST(…)} would take the file down — and
     * comments are sometimes the documentation of the format. A marker standing after prose is
     * therefore treated as prose.
     */
    private static final class Markers {

        private static final String TCONF = "TCONF(";
        private static final String CONST = "CONST(";

        /** The source statement for a key↔value pair; {@link SourceDeclaration#none()} when no section. */
        static SourceDeclaration of(NodeTuple pair, YamlSource origin) throws YamlProblem {
            String atValue = sectionText(pair.getValueNode());
            String atKey = sectionText(pair.getKeyNode());
            if (atValue != null && atKey != null) {
                throw new YamlProblem(new ConfigError.ParseFailed(source(origin.line(pair.getKeyNode())),
                    "a marker stands both on the key and on the value of the same pair — there is exactly ONE place for it"));
            }
            String comment = atValue != null ? atValue : atKey;
            if (comment == null) {
                return SourceDeclaration.none();
            }
            SourceDeclaration spoken = parse(comment, origin.line(pair.getValueNode()));
            // A key outside the schema has no declaration in code, so the TYPE has to come from somewhere — it
            // comes from the FORM of the node (a scalar → `str`, a sequence → `seq`, a map → `map`). A
            // COLLECTION also says what it HOLDS, and the form of its members is what says it.
            return spoken.mergedWith(form(pair.getValueNode(), origin, pair));
        }

        /**
         * The type a node states about itself — and, for a collection, the type of its members.
         *
         * <p>The members' form is read exactly as the node's own form is: a scalar is `str`, a sequence `seq`, a
         * mapping `map`. It is the FORM that speaks here, never a resolved YAML type — a carrier hands over text
         * and shape, and the declaration decides what a value IS.
         */
        private static SourceDeclaration form(Node node, YamlSource origin, NodeTuple pair) throws YamlProblem {
            Node real = node instanceof AnchorNode anchor ? anchor.getRealNode() : node;
            if (real instanceof SequenceNode sequence) {
                return SourceDeclaration.seq(memberForm(sequence.getValue(), origin, pair));
            }
            if (real instanceof MappingNode mapping) {
                // A map's keys are text everywhere; what varies is the type of the VALUES.
                return SourceDeclaration.map(ConfigType.STR, memberForm(mapping.getValue().stream()
                    .map(NodeTuple::getValueNode).toList(), origin, pair));
            }
            return SourceDeclaration.type(ConfigType.STR);
        }

        /**
         * The form every member has — a collection holds ONE type of thing.
         *
         * <p>A mix is a loud error of the source (`[{a: 1}, 2]`): such a collection has no element type, and
         * guessing one is how a rule ends up comparing apples with cars. An empty collection states `str`: there
         * is no member whose form could speak, and the value itself is emptiness (an error) anyway.
         */
        private static ConfigType memberForm(List<Node> members, YamlSource origin, NodeTuple pair)
            throws YamlProblem {
            ConfigType common = null;
            for (Node member : members) {
                ConfigType one = formOf(member);
                if (common == null) {
                    common = one;
                } else if (common != one) {
                    throw new YamlProblem(new ConfigError.ParseFailed(source(origin.line(pair.getValueNode())),
                        "a collection holds ONE type of thing, but this one mixes `" + common.yamlName()
                            + "` with `" + one.yamlName() + "` — every member must be of the same type"));
                }
            }
            return common == null ? ConfigType.STR : common;
        }

        /**
         * A pair whose value is a MAP: there is no entry for it, so a marker has nothing to attach
         * to. A map is a set of ADDRESSES — rules and notation for a WHOLE map are declared in CODE,
         * and from a file they can only be given to a field whose value is a COLLECTION written
         * inline.
         *
         * <p>Reached only for a mapping that stays a set of addresses: under a field declared as
         * {@code map} the mapping is the VALUE of that address, so a marker has a pair to stand on —
         * the same place as next to a list.
         */
        static void rejectOnMap(NodeTuple pair, YamlSource origin) throws YamlProblem {
            if (sectionText(pair.getValueNode()) != null || sectionText(pair.getKeyNode()) != null) {
                throw new YamlProblem(new ConfigError.ParseFailed(source(origin.line(pair.getValueNode())),
                    "marker on a value that is a MAP — a map is a set of ADDRESSES, so there is no "
                        + "entry for it; rules and notation for the whole map are declared in CODE, "
                        + "and from a file they can be given only to a field whose value is a "
                        + "COLLECTION (such as a list)"));
            }
        }

        /**
         * A marker <b>inside</b> a mapping that is the VALUE of a declared {@code map} field. The pairs
         * inside such a mapping are the content of that value, not addresses — so a marker there has
         * nothing to attach to, and letting it through would be a rule or a notation that silently does
         * NOT work. The marker of the whole value belongs on the line of the address (exactly as next
         * to a list).
         */
        static void rejectInsideValue(Node value, YamlSource origin) throws YamlProblem {
            Node real = value instanceof AnchorNode anchor ? anchor.getRealNode() : value;
            if (!(real instanceof MappingNode mapping)) {
                return;
            }
            Set<Node> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            for (NodeTuple tuple : mapping.getValue()) {
                rejectSections(tuple.getKeyNode(), origin, seen);
                rejectSections(tuple.getValueNode(), origin, seen);
            }
        }

        private static void rejectSections(Node node, YamlSource origin, Set<Node> seen) throws YamlProblem {
            Node real = node instanceof AnchorNode anchor ? anchor.getRealNode() : node;
            if (!seen.add(real)) {
                return;
            }
            if (startsSection(text(inLine(real)))) {
                throw new YamlProblem(new ConfigError.ParseFailed(source(origin.line(real)),
                    "marker INSIDE a mapping that is the VALUE of a declared `map` field — the pairs "
                        + "inside it are the content of that value, not entries, so a marker there "
                        + "would be a rule that does NOT work; put it on the line of the address "
                        + "instead"));
            }
            if (real instanceof MappingNode mapping) {
                for (NodeTuple tuple : mapping.getValue()) {
                    rejectSections(tuple.getKeyNode(), origin, seen);
                    rejectSections(tuple.getValueNode(), origin, seen);
                }
            } else if (real instanceof SequenceNode sequence) {
                for (Node element : sequence.getValue()) {
                    rejectSections(element, origin, seen);
                }
            }
        }

        /** The type from the form of the node: the only place where the YAML source says anything about type. */
        private static ConfigType formOf(Node node) {
            Node real = node instanceof AnchorNode anchor ? anchor.getRealNode() : node;
            if (real instanceof SequenceNode) {
                return ConfigType.SEQ;
            }
            if (real instanceof MappingNode) {
                return ConfigType.MAP;
            }
            return ConfigType.STR;
        }

        /**
         * REJECTION of markers where they have no right to stand. The reason this is an error rather
         * than a silent skip: a marker put anywhere else means a rule that does NOT work.
         *
         * <p>The walk is cycle-protected (an anchor may point at its own ancestor — a recursive
         * anchor must be an ERROR, not a loop).
         */
        static void rejectMisplaced(Node node, YamlSource origin) throws YamlProblem {
            walk(node, origin, java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
        }

        private static void walk(Node node, YamlSource origin, Set<Node> seen) throws YamlProblem {
            if (!seen.add(node)) {
                return;
            }
            String ownLine = text(block(node));
            if (ownLine != null && startsSection(ownLine)) {
                throw new YamlProblem(new ConfigError.ParseFailed(source(origin.line(node)),
                    "marker in a comment on ITS OWN LINE — it may stand only on the same line as the "
                        + "value of a pair (a file header, a line above a key and a line between "
                        + "pairs are an ERROR)"));
            }
            if (node instanceof MappingNode mapping) {
                for (NodeTuple tuple : mapping.getValue()) {
                    walk(tuple.getKeyNode(), origin, seen);
                    walk(tuple.getValueNode(), origin, seen);
                }
            } else if (node instanceof SequenceNode sequence) {
                for (Node element : sequence.getValue()) {
                    if (startsSection(text(inLine(element)))) {
                        throw new YamlProblem(new ConfigError.ParseFailed(source(origin.line(element)),
                            "marker on a LIST ELEMENT — notation and rules concern the WHOLE value of "
                                + "a field, not a single element"));
                    }
                    walk(element, origin, seen);
                }
            }
        }

        /** The sections of a comment when the comment STARTS with a section; otherwise `null` (plain comment). */
        private static String sectionText(Node node) {
            String text = text(inLine(node));
            return startsSection(text) ? text : null;
        }

        private static boolean startsSection(String text) {
            return text != null && (text.startsWith(TCONF) || text.startsWith(CONST));
        }

        /** The whole text of the `IN_LINE`/`BLOCK` comments of one node (usually one), joined. */
        private static String text(List<CommentLine> lines) {
            List<CommentLine> present = comments(lines);
            if (present == null || present.isEmpty()) {
                return null;
            }
            StringBuilder joined = new StringBuilder();
            for (CommentLine line : present) {
                if (joined.length() > 0) {
                    joined.append(' ');
                }
                joined.append(line.getValue().strip());
            }
            return joined.toString();
        }

        private static List<CommentLine> inLine(Node node) {
            return node.getInLineComments();
        }

        private static List<CommentLine> block(Node node) {
            return node.getBlockComments();
        }

        private static SourceDeclaration parse(String comment, String at) throws YamlProblem {
            String rest = comment;
            Optional<String> notation = Optional.empty();
            Optional<String> chain = Optional.empty();
            while (true) {
                String kind = rest.startsWith(TCONF) ? TCONF : rest.startsWith(CONST) ? CONST : null;
                if (kind == null) {
                    break;   // prose after the sections is ignored
                }
                int close = close(rest, kind.length(), at, kind);
                String payload = rest.substring(kind.length(), close).strip();
                if (payload.isEmpty()) {
                    throw new YamlProblem(new ConfigError.ParseFailed(source(at),
                        "section `" + name(kind) + "(…)` with no content means nothing"));
                }
                if (TCONF.equals(kind)) {
                    if (notation.isPresent()) {
                        throw new YamlProblem(new ConfigError.ParseFailed(source(at),
                            "section `TCONF(…)` a second time in one comment — at most ONE"));
                    }
                    notation = Optional.of(payload);
                } else {
                    if (chain.isPresent()) {
                        throw new YamlProblem(new ConfigError.ParseFailed(source(at),
                            "section `CONST(…)` a second time in one comment — at most ONE"));
                    }
                    chain = Optional.of(payload);
                }
                rest = rest.substring(close + 1).strip();
            }

            SourceDeclaration spoken = SourceDeclaration.none();
            if (notation.isPresent()) {
                spoken = spoken.mergedWith(SourceDeclaration.config(notation.get()));
            }
            if (chain.isPresent()) {
                try {
                    // The chain parser runs when the statement is built: a bad rule must be a SOURCE
                    // error with a position in the file, not a surprise during validation.
                    spoken = spoken.mergedWith(SourceDeclaration.constraints(chain.get()));
                } catch (IllegalArgumentException broken) {
                    throw new YamlProblem(new ConfigError.ParseFailed(source(at),
                        "the rule in section `CONST(…)` is invalid: " + broken.getMessage()));
                }
            }
            return spoken;
        }

        /** The position of the `)` that closes the payload: the FIRST unescaped one outside backticks. */
        private static int close(String text, int from, String at, String kind) throws YamlProblem {
            boolean backtick = false;
            for (int index = from; index < text.length(); index++) {
                char character = text.charAt(index);
                if (character == '\\') {
                    index++;   // `\)` protects the bracket, `\`` protects a backtick — one escape place
                    continue;
                }
                if (character == '`') {
                    backtick = !backtick;
                    continue;
                }
                if (character == ')' && !backtick) {
                    return index;
                }
            }
            throw new YamlProblem(new ConfigError.ParseFailed(source(at),
                "section `" + name(kind) + "(…)` is not closed with a bracket"));
        }

        private static String name(String kind) {
            return kind.substring(0, kind.length() - 1);
        }
    }

    /** A problem of the file: a configuration error plus the position where it was seen. */
    private static final class YamlProblem extends Exception {

        private static final long serialVersionUID = 1L;

        private final transient ConfigError error;

        private YamlProblem(ConfigError error) {
            super(error.report());
            this.error = error;
        }
    }
}
