package dev.jucm.casciian;

import static org.assertj.core.api.Assertions.assertThat;

import casciian.TButton;
import casciian.TLabel;
import casciian.TWidget;
import casciian.backend.HeadlessBackend;
import dev.jucm.core.ConfigConstraint;
import dev.jucm.core.ConfigDefinition;
import dev.jucm.core.ConfigDraft;
import dev.jucm.core.ConfigField;
import dev.jucm.core.ConfigPath;
import dev.jucm.core.ConfigPatch;
import dev.jucm.core.ConfigSource;
import dev.jucm.core.ConfigString;
import dev.jucm.core.ConfigType;
import dev.jucm.core.Layer;
import dev.jucm.core.SourceDeclaration;
import dev.jucm.core.TransformerRegistry;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The configurator as seen through its own driver (`TuiDriver`) — the terminal counterpart of a
 * browser driver.
 *
 * <p>Every case pins the CAUSE, not the symptom: text typed back to front, the tail of a long value
 * lost, an application left without a window, a message that never reaches the screen, a file written
 * where the user expected output.
 */
class ConfiguratorTest {

    private static final TransformerRegistry REGISTRY = TransformerRegistry.defaults();

    private static ConfigDefinition model() {
        return ConfigDefinition.of(
            ConfigField.required("host", ConfigType.STR, ConfigString.of("localhost"))
                .described("Service host."),
            ConfigField.required("port", ConfigType.INT, ConfigString.of("8080"))
                .described("Service port."),
            ConfigField.required("uri", ConfigType.STR, ConfigString.of("http://${host}:${port}/api"))
                .described("Base address computed from the host and the port."));
    }

    private record Open(HeadlessBackend backend, TuiApplication application, TuiDriver ui,
                        Configurator configurator, Configurator.Targets targets,
                        ByteArrayOutputStream stdout) implements AutoCloseable {

        static Open of() {
            return of(view -> { });
        }

        /** The variant with an extension point — the same one the application uses on its toolbox. */
        static Open of(java.util.function.Consumer<Configurator> extension) {
            return of(extension, model(), ConfigDraft.of(model(), REGISTRY,
                ConfigPatch.set(ConfigPath.of("host"), ConfigString.of("example.org"),
                    ConfigSource.of(Layer.YAML, "application.yaml"))));
        }

        /** The variant with ITS OWN model and working copy — for cases that change declarations or layers. */
        static Open of(java.util.function.Consumer<Configurator> extension, ConfigDefinition definition,
                       ConfigDraft draft) {
            HeadlessBackend backend = new HeadlessBackend();
            backend.setBackend(backend);
            TuiApplication application = new TuiApplication(backend);
            Configurator.Targets targets = Configurator.Targets.in(java.nio.file.Path.of("build"));

            // The buttons have NO right to print anything to the screen (the report was "a meaningless
            // line that disappears once you leave the tui") — stdout is captured so that can be asserted
            // directly.
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            Configurator configurator;
            PrintStream previous = System.out;
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            try {
                configurator = Configurator.open(application, definition, draft, targets, "uri-app", extension);
            } finally {
                System.setOut(previous);
            }
            return new Open(backend, application, TuiDriver.start(application, backend), configurator,
                targets, stdout);
        }

        @Override
        public void close() {
            ui.close();
        }

        String output() {
            return stdout.toString(StandardCharsets.UTF_8);
        }

        String read(java.nio.file.Path file) throws Exception {
            return Files.readString(file);
        }
    }

    private static String field(Open open, String path) {
        return open.configurator().field(ConfigPath.of(path)).getText();
    }

    /** This is what a user edit looks like: the text lands IN THE FIELD, and the field calls `updateAction`. */
    private static void type(Open open, String path, String text) {
        open.ui().fill(open.configurator().field(ConfigPath.of(path)), text);
        open.configurator().edit(ConfigPath.of(path), text);
    }

    @Test
    @DisplayName("the view is built FROM THE DECLARATION: one row per field, caption from `description`, rows ordered by address")
    void viewComesFromTheModel() {
        try (Open open = Open.of()) {
            assertThat(open.configurator().paths()).extracting(ConfigPath::canonical)
                .as("rows are the fields of the model in a REPRODUCIBLE order (the model does not guarantee one)")
                .containsExactly("host", "port", "uri");
            assertThat(open.configurator().caption(ConfigPath.of("host")).getLabel())
                .isEqualTo("Service host.");
            assertThat(open.configurator().caption(ConfigPath.of("uri")).getLabel())
                .as("a long description is clipped to its column — otherwise it covered the value (caught by eye)")
                .startsWith("Base address").hasSizeLessThanOrEqualTo(16);
            assertThat(field(open, "uri")).as("and the value is visible in full")
                .isEqualTo("http://example.org:8080/api");
        }
    }

