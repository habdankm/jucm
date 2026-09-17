package dev.jucm.casciian;

import dev.jucm.core.ConfigConstraint;
import dev.jucm.core.ConfigDefinition;
import dev.jucm.core.ConfigDraft;
import dev.jucm.core.ConfigField;
import dev.jucm.core.ConfigPath;
import dev.jucm.core.ConfigPatch;
import dev.jucm.core.SourceDeclaration;
import dev.jucm.core.ConfigValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes what the configurator changed to a file.
 *
 * <p>What this writer does, and what it deliberately does not do:
 *
 * <ul>
 *   <li>it writes <strong>only the changes made in the session</strong> ({@link ConfigDraft#patch()}),
 *       never the whole state — the source file therefore stays the source of the STRUCTURE, and
 *       anything computed keeps being computed (writing a resolved {@code uri} would freeze the
 *       interpolation);</li>
 *   <li>it writes to a <strong>separate file</strong> ({@code application.applied.yaml} by default)
 *       rather than over the source file: the configurator does not know the whole source file
 *       (comments, keys outside the schema), so overwriting it would lose data. The application reads
 *       the source file and then applies this file on top;</li>
 *   <li>it writes every value <strong>quoted</strong>: the reader casts it to the type declared by the
 *       field anyway, and quoting removes a whole class of ambiguity ({@code 010}, {@code yes},
 *       {@code #}, {@code ${…}}).</li>
 * </ul>
 *
 * <p><strong>RULES ({@code CONST}) AND NOTATION ({@code TCONF}) TRAVEL WITH EVERY OUTPUT</strong> —
 * the same grammar as in YAML sources: a {@code CONST(…)} / {@code TCONF(…)} section in a comment on
 * the SAME LINE as the value. The statement is taken from the draft entry (a rule given in the
 * session) and, when the session gave none, from the field declaration (a rule from code). Without
 * this the written file would reproduce VALUES without the rules that bind them.
 *
 * <p>Out of scope, and named here so it is not taken for done: the full pre-filled file with
 * {@code description} comments, example values and declaration order is the job of the generator
 * ({@code config-generate}) — one generator, two inputs. This writer is the smallest correct
 * artifact: a file the application can read back to get the same state.
 *
 * @since 1.0
 */
public final class DraftWriter {

    private static final String HEADER = """
        # Written by the JUCM configurator (`tui` action) — SESSION CHANGES ONLY.
        # The source file (application.yaml) stays untouched; the application reads it, and then applies this one on top.
        # Values are quoted because the type comes from the field declaration, not from the file.
        # Rules and notation travel as markers in a comment on the SAME LINE: `# CONST(<chain>)`,
        # `# TCONF(<notation>)` — the reader picks them up (the same grammar as in every other source).
        """;

    private DraftWriter() {
    }

    /**
     * Writes the changes from a draft, ordered by address.
     *
     * <p>The order is by address, so the same state written twice produces a file that can be
     * compared line by line — the same rule as in {@link ConfigDraft#diff} and as in the row order of
     * the view.
     *
     * @param changes changes to write; must not be {@code null}
     * @param file target file; missing parent directories are created, an existing file is overwritten
     * @return the number of fields written; {@code 0} when the changes carry no field at all
     * @throws IOException if the file cannot be written — the caller is expected to report it, not to
     *         swallow it
     * @since 1.0
     */
    public static int write(ConfigPatch changes, Path file) throws IOException {
        List<ConfigPath> paths = new ArrayList<>(changes.paths());
        // Lines are ordered by address so that two writes of the same state can be compared.
        paths.sort(java.util.Comparator.comparing(ConfigPath::canonical));

        StringBuilder text = new StringBuilder(HEADER);
        for (ConfigPath path : paths) {
            ConfigPatch.SetValue set = (ConfigPatch.SetValue) changes.operation(path).orElseThrow();
            text.append(path.canonical()).append(": \"").append(escape(set.value().display())).append("\"");
            String markers = markers(set.declaration());
            if (!markers.isEmpty()) {
                text.append("   #").append(markers);
            }
            text.append('\n');
        }
        if (paths.isEmpty()) {
            text.append("# (no changes — the file is empty but present)\n");
        }
        writeText(text.toString(), file);
        return paths.size();
    }

    /**
     * Returns the whole configuration as an {@code sh} script, one argument per field — the script
     * counterpart of the YAML file.
     *
     * <p>The script is returned instead of printed because printing to stdout while the TUI runs is
     * useless: the screen is drawn in raw mode and the first repaint wipes it. A file survives, can be
     * executed and can be pasted into another script.
     *
     * @param definition declarations the fields come from; must not be {@code null}
     * @param draft draft whose resolved values are written; must not be {@code null}
     * @param program program name used as the command; must not be {@code null}
     * @return a {@code #!/bin/sh} script that ends with {@code "$@"}, so arguments added when running
     *         are passed on to the program
     * @since 1.0
     */
    public static String asScript(ConfigDefinition definition, ConfigDraft draft, String program) {
        return "#!/bin/sh\n"
            + "# Generated by the JUCM configurator: the whole configuration as arguments.\n"
            + "# The trailing `\"$@\"` passes on whatever you add when running this script.\n"
            + "exec " + asCommandLine(definition, draft, program) + " \"$@\"\n";
    }

    /**
     * Writes text to a file: the single place where a file is created (parent directories plus UTF-8).
     *
     * @param text content to write; must not be {@code null}
     * @param file target file; missing parent directories are created
     * @throws IOException if the file cannot be written
     * @since 1.0
     */
    public static void writeText(String text, Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    /**
     * Returns the whole configuration as YAML — the resolved view, so it is fit to be pasted as a file.
     *
     * <p>When a field was computed rather than written by hand, the comment says what it came from:
     * this is the only place where {@code uri = ...} and a value such as {@code http://host:port/api}
     * can be told apart.
     *
     * @param definition declarations the fields come from; must not be {@code null}
     * @param draft draft whose resolved values are written; must not be {@code null}
     * @return one line per field, ordered by address, each value quoted and followed by its rule and,
     *         for computed fields, by the value as written
     * @since 1.0
     */
    public static String asYaml(ConfigDefinition definition, ConfigDraft draft) {
        StringBuilder text = new StringBuilder(HEADER);
        for (ConfigField field : sorted(definition)) {
            ConfigPath path = field.path();
            String resolved = value(draft.snapshot(), path);
            text.append(path.canonical()).append(": \"").append(escape(resolved)).append("\"");
            // The rule comes from the SESSION and, when the session gave none, from the field
            // declaration. A comment starts with the sections; prose follows them, because the reader
            // takes the sections from the beginning of the comment.
            String rule = ruleInForce(definition, draft, path);
            String raw = draft.written(path);
            boolean note = !raw.equals(resolved);
            if (!rule.isEmpty() || note) {
                text.append("   #");
                if (!rule.isEmpty()) {
                    text.append(" CONST(").append(rule).append(")");
                }
                if (note) {
                    text.append(rule.isEmpty() ? " as written: " : " | as written: ").append(raw);
                }
            }
            text.append('\n');
        }
        return text.toString();
    }

    /**
     * Returns the whole configuration as a <strong>command line</strong> with {@code --} flags — fit to
     * be pasted next to {@code config-validation}.
     *
     * <p>Flag names are computed by the same rule as in the CLI source (canonical path, dots replaced
     * by dashes), so the printed line can be used as arguments of the same application — and that is
     * pinned by a test.
     *
     * @param definition declarations the fields come from; must not be {@code null}
     * @param draft draft whose resolved values are written; must not be {@code null}
     * @param program program name the line starts with; must not be {@code null}
     * @return the program name, then one {@code --flag value} per field ordered by address, then the
     *         session rule and notation of every field as {@code --flag--const} / {@code --flag--tconf}
     * @since 1.0
     */
    public static String asCommandLine(ConfigDefinition definition, ConfigDraft draft, String program) {
        StringBuilder line = new StringBuilder(program);
        for (ConfigField field : sorted(definition)) {
            line.append(" --").append(flag(field)).append(' ')
                .append(quoted(value(draft.snapshot(), field.path())));
        }
        // Rules spoken in the SESSION are appended as `--x--const` (notation as `--x--tconf`) — the
        // same set of markers as in the CLI source. The printed line has to reproduce exactly the
        // state you are looking at.
        for (ConfigField field : sorted(definition)) {
            sessionDeclaration(draft, field.path()).ifPresent(rule ->
                line.append(" --").append(flag(field)).append("--const ").append(quoted(rule)));
            sessionConfig(draft, field.path()).ifPresent(note ->
                line.append(" --").append(flag(field)).append("--tconf ").append(quoted(note)));
        }
        return line.toString();
    }

    /**
     * Returns the rule in force for a field: the one spoken by the session or the file and, when nobody
     * spoke it, the one declared by the field.
     *
     * <p>One place for the view's {@code CONST} value and for the writes: if the two drifted apart, the
     * rule you see would not be the rule that gets written.
     *
     * @param definition declarations queried for a declared rule; must not be {@code null}
     * @param draft draft queried for a spoken rule; must not be {@code null}
     * @param path field address; must not be {@code null}
     * @return the rule in force, or an empty string when neither the session nor the declaration has one
     * @since 1.0
     */
    public static String ruleInForce(ConfigDefinition definition, ConfigDraft draft, ConfigPath path) {
        return sessionDeclaration(draft, path)
            .orElseGet(() -> definition.field(path).map(DraftWriter::declaredRule).orElse(""));
    }

    /** Markers of a statement: {@code # TCONF(…) CONST(…)}, sections first in the comment. */
    private static String markers(SourceDeclaration declaration) {
        StringBuilder text = new StringBuilder();
        declaration.transformerConfig().ifPresent(config -> text.append(" TCONF(").append(config).append(")"));
        declaration.constraints().ifPresent(rule -> text.append(" CONST(").append(rule).append(")"));
        return text.toString();
    }

    /** Rule spoken for a field (session or file) — empty when nobody spoke one. */
    private static java.util.Optional<String> sessionDeclaration(ConfigDraft draft, ConfigPath path) {
        return draft.writtenDeclaration(path).flatMap(SourceDeclaration::constraints);
    }

    /** Notation spoken for a field (session or file, {@code TCONF}) — empty when nobody spoke one. */
    private static java.util.Optional<String> sessionConfig(ConfigDraft draft, ConfigPath path) {
        return draft.writtenDeclaration(path).flatMap(SourceDeclaration::transformerConfig);
    }

    /**
     * Rule taken from the field declaration. Several rules are joined with {@code &} because validation
     * requires ALL of them — and every element after the first MUST start with {@code &} or {@code |},
     * otherwise the chain is invalid.
     */
    private static String declaredRule(ConfigField field) {
        StringBuilder chain = new StringBuilder();
        for (ConfigConstraint constraint : field.constraints()) {
            if (chain.length() > 0) {
                chain.append('&');
            }
            chain.append(constraint instanceof ConfigConstraint.Const constant
                ? constant.chain() : constraint);
        }
        return chain.toString();
    }

    private static String flag(ConfigField field) {
        return field.path().canonical().replace('.', '-');
    }

    private static List<ConfigField> sorted(ConfigDefinition definition) {
        List<ConfigField> fields = new ArrayList<>(definition.fields());
        fields.sort(java.util.Comparator.comparing(field -> field.path().canonical()));
        return fields;
    }

    private static String value(dev.jucm.core.Config config, ConfigPath path) {
        return config.get(path) instanceof dev.jucm.core.ConfigResult.ValueResult<ConfigValue> found
            ? found.value().display()
            : "";
    }

    /** Quotes an argument only when quoting is needed — the line has to stay readable. */
    private static String quoted(String value) {
        return value.matches("[A-Za-z0-9._:/-]+") ? value : "'" + value.replace("'", "'\\''") + "'";
    }

    /** Puts a value in quotes: only the backslash and the quote itself need escaping. */
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
