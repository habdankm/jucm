package dev.jucm.picocli;

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
import dev.jucm.env.StructureReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import picocli.CommandLine;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.ParseResult;

/**
 * CLI adapter — **reading arguments into a patch**. It takes the parse result of a {@code CommandLine}
 * built from {@link CliSpec} and returns the {@link ConfigPatch} of the {@code CLI} layer.
 *
 * <p>Four things it guards:
 *
 * <ul>
 *   <li><b>values as text</b>, numbers and booleans included: `--server-port 010` arrives as `010`, and
 *       only the validator rejects it (`CastFailed` with the path, the value and the source). That
 *       keeps the notation of numbers **the same** as in YAML and ENV (`010` is octal eight).</li>
 *   <li>the space of `-` belongs to the APPLICATION: the engine claims no alias, so short names —
 *       multi-character ones such as `-version` too — exist only when they are declared; an unknown
 *       name is an error, not a guess.</li>
 *   <li><b>unknown options → auxiliary</b>: a typo WITH a value (`--prot 9000`) is exactly the same case
 *       as a key outside the schema in YAML — an entry nobody leads to is produced, that is
 *       `UnusedAuxiliary` in the default mode. A <b>bare</b> option token (`--prot` with nothing after
 *       it) is NOT a key: whether the name is declared or not, someone meant to set a parameter and set
 *       nothing, so it is a USAGE error ({@link CommandLine.ParameterException}). The same reading covers
 *       BOTH sides of an action word, because an action carries a copy of the options: a token after the
 *       action (`tui --prot`) is not dropped in silence. <b>One name gets a hint</b>: an engine option
 *       written with two dashes (`--help`, `--strict-config-parser`) — the engine's options are
 *       single-dash, so the double-dash form is the one mistake that is likely rather than accidental,
 *       and the message names the form the user probably meant.</li>
 *   <li><b>the `--x--type` / `--x--tconf` / `--x--const` markers</b> → the {@link SourceDeclaration}
 *       of the entry (the counterpart of `___TYPE`/`___TCONF` in ENV); a marker without a value, or
 *       without the option it refers to, is a parse error — never a silent skip.</li>
 * </ul>
 *
 * <p>`-strict-config-parser` **is not an entry**: it is an engine option, so the application reads it
 * from the same parse result and the adapter skips it. The engine's help asks the same rule of the
 * adapter for a different reason: `-help` never reaches a patch, because a request for help ends the run
 * before the arguments are read ({@code ConfigTool} answers it first).
 *
 * @since 1.0
 */
public final class CliSource {

    private static final String DASHES = "--";
    private static final String SHORT = "-";
    private static final String TYPE_MARKER = "--type";
    private static final String TCONF_MARKER = "--tconf";
    private static final String CONST_MARKER = "--const";

    /** The first characters of a flow literal: a value that starts with one of them is a STRUCTURE, not text. */
    private static final String FLOW = "[{";

    private CliSource() {
    }

    /**
     * The selected ACTION, when the user gave a bare word. An action is not configuration: its
     * parameters stay in its own spec, and the patch from {@link #read} carries main-level options
     * only — the application decides what to do with the selected action.
     *
     * @param parsed the parse result of a {@code CommandLine}; must not be {@code null}
     * @return the name of the selected action, or an empty optional when there is none
     * @since 1.0
     */
    public static Optional<String> selectedAction(ParseResult parsed) {
        Objects.requireNonNull(parsed, "parsed");
        return Optional.ofNullable(parsed.subcommand()).map(action -> action.commandSpec().name());
    }

    /**
     * Arguments → patch. The order of entries follows the declaration, then the unknown options.
     *
     * @param parsed the parse result of a {@code CommandLine} built from {@link CliSpec}; must not be
     *        {@code null}
     * @return the patch of the CLI layer, or a parse error naming the option at fault
     * @throws picocli.CommandLine.ParameterException when an option token carries no value at all
     *         ({@code --prot} with nothing after it) — a USAGE error, whatever the option is called
     * @since 1.0
     */
    public static ConfigResult<ConfigPatch> read(ParseResult parsed) {
        return read(parsed, StructureReader.none());
    }