    @Test
    @DisplayName("editing `port` recomputes and REDRAWS `uri` — a field far from the cause follows the flow")
    void dependentFieldIsRedrawn() {
        try (Open open = Open.of()) {
            assertThat(field(open, "uri")).as("host from the file, port from the model")
                .isEqualTo("http://example.org:8080/api");

            open.ui().fill(open.configurator().field(ConfigPath.of("port")), "7000");
            var changes = open.configurator().edit(ConfigPath.of("port"), "7000");

            assertThat(changes).extracting(change -> change.path().canonical())
                .as("the model named BOTH changes, and the view redrew both").containsExactly("port", "uri");
            assertThat(field(open, "port")).isEqualTo("7000");
            assertThat(field(open, "uri")).as("`uri` recomputed by the flow, not by the view")
                .isEqualTo("http://example.org:7000/api");
        }
    }

    @Test
    @DisplayName("the SYMBOLIC view beside the resolved one: the WRITTEN form is visible, not only the result")
    void symbolicViewShowsTheWrittenForm() {
        try (Open open = Open.of()) {
            assertThat(open.configurator().symbol(ConfigPath.of("uri")))
                .as("the symbolic one starts with the written form (`http://${host}`); the resolved one would start"
                    + " with the value (`http://example.org`) — which is why these are two columns")
                .startsWith("uri = http://${host}");
            assertThat(open.configurator().symbol(ConfigPath.of("host")))
                .as("and for a field set in the file — what the layer set")
                .isEqualTo("host = example.org");
        }
    }

    @Test
    @DisplayName("the status line comes from the validator — the same one the application runs at startup")
    void statusComesFromTheValidator() {
        try (Open open = Open.of()) {
            assertThat(open.configurator().status().getText()).startsWith("status: ok");
            open.configurator().edit(ConfigPath.of("port"), "not-a-number");
            assertThat(open.configurator().status().getText()).startsWith("status: 1 problem");
        }
    }

    @Test
    @DisplayName("`Reset` rolls the edits back and restores the view from the input layers")
    void resetButtonRestoresTheView() {
        try (Open open = Open.of()) {
            type(open, "host", "somewhere.else");
            type(open, "port", "9999");
            assertThat(field(open, "uri")).isEqualTo("http://somewhere.else:9999/api");

            open.ui().click(open.ui().findButton("Reset"));

            assertThat(field(open, "host")).as("the host from the file is back").isEqualTo("example.org");
            assertThat(field(open, "uri")).as("and `uri` recomputed from the input layers")
                .isEqualTo("http://example.org:8080/api");
        }
    }

    @Test
    @DisplayName("a field BEING EDITED is not rewritten: `setText` moves the caret to 0, so characters arrived back to front")
    void editedFieldIsNotRewritten() {
        try (Open open = Open.of()) {
            type(open, "port", "010");

            assertThat(field(open, "port"))
                .as("the field keeps WHAT THE USER WROTE — rewriting it on every keystroke pushed the caret to 0"
                    + " (`position = 0` in their `TField`), so characters landed back to front")
                .isEqualTo("010");
            assertThat(field(open, "uri")).as("while the dependent field sees the result of the flow")
                .isEqualTo("http://example.org:010/api");

            open.configurator().enter(ConfigPath.of("port"));
            assertThat(field(open, "port")).as("only CONFIRMING shows the canonical value")
                .isEqualTo("8");
        }
    }

    @Test
    @DisplayName("a LONG VALUE is not clipped to the field width — it was, while the field was `fixed = true`")
    void longValueIsNotTruncated() {
        try (Open open = Open.of()) {
            String longHost = "a.very.long.host.name.that.exceeds.the.width.of.the.field.example.org";
            String uri = "http://" + longHost + ":8080/api";
            assertThat(uri.length()).as("the value MUST be longer than the field, otherwise the test proves nothing")
                .isGreaterThan(26);

            type(open, "host", longHost);

            assertThat(field(open, "uri"))
                .as("`TField.setText` with `fixed = true` clipped the text to the field width (measured)")
                .isEqualTo(uri).endsWith("/api");
        }
    }

