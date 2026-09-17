package dev.jucm.env;

import dev.jucm.core.ConfigDefinition;
import dev.jucm.core.ConfigError;
import dev.jucm.core.ConfigPatch;
import dev.jucm.core.ConfigPath;
import dev.jucm.core.ConfigResult;
import dev.jucm.core.ConfigSource;
import dev.jucm.core.ConfigString;
import dev.jucm.core.ConfigType;
import dev.jucm.core.ConfigValue;
import dev.jucm.core.Layer;
import dev.jucm.core.SourceDeclaration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * An ENV source — environment variables as a {@link ConfigPatch}.
 *
 * <p>The order of the cuts is <b>part of the contract</b>, because {@code ___} contains {@code __}:
 *
 * <pre>
 *   AIMENAGERIE__SERVER__PORT___TYPE = seq
 *     1. cut the prefix (which ends with `__`)      SERVER__PORT___TYPE
 *     2. cut the TRAILING marker `___TYPE`          SERVER__PORT      + type = seq
 *     3. split what is left on `__`                 server.port
 * </pre>
 *
 * <p>The reverse order would break {@code PORT___TYPE} into {@code port} and {@code _type} — hence the
 * prefix first, then the marker, and the split last. A variable without the prefix is
 * <b>ignored</b>: {@code PATH}, {@code HOME} and {@code TERM} have nothing to do with us.
 *
 * <p>A value enters as <b>text</b> ({@link ConfigString}): there is no second place where a number
 * becomes a number — our notation lives in the cast, so {@code AIMENAGERIE__PORT=8080},
 * {@code --port 8080} and {@code port: 8080} yield the <b>same</b> value under the same declaration.
 * The one exception is a flow literal, which is read by <b>a single</b> text reader per structure
 * ({@link StructureReader}).
 *
 * <p><b>An absent variable and an empty one both bring in nothing</b>, for two different reasons: an
 * absent variable is not ours to read ({@code getenv} answers {@code null}), while an empty one
 * ({@code AIMENAGERIE__PORT=}) is a variable the user left without a value - a setting that means
 * nothing, so this layer behaves as if the variable were not there and a lower layer (or the
 * declaration) answers. An emptiness **error** is reserved for emptiness written on purpose: a YAML
 * {@code x: ""} node or an explicit {@code --host ""} argument, which the model reports as
 * {@code NullValue}. A **marker** with an empty payload ({@code PORT___TYPE=}) stays a source error:
 * a statement that states nothing is a mistake, not an empty value.
 *
 * @since 1.0
 */
public final class EnvSource {

    private static final String SEGMENT = "__";
    private static final String TYPE_MARKER = "___TYPE";
    private static final String TCONF_MARKER = "___TCONF";
    private static final String CONST_MARKER = "___CONST";
    private static final String FLOW = "[{";

    /**
     * Type names the marker accepts — a mirror of {@code ConfigType.fromYamlName}. They are kept here
     * so that the message about a misspelling can SHOW them (without that the user guesses the
     * spelling); a test guards that this list does not drift away from the type dictionary.
     */
    static final List<String> KNOWN_TYPES = List.of("str", "int", "float", "bool", "seq", "map", "timestamp",
        "binary", "duration", "uri");

    private final Map<String, String> environment;
    private final String prefix;
    private final StructureReader reader;

    private EnvSource(Map<String, String> environment, String prefix, StructureReader reader) {
        this.environment = environment;
        this.prefix = prefix;
        this.reader = reader;
    }

