package dev.jucm.casciian;

import casciian.TApplication;
import casciian.TButton;
import casciian.TField;
import casciian.TLabel;
import casciian.TWidget;
import casciian.TWindow;
import casciian.TComboBox;
import dev.jucm.core.ConfigConstraint;
import dev.jucm.core.ConfigDefinition;
import dev.jucm.core.ConfigDraft;
import dev.jucm.core.ConfigField;
import dev.jucm.core.ConfigPath;
import dev.jucm.core.ConfigResult;
import dev.jucm.core.ConfigString;
import dev.jucm.core.ConfigValidator;
import dev.jucm.core.SourceDeclaration;
import dev.jucm.core.ConfigValue;
import dev.jucm.core.Layer;
import dev.jucm.core.ConfigSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A terminal view over a {@link ConfigDraft}: one row per declared field, every edit written to the
 * working copy, and state reported by the same {@link ConfigValidator} the application runs at
 * startup.
 *
 * <p>Contract:
 *
 * <ol>
 *   <li><b>The view is built from the declaration.</b> Rows are the fields of the
 *       {@link ConfigDefinition} and captions come from {@link ConfigField#description()}, so the view
 *       holds no text of its own.</li>
 *   <li><b>Every edit goes through the model.</b> A widget calls its {@code updateAction}, this class
 *       writes the edit into {@link ConfigDraft#edit}, and only the fields named by the resulting
 *       change list are redrawn — including fields far from the cause, such as a {@code uri} that
 *       follows a {@code port}. No value is computed in the view.</li>
 *   <li><b>State comes from the validator.</b> The status line shows exactly the report startup
 *       validation would produce, so the two cannot disagree.</li>
 * </ol>
 *
 * <p>Setting the text of a field invokes its {@code updateAction} just like typing does, so redrawing
 * is fenced off from editing by {@code refreshing}: without that fence a refresh of one field would
 * write a patch on behalf of another.
 *
 * @since 1.0
 */
public final class Configurator {

    // Widths chosen so the default window fits in 80 columns: a window wider than the terminal cuts
    // the value column, which is the one column that always has to be visible.
    private static final int LABEL_WIDTH = 16;
    /** The caption gives up room FIRST — it is cheaper to shorten than a field the user types in. */
    private static final int MIN_LABEL_WIDTH = 10;
    /** Default width of the field-name column; the rest of the layout adapts to it. */
    private static final int SYMBOL_WIDTH = 30;
    private static final int VALUE_WIDTH = 26;
    private static final int ROW = 2;
    /** `type` column: a dictionary of known types. Read-only until a type can be chosen here. */
    private static final int TYPE_WIDTH = 10;
    /**
     * `defaults` column: the value DECLARED in the model, which is what you get when no source
     * supplies one. The `defaults` header sits above THIS column, not above the value column.
     */
    private static final int DEFAULTS_WIDTH = 14;
    /** `source` column: the layer that supplies the value shown in the `value` column. */
    private static final int SOURCE_WIDTH = 20;
    /** Gap between the value column and `CONST`; without it two fields merge into one run of text. */
    private static final int RULE_GAP = 2;
    /** Smallest sensible width of the `CONST` field; the rest comes from the WINDOW width. */
    private static final int MIN_RULE_WIDTH = 18;
    /** Smallest sensible width of the field-name column (`port = 8080`). */
    private static final int MIN_SYMBOL_WIDTH = 16;
    /** Columns derived from the window width: a maximised window has to use ALL of its width. */
    private int labelWidth;
    private int typeColumn;
    private int defaultsColumn;
    private int sourceColumn;
    private int symbolWidth;
    private int symbolColumn;
    private int valueColumn;
    private int ruleColumn;

    private final TApplication application;
    private final TWindow window;
    private final ConfigDefinition definition;
    private final ConfigDraft draft;
    private final Map<ConfigPath, TField> values = new LinkedHashMap<>();
    private final Map<ConfigPath, TLabel<?>> captions = new LinkedHashMap<>();
    private final Map<ConfigPath, TLabel<?>> symbols = new LinkedHashMap<>();
    private final Map<ConfigPath, TLabel<?>> sources = new LinkedHashMap<>();
    private final Map<ConfigPath, TLabel<?>> types = new LinkedHashMap<>();
    private final Map<ConfigPath, TField> rules = new LinkedHashMap<>();
    private final Map<ConfigPath, Integer> rows = new LinkedHashMap<>();
    /** Choice fields, present only where the rule in force says `in[…]`; all other rules use a text field. */
    private final Map<ConfigPath, TComboBox> choices = new LinkedHashMap<>();
    /** Rules INTRODUCED in this session: the model sees them on every redraw; an empty field clears one. */
    private final Map<ConfigPath, SourceDeclaration> applied = new LinkedHashMap<>();
    /**
     * What was LAST written into the `CONST` field. It distinguishes "the field holds our value" from
     * "the user is typing in it": a redraw only touches the first case.
     */
    private final Map<ConfigPath, String> shownRules = new LinkedHashMap<>();
    private final Targets targets;
    private final String program;
    private TField status;
    private boolean refreshing;

    private Configurator(TApplication application, TWindow window, ConfigDefinition definition,
                         ConfigDraft draft, Targets targets, String program) {
        this.application = application;
        this.window = window;
        this.definition = definition;
        this.draft = draft;
        this.targets = targets;
        this.program = program;
    }

    /**
     * Where the configurator writes: the session changes, the whole configuration and the script.
     * Each file is separate because each has a different role, and none of them overwrites
     * `application.yaml` — see {@link DraftWriter}.
     *
     * @param applied file the changes of this session are written to
     * @param resolved file the whole resolved configuration is written to
     * @param script file the command line is written to
     * @since 1.0
     */
    public record Targets(java.nio.file.Path applied, java.nio.file.Path resolved,
                          java.nio.file.Path script) {

        /**
         * Creates a target set.
         *
         * @param applied file the changes of this session are written to
         * @param resolved file the whole resolved configuration is written to
         * @param script file the command line is written to
         * @return a target set for those files
         * @since 1.0
         */
        public static Targets of(java.nio.file.Path applied, java.nio.file.Path resolved,
                                 java.nio.file.Path script) {
            return new Targets(applied, resolved, script);
        }

        /**
         * Returns the three files of the `application.*` family in the given directory:
         * `application.applied.yaml`, `application.resolved.yaml` and `application.sh`, so none of
         * them lands on top of `application.yaml`.
         *
         * @param directory directory the files are resolved against, normally the working directory
         * @return a target set rooted in that directory
         * @since 1.0
         */
        public static Targets in(java.nio.file.Path directory) {
            return new Targets(directory.resolve("application.applied.yaml"),
                directory.resolve("application.resolved.yaml"), directory.resolve("application.sh"));
        }
    }

    /**
     * Opens the configurator window: captions, the symbolic view, the resolved view, the status line
     * and the buttons, then refreshes it once.
     *
     * <p>The buttons do not print to the screen: anything written to stdout while the TUI owns the
     * terminal is swallowed by the next repaint, so each button writes a file and the status line
     * names that file.
     *
     * @param application TUI application that owns the window; must not be {@code null}
     * @param definition declaration the rows are built from; must not be {@code null}
     * @param draft working copy the edits are written to; must not be {@code null}
     * @param targets files written by `Save`, `YAML` and `CLI`
     * @param program program name used in the script and on the command line, such as `uri-app`;
     *        {@code null} means `app`
     * @return the open configurator, with focus on the first value cell
     * @throws NullPointerException if {@code application}, {@code definition} or {@code draft} is
     *         {@code null}
     * @since 1.0
     */
    public static Configurator open(TApplication application, ConfigDefinition definition, ConfigDraft draft,
                                    Targets targets, String program) {
        return open(application, definition, draft, targets, program, view -> { });
    }

    /**
     * Same as {@link #open(TApplication, ConfigDefinition, ConfigDraft, Targets, String)}, plus an
     * extension point: {@code extension} receives the view AFTER the window has been built and BEFORE
     * the event loop starts, so additional widgets are usable in the same session.
     *
     * <p>{@code extension} receives:
     *
     * <ul>
     *   <li>{@link #window()} — where to add widgets, through the {@code addField} / {@code addButton}
     *       / {@code addText} family of {@code TWidget};</li>
     *   <li>{@link #freeRow()} — the first free row below this layout, which is the only place that
     *       does not collide with the rows built here;</li>
     *   <li>{@link #config()} — the RESOLVED state, which is what the application actually receives;
     *       the view is a projection of the model, so values are read from the model, not from a
     *       widget;</li>
     *   <li>{@link #draft()} — the working copy, when a widget has to CHANGE the configuration;</li>
     *   <li>{@link #edit(ConfigPath, String)} and {@link #refresh()} — to keep the view consistent
     *       after such a change;</li>
     *   <li>{@link #application()} — for {@code invokeLater}, {@code addTimer} and dialogs, and
     *       {@link #statusMessage(String)} — where a background widget reports its result.</li>
     * </ul>
     *
     * @param application TUI application that owns the window; must not be {@code null}
     * @param definition declaration the rows are built from; must not be {@code null}
     * @param draft working copy the edits are written to; must not be {@code null}
     * @param targets files written by `Save`, `YAML` and `CLI`
     * @param program program name used in the script and on the command line; {@code null} means `app`
     * @param extension called once with the built view, before the first repaint
     * @return the open configurator, with focus on the first value cell
     * @throws NullPointerException if {@code application}, {@code definition} or {@code draft} is
     *         {@code null}
     * @since 1.0
     */
    public static Configurator open(TApplication application, ConfigDefinition definition, ConfigDraft draft,
                                    Targets targets, String program, java.util.function.Consumer<Configurator> extension) {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(draft, "draft");

        int height = ROW * (definition.fields().size() + 3) + 8;
        ClosingWindow window = new ClosingWindow(application, "configurator (JUCM)", 1, 1,
            LABEL_WIDTH + TYPE_WIDTH + SYMBOL_WIDTH + VALUE_WIDTH + SOURCE_WIDTH + 2 * RULE_GAP
                + MIN_RULE_WIDTH + 8,
            Math.min(height, 40));   // start size only — `maximize()` takes the whole screen
        // A maximised window is required, not cosmetic: in a narrower window the `defaults` and
        // `CONST` columns would end mid-screen and lose their content.
        window.maximize();
        Configurator configurator = new Configurator(application, window, definition, draft, targets,
            program == null ? "app" : program);
        configurator.build();
        extension.accept(configurator);
        configurator.repaint();
        return configurator;
    }

    /**
     * Computes the column layout from the window WIDTH: a maximised window has the whole screen, so
     * the field-name column and the `CONST` field take the space instead of stopping mid-screen.
     * Priority is deliberate: EDITABLE fields (value, `CONST`) keep their minimum, while the
     * field-name column may shrink — it is only there to be read.
     */
    private void layout() {
        Columns columns = columnsFor(window.getWidth());
        labelWidth = columns.label();
        symbolWidth = columns.symbol();
        typeColumn = columns.type();
        defaultsColumn = columns.defaults();
        sourceColumn = columns.source();
        symbolColumn = columns.symbolAt();
        valueColumn = columns.value();
        ruleColumn = columns.rule();
    }

    /** Column geometry of a field row: {label, symbol, type, defaults, symbolAt, value, source, rule, ruleWidth}. */
    record Columns(int label, int symbol, int type, int defaults, int symbolAt, int value,
                   int source, int rule, int ruleWidth) {
    }

    /**
     * Columns for a given window width — a PURE function, so the layout can be asserted without a
     * screen and without a window.
     *
     * <p>Priority is deliberate: the editable fields (value, `CONST`) keep their minimum, the
     * field-name column grows up to 30, and the caption gives up whatever is left.
     *
     * @param windowWidth width of the window in columns
     * @return the column geometry for that width
     */
    static Columns columnsFor(int windowWidth) {
        int fixed = 2 + TYPE_WIDTH + DEFAULTS_WIDTH + VALUE_WIDTH + SOURCE_WIDTH + 2 * RULE_GAP
            + MIN_RULE_WIDTH + MIN_SYMBOL_WIDTH + 3;
        int spare = windowWidth - fixed;
        int label = Math.max(MIN_LABEL_WIDTH, Math.min(LABEL_WIDTH, spare - SYMBOL_WIDTH));
        int symbol = Math.max(MIN_SYMBOL_WIDTH, Math.min(SYMBOL_WIDTH, spare - label));
        int type = 2 + label;
        int defaults = type + TYPE_WIDTH;
        int symbolAt = defaults + DEFAULTS_WIDTH;
        int value = symbolAt + symbol;
        int source = value + VALUE_WIDTH + RULE_GAP;
        int rule = source + SOURCE_WIDTH + RULE_GAP;
        int ruleWidth = Math.max(MIN_RULE_WIDTH, windowWidth - rule - 3);
        return new Columns(label, symbol, type, defaults, symbolAt, value, source, rule, ruleWidth);
    }

    /**
     * Returns the value DECLARED in the model: what applies when no source supplies one.
     *
     * @param path field path
     * @return the declared default, or {@code [none]} when the field has no default value — a value
     *         computed by a transformer, for instance
     * @since 1.0
     */
    public String defaultText(ConfigPath path) {
        return definition.field(path).flatMap(ConfigField::defaultValue)
            .map(ConfigValue::display).orElse("[none]");
    }

    /**
     * Returns the column the `defaults` header sits above.
     *
     * @return the absolute x of the `defaults` column
     * @since 1.0
     */
    public int defaultsColumn() {
        return defaultsColumn;
    }

    /**
     * Returns the column holding the resolved value.
     *
     * @return the absolute x of the value column
     * @since 1.0
     */
    public int valueColumn() {
        return valueColumn;
    }

    /**
     * Returns the width of the caption column, which is the first to give up room in a narrow window.
     *
     * @return the visible width of the caption column
     * @since 1.0
     */
    public int labelWidth() {
        return labelWidth;
    }

    /**
     * Returns the width of the field-name column.
     *
     * @return the visible width of the field-name column
     * @since 1.0
     */
    public int symbolWidth() {
        return symbolWidth;
    }

    /**
     * Returns the width of the window, which is what the editable fields size themselves against.
     *
     * @return the window width; after {@code maximize()} this is the screen width
     * @since 1.0
     */
    public int windowWidth() {
        return window.getWidth();
    }

    /**
     * Returns the width of the `CONST` field: whatever is left of the window to the right of it, never
     * below {@code MIN_RULE_WIDTH}.
     *
     * @return the visible width of the rule field
     * @since 1.0
     */
    public int ruleWidth() {
        return Math.max(MIN_RULE_WIDTH, window.getWidth() - ruleColumn - 3);
    }

    /**
     * Returns the gap between the value cell and the `CONST` field, which keeps the two visually
     * separate.
     *
     * @return the number of columns between the two
     * @since 1.0
     */
    public int gapBetweenValueAndRule() {
        return ruleColumn - (valueColumn + VALUE_WIDTH);
    }

    private void build() {
        layout();
        // Constructing a TComboBox activates its field, so after building, focus would sit on the last
        // selector. It is set where it belongs: on the FIRST value cell.
        // ROW ORDER IS PART OF THE CONTRACT: the model does not guarantee field order (`fields()` is an
        // immutable collection, so iteration order varies per JVM run), while the view has to redraw
        // reproducibly. Fields are sorted by canonical path, exactly like `ConfigDraft.diff`.
        List<ConfigField> fields = new ArrayList<>(definition.fields());
        fields.sort(Comparator.comparing(field -> field.path().canonical()));
        int row = 1;
        for (ConfigField field : fields) {
            // `fixed = FALSE` is not cosmetic, it prevents silent data loss: with `fixed = true`,
            // `TField.setText` TRUNCATES the text to the width of the field
            // (`text.substring(0, textAreaWidth())`), so a long interpolated value (a `uri` with a long
            // host) loses its tail, and editing such a field would operate on what is VISIBLE rather
            // than on what it is. With `false` the field holds the whole value and only scrolls.
            TLabel<TWidget> symbol = window.addLabel("", symbolColumn, row);
            symbols.put(field.path(), symbol);
            rows.put(field.path(), row);
            buildValueCell(field.path(), "");
            buildChoiceCell(field.path());
            // `addLabel(text, x, y, field)` binds the label to a field and returns `TLabel<F>`;
            // `addLabelFor(...)` returns the WIDGET (type F), not the label.
            TLabel<TField> caption = window.addLabel(help(field), 2, row, values.get(field.path()));
            captions.put(field.path(), caption);

            // The type hangs beside the field as a value from the dictionary of known types. Read-only:
            // changing the type changes the schema, and the schema of 1.0 is code.
            TLabel<TWidget> type = window.addLabel(typeName(field), typeColumn, row);
            window.addLabel(fit(defaultText(field.path()), DEFAULTS_WIDTH), defaultsColumn, row);
            sources.put(field.path(), window.addLabel(fit(sourceText(field.path()), SOURCE_WIDTH),
                sourceColumn, row));
            types.put(field.path(), type);

            // `CONST`: the same chain of conditions as `___CONST` / `--x--const` / a `CONST(…)` section
            // in a YAML file — one chain in one text. Confirming (enter) puts it into the model; an
            // empty field clears the rule of this field, restoring the declared rules.
            TField rule = window.addField(ruleColumn, row, ruleWidth(), false, "", new casciian.TAction() {
                @Override
                public void DO() {
                    applyRule(field.path());
                }
            }, new casciian.TAction() {
                @Override
                public void DO() {
                    ruleEdited(field.path());
                }
            });
            rules.put(field.path(), rule);
            shownRules.put(field.path(), "");
            row += ROW;
        }
        // The status field does not truncate either: when it is longer than the column, it scrolls
        // instead of losing the tail of the message.
        // Column headers: `defaults` above the value column (the value the application receives when no
        // source supplies one), `type` above the dictionary, `CONST` above the rule field.
        window.addLabel("type", typeColumn, 0);
        window.addLabel("defaults", defaultsColumn, 0);
        window.addLabel("value", valueColumn, 0);
        window.addLabel("source", sourceColumn, 0);
        window.addLabel("CONST", ruleColumn, 0);
        status = window.addField(2, row, window.getWidth() - 4, false, "", null, null);
        window.addButton("Reset", 2, row + ROW, () -> {
            draft.reset();
            applied.clear();
            for (TField rule : rules.values()) {
                rule.setText("");
            }
            show(draft.snapshot(), null, null);
            repaint();
        });
        // Buttons in one row, without overlapping. Each writes a FILE — output to stdout while the TUI
        // owns the terminal is swallowed by the next repaint, so it would never be read.
        window.addButton("YAML", 2 + LABEL_WIDTH, row + ROW,
            () -> write(DraftWriter.asYaml(definition, draft), targets.resolved()));
        window.addButton("CLI", 2 + LABEL_WIDTH + 8, row + ROW,
            () -> write(DraftWriter.asScript(definition, draft, program), targets.script()));
        window.addButton("Save", 2 + LABEL_WIDTH + 14, row + ROW, this::save);
        // `Cancel` discards the changes and EXITS. Closing the window alone would leave the process
        // without a window, because the configurator owns the whole screen rather than being one view
        // among several.
        window.addButton("Cancel", 2 + LABEL_WIDTH + 22, row + ROW, application::exit);
        // A legend, not decoration: the value list opens with `Alt+↓` or by clicking the arrow, so
        // without this label there is nothing to suggest that a choice exists.
        window.addLabel("Alt+\u2193 = value list (rule `in[…]`)", 2 + LABEL_WIDTH + 32, row + ROW);
        refresh();
        // FOCUS ON THE FIRST CELL: constructing a selector activates its field, so without this the
        // last selector would end up selected.
        for (ConfigField field : fields) {
            TWidget first = choices.get(field.path()).isVisible()
                ? choices.get(field.path()) : values.get(field.path());
            if (first.isEnabled()) {
                first.activate();
                break;
            }
        }
    }

    /**
     * Repaints the whole screen after a button action.
     *
     * <p>Anything printed to the terminal while the TUI runs corrupts the screen, because the TUI
     * draws in raw mode. Cascian exposes {@code TApplication.doRepaint()} for this; it is invoked
     * through {@code invokeLater} so that it runs on the application thread and not on the event
     * thread.
     *
     * @since 1.0
     */
    public void repaint() {
        application.invokeLater(application::doRepaint);
    }

    /** Writes a text file and names it in the status line, so the destination is never left implicit. */
    private void write(String text, java.nio.file.Path file) {
        try {
            DraftWriter.writeText(text, file);
            status.setText("written -> " + file.toAbsolutePath());
        } catch (java.io.IOException failure) {
            status.setText("not written: " + failure.getMessage());
        }
        repaint();
    }

    /**
     * Writes the changes of this session to {@link Targets#applied()} and reports the outcome in the
     * status line, including the ABSOLUTE path so the file can be found without guessing the working
     * directory.
     *
     * <p>A failed write is reported, not thrown: the error message lands in the status line.
     *
     * @since 1.0
     */
    public void save() {
        try {
            int written = DraftWriter.write(draft.patch(), targets.applied());
            status.setText("wrote " + written + " field(s) -> " + targets.applied().toAbsolutePath());
        } catch (java.io.IOException failure) {
            status.setText("not written: " + failure.getMessage());
        }
        repaint();
    }

    /**
     * Redraws the whole view from the model: values come from the resolution flow, not from the
     * widgets.
     *
     * @since 1.0
     */
    public void refresh() {
        show(draft.snapshot(), null, null);
    }

    /**
     * Writes an edit into the working copy and redraws exactly the fields named by the resulting
     * change list, which may include fields other than the edited one.
     *
     * <p>This is the same path a widget takes through its {@code updateAction}, which is why it can be
     * called from a test without synthesising keystrokes. Each edit also restates the source
     * declaration of the field, including its `CONST` rule: writing a value REPLACES the whole entry,
     * so a value edit without the rule would keep the rule on screen only.
     *
     * @param path field path being edited
     * @param text text written by the user, stored verbatim
     * @return the changes the edit produced
     * @since 1.0
     */
    public List<ConfigDraft.Change> edit(ConfigPath path, String text) {
        List<ConfigDraft.Change> changes = draft.edit(path, ConfigString.of(text),
            ConfigSource.of(Layer.TUI, path.canonical()),
            applied.getOrDefault(path, SourceDeclaration.none()));
        show(draft.snapshot(), changes, path);
        return changes;
    }

    /**
     * Confirms a cell (`enterAction`): only now does the cell show what the model resolved.
     *
     * <p>While the user types, the field holds the RAW text: showing the resolved value on each
     * keystroke would turn `010` into `8` after the first character and no further digit could be
     * appended.
     *
     * @param path field path being confirmed
     * @since 1.0
     */
    public void enter(ConfigPath path) {
        if (values.containsKey(path) || choices.containsKey(path)) {
            edit(path, valueText(path));
        }
        show(draft.snapshot(), null, null);
    }

    /**
     * Puts the text of the `CONST` field into the model.
     *
     * <p>{@link DraftWriter} and validation see it as any other source declaration: a rule from a
     * source REPLACES the declared rules, and an empty field restores them. When the field has no
     * resolved value there is nothing to constrain, and when the chain does not parse it does NOT
     * enter the model — both cases are reported in the status line rather than applied silently.
     *
     * @param path field path whose `CONST` field is confirmed
     * @since 1.0
     */
    public void applyRule(ConfigPath path) {
        TField widget = rules.get(path);
        if (widget == null) {
            return;
        }
        String typed = widget.getText() == null ? "" : widget.getText().strip();
        ConfigResult<ConfigValue> current = config().get(path);
        if (!(current instanceof ConfigResult.ValueResult<ConfigValue> present)) {
            status.setText("rule without a value — set the value of field `" + path.canonical() + "` first");
            repaint();
            return;
        }
        try {
            if (typed.isEmpty()) {
                applied.remove(path);
            } else {
                applied.put(path, SourceDeclaration.constraints(typed));
            }
        } catch (IllegalArgumentException failure) {
            status.setText("invalid rule: " + failure.getMessage());
            repaint();
            return;
        }
        draft.edit(path, present.value(), ConfigSource.of(Layer.TUI, path.canonical()),
            applied.getOrDefault(path, SourceDeclaration.none()));
        shownRules.put(path, typed);   // the field holds OUR value now — a redraw may touch it
        java.util.List<String> allowed = allowedValues(path);
        // `show` sets the status line to the validation state, so our message has to come AFTER it,
        // otherwise it would disappear in the same pass.
        show(draft.snapshot(), null, null);
        String rule = typed.isEmpty()
            ? "rule: from declarations (cleared on `" + path.canonical() + "`)"
            : "rule `" + path.canonical() + "`: " + typed
                + (allowed.isEmpty() ? "" : " | allowed: " + String.join(", ", allowed) + " (Alt+\u2193)");
        status.setText(rule + " — " + statusFor(draft.report()));
        repaint();
    }

    /**
     * Returns the type of a field, taken from the dictionary of known types.
     *
     * @param path field path
     * @return the lower-cased type name, or {@code -} when the definition has no such field
     * @since 1.0
     */
    public String typeName(ConfigPath path) {
        return definition.field(path).map(Configurator::typeName).orElse("-");
    }

    private static String typeName(ConfigField field) {
        return field.type().name().toLowerCase(java.util.Locale.ROOT);
    }

    /** The text field of a value cell — created in one place, so both cells are built the same way. */
    private void buildValueCell(ConfigPath path, String current) {
        TField field = window.addField(valueColumn, rows.get(path), VALUE_WIDTH, false, "",
            new casciian.TAction() {
                @Override
                public void DO() {
                    enter(path);
                }
            }, new casciian.TAction() {
                @Override
                public void DO() {
                    edited(path);
                }
            });
        if (!current.isEmpty()) {
            field.setText(current);
        }
        values.put(path, field);
    }

    /**
     * Creates the choice cell ONCE, in row order, beside the text field and at the same coordinates.
     *
     * <p>Both widgets of a cell must exist from the start: {@code TWidget.switchWidget} (TAB handling)
     * walks the {@code tabOrder} and SKIPS widgets with {@code enabled == false}, and {@code tabOrder}
     * is the order of addition — a widget created later, for instance at the first redraw, would land
     * after every other field.
     *
     * <p>This uses the {@code TComboBox} constructor that takes
     * {@code (…, values, valuesIndex, maxValuesHeight, limitToListValue, updateAction)} with
     * {@code limitToListValue = false}. The overload without that argument sets it to {@code true},
     * which DISABLES the field inside the combo and activates it at construction, so the cell could
     * neither be reached with TAB nor given a value. With {@code false} the field is live and the
     * choice only advises — the rule decides.
     */
    private void buildChoiceCell(ConfigPath path) {
        TComboBox combo = new TComboBox(window, valueColumn, rows.get(path), VALUE_WIDTH,
            java.util.List.of(), -1, 8, false, new casciian.TAction() {
                @Override
                public void DO() {
                    enter(path);
                }
            });
        combo.setVisible(false);
        combo.setEnabled(false);   // disabled means TAB does not see it; hiding alone is not enough
        choices.put(path, combo);
    }

    /**
     * Makes the value cell a CHOICE LIST when the rule in force says `in[…]`, and a text field in every
     * other case: `!in[…]` accepts any other text, so a list there would be a lie.
     *
     * <p>The widget is re-decided on EVERY redraw, because `CONST` can change while the application
     * runs and the view has to switch with it rather than keep the widget chosen at build time.
     *
     * <p>Exactly one of the two widgets is visible and enabled; that is the whole decision.
     * {@code TWidget.setEnabled(false)} moves the focus to the FIRST enabled widget of the window, so
     * it is called only when the state really changes, and after such a change the focus is returned
     * to where it stood.
     */
    private void syncValueCell(ConfigPath path) {
        List<String> allowed = allowedValues(path);
        boolean picker = !allowed.isEmpty();
        TField text = values.get(path);
        TComboBox combo = choices.get(path);
        if (text == null || combo == null) {
            return;
        }
        String current = text.isVisible() ? text.getText() : combo.getText();
        if (current == null || current.isEmpty()) {
            current = text(draft.snapshot(), path);
        }
        if (picker && !combo.getList().equals(allowed)) {
            combo.setList(allowed);
        }
        if (!current.isEmpty()) {
            if (picker) {
                combo.setText(current);
            } else {
                text.setText(current);
            }
        }
        boolean switched = text.isEnabled() != !picker || combo.isEnabled() != picker;
        TWidget wasActive = window.getActiveChild();
        text.setVisible(!picker);
        combo.setVisible(picker);
        if (text.isEnabled() != !picker) {
            text.setEnabled(!picker);
        }
        if (combo.isEnabled() != picker) {
            combo.setEnabled(picker);
        }
        if (switched) {
            TWidget cell = picker ? combo : text;
            if (wasActive == text || wasActive == combo) {
                cell.activate();      // the caret stood in this cell — hand it to the new widget
            } else if (wasActive != null && wasActive.isEnabled()) {
                wasActive.activate();
            }
        }
    }

    /**
     * Returns the values allowed by the `in[…]` rule in force, taken from the CONSTRAINT rather than
     * from the text of the field.
     *
     * @param path field path
     * @return the allowed values, or an empty list when there is nothing to choose from: no rule,
     *         `!in[…]`, an order, a regular expression, or a chain that does not parse
     * @since 1.0
     */
    public List<String> allowedValues(ConfigPath path) {
        String rule = ruleInForce(path);
        if (rule.isEmpty()) {
            return List.of();
        }
        try {
            return new ConfigConstraint.Const(rule).allowedValues().orElse(List.of());
        } catch (IllegalArgumentException broken) {
            return List.of();   // an unparsable chain is not in force at all
        }
    }

    /**
     * Returns the choice widget of a value cell.
     *
     * @param path field path
     * @return the choice widget, or {@code null} when the cell holds a plain text field
     * @since 1.0
     */
    public TComboBox choices(ConfigPath path) {
        return choices.get(path);
    }

    /**
     * Returns the text of a value cell, read from the widget that is actually the cell: the choice
     * list when it is visible, the text field otherwise. An invisible selector lies beside the cell and
     * has nothing to say.
     *
     * @param path field path
     * @return the text of the cell, never {@code null}; empty when the field is not in the view
     * @since 1.0
     */
    public String valueText(ConfigPath path) {
        TComboBox combo = choices.get(path);
        if (combo != null && combo.isVisible()) {
            return combo.getText() == null ? "" : combo.getText();
        }
        TField field = values.get(path);
        return field == null || field.getText() == null ? "" : field.getText();
    }

    private void setValueText(ConfigPath path, String text) {
        TComboBox combo = choices.get(path);
        if (combo != null && combo.isVisible()) {
            if (!text.equals(combo.getText())) {
                combo.setText(text);
            }
            return;
        }
        TField field = values.get(path);
        if (field != null) {
            field.setText(text);
        }
    }

    /** Fields in row order (by canonical path) — the same order {@code build} uses. */
    private List<ConfigField> sortedFields() {
        List<ConfigField> fields = new ArrayList<>(definition.fields());
        fields.sort(java.util.Comparator.comparing(field -> field.path().canonical()));
        return fields;
    }

    /**
     * Returns the rule in force for a field: the one a source declared (this session or a file), and
     * when no source declared one, the constraints of the declaration.
     *
     * @param path field path
     * @return the rule in force, or an empty string when the field has none
     * @since 1.0
     */
    public String ruleInForce(ConfigPath path) {
        return DraftWriter.ruleInForce(definition, draft, path);
    }

    /**
     * Takes the text of the `CONST` field into the model ON EACH CHARACTER, but only when the chain
     * parses. This is the path the field takes on every keystroke (and which a test may call directly,
     * like {@link #edit}).
     *
     * <p>A chain that does not parse does NOT enter the model, so a rule waiting for a confirmation
     * never looks as if it were in force: the status line then says which rule actually applies.
     *
     * @param path field path whose `CONST` field is being typed in
     * @since 1.0
     */
    public void ruleEdited(ConfigPath path) {
        String typed = ruleText(path);
        String inForce = applied.containsKey(path) ? applied.get(path).constraints().orElse("") : "";
        if (typed.equals(inForce)) {
            return;   // nothing changed — there is nothing to apply
        }
        if (!typed.isEmpty()) {
            try {
                SourceDeclaration.constraints(typed);   // the same parser as for `___CONST` and YAML
            } catch (IllegalArgumentException failure) {
                status.setText("invalid rule: " + failure.getMessage()
                    + " | still in force: "
                    + (inForce.isEmpty() ? "rules from declarations" : "`" + inForce + "`"));
                repaint();
                return;
            }
        }
        applyRule(path);
    }

    /**
     * Returns the text of the `CONST` field of a field, stripped.
     *
     * @param path field path
     * @return the text of the rule field, or an empty string when there is no such field
     * @since 1.0
     */
    public String ruleText(ConfigPath path) {
        TField widget = rules.get(path);
        return widget == null || widget.getText() == null ? "" : widget.getText().strip();
    }

    /**
     * Returns the `CONST` widget of a field.
     *
     * @param path field path
     * @return the rule widget, or {@code null} when the field is not in the view
     * @since 1.0
     */
    public TField rule(ConfigPath path) {
        return rules.get(path);
    }

    /** The widget-side edit path: read the cell and go through {@link #edit}. Fenced by {@code refreshing}. */
    private void edited(ConfigPath path) {
        if (refreshing) {
            return;
        }
        if (values.containsKey(path) || choices.containsKey(path)) {
            edit(path, valueText(path));
        }
    }

    /**
     * Projects a snapshot onto the view: without a change list everything is redrawn (start, `reset`),
     * with one only the named fields are. Fenced by {@code refreshing} so that a redraw never counts as
     * an edit.
     */
    private void show(dev.jucm.core.Config snapshot, List<ConfigDraft.Change> changes, ConfigPath edited) {
        refreshing = true;
        try {
            for (ConfigField field : sortedFields()) {
                ConfigPath path = field.path();
                // Which widget belongs in the value cell right now: the text field or the choice list.
                syncValueCell(path);
                // AN EDITED FIELD IS NOT REWRITTEN. `TField.setText` sets `position = 0`, so rewriting
                // a field on every keystroke pushes the caret back to the start: the next character
                // lands BEFORE the previous one and "hewm" becomes "mhwe". The model shows the value of
                // this field only once it is confirmed (`enter`), never while it is being typed.
                // The `source` column DOES refresh, also for the field being typed in: a label has no
                // caret, so `setLabel` cannot break it, and it shows at once which layer supplies the
                // value.
                setSource(path);
                if (path.equals(edited)) {
                    // the symbolic view shows the RAW text while typing as well — a label has no
                    // caret, so `setText` is not a trap here
                    setSymbol(path, valueText(path));
                    continue;
                }
                boolean wanted = changes == null || changes.stream()
                    .anyMatch(change -> change.path().equals(path));
                if (wanted) {
                    setValueText(path, text(snapshot, path));
                    setSymbol(path, draft.written(path));
                }
            }
            // The `CONST` field is redrawn ONLY while it holds the value we wrote into it ourselves.
            // A rule typed by the user, not yet applied, must not be overwritten by a redraw; and a rule
            // coming from the declaration or from a file (`# CONST(in[8080,443])` in YAML) has to be
            // shown at start. When the user is typing, the field text differs from ours and is left
            // alone.
            for (Map.Entry<ConfigPath, TField> entry : rules.entrySet()) {
                ConfigPath path = entry.getKey();
                String inForce = ruleInForce(path);
                if (entry.getValue().getText().equals(shownRules.get(path))
                    && !inForce.equals(entry.getValue().getText())) {
                    entry.getValue().setText(inForce);
                    shownRules.put(path, inForce);
                }
            }
            ConfigValidator.Report report = draft.report();
            status.setText(statusFor(report));
        } finally {
            refreshing = false;
        }
    }

    /**
     * Builds the status line: the first problem with its message and the source it came from, plus how
     * many further problems there are. The line states why the state is not valid, not merely that it
     * is not.
     */
    private String statusFor(ConfigValidator.Report report) {
        if (report.errors().isEmpty()) {
            return "status: ok" + (draft.isDirty() ? " (changes pending)" : "");
        }
        java.util.List<dev.jucm.core.ConfigError> errors = report.errors();
        dev.jucm.core.ConfigError first = errors.get(0);
        StringBuilder text = new StringBuilder("status: ").append(errors.size()).append(" problem(s) — ")
            .append(first.message());
        // WHERE: a message names the rule that was not met but not where to look. The source of the
        // entry carries its own name (`TUI:port`, `ENV:URIAPP__PORT`, `YAML:file:line`), which is the
        // missing half; it is called a source because for ENV it is a variable, not a field path.
        dev.jucm.core.ConfigSource source = first.source();
        if (source != null) {
            text.append(" [source ").append(source.display()).append(']');
        }
        if (errors.size() > 1) {
            text.append(" (+").append(errors.size() - 1).append(" more)");
        }
        return text.toString();
    }

    /**
     * Returns the symbolic view of a field: the RAW text written into the patch, and when nothing was
     * written, the declared default.
     *
     * @param path field path
     * @return e.g. {@code server.port = 8080}, or {@code server.port = [none]} when neither a patch nor
     *         a default supplies a value
     * @since 1.0
     */
    public String symbolic(ConfigPath path) {
        return draft.patch().operation(path)
            .map(operation -> ((dev.jucm.core.ConfigPatch.SetValue) operation).value().display())
            .map(text -> path.canonical() + " = " + text)
            .orElse(path.canonical() + " = " + (declared(path) == null ? "[none]" : declared(path)));
    }

    private String declared(ConfigPath path) {
        for (ConfigField field : definition.fields()) {
            if (field.path().equals(path)) {
                return field.defaultValue()
                    .map(ConfigValue::display)
                    .orElse(null);
            }
        }
        return null;
    }

    /**
     * Returns the source of the value shown in the `value` column: the layer and the place within it,
     * or {@code [none]} when the value came from the declaration without a source, as happens for a
     * value computed by a transformer.
     *
     * @param path field path
     * @return the printable source, for example {@code YAML:application.yaml:12}, or {@code [none]}
     * @since 1.0
     */
    public String sourceText(ConfigPath path) {
        return draft.snapshot().entry(path)
            .map(entry -> entry.source().display())
            .orElse("[none]");
    }

    private void setSource(ConfigPath path) {
        TLabel<?> label = sources.get(path);
        if (label != null) {
            label.setLabel(fit(sourceText(path), SOURCE_WIDTH));
        }
    }

    /**
     * Returns the column the `source` header sits above.
     *
     * @return the absolute x of the `source` column
     * @since 1.0
     */
    public int sourceColumn() {
        return sourceColumn;
    }

    /** The value as the application sees it; an empty string when the path has no value, never a throw. */
    private static String text(dev.jucm.core.Config snapshot, ConfigPath path) {
        return snapshot.get(path) instanceof ConfigResult.ValueResult<ConfigValue> value
            ? value.value().display()
            : "";
    }

    /**
     * Returns the caption of a field, taken from the declaration, which is the only source of text in
     * the view.
     *
     * <p>It is clipped to the caption column: casciian gives a label the width of its TEXT
     * ({@code TLabel.setLabel} sets {@code width} from the length of the string), so a longer
     * description would overrun the value field. The full description is not lost — the CLI option help
     * reads the same declaration.
     */
    private static String help(ConfigField field) {
        String description = field.description();
        String text = description == null || description.isBlank() ? field.path().canonical() : description;
        return fit(text, LABEL_WIDTH);   // the caption is clipped to the MAXIMUM, never the window width
    }

    /** A label does not know its column (its width comes from the text), so text is clipped on write. */
    private static String fit(String text, int width) {
        return text.length() <= width ? text : text.substring(0, width - 2) + "..";
    }

    /**
     * Returns the configurator window, for tests and for code that adds widgets to it.
     *
     * @return the window owned by this configurator
     * @since 1.0
     */
    public TWindow window() {
        return window;
    }

    /**
     * Returns the TUI application: {@code invokeLater}, {@code addTimer} and dialogs for widgets that
     * work in the background.
     *
     * @return the application that owns the window
     * @since 1.0
     */
    public TApplication application() {
        return application;
    }

    /**
     * Returns the RESOLVED state: what the application actually receives, since the view is a
     * projection of the model.
     *
     * @return the resolved configuration of the working copy
     * @since 1.0
     */
    public dev.jucm.core.Config config() {
        return draft.snapshot();
    }

    /**
     * Returns the working copy, for a widget that has to CHANGE the configuration rather than show it.
     *
     * @return the working copy edited by the view
     * @since 1.0
     */
    public ConfigDraft draft() {
        return draft;
    }

    /**
     * Returns the destination of `Save`, `YAML` and `CLI`, for an added widget that also writes a file.
     *
     * @return the target files of this configurator
     * @since 1.0
     */
    public Targets targets() {
        return targets;
    }

    /**
     * Returns the first row below the fields and buttons built here, so added widgets start there and
     * do not collide with an existing one.
     *
     * @return the first free row of the layout
     * @since 1.0
     */
    public int freeRow() {
        return ROW * (definition.fields().size() + 3) + 2;
    }

    /**
     * Writes a message into the status line, which is how an added widget reports its result.
     *
     * @param message text to show; it replaces the current status
     * @since 1.0
     */
    public void statusMessage(String message) {
        status.setText(message);
    }

    /**
     * Returns the symbolic view of a field as shown on screen, clipped to the symbol column.
     *
     * @param path field path
     * @return the label text, or {@code null} when the field is not in the view
     * @since 1.0
     */
    public String symbol(ConfigPath path) {
        TLabel<?> symbol = symbols.get(path);
        return symbol == null ? null : symbol.getLabel();
    }

    private void setSymbol(ConfigPath path, String raw) {
        TLabel<?> symbol = symbols.get(path);
        if (symbol != null) {
            symbol.setLabel(fit(path.canonical() + " = " + raw, SYMBOL_WIDTH));
        }
    }

    /**
     * Returns the paths of the fields shown in the window, in build order.
     *
     * @return the field paths of the view
     * @since 1.0
     */
    public List<ConfigPath> paths() {
        return new ArrayList<>(values.keySet());
    }

    /**
     * Returns the text field of a value cell.
     *
     * @param path field path
     * @return the value widget, or {@code null} when the field is not in the view
     * @since 1.0
     */
    public TField field(ConfigPath path) {
        return values.get(path);
    }

    /**
     * Returns the caption label of a field.
     *
     * @param path field path
     * @return the caption, or {@code null} when the field is not in the view
     * @since 1.0
     */
    public TLabel<?> caption(ConfigPath path) {
        return captions.get(path);
    }

    /**
     * Returns the status line of the view.
     *
     * @return the status field
     * @since 1.0
     */
    public TField status() {
        return status;
    }

    /**
     * The configurator window: <b>the close box in the title bar ends the application</b>, not merely
     * the window, because the configurator owns the whole screen and closing the window alone would
     * leave the process without one. {@code TWindow.onClose()} is {@code protected}, so subclassing is
     * the only way to hook it.
     *
     * @since 1.0
     */
    public static final class ClosingWindow extends TWindow {

        /**
         * Creates the window.
         *
         * @param application application that owns the window
         * @param title title shown in the title bar
         * @param x column of the top-left corner
         * @param y row of the top-left corner
         * @param width width of the window
         * @param height height of the window
         * @since 1.0
         */
        public ClosingWindow(TApplication application, String title, int x, int y, int width, int height) {
            super(application, title, x, y, width, height);
        }

        @Override
        protected void onClose() {
            super.onClose();
            if (getApplication() != null) {
                getApplication().exit();
            }
        }
    }

    /**
     * Returns the layer edits are written to.
     *
     * @return {@link Layer#TUI}
     * @since 1.0
     */
    public static Layer editLayer() {
        return Layer.TUI;
    }
}