    @Test
    @DisplayName("the file names are the `application.*` family — named after the role of the file, not the program")
    void targetNames() {
        Configurator.Targets targets = Configurator.Targets.in(java.nio.file.Path.of("/tmp/example"));

        assertThat(targets.applied().getFileName().toString()).isEqualTo("application.applied.yaml");
        assertThat(targets.resolved().getFileName().toString()).isEqualTo("application.resolved.yaml");
        assertThat(targets.script().getFileName().toString())
            .as("the CLI writes `application.sh`, not `uri-app.sh`")
            .isEqualTo("application.sh");
    }

    @Test
    @DisplayName("`Save` writes the CHANGES only, not the whole state: the computed `uri` does not freeze the interpolation")
    void saveWritesOnlyTheChanges() throws Exception {
        try (Open open = Open.of()) {
            type(open, "port", "7000");
            open.ui().click(open.ui().findButton("Save"));

            assertThat(open.read(open.targets().applied()))
                .contains("port: \"7000\"").doesNotContain("uri:");
            assertThat(open.configurator().status().getText())
                .as("the user sees where it went — as a full path, not just a file name")
                .startsWith("wrote 1")
                .contains(open.targets().applied().toAbsolutePath().toString());
        }
    }

    @Test
    @DisplayName("`YAML` writes the WHOLE configuration to a file and prints NOTHING to the screen")
    void yamlIsWrittenToAFile() throws Exception {
        try (Open open = Open.of()) {
            open.ui().click(open.ui().findButton("YAML"));

            assertThat(open.output()).as("writing to stdout while the TUI runs was eaten by the repaint")
                .isEmpty();
            assertThat(open.read(open.targets().resolved()))
                .contains("host: \"example.org\"").contains("port: \"8080\"")
                .contains("uri: \"http://example.org:8080/api\"")
                .contains("# as written: http://${host}:${port}/api");
            assertThat(open.configurator().status().getText()).startsWith("written ->")
                .as("the full path, so nobody has to guess where the file landed")
                .contains(open.targets().resolved().toAbsolutePath().toString());
        }
    }

    @Test
    @DisplayName("`CLI` writes a `.sh` SCRIPT and prints NOTHING to the screen")
    void cliIsWrittenAsAShellScript() throws Exception {
        try (Open open = Open.of()) {
            type(open, "port", "7000");
            open.ui().click(open.ui().findButton("CLI"));

            assertThat(open.output()).as("the \"meaningless line that disappears\" was output to stdout")
                .isEmpty();
            assertThat(open.read(open.targets().script()))
                .startsWith("#!/bin/sh")
                .contains("exec uri-app --host example.org").contains("--port 7000")
                .contains("--uri http://example.org:7000/api")
                .as("the script passes on whatever you add when running it")
                .endsWith("\"$@\"\n");

            type(open, "host", "example org");
            open.ui().click(open.ui().findButton("CLI"));
            assertThat(open.read(open.targets().script()))
                .as("a value with a space MUST be quoted, otherwise the shell splits it into two arguments")
                .contains("--host 'example org'");
        }
    }

    @Test
    @DisplayName("a redraw is NOT an edit: after the repaint the working copy holds no new entries")
    void redrawDoesNotEdit() {
        try (Open open = Open.of()) {
            type(open, "host", "example.org");
            open.configurator().refresh();

            assertThat(open.configurator().status().getText()).startsWith("status: ok");
            assertThat(field(open, "uri")).as("a redraw must not add a patch on behalf of the user")
                .isEqualTo("http://example.org:8080/api");
        }
    }

    @Test
    @DisplayName("after a button action the screen is REPAINTED (output from outside the TUI scattered it)")
    void actionsRequestFullRepaint() {
        try (Open open = Open.of()) {
            int before = open.application().repaints();

            open.ui().click(open.ui().findButton("YAML"));
            open.ui().await(() -> open.application().repaints() > before, "repaint after YAML", "requested");

            int afterYaml = open.application().repaints();
            open.ui().click(open.ui().findButton("Save"));
            open.ui().await(() -> open.application().repaints() > afterYaml, "repaint after Save",
                "requested");
        }
    }