    /**
     * Creates a source over a map of variables.
     *
     * <p>A prefix without {@code __} gets it appended, so {@code AIMENAGERIE} and {@code AIMENAGERIE__}
     * mean the same thing. Prefix matching is case sensitive.
     *
     * @param environment variables to read, keyed by name; must not be {@code null}; the map is kept by
     *        reference and never modified
     * @param prefix prefix that makes a variable ours; must not be {@code null}
     * @param reader reader of flow literals; must not be {@code null}
     * @return a source over those variables
     * @throws NullPointerException if any argument is {@code null}
     * @since 1.0
     */
    public static EnvSource of(Map<String, String> environment, String prefix, StructureReader reader) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(reader, "reader");
        return new EnvSource(environment, prefix.endsWith(SEGMENT) ? prefix : prefix + SEGMENT, reader);
    }

    /**
     * Creates a source without a structure reader: a flow literal becomes an error (see
     * {@link StructureReader#none()}).
     *
     * @param environment variables to read, keyed by name; must not be {@code null}
     * @param prefix prefix that makes a variable ours; must not be {@code null}
     * @return a source over those variables
     * @throws NullPointerException if any argument is {@code null}
     * @since 1.0
     */
    public static EnvSource of(Map<String, String> environment, String prefix) {
        return of(environment, prefix, StructureReader.none());
    }

    /**
     * Creates a source over the environment of this process.
     *
     * @param prefix prefix that makes a variable ours; must not be {@code null}
     * @return a source over {@code System.getenv()}
     * @throws NullPointerException if {@code prefix} is {@code null}
     * @since 1.0
     */
    public static EnvSource of(String prefix) {
        return of(System.getenv(), prefix);
    }

    /**
     * Turns the variables carrying the prefix into the entries of a patch.
     *
     * <p>Variables are processed in name order, so the result does not depend on their order in the
     * environment. The order in which entries are <i>listed</i> is not promised: {@link
     * ConfigPatch#paths()} is an immutable set, and those iterate in an order that varies between runs
     * of the JVM.
     *
     * <p>A variable name that cannot become an address is a <b>source</b> error, not a model error —
     * the name sits in the environment and can be fixed without recompiling — so its message carries
     * the variable name.
     *
     * @param definition declaration of the model, used to tell which paths are declared in code; must
     *        not be {@code null}
     * @return the entries of this source, or a source error naming the variable that caused it
     * @throws NullPointerException if {@code definition} is {@code null}
     * @since 1.0
     */
    public ConfigResult<ConfigPatch> patch(ConfigDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        Map<ConfigPath, Slot> values = new LinkedHashMap<>();
        Map<ConfigPath, Site> markers = new LinkedHashMap<>();

        for (Map.Entry<String, String> variable : new TreeMap<>(environment).entrySet()) {
            String name = variable.getKey();
            if (!name.startsWith(prefix)) {
                continue;
            }
            String body = name.substring(prefix.length());
            Marker marker = Marker.of(body);
            if (marker == null && variable.getValue().isEmpty()) {
                // An empty value brings in NOTHING: the layer behaves as if the variable were absent, so the
                // value comes from a lower layer or from the declaration. Emptiness is an error only when
                // someone wrote it on purpose (a YAML `x: ""`, a `--host ""` argument). A MARKER with an
                // empty payload is still a source error - a statement that states nothing is a mistake, not
                // an empty value, and it must be loud (a silently ignored marker is a rule that does not work
                // for reasons nobody can see).
                continue;
            }
            ConfigPath path;
            try {
                path = address(marker == null ? body : marker.target(), name);
            } catch (IllegalArgumentException broken) {
                // The variable name sits in the ENVIRONMENT, so it is a SOURCE error, not a model error:
                // it can be fixed without recompiling. The message MUST carry the variable name.
                return ConfigResult.error(new ConfigError.ParseFailed(source(name), broken.getMessage()));
            }
            if (marker == null) {
                ConfigResult<ConfigValue> read = value(variable.getValue(), name);
                if (read instanceof ConfigResult.ErrorResult<ConfigValue> broken) {
                    return ConfigResult.error(broken.error());
                }
                values.put(path, new Slot(((ConfigResult.ValueResult<ConfigValue>) read).value(),
                    source(name), SourceDeclaration.none()));
                continue;
            }
            ConfigResult<SourceDeclaration> declaration = marker.declaration(variable.getValue(), name);
            if (declaration instanceof ConfigResult.ErrorResult<SourceDeclaration> broken) {
                return ConfigResult.error(broken.error());
            }
            Site site = new Site(((ConfigResult.ValueResult<SourceDeclaration>) declaration).value(), name);
            markers.merge(path, site, (older, newer) -> new Site(older.declaration().mergedWith(newer.declaration()),
                newer.name()));
        }

        for (Map.Entry<ConfigPath, Site> marker : markers.entrySet()) {
            Slot slot = values.get(marker.getKey());
            if (slot == null) {
                return ConfigResult.error(new ConfigError.ParseFailed(source(marker.getValue().name()),
                    "marker without a value — `" + marker.getValue().name() + "` has nothing to type"));
            }
            SourceDeclaration declaration = declarationOf(marker, slot, definition);
            // The carrier read the value, so for a key OUTSIDE the schema it can state what the members are
            // (`___TYPE=seq` on `[[1,2]]` is a list of lists, not a list of text).
            Optional<SourceDeclaration> holding = declaration.holding(slot.value());
            if (holding.isEmpty()) {
                return ConfigResult.error(new ConfigError.ParseFailed(source(marker.getValue().name()),
                    "a collection holds ONE type of thing, but `" + marker.getValue().name()
                        + "` mixes member types — every member must be of the same type"));
            }
            declaration = holding.get();
            // A key OUTSIDE the schema needs no type: such a key travels as a STRING, so a statement
            // that brings a rule or a notation and no type is CARRIED and inert — there is nothing to
            // read the value with, and nothing to compare the rule against. Refusing it would refuse
            // the VALUE, and a key outside the schema is often exactly how an application supplies an
            // auxiliary: `___TYPE` may state a type, but nothing here requires one.
            values.put(marker.getKey(), new Slot(slot.value(), slot.source(), declaration));
        }

        ConfigPatch patch = ConfigPatch.empty();
        for (Map.Entry<ConfigPath, Slot> entry : values.entrySet()) {
            patch = patch.with(entry.getKey(), entry.getValue().value(), entry.getValue().source(),
                entry.getValue().declaration());
        }
        return ConfigResult.value(patch);
    }

    /**
     * The declaration of an entry. On a path <b>declared in code</b> the marker neither retypes the
     * field nor imposes a notation (the declaration from code wins), but a <b>rule from the marker does
     * apply</b>: {@code ___CONST} replaces the rules from code.
     */
    private SourceDeclaration declarationOf(Map.Entry<ConfigPath, Site> marker, Slot slot,
                                           ConfigDefinition definition) {
        SourceDeclaration spoken = marker.getValue().declaration();
        if (definition.field(marker.getKey()).isPresent()) {
            // Type and notation come from CODE; the rule from the source stays (and replaces the rules
            // from code further on in the pipeline).
            return new SourceDeclaration(Optional.empty(), spoken.transformerConfig(), spoken.constraints(),
                Optional.empty(), Optional.empty());
        }
        return slot.declaration().mergedWith(spoken);
    }

    /** The value: a structure when it looks like a flow literal, plain text otherwise. */
    private ConfigResult<ConfigValue> value(String text, String name) {
        if (!text.isEmpty() && FLOW.indexOf(text.charAt(0)) >= 0) {
            ConfigResult<ConfigValue> read = reader.read(text);
            if (read instanceof ConfigResult.ErrorResult<ConfigValue> broken) {
                return ConfigResult.error(new ConfigError.ParseFailed(source(name),
                    "the value is not a readable flow literal: " + broken.error().message()));
            }
            return read;
        }
        return ConfigResult.value(ConfigString.of(text));
    }

    /**
     * A variable name as an address. A single {@code _} STAYS in the segment ({@code MAX_CONNECTIONS}
     * becomes {@code max_connections}), and case is normalised to lower case: the convention is
     * documented, not enforced.
     */
    private static ConfigPath address(String body, String name) {
        String[] segments = body.split(SEGMENT, -1);
        for (int index = 0; index < segments.length; index++) {
            segments[index] = segments[index].toLowerCase(Locale.ROOT);
        }
        try {
            return ConfigPath.of(java.util.List.of(segments));
        } catch (IllegalArgumentException broken) {
            throw new IllegalArgumentException("variable name `" + name + "` cannot be turned into an address: "
                + broken.getMessage() + " (a segment cannot contain `" + SEGMENT + "`)");
        }
    }

    private static ConfigSource source(String name) {
        return ConfigSource.of(Layer.ENV, name);
    }

    private record Slot(ConfigValue value, ConfigSource source, SourceDeclaration declaration) {
    }

    private record Site(SourceDeclaration declaration, String name) {
    }

    private record Marker(String target, String kind, String payload) {

        static Marker of(String body) {
            if (body.endsWith(TYPE_MARKER)) {
                return new Marker(body.substring(0, body.length() - TYPE_MARKER.length()), TYPE_MARKER, null);
            }
            if (body.endsWith(TCONF_MARKER)) {
                return new Marker(body.substring(0, body.length() - TCONF_MARKER.length()), TCONF_MARKER, null);
            }
            if (body.endsWith(CONST_MARKER)) {
                return new Marker(body.substring(0, body.length() - CONST_MARKER.length()), CONST_MARKER, null);
            }
            return null;
        }

        /** The payload is the VALUE of the marker variable (for example {@code X___TYPE=seq}). */
        ConfigResult<SourceDeclaration> declaration(String payload, String name) {
            String text = payload == null ? "" : payload.trim();
            if (text.isEmpty()) {
                return ConfigResult.error(new ConfigError.ParseFailed(source(name),
                    "marker without a value — `" + kind + "` means nothing then"));
            }
            if (TCONF_MARKER.equals(kind)) {
                return ConfigResult.value(SourceDeclaration.config(text));
            }
            if (CONST_MARKER.equals(kind)) {
                try {
                    return ConfigResult.value(SourceDeclaration.constraints(text));
                } catch (IllegalArgumentException broken) {
                    // The chain is parsed inside `SourceDeclaration`, so a syntax error surfaces here —
                    // with the VARIABLE name, because that is what it concerns.
                    return ConfigResult.error(new ConfigError.ParseFailed(source(name),
                        "the rule from the `" + CONST_MARKER + "` marker is not valid: " + broken.getMessage()));
                }
            }
            try {
                return ConfigResult.value(SourceDeclaration.type(ConfigType.fromYamlName(text)));
            } catch (IllegalArgumentException unknown) {
                return ConfigResult.error(new ConfigError.ParseFailed(source(name),
                    "unknown type name `" + text + "`; known: " + String.join(", ", KNOWN_TYPES)));
            }
        }
    }
}
