package dev.jucm.picocli;

import dev.jucm.core.ConfigDefinition;
import dev.jucm.core.ConfigField;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

/**
 * CLI adapter — **building the spec from the model**: {@code CommandSpec}/{@code OptionSpec} are built
 * programmatically, without annotations and without hand-written mappings. Annotations are rejected,
 * because the model is our {@link ConfigDefinition} and not an application class — the spec is
 * **derived** from it.
 *
 * <p>The rules this spec derives:
 *
 * <ul>
 *   <li><b>the long name comes from the canonical path</b>: `server.port` → `--server-port`, and
 *       `server.max_connections` → `--server-max_connections` (a dot becomes a dash, an underscore
 *       stays).</li>
 *   <li><b>aliases come from the declaration</b> ({@link ConfigField#aliases()}) — next to
 *       `description`, `required` and `default`, and not in a separate CLI registry. They are short,
 *       so they go out as `-p`.</li>
 *   <li><b>the argument type is text</b> — for EVERY type, because our cast is what gives the type.
 *       `bool` would have to be text even without that (picocli on `Boolean` answers `null` with exit
 *       code `0` for `--x=yes`, that is an EMPTINESS without an error), but numbers are the same
 *       problem: with picocli typing them, the same input would give `10` from the CLI and `8` from
 *       YAML (`010` in our notation is octal eight) — one value, two meanings.</li>
 *   <li><b>the dash convention: the engine takes ONE dash, the model takes TWO</b> — the engine owns
 *       `-help` and `-strict-config-parser`, a field owns its path in the long form (`server.port` →
 *       `--server-port`), and a declared alias owns `-p`. So `--help` is a legal FIELD name (`-help` is
 *       the help of the engine), and there is deliberately no short `-h`: short names are DECLARED, never
 *       guessed. A collision with an engine name is a **model error** raised while the spec is built.</li>
 *   <li><b>no clustering of short options</b>: `-action` is NOT an option name — picocli would read it
 *       as a cluster (`-a` plus the rest as its value), that is a silently different meaning; see
 *       {@link #of(ConfigDefinition, String...)}.</li>
 *   <li><b>no defaults from picocli</b>: the default lives in the model (layer `COMPILED`), so
 *       injecting one here would pretend the value came from the CLI — and the origin would then lie
 *       about the layer.</li>
 * </ul>
 *
 * @since 1.0
 */
public final class CliSpec {

    /** Name of the engine-reserved option: a single dash, never derived from a field declaration. */
    public static final String STRICT_PARSER = "strict-config-parser";

    /**
     * The help of the ENGINE: `-help` — ONE dash, like `-strict-config-parser`. Without a help option of
     * its own, the engine would swallow nothing and the application would simply start — the worst
     * possible outcome, because the user asks for help and gets a run. The DOUBLE dash is left to the
     * MODEL, so a field named `help` is legal and reachable as `--help`; the two spellings cannot be
     * confused, because the usage line prints both. There is deliberately no short `-h`: our convention
     * is that short names are DECLARED (on a field or on an action), never guessed.
     */
    public static final String HELP = "help";

    /**
     * The engine options by their SINGLE-DASH names — the form only the engine occupies (see the dash
     * convention in the class comment). Used for the hint about a likely mistake: a bare `--help` (two
     * dashes) is what a user types for the help, while the engine's help is `-help`.
     */
    static final List<String> ENGINE_OPTIONS = List.of(HELP, STRICT_PARSER);

    /**
     * Actions reserved to the engine — bare words without a dash. `config-generate` is reserved right
     * away even though its implementation is still to come: the reservation is to mean "this is not a
     * configuration field", not "this already works".
     *
     * <p>`config-validation-with-layers` is a SEPARATE ACTION and not the `--layers` option: the
     * option name `--layers` belongs to the MODEL (the `layers` field has the right to exist in the
     * configuration), so the engine must not take it. An action name carries dashes and the canonical
     * form of a path does not allow them — a collision is impossible.
     */
    public static final List<String> ACTIONS =
        List.of("config-validation", "config-generate", "config-validation-with-layers");

    static final String LONG = "--";
    static final String SHORT = "-";

    private CliSpec() {
    }

    /**
     * Builds a spec from the application model. The program name is NOT set here — the library does not
     * know the name of its user, so it is set by whoever builds the command line: through the toolbox
     * that is {@code ConfigTool.program("my-tool")}.
     *
     * <p>The name must be given, because {@code CommandSpec.create()} hands out the placeholder
     * {@code <main class>} and a help whose first line reads {@code Usage: <main class> --port …} is
     * unusable: that line is exactly what the user copies into a script. The placeholder is a defect to
     * remove, not an answer to print — hence {@link #of(ConfigDefinition, String...)} does not fill it
     * with a name of its own (a made-up name would lie just as loudly).
     *
     * @param definition the application model; must not be {@code null}
     * @return a spec with one option per declared field, plus the engine options
     * @throws IllegalArgumentException when an option name or an alias collides with a reserved name
     *         or with another option — that is a MODEL error, so it is loud
     * @since 1.0
     */
    public static CommandSpec of(ConfigDefinition definition) {
        return of(definition, new String[0]);
    }