    @Test
    @DisplayName("`Cancel` ENDS the application — closing the window alone left the process without one")
    void cancelExitsTheApplication() {
        try (Open open = Open.of()) {
            assertThat(open.ui().isRunning()).isTrue();
            open.ui().click(open.ui().findButton("Cancel"));
            open.ui().await(() -> !open.ui().isRunning(), "the TUI loop", "ended by Cancel");
        }
    }

    @Test
    @DisplayName("closing the WINDOW ends the application too — the same trap down the second path")
    void closingTheWindowExits() {
        try (Open open = Open.of()) {
            assertThat(open.ui().isRunning()).isTrue();
            open.configurator().window().close();
            open.ui().await(() -> !open.ui().isRunning(), "the TUI loop", "ended by closing the window");
        }
    }

    @Test
    @DisplayName("EXTENSION POINT: a developer adds a widget, reads the model and writes to the status line")
    void extensionAddsItsOwnWidgets() {
        try (Open open = Open.of(view -> {
            // this is what developer code looks like: a place from `freeRow()`, a value from the model, a report in the status line
            TLabel<TWidget> echo = view.window().addLabel("", 2, view.freeRow());
            view.window().addButton("Ping", 2, view.freeRow() + 1, () ->
                echo.setLabel("ping: " + displayFromModel(view)));
        })) {
            assertThat(open.ui().findButton("Ping"))
                .as("the developer's widget is in the same window and `findButton` sees it")
                .isInstanceOf(TButton.class);

            open.ui().click(open.ui().findButton("Ping"));

            assertThat(open.ui().findLabel("ping: http://example.org:8080/api"))
                .as("the developer's widget got the value RESOLVED by the model (not from a widget) and it worked")
                .isNotNull();
        }
    }

    /** The same thing the application does: the value comes from the model, not from a widget. */
    private static String displayFromModel(Configurator view) {
        return view.config().get(ConfigPath.of("uri"))
            instanceof dev.jucm.core.ConfigResult.ValueResult<dev.jucm.core.ConfigValue> value
            ? value.value().display() : "";
    }

    @Test
    @DisplayName("the buttons are in the window under their own labels — `findButton` sees them")
    void buttonsExist() {
        try (Open open = Open.of()) {
            assertThat(open.ui().findButton("Reset")).isInstanceOf(TButton.class);
            assertThat(open.ui().findButton("YAML")).isInstanceOf(TButton.class);
            assertThat(open.ui().findButton("CLI")).isInstanceOf(TButton.class);
            assertThat(open.ui().findButton("Save")).isInstanceOf(TButton.class);
            assertThat(open.ui().findButton("Cancel")).isInstanceOf(TButton.class);
        }
    }
    @Test
    @DisplayName("layout: column headers, a gap between the value and `CONST`, a field name wide enough to read")
    void layoutKeepsThingsApart() {
        try (Open open = Open.of()) {
            Configurator view = open.configurator();

            // `findLabel` throws when the text is absent — the call itself is the assertion.
            open.ui().findLabel("defaults");
            open.ui().findLabel("type");
            open.ui().findLabel("CONST");

            assertThat(view.gapBetweenValueAndRule())
                .as("`CONST` is separated from the value by at least one character — otherwise the fields merge")
                .isGreaterThanOrEqualTo(1);
            assertThat(view.symbolWidth())
                .as("on the test screen (80 columns) the field name keeps its minimum").isGreaterThanOrEqualTo(16);
            assertThat(view.defaultsColumn())
                .as("the `defaults` header hangs over the DEFAULTS column, that is BEFORE the value column")
                .isLessThan(view.valueColumn());
            assertThat(view.labelWidth())
                .as("the caption gives up room FIRST — never below 10 characters").isBetween(10, 16);
            assertThat(view.ruleWidth())
                .as("the `CONST` field takes the rest of the window width, but never drops to zero")
                .isGreaterThanOrEqualTo(18);
            assertThat(view.windowWidth())
                .as("the window is maximised — the whole screen width").isGreaterThanOrEqualTo(80);
        }
    }