    /**
     * The same, with a reader that turns a FLOW LITERAL into a structure (`[80, 443]` becomes a list), so that a
     * collection can be given on the command line exactly as it is given in a file or in ENV: one grammar, three
     * carriers. Without a reader every value stays TEXT — and then a declared collection cannot be filled from the
     * command line at all (`cast-failed: expected type `seq`, but the incoming value has type `str``).
     *
     * <p>A value that starts with `[` or `{` is a literal and not text, as in the ENV adapter: `--name "[a,b]"`
     * is a LIST, and a text that begins with a bracket has no reading on the command line.
     *
     * @param parsed the parse result of a {@code CommandLine} built from {@link CliSpec}; must not be {@code null}
     * @param reader the reader that parses a flow literal; must not be {@code null}
     * @return the patch of the CLI layer, or a parse error naming the option at fault
     * @since 1.0
     */
    public static ConfigResult<ConfigPatch> read(ParseResult parsed, StructureReader reader) {
        Objects.requireNonNull(reader, "reader");
        Map<ConfigPath, Value> values = new LinkedHashMap<>();

        // Options are read from TWO levels: the root and the selected action, because the action has a
        // copy of the same options, so that `--port 8080 tui` and `tui --port 8080` mean the same.
        List<ParseResult> levels = new ArrayList<>();
        levels.add(parsed);
        if (parsed.subcommand() != null) {
            levels.add(parsed.subcommand());
        }
        for (ParseResult level : levels) {
            ConfigResult<ConfigPatch> options = options(level, values);
            if (options != null) {
                return options;
            }
        }

        List<String> unmatched = new ArrayList<>(parsed.unmatched());
        // An action carries a COPY of the options, so `--port 8080 tui` and `tui --port 8080` mean the
        // same. Everything else has to be read the same way, too: picocli collects what it does not know
        // PER LEVEL, so a token after the action word (`tui --prot 9000`, `tui --prot`, or the double-dash
        // help the engine does not own) would otherwise be dropped in silence — and a dropped help token
        // is the worst version of that: the user asks for help and the application runs.
        if (parsed.subcommand() != null) {
            unmatched.addAll(parsed.subcommand().unmatched());
        }
        Map<ConfigPath, SourceDeclaration> markers = new LinkedHashMap<>();
        List<String> leftovers = new ArrayList<>();
        for (int index = 0; index < unmatched.size(); index++) {
            String token = unmatched.get(index);
            Marker marker = Marker.of(token);
            if (marker == null) {
                leftovers.add(token);
                continue;
            }
            String attached = marker.value();
            if (attached == null) {
                if (index + 1 >= unmatched.size()) {
                    return problem("marker `" + token + "` without a value — there is nothing to assign");
                }
                attached = unmatched.get(++index);
            }
            ConfigPath target = address(DASHES + marker.target());
            // The three markers of one target are THREE axes (`--x--type`, `--x--tconf`, `--x--const`)
            // and not an overwrite: `type` from one, configuration and rule from the others — they
            // compose into a single statement.
            SourceDeclaration spoken;
            try {
                spoken = marker.declaration(attached);
            } catch (IllegalArgumentException broken) {
                // The constraints chain is parsed while the statement is built, so a syntax error
                // surfaces here — as a SOURCE error, with the option name.
                return problem("the rule from marker `" + marker.kind() + "` is invalid: "
                    + broken.getMessage());
            }
            markers.merge(target, spoken, SourceDeclaration::mergedWith);
        }

        for (int index = 0; index < leftovers.size(); index++) {
            String token = leftovers.get(index);
            if (!token.startsWith(SHORT)) {
                // A bare word is an ACTION: `--x` sets configuration, `x` runs an action. An unknown
                // action is an error that SAYS what is known — instead of guessing whether the user
                // meant a field or an action.
                return problem("`" + token + "` is not an action — known actions: " + String.join(", ", known(parsed))
                    + "; setting configuration is `--" + token + "`");
            }
            String[] split = token.replaceFirst("^-+", "").split("=", 2);
            // picocli reads `-action` as a CLUSTER of short options by default (`-a` plus the rest as
            // its value) — that is quietly a DIFFERENT meaning. Clustering is turned off in `CliSpec`,
            // so such an argument is simply unknown here. Instead of "repairing" it (because `-action`
            // is never a long option name), we state plainly what the convention is.
            if (token.startsWith(SHORT) && !token.startsWith(DASHES)) {
                return problem("`" + token + "` is not a known option — short names must be"
                    + " DECLARED (on a field or on an action), and configuration is `--" + split[0] + "`");
            }
            String attached = split.length == 2 ? split[1] : null;
            if (attached == null && index + 1 < leftovers.size() && !leftovers.get(index + 1).startsWith("-")) {
                attached = leftovers.get(++index);
            }
            if (attached == null) {
                // A BARE option token: there is no value attached and none to take from the next
                // argument, so someone meant to set a parameter and set nothing. That is a USAGE error
                // for EVERY option name, declared (`--port`) or not (`--prot`) — an option with no
                // value sets nothing, and pretending it said `""` would hide a mistyped command line.
                // The boundary is the VALUE, not the name: `--prot 9000` stays an auxiliary key below.
                throw new CommandLine.ParameterException(commandLineOf(parsed),
                    "Missing parameter for option `" + token + "` — a bare option sets nothing"
                        + engineHint(token));
            }
            ConfigPath path = address(DASHES + split[0]);
            if (values.containsKey(path)) {
                return problem("option `" + token + "` repeated — there is no way to tell which value applies");
            }
            values.put(path, new Value(text(attached), source(token), SourceDeclaration.none()));
        }

        for (Map.Entry<ConfigPath, SourceDeclaration> marker : markers.entrySet()) {
            Value value = values.get(marker.getKey());
            if (value == null) {
                return problem("marker for `" + DASHES + marker.getKey().canonical().replace('.', '-')
                    + "` without a value — there is nothing to type");
            }
            values.put(marker.getKey(), new Value(value.text(), value.source(), marker.getValue()));
        }

        ConfigPatch patch = ConfigPatch.empty();
        for (Map.Entry<ConfigPath, Value> entry : values.entrySet()) {
            ConfigResult<ConfigValue> value = literal(entry.getValue().text(), reader, entry.getValue().source());
            if (value instanceof ConfigResult.ErrorResult<ConfigValue> broken) {
                return ConfigResult.error(broken.error());
            }
            ConfigValue read = ((ConfigResult.ValueResult<ConfigValue>) value).value();
            // The carrier read the value, so for a key OUTSIDE the schema it can state what the members are
            // (`--m [[1,2]] --m--type seq` is a list of lists, not a list of text).
            Optional<SourceDeclaration> holding = entry.getValue().declaration().holding(read);
            if (holding.isEmpty()) {
                return problem("a collection holds ONE type of thing, but `" + entry.getKey().canonical()
                    + "` mixes member types — every member must be of the same type");
            }
            patch = patch.with(entry.getKey(), read, entry.getValue().source(), holding.get());
        }
        return ConfigResult.value(patch);
    }