    /**
     * Builds a spec from the model plus **application actions**. An action is a BARE WORD, extensible
     * on the application side. Every action becomes a subcommand carrying a **copy of the same
     * options**, so `--port 8080 tui` and `tui --port 8080` mean exactly the same thing — the user
     * does not have to know where the action ends and configuration begins.
     *
     * <p>Engine-reserved names ({@link #ACTIONS}) and repetitions are a **model error**, just like
     * option collisions: an action `config-validation` declared by the application would mean the same
     * word has two meanings.
     *
     * @param definition the application model; must not be {@code null}
     * @param applicationActions names of the actions the application offers; bare words, must not be
     *        blank and must not repeat
     * @return a spec with the engine-reserved actions and the application actions as subcommands
     * @throws IllegalArgumentException when an action is blank, starts with a dash, takes a reserved
     *         name or is declared twice — that is a MODEL error, so it is loud
     * @since 1.0
     */
    public static CommandSpec of(ConfigDefinition definition, String... applicationActions) {
        CommandSpec spec = CommandSpec.create();
        // picocli reads `-action` as a CLUSTER of short options by default (`-a` plus the rest as its
        // value), so a declared alias `-a` would quietly capture such an argument — and that is a
        // silent change of meaning, not parsing. Our aliases are single characters and options
        // require a value, so clustering buys us nothing and we turn it off: `-action` stays
        // unrecognised (visible in `unmatched()`) instead of pretending to be something else.
        spec.parser().posixClusteredShortOptionsAllowed(false);
        Set<String> taken = new LinkedHashSet<>();
        Set<String> actionNames = new LinkedHashSet<>();
        // The engine's names are NOT pre-loaded into `taken`: `taken` holds what a FIELD/alias occupies,
        // and each of the two dash spaces has its own engine guard (`reserveLong`/`reserveShort`).
        // Pre-loading `help` here was exactly the defect that made a legal field `help` a model error.

        for (ConfigField field : definition.fields()) {
            spec.addOption(option(field, taken));
        }

        spec.addOption(helpOption());
        spec.addOption(strictParserOption());

        // Engine-reserved actions are subcommands EXACTLY like application actions — otherwise they
        // could not be invoked at all: the token would land in `unmatched()`, and `CliSource` rejects
        // every bare word as an unknown action. A message about an unknown action must not list them
        // as KNOWN while they cannot be called.
        for (String reserved : ACTIONS) {
            spec.addSubcommand(reserved, actionsSpec(definition, reserved, described(reserved)));
        }
        for (String action : applicationActions) {
            checkAction(action, actionNames);
            spec.addSubcommand(action, actionsSpec(definition, action, described(action)));
        }
        return spec;
    }

    /** The description of an action in help: reserved ones have it here, the application passes its own. */
    private static String described(String action) {
        return switch (action) {
            case "config-validation" ->
                "checks the configuration and prints a report — without running the application";
            case "config-generate" -> "prints a sample YAML file matching the model (to stdout)";
            case "config-validation-with-layers" ->
                "checks the configuration and prints the state AFTER EACH layer: what the layer brings in and what comes out of it";
            default -> "";
        };
    }

    /**
     * Action names **with descriptions** — for an application that wants its actions to read well in
     * `-help` (the metadata comes from one place; an action description is not a field declaration,
     * so the application supplies it).
     *
     * @param definition the application model; must not be {@code null}
     * @param applicationActions action name to description; the name is a bare word
     * @return a spec whose application actions carry those descriptions in their help
     * @since 1.0
     */
    public static CommandSpec of(ConfigDefinition definition, java.util.Map<String, String> applicationActions) {
        CommandSpec spec = of(definition, applicationActions.keySet().toArray(String[]::new));
        applicationActions.forEach((action, description) ->
            spec.subcommands().get(action).getCommandSpec().usageMessage().description(description));
        return spec;
    }

    /** A subcommand of an action: the same options as the root, so configuration works on BOTH sides. */
    private static CommandSpec actionsSpec(ConfigDefinition definition, String name, String description) {
        CommandSpec action = CommandSpec.create();
        action.name(name);
        if (!description.isEmpty()) {
            action.usageMessage().description(description);
        }
        action.parser().posixClusteredShortOptionsAllowed(false);
        Set<String> taken = new LinkedHashSet<>();
        // An action carries the SAME options as the root, so the same rule holds here: `taken` holds what
        // fields and aliases occupy, and the engine's names are guarded per dash space.
        for (ConfigField field : definition.fields()) {
            action.addOption(option(field, taken));
        }
        action.addOption(helpOption());
        action.addOption(strictParserOption());
        return action;
    }