    @Test
    @DisplayName("the layout follows the window width: at 160 columns the field name gets its full 30 and `CONST` the rest")
    void columnsFollowTheWindow() {
        // A pure layout function — asserted without a screen, so it behaves the same in every terminal.
        // The numbers are MEASURED (`columnsFor`), not guessed: adding the `source` column moved the
        // full layout from 140 to 160 columns.
        Configurator.Columns wide = Configurator.columnsFor(160);
        assertThat(wide.label()).as("wide window: the caption stays at 16").isEqualTo(16);
        assertThat(wide.symbol()).as("the field name gets its full 30")
            .isEqualTo(30);
        assertThat(wide.ruleWidth()).as("`CONST` takes ALL that is left of the window").isEqualTo(160 - wide.rule() - 3);
        assertThat(wide.rule()).as("and a gap, not zero, separates it from the source and the value")
            .isEqualTo(wide.source() + 20 + 2);
        assertThat(wide.source()).as("`source` stands between the value and `CONST`")
            .isEqualTo(wide.value() + 26 + 2);
        assertThat(java.util.List.of(wide.type(), wide.defaults(), wide.symbolAt(), wide.value(),
                wide.source(), wide.rule()))
            .as("column order: type | defaults | field name | value | source | CONST")
            .isSorted();

        Configurator.Columns tight = Configurator.columnsFor(140);
        assertThat(tight.label()).as("at 140 columns the caption narrows first — there is less room for it")
            .isEqualTo(10);
        assertThat(tight.symbol()).as("while the field name still holds above its minimum").isEqualTo(17);

        Configurator.Columns narrow = Configurator.columnsFor(80);
        assertThat(narrow.label()).as("narrow window: the caption is the first to go (10)").isEqualTo(10);
        assertThat(narrow.symbol()).as("the field name keeps its minimum of 16").isEqualTo(16);
        assertThat(narrow.ruleWidth()).as("`CONST` has its minimum too — never zero").isGreaterThanOrEqualTo(18);
    }

    @Test
    @DisplayName("the `source` column shows WHICH layer supplies the value — and it changes with an edit")
    void theSourceColumnFollowsTheValue() {
        try (Open open = Open.of()) {
            Configurator view = open.configurator();
            ConfigPath port = ConfigPath.of("port");

            String fromDeclaration = view.sourceText(port);
            assertThat(fromDeclaration).as("without an edit the value comes from the declaration")
                .contains("COMPILED");

            view.edit(port, "443");

            assertThat(view.sourceText(port))
                .as("after an edit the column names a different source — otherwise it would not answer "
                    + "the question `did we lose anything`")
                .isNotEqualTo(fromDeclaration);
        }
    }

    @Test
    @DisplayName("editing the VALUE does not lose the rule in the `CONST` field — neither on screen nor in the model")
    void editingTheValueKeepsTheRule() {
        try (Open open = Open.of()) {
            Configurator view = open.configurator();
            ConfigPath port = ConfigPath.of("port");
            view.rule(port).setText("=8080");
            view.applyRule(port);
            assertThat(view.draft().report().errors()).as("to start with the rule is in force and 8080 satisfies it").isEmpty();

            // The same path the field calls on EVERY keystroke — this is exactly where the rule was lost.
            view.edit(port, "443");
            assertThat(view.rule(port).getText()).as("the rule stays in the field").isEqualTo("=8080");
            assertThat(view.draft().report().errors())
                .as("and it is STILL in force: 443 breaks the rule `=8080` (the rule used to vanish silently)")
                .isNotEmpty();

            view.field(port).setText("443");
            view.enter(port);
            assertThat(view.rule(port).getText()).as("after confirming as well").isEqualTo("=8080");
            assertThat(view.draft().report().errors()).as("and it is still in force").isNotEmpty();

            view.rule(port).setText("in[443,8080]");
            view.applyRule(port);
            assertThat(view.draft().report().errors()).as("the rule from the field can still let 443 through").isEmpty();
        }
    }

    @Test
    @DisplayName("a rule typed WITHOUT confirming is not wiped by an edit of the value")
    void uncommittedRuleIsNotWipedByTheValue() {
        try (Open open = Open.of()) {
            Configurator view = open.configurator();
            ConfigPath port = ConfigPath.of("port");

            // Typed, but NOT confirmed: enter was not pressed, so the model does not know about it yet.
            view.rule(port).setText("=443");
            view.edit(port, "8080");   // the same path the value field calls on every keystroke
            assertThat(view.rule(port).getText())
                .as("the `CONST` field belongs to the user — a redraw has NO RIGHT to clear it")
                .isEqualTo("=443");

            // Typing a rule puts it into the model STRAIGHT AWAY (the path the field calls on every keystroke).
            view.ruleEdited(port);
            assertThat(view.draft().report().errors()).as("now `=443` breaks 8080").isNotEmpty();

            // A further edit of the value loses neither the text nor its force.
            view.edit(port, "8080");
            assertThat(view.rule(port).getText()).isEqualTo("=443");
            assertThat(view.draft().report().errors()).as("the rule is still in force").isNotEmpty();
        }
    }