    /** The options of one level; {@code null} when all is well, or a problem (a repetition). */
    private static ConfigResult<ConfigPatch> options(ParseResult level, Map<ConfigPath, Value> values) {
        for (OptionSpec option : level.matchedOptions()) {
            String name = option.names()[0];
            if ((CliSpec.SHORT + CliSpec.STRICT_PARSER).equals(name)) {
                continue; // an engine option, not a configuration field
            }
            ConfigPath path = address(name);
            if (values.containsKey(path)) {
                return problem("option `" + name + "` given twice (before the action and inside it)"
                    + " — there is no way to tell which value applies");
            }
            values.put(path, new Value(text(level.matchedOptionValue(name, "")), source(name),
                SourceDeclaration.none()));
        }
        return null;
    }

    /** The value and the origin of an entry, before it becomes a patch. */
    private record Value(String text, ConfigSource source, SourceDeclaration declaration) {
    }

    /** A marker from a raw token: `--x--type`, `--x--type=int`, `--x--tconf`. */
    private record Marker(String target, String kind, String value) {

        static Marker of(String token) {
            if (!token.startsWith(SHORT)) {
                return null;
            }
            String body = token.substring(DASHES.length());
            int at = body.indexOf(DASHES);
            if (at <= 0) {
                return null;
            }
            String target = body.substring(0, at);
            String rest = body.substring(at);
            String[] split = rest.split("=", 2);
            boolean type = TYPE_MARKER.equals(split[0]);
            boolean config = TCONF_MARKER.equals(split[0]);
            boolean rule = CONST_MARKER.equals(split[0]);
            return type || config || rule
                ? new Marker(target, split[0], split.length == 2 ? split[1] : null) : null;
        }