    private static void checkAction(String action, Set<String> actionNames) {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("an action without a name — actions are BARE WORDS");
        }
        if (action.startsWith(SHORT)) {
            throw new IllegalArgumentException("action `" + action + "` starts with a dash — actions are"
                + " BARE WORDS, while `-" + action.replaceFirst("^-+", "") + "` sets configuration");
        }
        if (ACTIONS.contains(action)) {
            throw new IllegalArgumentException("the action name `" + action + "` is reserved to the engine"
                + " — the application cannot redefine it");
        }
        if (!actionNames.add(action)) {
            throw new IllegalArgumentException("action `" + action + "` declared twice");
        }
    }

    /** One way of building a field option — the root and action subcommands must give the SAME shape. */
    private static OptionSpec option(ConfigField field, List<String> names) {
        // picocli takes all names in ONE call (`names`) instead of adding aliases one by one
        return OptionSpec.builder(names.toArray(String[]::new))
            .type(String.class)
            .arity("1")
            .paramLabel("<" + field.type().name().toLowerCase(Locale.ROOT) + ">")
            .description(help(field))
            .build();
    }

    private static OptionSpec option(ConfigField field, Set<String> taken) {
        String name = optionName(field);
        reserveLong(name, "field `" + field.path().canonical() + "`", taken);
        List<String> names = new ArrayList<>();
        names.add(LONG + name);
        for (String alias : field.aliases()) {
            String shortName = alias.replaceFirst("^-+", "");
            reserveShort(shortName, "alias of field `" + field.path().canonical() + "`", taken);
            names.add(SHORT + shortName);
        }
        return option(field, names);
    }

    /** `-help` takes no value: its presence is the request for help (picocli recognises `usageHelp`). */
    private static OptionSpec helpOption() {
        return OptionSpec.builder(SHORT + HELP)
            .usageHelp(true)
            .description("prints this help and exits (does not run the application)")
            .build();
    }

    private static OptionSpec strictParserOption() {
        return OptionSpec.builder(SHORT + STRICT_PARSER)
            .type(String.class)
            .arity("1")
            .paramLabel("<true|false>")
            .description("Lenient mode of the configuration parser: warnings instead of an error.")
            .build();
    }

    /** `server.port` → `server-port`; the dot is a separator, so it becomes a dash. */
    static String optionName(ConfigField field) {
        return field.path().canonical().replace('.', '-');
    }

    /** Help from the model metadata: the literal `description`. Default values are never invented. */
    static String help(ConfigField field) {
        String description = field.description();
        return description == null || description.isBlank() ? "Field `" + field.path().canonical() + "`."
            : description;
    }

    /**
     * The guard of the LONG space (`--<dashless name>`) — the space the MODEL owns. One family of engine
     * names can still collide here for real: an action name (an action is a bare word, and `--tui` would read
     * as a setting). `help` and `strict-config-parser` are deliberately NOT guarded: the engine's forms are
     * `-help` and `-strict-config-parser` — ONE dash — so both double-dash spellings belong to a field of
     * that name, and the dash count is what tells the two spaces apart.
     */
    private static void reserveLong(String name, String what, Set<String> taken) {
        if (ACTIONS.contains(name)) {
            throw new IllegalArgumentException(what + " takes the action name `" + name
                + "` — actions are bare words, and `--" + name + "` cannot mean two things");
        }
        duplicate(name, what, taken);
    }

    /**
     * The guard of the SHORT space (one dash plus the name) — the space the engine and the DECLARED aliases
     * share. The engine holds `-help` and `-strict-config-parser` there, so an alias taking either name is a
     * model error (a shadowed engine option would silently mean something else).
     */
    private static void reserveShort(String name, String what, Set<String> taken) {
        if (ACTIONS.contains(name)) {
            throw new IllegalArgumentException(what + " takes the action name `" + name
                + "` — actions are bare words, and `--" + name + "` cannot mean two things");
        }
        if (HELP.equals(name)) {
            throw new IllegalArgumentException(what + " takes the reserved name `" + SHORT + HELP
                + "` — the engine always has it, because that is help; a field of that name keeps the"
                + " long form `" + LONG + HELP + "`");
        }
        if (STRICT_PARSER.equals(name)) {
            throw new IllegalArgumentException(what + " takes the reserved name `" + SHORT + STRICT_PARSER
                + "` — the engine always has it");
        }
        duplicate(name, what, taken);
    }

    private static void duplicate(String name, String what, Set<String> taken) {
        if (!taken.add(name)) {
            throw new IllegalArgumentException(what + " repeats the name `" + name + "` — CLI names"
                + " must be unambiguous, otherwise there is no way to tell which field the user set");
        }
    }
}