    @Test
    @DisplayName("the `defaults` column shows the value from the MODEL (and `[none]` when the field has none)")
    void defaultsColumnShowsTheModelValue() {
        try (Open open = Open.of()) {
            Configurator view = open.configurator();
            assertThat(view.defaultText(ConfigPath.of("port")))
                .as("what you get with no source at all — the value from the field declaration").isEqualTo("8080");
            open.ui().findLabel("defaults");
        }
    }

    @Test
    @DisplayName("`type` and `CONST` hang beside the field: the type from the dictionary (read-only), the rule from the field DECIDES")
    void ruleHangsNextToTheFieldAndDecides() {
        try (Open open = Open.of()) {
            Configurator view = open.configurator();
            ConfigPath port = ConfigPath.of("port");

            assertThat(view.typeName(port)).as("the type from the dictionary of known types, read-only for now")
                .isEqualTo("int");
            assertThat(view.rule(port)).as("a CONST field beside the value field").isNotNull();

            view.rule(port).setText(">=1&<=10");
            view.applyRule(port);
            assertThat(view.draft().report().errors())
                .as("port holds 8080 while the rule from the field says `<=10` — the model sees it").isNotEmpty();

            view.rule(port).setText("=8080");
            view.applyRule(port);
            assertThat(view.draft().report().errors()).as("the rule from the field is in force").isEmpty();

            view.rule(port).setText("");
            view.applyRule(port);
            assertThat(view.draft().report().errors())
                .as("an empty field = back to the rules from code (the marker drops its own rule)").isEmpty();
        }
    }

    @Test
    @DisplayName("a rule typed into `CONST` is in force AT ONCE — and it is the one deciding the new value")
    void ruleTypedLiveGovernsTheNewValue() {
        try (Open open = Open.of()) {
            Configurator view = open.configurator();
            ConfigPath port = ConfigPath.of("port");

            // A new version of the rule: only 9999. The field calls `ruleEdited` on every keystroke.
            view.rule(port).setText("in[9999]");
            view.ruleEdited(port);
            assertThat(view.status().getText())
                .as("8080 does not satisfy the new rule — the status line says WHY and WHICH field")
                .contains("port").contains("in[9999]");

            view.edit(port, "9999");
            assertThat(view.draft().report().errors())
                .as("the new value goes through, because the new rule allows it").isEmpty();

            view.edit(port, "1234");
            assertThat(view.draft().report().errors())
                .as("a value outside the new rule is rejected — an arbitrary value cannot be entered")
                .isNotEmpty();
        }
    }

    @Test
    @DisplayName("nonsense in `CONST` is LOUD and does NOT enter the model — the previous rule stays in force")
    void garbageInTheRuleIsLoudAndDoesNotEnter() {
        try (Open open = Open.of()) {
            Configurator view = open.configurator();
            ConfigPath port = ConfigPath.of("port");

            view.rule(port).setText("in[9999]");
            view.ruleEdited(port);            // a good rule enters...
            view.rule(port).setText("abc");
            view.ruleEdited(port);            // ...and now for the nonsense

            assertThat(view.status().getText())
                .as("the message says WHY, and what is STILL in force")
                .contains("invalid rule").contains("still in force").contains("in[9999]");
            view.edit(port, "9999");
            assertThat(view.draft().report().errors())
                .as("the model works on the PREVIOUS rule — 9999 still goes through").isEmpty();
            view.edit(port, "1234");
            assertThat(view.draft().report().errors())
                .as("...and 1234 is still rejected").isNotEmpty();
        }
    }

    @Test
    @DisplayName("a broken rule in the `CONST` field is LOUD, and the model stays with the previous one")
    void brokenRuleIsLoudAndNothingEntersSilently() {
        try (Open open = Open.of()) {
            Configurator view = open.configurator();
            ConfigPath port = ConfigPath.of("port");

            view.rule(port).setText("<2>0");
            view.applyRule(port);

            assertThat(view.status().getText())
                .as("the status line says WHY instead of staying silent").contains("invalid rule");
            assertThat(view.draft().report().errors())
                .as("nothing entered silently — the model is unchanged").isEmpty();
        }
    }