        SourceDeclaration declaration(String attached) {
            if (TCONF_MARKER.equals(kind)) {
                return SourceDeclaration.config(attached);
            }
            if (CONST_MARKER.equals(kind)) {
                return SourceDeclaration.constraints(attached);
            }
            return SourceDeclaration.type(ConfigType.fromYamlName(attached));
        }
    }

    /** `--server-port` → `server.port`; the dot comes back from the dash, the underscore stays. */
    private static ConfigPath address(String optionName) {
        String body = optionName.replaceFirst("^-+", "").replace('-', '.');
        try {
            return ConfigPath.of(body);
        } catch (IllegalArgumentException broken) {
            throw new IllegalArgumentException("option `" + optionName + "` cannot be turned into an address: "
                + broken.getMessage());
        }
    }

    private static ConfigSource source(String optionName) {
        return ConfigSource.of(Layer.CLI, optionName);
    }

    /**
     * The hint for ONE likely mistake, and only for it: a bare DOUBLE-dash token whose name is an engine
     * option's name (`--help`, `--strict-config-parser`). The engine occupies single-dash names and the
     * model's fields double-dash ones, so `--help` is the spelling a user reaches for while the help of
     * the engine is `-help` — a mistake worth naming, unlike a typo (`--prot`), which the plain message
     * already covers. The hint appears only when that name is NOT a declared field: a declared `--help`
     * never gets here (picocli reports its missing value first), so the user who forgot a value is told
     * about the value, not about the engine.
     */
    private static String engineHint(String token) {
        if (!token.startsWith(DASHES)) {
            return "";
        }
        String name = token.substring(DASHES.length()).split("=", 2)[0];
        if (!CliSpec.ENGINE_OPTIONS.contains(name)) {
            return "";
        }
        return " — did you mean `" + SHORT + name + "` ("
            + (CliSpec.HELP.equals(name) ? "the engine's help" : "an engine option") + ")?";
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    /** The action names this spec knows: registered subcommands plus the engine-reserved ones. */
    private static List<String> known(ParseResult parsed) {
        Map<String, ?> registered = parsed.commandSpec().subcommands();
        List<String> names = new ArrayList<>(registered.keySet());
        for (String action : CliSpec.ACTIONS) {
            if (!names.contains(action)) {
                names.add(action);
            }
        }
        return names;
    }

    /** The value of a text: a structure when it looks like a flow literal, plain text otherwise. */
    private static ConfigResult<ConfigValue> literal(String text, StructureReader reader, ConfigSource source) {
        if (text.isEmpty() || FLOW.indexOf(text.charAt(0)) < 0) {
            return ConfigResult.value(ConfigString.of(text));
        }
        ConfigResult<ConfigValue> read = reader.read(text);
        if (read instanceof ConfigResult.ErrorResult<ConfigValue> broken) {
            return ConfigResult.error(new ConfigError.ParseFailed(source,
                "the value is not a readable flow literal: " + broken.error().message()));
        }
        return read;
    }

    private static ConfigResult<ConfigPatch> problem(String cause) {
        return ConfigResult.error(new ConfigError.ParseFailed(source("argv"), cause));
    }

    /**
     * The {@code CommandLine} a USAGE error has to carry. A parse result is by contract the result of a
     * {@code CommandLine} built from {@link CliSpec}, so the spec is always bound to one.
     */
    private static CommandLine commandLineOf(ParseResult parsed) {
        return parsed.commandSpec().commandLine();
    }
}
