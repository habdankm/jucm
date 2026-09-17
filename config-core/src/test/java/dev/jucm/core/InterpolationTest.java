package dev.jucm.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Interpolation: the `${…}` parser, lazy resolving, cycles and the trace of the `??` decisions.
 * Everything goes through the public API — exactly as an application sees it.
 */
class InterpolationTest {

    private static final ConfigSource FROM_YAML = ConfigSource.of(Layer.YAML, "application.yaml:12");
    private static final ConfigPath DATA = ConfigPath.of("data");

    private static ConfigPatch patch(Map<String, ConfigValue> values) {
        ConfigPatch patch = ConfigPatch.empty();
        for (Map.Entry<String, ConfigValue> entry : values.entrySet()) {
            patch = patch.with(ConfigPath.of(entry.getKey()), entry.getValue(), FROM_YAML);
        }
        return patch;
    }

    private static ConfigPatch text(Map<String, String> values) {
        ConfigPatch patch = ConfigPatch.empty();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            patch = patch.with(ConfigPath.of(entry.getKey()), ConfigString.of(entry.getValue()), FROM_YAML);
        }
        return patch;
    }

    private static ConfigValue parse(String raw) {
        ConfigResult<ConfigValue> parsed = Interpolation.parse(DATA, raw, FROM_YAML);
        assertThat(parsed).as("expected valid syntax for `%s`", raw)
            .isInstanceOf(ConfigResult.ValueResult.class);
        return ((ConfigResult.ValueResult<ConfigValue>) parsed).value();
    }

    private static String syntaxProblem(String raw) {
        ConfigResult<ConfigValue> parsed = Interpolation.parse(DATA, raw, FROM_YAML);
        assertThat(parsed).as("expected a syntax error for `%s`", raw)
            .isInstanceOf(ConfigResult.ErrorResult.class);
        ConfigError error = ((ConfigResult.ErrorResult<ConfigValue>) parsed).error();
        assertThat(error.kind()).isEqualTo(ConfigError.Kind.INVALID_SYNTAX);
        return error.report();
    }

    /** The variant AFTER the interpolation step — that one carries values; the snapshot itself stays symbolic. */
    private static Config resolved(ConfigDefinition definition, ConfigPatch patch) {
        Config config = Config.root(definition).apply(patch);
        return ConfigPipeline.interpolation().apply(config);
    }

    private static ConfigValue valueOf(Config config, String path) {
        ConfigResult<ConfigValue> found = config.get(ConfigPath.of(path));
        assertThat(found).as("expected a value under `%s`", path).isInstanceOf(ConfigResult.ValueResult.class);
        return ((ConfigResult.ValueResult<ConfigValue>) found).value();
    }

    private static String report(Config config, String path) {
        ConfigDefinition definition = ConfigDefinition.of(ConfigField.optional(path, ConfigType.STR));
        ConfigValidator.Report report = ConfigValidator.build(definition, config);
        return report.describe();
    }

    @Nested
    @DisplayName("the parser: one part is a symbolic value, two parts are an expression")
    class Parsing {

        @Test
        @DisplayName("`${a}` is a reference, not an expression — quotes change nothing here")
        void singleSubstitutionIsAReference() {
            assertThat(parse("${a}")).isEqualTo(ConfigReference.to("a"));
            assertThat(parse("${?a}")).isEqualTo(ConfigReference.optional("a"));
            assertThat(parse("${a ?? b}"))
                .isEqualTo(new ConfigCoalesce(ConfigReference.to("a"), ConfigReference.to("b")));
        }

        @Test
        @DisplayName("an expression is an ORDERED map of parts keyed by `\"<position>:<kind>\"`")
        void expressionIsAnOrderedMapOfParts() {
            ConfigValue value = parse("http://${host}:${port}/api");

            assertThat(value).isInstanceOf(ConfigExpression.class);
            ConfigExpression expression = (ConfigExpression) value;
            assertThat(expression.parts().keySet()).containsExactly("0:text", "1:ref", "2:text", "3:ref", "4:text");
            assertThat(expression.type()).as("concatenation gives text").isEqualTo(ConfigType.STR);
            assertThat(expression.display()).isEqualTo("http://${host}:${port}/api");
        }

        @Test
        @DisplayName("a part knows its WHOLE expression — the back-reference serves diagnostics and stays out of equality")
        void partsKnowTheirWhole() {
            ConfigExpression expression = (ConfigExpression) parse("pre_${a}_post");

            for (ConfigExpression.Part part : expression.parts().values()) {
                assertThat(part.owner()).as("the part -> whole back-reference").isSameAs(expression);
            }
            assertThat(expression).as("equal expressions are equal parts, with no back-references")
                .isEqualTo(parse("pre_${a}_post"));
            assertThat(expression.hashCode()).isEqualTo(parse("pre_${a}_post").hashCode());
        }

        @Test
        @DisplayName("a chained `??`: `a ?? b ?? c` nests to the right, with a single descent")
        void fallbackChainNestsRight() {
            ConfigValue value = parse("${a ?? b ?? c}");

            assertThat(value).isInstanceOf(ConfigCoalesce.class);
            ConfigCoalesce outer = (ConfigCoalesce) value;
            assertThat(outer.left()).isEqualTo(ConfigReference.to("a"));
            assertThat(outer.right())
                .isEqualTo(new ConfigCoalesce(ConfigReference.to("b"), ConfigReference.to("c")));
            assertThat(outer.display()).as("the notation is reproducible: `parse(display(x)) == x`")
                .isEqualTo("${a ?? b ?? c}");
        }

        @Test
        @DisplayName("a `}` outside a substitution is an ordinary character, and so is a `$` without `{`")
        void closingBraceOutsideIsAnOrdinaryCharacter() {
            ConfigExpression expression = (ConfigExpression) parse("${a}}");

            assertThat(expression.display()).isEqualTo("${a}}");
            assertThat(parse("cost 5$")).isEqualTo(ConfigString.of("cost 5$"));
            assertThat(parse("$$")).as("a `$` outside `${` is an ordinary character").isEqualTo(ConfigString.of("$$"));
        }

        @Test
        @DisplayName("syntax without exceptions: `$${`, an unclosed substitution, braces in the body, `??` without a side")
        void brokenSyntaxIsNamed() {
            assertThat(syntaxProblem("$${a}")).contains("$${").contains("is NOT an escape");
            assertThat(syntaxProblem("${a")).contains("has no closing");
            assertThat(syntaxProblem("${{a}}")).contains("carries no braces");
            assertThat(syntaxProblem("${${a}}")).contains("carries no braces");
            assertThat(syntaxProblem("${}")).contains("empty substitution");
            assertThat(syntaxProblem("${a ?? }")).contains("empty side");
            assertThat(syntaxProblem("${?a ?? b}")).contains("says the same thing twice");
        }
    }

    @Nested
    @DisplayName("lazy resolving: a snapshot stays symbolic, a value comes from the read")
    class Lazy {

        @Test
        @DisplayName("two faces of an entry: the symbolic one shows the notation, the resolved one the value")
        void symbolicAndResolvedViews() {
            ConfigDefinition definition = ConfigDefinition.of(ConfigField.required("c", ConfigType.INT));
            Config config = Config.root(definition).apply(text(Map.of("a", "10", "c", "${a}")));

            assertThat(config.entry(ConfigPath.of("c")).orElseThrow().value())
                .as("the symbolic view: what the user wrote (interpolation does not resolve while merging)")
                .isEqualTo(ConfigString.of("${a}"));
            assertThat(valueOf(config, "c")).as("the resolved view: a read resolves against the snapshot")
                .isEqualTo(ConfigString.of("10"));

            assertThat(valueOf(config.with("a", ConfigString.of("30")), "c"))
                .as("without touching `c` — that is the whole difference from materialisation")
                .isEqualTo(ConfigString.of("30"));
        }

        @Test
        @DisplayName("the chain `a -> b -> c` resolves to the end, and a new snapshot changes the result")
        void chainResolvesAndFollowsTheSnapshot() {
            ConfigDefinition definition = ConfigDefinition.of(
                ConfigField.required("c", ConfigType.INT), ConfigField.required("a", ConfigType.INT));
            ConfigPatch patch = text(Map.of("a", "10", "b", "${a}", "c", "${b}"));
            Config config = Config.root(definition).apply(patch);

            assertThat(valueOf(ConfigPipeline.interpolation().apply(config), "c")).isEqualTo(ConfigString.of("10"));
            assertThat(valueOf(ConfigPipeline.interpolation().apply(config.with("a", ConfigString.of("30"))), "c"))
                .as("a reference resolves against ONE PARTICULAR snapshot")
                .isEqualTo(ConfigString.of("30"));
        }

        @Test
        @DisplayName("interpolation runs BEFORE transformation: `010` arriving from a reference is octal 8")
        void interpolationRunsBeforeTransformation() {
            ConfigDefinition definition = ConfigDefinition.of(
                ConfigField.required("c", ConfigType.INT), ConfigField.required("a", ConfigType.STR));
            Config config = Config.root(definition).apply(text(Map.of("a", "010", "c", "${a}")));

            Config result = ConfigPipeline.all(definition, TransformerRegistry.defaults()).apply(config);

            assertThat(valueOf(result, "c")).as("a value coming from a reference takes the ordinary route")
                .isEqualTo(ConfigInt.of(8));
        }

        @Test
        @DisplayName("an expression glues non-`str` values in their canonical form")
        void expressionStringifiesCanonicalForm() {
            ConfigDefinition definition = ConfigDefinition.of(ConfigField.required("data", ConfigType.STR));
            Config config = Config.root(definition)
                .apply(patch(Map.of("year", ConfigInt.of(2026), "data", ConfigString.of("${year}-x"))));

            assertThat(valueOf(ConfigPipeline.interpolation().apply(config), "data"))
                .isEqualTo(ConfigString.of("2026-x"));
        }

        @Test
        @DisplayName("containers: `ports.1` is an element of a list, `weight.home` a key of a map")
        void containersAreAddressable() {
            ConfigDefinition definition = ConfigDefinition.of(ConfigField.required("c", ConfigType.INT));
            Config config = Config.root(definition).apply(patch(Map.of(
                "ports", ConfigSeq.of(List.of(ConfigInt.of(10), ConfigInt.of(20))),
                "weight", ConfigMap.of(Map.of("home", ConfigInt.of(7))),
                "c", ConfigString.of("${ports.1}"))));

            assertThat(valueOf(ConfigPipeline.interpolation().apply(config), "c")).isEqualTo(ConfigInt.of(20));
        }
    }

    @Nested
    @DisplayName("a read beyond the end of a container, and a missing value")
    class MissingThings {

        @Test
        @DisplayName("a missing target: `${x}` is an error, `${?x}` \"contributed nothing\" — and the entry disappears")
        void absentTargetIsAnErrorOrNothing() {
            ConfigDefinition definition = ConfigDefinition.of(
                ConfigField.required("c", ConfigType.STR), ConfigField.required("d", ConfigType.STR));
            Config config = Config.root(definition).apply(text(Map.of("c", "${missing}", "d", "${?missing}")));

            Config result = ConfigPipeline.interpolation().apply(config);

            assertThat(report(result, "c")).contains("unresolved-reference").contains("leads to no value");
            assertThat(result.entry(ConfigPath.of("d")))
                .as("an optional reference with no target leaves no entry — the layer contributes nothing")
                .isEmpty();
        }

        @Test
        @DisplayName("a layer that contributed nothing does not block the one below")
        void droppedEntryFallsThroughToLowerLayer() {
            ConfigDefinition definition = ConfigDefinition.of(ConfigField.required("c", ConfigType.STR));
            Config lower = Config.root(definition).apply(text(Map.of("c", "from-yaml")));
            Config upper = lower.apply(text(Map.of("c", "${?missing}")));

            Config result = ConfigPipeline.interpolation().apply(upper);

            assertThat(valueOf(result, "c")).as("the lookup descends, because the upper layer contributed nothing")
                .isEqualTo(ConfigString.of("from-yaml"));
            assertThat(result.entry(ConfigPath.of("c")).orElseThrow().source().display())
                .as("the value came from the lower layer — and that is visible").isEqualTo("YAML:application.yaml:12");
        }

        @Test
        @DisplayName("a read beyond the end of a list is UNKNOWN_PATH, not Absent (the address exists, the element does not)")
        void outOfRangeElementIsUnknownPath() {
            ConfigDefinition definition = ConfigDefinition.of(ConfigField.required("c", ConfigType.STR));
            Config config = Config.root(definition).apply(patch(Map.of(
                "ports", ConfigSeq.of(List.of(ConfigInt.of(10))),
                "c", ConfigString.of("${ports.5}"))));

            assertThat(report(ConfigPipeline.interpolation().apply(config), "c")).contains("unknown-path");
        }
    }

    @Nested
    @DisplayName("`${?x}` in a collection: a list ELEMENT vanishes and keeps its address, a map KEY goes away")
    class Collections {

        @Test
        @DisplayName("an optional element with no target vanishes — `[1, ${?x}, 3]` is `[1, 3]`, and `2` is still address `2`")
        void optionalElementVanishesAndKeepsItsAddress() {
            ConfigDefinition definition = ConfigDefinition.of(ConfigField.seq("ports", ConfigType.INT));
            Config config = Config.root(definition).apply(patch(Map.of("ports", ConfigSeq.of(List.of(
                ConfigInt.of(1), ConfigString.of("${?may_be_missing}"), ConfigInt.of(3))))));

            ConfigValue ports = valueOf(ConfigPipeline.interpolation().apply(config), "ports");

            assertThat(ports)
                .as("the content is what is there — the vanished element is not in it")
                .isEqualTo(ConfigSeq.of(List.of(ConfigInt.of(1), ConfigInt.of(3))));
            assertThat(((ConfigSeq) ports).addresses())
                .as("the ADDRESSING keeps the neighbour in place: `ports.2` is still the element written third")
                .containsExactly(0, 2);
        }

        @Test
        @DisplayName("a reference to a vanished address leads NOWHERE — it never follows the neighbour")
        void referenceToAVanishedAddressLeadsNowhere() {
            ConfigDefinition definition = ConfigDefinition.of(
                ConfigField.seq("ports", ConfigType.INT),
                ConfigField.required("second", ConfigType.INT),
                ConfigField.required("third", ConfigType.INT));
            Config config = Config.root(definition).apply(patch(Map.of(
                "ports", ConfigSeq.of(List.of(ConfigInt.of(1), ConfigString.of("${?may_be_missing}"), ConfigInt.of(3))),
                "second", ConfigString.of("${ports.1}"),
                "third", ConfigString.of("${ports.2}"))));

            Config resolved = ConfigPipeline.interpolation().apply(config);

            assertThat(valueOf(resolved, "third")).as("address `2` survived").isEqualTo(ConfigInt.of(3));
            assertThat(report(resolved, "second"))
                .as("address `1` is empty — not the element that took its place")
                .contains("unknown-path");
        }

        @Test
        @DisplayName("every element vanished: the whole value contributes nothing — the default answers")
        void everyElementVanishingDropsTheWholeValue() {
            ConfigSeq fallback = ConfigSeq.of(List.of(ConfigString.of("kept")));
            ConfigDefinition definition = ConfigDefinition.of(
                ConfigField.seq("features", ConfigType.STR, fallback));
            Config config = Config.root(definition).apply(patch(Map.of(
                "features", ConfigSeq.of(List.of(ConfigString.of("${?missing}"))))));

            assertThat(valueOf(ConfigPipeline.interpolation().apply(config), "features"))
                .as("nothing was said about the address, so what is below answers — not an empty list")
                .isEqualTo(fallback);
        }

        @Test
        @DisplayName("an explicitly EMPTY list is still emptiness — that is a statement, not \"nothing was said\"")
        void explicitlyEmptyListIsStillEmptiness() {
            ConfigDefinition definition = ConfigDefinition.of(ConfigField.optionalSeq("features", ConfigType.STR));
            Config config = Config.root(definition).apply(patch(Map.of("features", ConfigSeq.empty())));

            assertThat(ConfigValidator.build(definition, ConfigPipeline.interpolation().apply(config)).errors())
                .extracting(ConfigError::kind)
                .containsExactly(ConfigError.Kind.NULL_VALUE);
        }

        @Test
        @DisplayName("every ENTRY of a map vanished: the whole value contributes nothing — the same rule as a list")
        void everyEntryVanishingDropsTheWholeMap() {
            ConfigMap fallback = ConfigMap.of(Map.of("kept", ConfigString.of("1")));
            ConfigDefinition definition = ConfigDefinition.of(
                ConfigField.map("weight", ConfigType.STR, ConfigType.STR, fallback));
            Config config = Config.root(definition).apply(patch(Map.of("weight",
                ConfigMap.of(Map.of("home", ConfigString.of("${?missing}"))))));

            assertThat(valueOf(ConfigPipeline.interpolation().apply(config), "weight"))
                .as("the entries went away, so the address says nothing — an empty map would be an error")
                .isEqualTo(fallback);
        }

        @Test
        @DisplayName("inside a map a key may simply not exist — the entry goes away, the map stays")
        void optionalInMapValueRemovesTheKey() {
            ConfigDefinition definition = ConfigDefinition.of(ConfigField.map("weight", ConfigType.STR, ConfigType.INT));
            Config config = Config.root(definition).apply(patch(Map.of("weight", ConfigMap.of(Map.of(
                "home", ConfigString.of("${?missing}"), "car", ConfigInt.of(1))))));

            assertThat(valueOf(ConfigPipeline.interpolation().apply(config), "weight"))
                .isEqualTo(ConfigMap.of(Map.of("car", ConfigInt.of(1))));
        }
    }

    @Nested
    @DisplayName("`??`: it descends only on absence and leaves its decision in the trace")
    class Fallback {

        @Test
        @DisplayName("the left-hand side exists -> the right-hand side is not checked; the left is absent -> the right wins")
        void fallbackChoosesByAbsence() {
            ConfigDefinition definition = ConfigDefinition.of(ConfigField.required("c", ConfigType.STR));
            Config config = Config.root(definition).apply(text(Map.of("a", "9001", "c", "${a ?? b}")));

            assertThat(valueOf(ConfigPipeline.interpolation().apply(config), "c")).isEqualTo(ConfigString.of("9001"));

            Config withoutLeft = Config.root(definition).apply(text(Map.of("b", "spare", "c", "${a ?? b}")));
            assertThat(valueOf(ConfigPipeline.interpolation().apply(withoutLeft), "c"))
                .isEqualTo(ConfigString.of("spare"));
        }

        @Test
        @DisplayName("the trace says which side was chosen and WHY (an object of its own)")
        void traceExplainsTheChoice() {
            ConfigDefinition definition = ConfigDefinition.of(ConfigField.required("c", ConfigType.STR));
            Config config = Config.root(definition)
                .apply(text(Map.of("new_port", "9001", "default_port", "8080", "c", "${new_port ?? default_port}")));

            Interpolation.Resolved resolution = ((ConfigResult.ValueResult<Interpolation.Resolved>)
                Interpolation.resolve(config, ConfigPath.of("c"))).value();

            assertThat(resolution.value()).isEqualTo(ConfigString.of("9001"));
            assertThat(resolution.trace()).hasSize(1);
            Interpolation.Decision decision = resolution.trace().get(0);
            assertThat(decision.chosen()).isEqualTo(Interpolation.Side.LEFT);
            assertThat(decision.reason()).contains("the left-hand side exists").contains("9001");
            assertThat(decision.right()).as("the right side was not checked — and the trace says so").isEmpty();
        }

        @Test
        @DisplayName("with the left-hand side absent the trace shows BOTH sides — that is why it is an object of its own")
        void traceShowsBothSides() {
            ConfigDefinition definition = ConfigDefinition.of(ConfigField.required("c", ConfigType.STR));
            Config config = Config.root(definition).apply(text(Map.of("default_port", "8080", "c", "${new_port ?? default_port}")));

            Interpolation.Resolved resolution = ((ConfigResult.ValueResult<Interpolation.Resolved>)
                Interpolation.resolve(config, ConfigPath.of("c"))).value();
            Interpolation.Decision decision = resolution.trace().get(0);

            assertThat(decision.chosen()).isEqualTo(Interpolation.Side.RIGHT);
            assertThat(decision.left()).isEqualTo(new Interpolation.Outcome.YieldsAbsent());
            assertThat(decision.right()).contains(new Interpolation.Outcome.Value(ConfigString.of("8080")));
            assertThat(decision.reason()).contains("there was NO left-hand side");
        }

        @Test
        @DisplayName("an error on the left-hand side is NOT swallowed as a fallback")
        void errorOnTheLeftIsPropagated() {
            ConfigDefinition definition = ConfigDefinition.of(ConfigField.required("c", ConfigType.STR));
            Config config = Config.root(definition)
                .apply(text(Map.of("broken", "${missing}", "b", "spare", "c", "${broken ?? b}")));

            assertThat(report(ConfigPipeline.interpolation().apply(config), "c"))
                .as("a typo must not become a silent default value")
                .contains("unresolved-reference");
        }
    }

    @Nested
    @DisplayName("cycles: a self-reference is no exception, and every participant gets the verdict")
    class Cycles {

        private ConfigValidator.Report build(ConfigDefinition definition, Config config) {
            return ConfigValidator.build(definition, config);
        }

        @Test
        @DisplayName("`a = ${b}, b = ${a}`: one cycle path, two FAILED verdicts — not two problems")
        void cycleMarksEveryParticipant() {
            ConfigDefinition definition = ConfigDefinition.of(
                ConfigField.required("a", ConfigType.STR), ConfigField.required("b", ConfigType.STR));
            Config config = Config.root(definition).apply(text(Map.of("a", "${b}", "b", "${a}")));

            Config result = ConfigPipeline.interpolation().apply(config);

            for (String path : List.of("a", "b")) {
                assertThat(result.entry(ConfigPath.of(path)).orElseThrow().problems())
                    .as("every participant of the cycle gets the same problem")
                    .hasSize(1)
                    .allSatisfy(error -> assertThat(error.kind()).isEqualTo(ConfigError.Kind.CYCLE));
            }
            assertThat(result.entry(ConfigPath.of("a")).orElseThrow().problems().get(0).report())
                .contains("a").contains("b");
        }

        @Test
        @DisplayName("a self-reference is a cycle of length one — also inside a text")
        void selfReferenceIsACycle() {
            ConfigDefinition definition = ConfigDefinition.of(ConfigField.required("a", ConfigType.STR));
            Config bare = ConfigPipeline.interpolation()
                .apply(Config.root(definition).apply(text(Map.of("a", "${a}"))));
            Config insideText = ConfigPipeline.interpolation()
                .apply(Config.root(definition).apply(text(Map.of("a", "${a}:x"))));

            assertThat(build(definition, bare).describe()).contains("cycle");
            assertThat(build(definition, insideText).describe())
                .as("a cycle inside a text is the same cycle").contains("cycle");
        }

        @Test
        @DisplayName("the report shows a cycle ONCE, although every participant carries a FAILED verdict")
        void cycleIsOneProblem() {
            ConfigDefinition definition = ConfigDefinition.of(
                ConfigField.required("a", ConfigType.STR), ConfigField.required("b", ConfigType.STR));
            Config config = Config.root(definition).apply(text(Map.of("a", "${b}", "b", "${a}")));

            ConfigValidator.Report report = build(definition, config);

            assertThat(report.errors())
                .as("a cycle discovered from `a` and from `b` is the SAME cycle — the paths are stored canonically")
                .hasSize(1);
            assertThat(report.errors().get(0).kind()).isEqualTo(ConfigError.Kind.CYCLE);
        }

        @Test
        @DisplayName("a cycle is reported ONCE per entry — a stopped entry goes no further")
        void cycleStopsLaterSteps() {
            ConfigDefinition definition = ConfigDefinition.of(ConfigField.required("a", ConfigType.INT));
            Config config = Config.root(definition).apply(text(Map.of("a", "${a}")));

            ConfigValidator.Report report = build(definition, config);

            assertThat(report.errors()).hasSize(1);
            assertThat(report.errors().get(0).kind()).isEqualTo(ConfigError.Kind.CYCLE);
        }
    }

    @Nested
    @DisplayName("reachability of auxiliary keys: participation in an expression counts, not a read at runtime")
    class Reachability {

        private ConfigDefinition definition() {
            return ConfigDefinition.of(ConfigField.required("port", ConfigType.INT));
        }

        private ConfigValidator.Report build(Config config) {
            return ConfigValidator.build(definition(), config);
        }

        @Test
        @DisplayName("an aux key used in `${…}` is not \"unused\", while an orphaned one is")
        void referencedAuxIsUsed() {
            Config config = Config.root(definition())
                .apply(text(Map.of("base", "8080", "port", "${base}")));

            assertThat(build(config).describe()).doesNotContain("unused-auxiliary");

            Config orphan = Config.root(definition())
                .apply(text(Map.of("base", "8080", "port", "9000", "orphan", "x")));
            assertThat(build(orphan).describe()).contains("unused-auxiliary").contains("orphan");
        }

        @Test
        @DisplayName("reachability is TRANSITIVE: `port -> a -> b` makes `b` used")
        void reachabilityIsTransitive() {
            Config config = Config.root(definition())
                .apply(text(Map.of("a", "${b}", "b", "8080", "port", "${a}")));

            assertThat(build(config).describe()).doesNotContain("unused-auxiliary");
        }

        @Test
        @DisplayName("the result does not depend on which branch of `??` fired")
        void bothFallbackSidesCountAsUsed() {
            Config config = Config.root(definition())
                .apply(text(Map.of("new", "1", "old", "2", "port", "${new ?? old}")));

            assertThat(build(config).describe()).doesNotContain("unused-auxiliary");
        }

        @Test
        @DisplayName("the analysis counts on the tree OF LAYERS — the result of the flow holds no symbols")
        void analysisNeedsTheLayerTree() {
            Config layers = Config.root(definition()).apply(text(Map.of("base", "8080", "port", "${base}")));
            Config stepped = ConfigPipeline.interpolation().apply(layers);

            assertThat(build(layers).describe()).doesNotContain("unused-auxiliary");
            assertThat(build(stepped).describe())
                .as("after the step the values are resolved, so there is nothing to analyse — and no false reports")
                .doesNotContain("unused-auxiliary");
            assertThat(build(stepped).values()).as("build() stays idempotent").isEqualTo(build(layers).values());
        }
    }
}