    @Test
    @DisplayName("an `in[…]` rule gives a CHOICE field, and every other rule SWITCHES back to a text field")
    void pickerFollowsTheRule() {
        try (Open open = Open.of()) {
            Configurator view = open.configurator();
            ConfigPath port = ConfigPath.of("port");

            view.rule(port).setText("in[8080,443]");
            view.ruleEdited(port);
            assertThat(view.choices(port)).as("the selector exists FROM BUILD TIME — otherwise TAB lands at the end of the window")
                .isNotNull();
            assertThat(view.choices(port).getList()).as("values straight from the CONSTRAINT (the view asks the model)")
                .containsExactly("8080", "443");
            assertThat(view.choices(port).getHeight()).as("the cell is ONE ROW high, not a list").isEqualTo(1);
            assertThat(view.choices(port).isVisible()).as("`in[…]` → a visible selector").isTrue();
            assertThat(view.choices(port).isEnabled()).as("and ENABLED — TAB only walks enabled widgets").isTrue();
            assertThat(view.field(port).isVisible()).as("the text field gives way").isFalse();
            assertThat(view.field(port).isEnabled())
                .as("a disabled field does not catch TAB (hiding alone is NOT enough)").isFalse();

            // Someone changes `CONST` while the application runs — the view has to switch with it.
            view.rule(port).setText("!in[8080,443]");
            view.ruleEdited(port);
            assertThat(view.choices(port).isVisible())
                .as("`!in[…]` does NOT give a list: every other text is allowed").isFalse();
            assertThat(view.choices(port).isEnabled()).isFalse();
            assertThat(view.field(port).isVisible()).as("so the text field comes back").isTrue();
            assertThat(view.field(port).isEnabled()).isTrue();

            view.rule(port).setText(">1024");
            view.ruleEdited(port);
            assertThat(view.choices(port).isVisible()).as("an order condition gives no list either").isFalse();

            view.rule(port).setText("in[1,2]");
            view.ruleEdited(port);
            assertThat(view.choices(port).getList()).as("and back again: `in[…]` → a list").containsExactly("1", "2");
            assertThat(view.choices(port).isVisible()).isTrue();
        }
    }

    @Test
    @DisplayName("ENTER accepts the value and does NOT move the caret or the active field")
    void enterKeepsTheFocus() {
        try (Open open = Open.of()) {
            Configurator view = open.configurator();
            ConfigPath port = ConfigPath.of("port");

            view.field(port).activate();     // the caret stands in the port field (the 3rd field in order)
            view.field(port).setText("7000");
            view.enter(port);

            assertThat(view.window().getActiveChild())
                .as("after confirming the caret stays in the SAME field instead of jumping back to `host`")
                .isSameAs(view.field(port));

            // and a second time — a redraw on every ENTER could have moved the focus more than once
            view.field(port).setText("7100");
            view.enter(port);
            assertThat(view.window().getActiveChild()).isSameAs(view.field(port));

            // it works for a field with a selector too: the caret has to stay there
            ConfigPath uri = ConfigPath.of("uri");
            view.rule(uri).setText("in[http://example.org:8080/api]");
            view.ruleEdited(uri);
            view.choices(uri).activate();
            view.enter(uri);
            // `getActiveChild()` descends INTO the widget tree, so it reaches the field inside the
            // selector — what matters is that the caret did not leave the cell.
            TWidget active = view.window().getActiveChild();
            assertThat(active == view.choices(uri) || active == view.choices(uri).getActiveChild())
                .as("the caret stays in the cell with the selector (it does not run off to `host`)").isTrue();
        }
    }

    @Test
    @DisplayName("the view says HOW to open the list and WHAT can be chosen (legend plus status line)")
    void thePickerTellsYouWhatToPress() {
        try (Open open = Open.of()) {
            Configurator view = open.configurator();
            ConfigPath port = ConfigPath.of("port");

            open.ui().findLabel("Alt+\u2193 = value list (rule `in[…]`)");
            view.rule(port).setText("in[8080,443]");
            view.ruleEdited(port);

            assertThat(view.status().getText())
                .as("the choices are visible right after the rule is typed").contains("allowed")
                .contains("8080, 443").contains("Alt+");
        }
    }

    @Test
    @DisplayName("the selector sits in ITS OWN row (TAB does not skip it) and the field inside it is live")
    void choiceWidgetSitsInItsRow() {
        try (Open open = Open.of()) {
            Configurator view = open.configurator();
            ConfigPath host = ConfigPath.of("host");
            ConfigPath port = ConfigPath.of("port");
            view.rule(port).setText("in[8080,443]");
            view.ruleEdited(port);

            // Widget order in the window = order of creation = `tabOrder`, the order TAB walks.
            List<TWidget> order = view.window().getChildren();
            assertThat(order.indexOf(view.rule(host)))
                .as("host CONST before the port cell").isLessThan(order.indexOf(view.choices(port)));
            assertThat(order.indexOf(view.choices(port)))
                .as("...and the port cell BEFORE port CONST — so the selector stands in ITS OWN row")
                .isLessThan(order.indexOf(view.rule(port)));
            assertThat(order.indexOf(view.field(port)))
                .as("the port text field sits beside it (just before the selector) and is disabled")
                .isLessThan(order.indexOf(view.choices(port)));

            assertThat(view.choices(port).getActiveChild())
                .as("the field INSIDE the selector exists: the constructor variant with `limitToListValue=true` "
                    + "disables it, and then the cell can be neither reached with TAB nor given a value")
                .isNotNull();
            assertThat(view.choices(port).getActiveChild().isEnabled()).isTrue();
            assertThat(view.window().getActiveChild())
                .as("the focus starts on the FIRST value cell, not on the last selector")
                .isSameAs(view.field(host));
        }
    }

    @Test
    @DisplayName("a value PICKED from the list enters the model like a typed one — the rule still decides")
    void pickedValueEntersTheModel() {
        try (Open open = Open.of()) {
            Configurator view = open.configurator();
            ConfigPath port = ConfigPath.of("port");
            view.rule(port).setText("in[443,8443]");
            view.ruleEdited(port);

            view.choices(port).setText("8443");   // this is what picking an item from the list does...
            view.enter(port);                     // ...and this is confirming it
            assertThat(view.symbolic(port)).as("the written form in the model").contains("8443");
            assertThat(view.draft().report().errors()).as("the rule `in[443,8443]` lets it through").isEmpty();

            view.choices(port).setText("1");      // a value outside the list (picking does not block it)...
            view.enter(port);
            assertThat(view.draft().report().errors())
                .as("...is rejected by VALIDATION — the list advises, the model decides").isNotEmpty();
        }
    }

    @Test
    @DisplayName("the `CONST` field shows the rule IN FORCE from the moment it opens — from code and from the file")
    void ruleFieldShowsTheRuleInForce() {
        ConfigDefinition constrained = ConfigDefinition.of(
            ConfigField.required("port", ConfigType.INT, ConfigString.of("8080"))
                .constrained(ConfigConstraint.Const("in[8080,443]")));
        ConfigPath port = ConfigPath.of("port");

        // 1) A rule from CODE — the field has no right to be empty (the report was "CONST shows up empty").
        try (Open open = Open.of(v -> { }, constrained, ConfigDraft.of(constrained, REGISTRY))) {
            assertThat(open.configurator().ruleText(port))
                .as("a rule from the field declaration is visible as soon as the window opens").isEqualTo("in[8080,443]");
        }

        // 2) A rule from the FILE plus a value from ENV — exactly the case from the terminal:
        //    `URIAPP__PORT=222 ./uri-app tui` with `port: "443"  # CONST(in[8080,443])` in the file.
        ConfigDraft layered = ConfigDraft.of(constrained, REGISTRY,
            ConfigPatch.set(port, ConfigString.of("222"), ConfigSource.of(Layer.ENV, "URIAPP__PORT"),
                SourceDeclaration.constraints("in[443]")));
        try (Open open = Open.of(v -> { }, constrained, layered)) {
            assertThat(open.configurator().ruleText(port))
                .as("the rule from the file REPLACES the rule from code — and it is the one standing in the field")
                .isEqualTo("in[443]");
            assertThat(open.configurator().status().getText())
                .as("validation says the value from ENV breaks the rule, and WHERE that value comes from")
                .contains("problem").contains("URIAPP__PORT").contains("in[443]");
        }
    }
}
