package dev.jucm.yaml;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jucm.core.Config;
import dev.jucm.core.ConfigConstraint;
import dev.jucm.core.ConfigDefinition;
import dev.jucm.core.ConfigError;
import dev.jucm.core.ConfigField;
import dev.jucm.core.ConfigInt;
import dev.jucm.core.ConfigPatch;
import dev.jucm.core.ConfigResult;
import dev.jucm.core.ConfigString;
import dev.jucm.core.ConfigTimestamp;
import dev.jucm.core.ConfigType;
import dev.jucm.core.ConfigValidator;
import dev.jucm.core.TransformerRegistry;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Markers in a YAML comment: the `TCONF(…)` and `CONST(…)` sections.
 *
 * <p>THERE IS EXACTLY ONE PLACE FOR THEM — a comment on the SAME LINE as the value of a pair; any
 * other position is an ERROR, because a marker put anywhere else means a rule that does NOT work.
 * Sections are recognised from the BEGINNING of the comment (so that documentation prose quoting
 * `CONST(…)` does not take the file down).
 *
 * <p>A key outside the schema has no declaration in code, so its type comes from the FORM of the
 * node — that is why a rule from a comment works on an auxiliary list or map, and on an auxiliary
 * scalar only when it makes sense for `str` (otherwise it is a model error).
 */
class ConstraintMarkersTest {

    private static final TransformerRegistry REGISTRY = TransformerRegistry.defaults();

    private static final ConfigDefinition SCHEMA = ConfigDefinition.of(
        ConfigField.required("transport_protocol", ConfigType.STR, ConfigString.of("https")),
        ConfigField.required("retries", ConfigType.INT, ConfigInt.of(3)),
        ConfigField.required("start_date", ConfigType.TIMESTAMP,
            ConfigTimestamp.of(Instant.parse("2026-01-01T00:00:00Z"))));

    private static final ConfigDefinition NONE = ConfigDefinition.of();

    private static ConfigResult<ConfigPatch> read(String text, ConfigDefinition definition) {
        return YamlSource.of("application.yaml", text).patch(Config.root(definition), definition, REGISTRY);
    }

    private static ConfigValidator.Report report(String text, ConfigDefinition definition) {
        ConfigResult<ConfigPatch> result = read(text, definition);
        assertThat(result).as("expected entries, but there is an error: " + describe(result))
            .isInstanceOf(ConfigResult.ValueResult.class);
        ConfigPatch patch = ((ConfigResult.ValueResult<ConfigPatch>) result).value();
        return ConfigValidator.build(definition, Config.root(definition).apply(patch));
    }

    private static ConfigValidator.Report report(String text) {
        return report(text, SCHEMA);
    }

    private static ConfigError error(String text, ConfigDefinition definition) {
        ConfigResult<ConfigPatch> result = read(text, definition);
        assertThat(result).as("expected an error, but there are entries")
            .isInstanceOf(ConfigResult.ErrorResult.class);
        return ((ConfigResult.ErrorResult<ConfigPatch>) result).error();
    }

    private static String describe(ConfigResult<?> result) {
        return result instanceof ConfigResult.ErrorResult<?> bad ? bad.error().report() : "-";
    }

    @Nested
    @DisplayName("sections in the value line of a pair — the ONLY legal place")
    class Legal {

        @Test
        @DisplayName("a marker on an auxiliary COLLECTION states what it holds — the members' form decides, not a guess")
        void anAuxiliaryCollectionStatesWhatItHolds() {
            ConfigResult<ConfigPatch> read = read("features: [search, audit]   # CONST(c[search])\n", NONE);

            assertThat(read).isInstanceOf(ConfigResult.ValueResult.class);
            ConfigPatch patch = ((ConfigResult.ValueResult<ConfigPatch>) read).value();
            ConfigPatch.SetValue entry = (ConfigPatch.SetValue) patch.operation("features").orElseThrow();
            assertThat(entry.declaration().elementType())
                .as("every member is a scalar, so the file says `str` — the carrier hands over text and shape")
                .contains(ConfigType.STR);
        }

        @Test
        @DisplayName("a collection that MIXES forms states no element type — that is a loud error of the source")
        void aMixedCollectionIsLoud() {
            ConfigError error = error("features: [{a: 1}, 2]   # CONST(c[a])\n", NONE);

            assertThat(error.kind()).isEqualTo(ConfigError.Kind.PARSE_FAILED);
            assertThat(error.report())
                .contains("ONE type of thing").contains("`map`").contains("`str`");
        }

        @Test
        @DisplayName("a rule from a comment WORKS on a key outside the schema — the type comes from the node form")
        void ruleFromACommentWorksOnAnAuxiliary() {
            assertThat(report("features: [search, audit]   # CONST(c[search])\n", NONE).errors())
                .extracting(ConfigError::kind)
                .as("a sequence → type `seq`, so `c[…]` has something to stand on (and `unused-auxiliary` "
                    + "is the SCHEMA's business, not the marker's — nobody uses a key outside the schema)")
                .doesNotContain(ConfigError.Kind.VALIDATION_FAILED);

            assertThat(report("features: [search]   # CONST(c[audit])\n", NONE).errors())
                .extracting(ConfigError::kind)
                .as("the list has no element `audit`").containsExactly(ConfigError.Kind.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("two sections on ONE line, in any order, prose beside them ignored")
        void twoSectionsInAnyOrder() {
            assertThat(report("start_date: 07.03.2026   # TCONF(dd.MM.yyyy) CONST(<'2030-01-01T00:00:00Z') — project start\n")
                .errors()).as("the notation from the comment fills the field").isEmpty();

            assertThat(report("start_date: 07.03.2026   # CONST(<'2030-01-01T00:00:00Z') TCONF(dd.MM.yyyy) — order does not matter\n")
                .errors()).isEmpty();
        }

        @Test
        @DisplayName("a `)` INSIDE backticks belongs to the expression and does not close the section")
        void closingParenInsideBackticksBelongsToTheRegex() {
            assertThat(report("notes: a   # CONST(~`^(a|b)$`)\n", NONE).errors())
                .extracting(ConfigError::kind)
                .as("if the section ended at the `)` of the group, the body of the regex would be cut off")
                .doesNotContain(ConfigError.Kind.VALIDATION_FAILED, ConfigError.Kind.PARSE_FAILED);
        }

        @Test
        @DisplayName("prose BEFORE a section means \"this is not a marker\" — otherwise format docs would not pass")
        void proseBeforeSectionIsJustAComment() {
            assertThat(report("retries: 3   # here we write CONST(>=1) after a word — that is prose\n").errors())
                .as("a marker counts only from the BEGINNING of the comment").isEmpty();
        }

        @Test
        @DisplayName("a marker on a value that is a MAP is LOUD — a map is a set of ADDRESSES, not an entry")
        void markerOnAMapValueIsLoud() {
            // A comment next to `server:` lands as `IN_LINE` on the key — but there is no entry for
            // the map (entries come from the pairs inside), so a marker would have nothing to type.
            // A silent skip would mean a rule that does NOT work — hence an error.
            assertThat(error("server:            # CONST(c[host])\n  host: example.org\n", NONE).report())
                .contains("is a MAP")
                .contains("in CODE");
        }
    }

    @Nested
    @DisplayName("rejected positions — an ERROR with a position, because the rule would NOT work")
    class Misplaced {

        @Test
        @DisplayName("a file header: there is no pair the marker could belong to")
        void headerIsRejected() {
            assertThat(error("# CONST(<2&>0)\nretries: 3\n", SCHEMA).report())
                .contains("ITS OWN LINE");
        }

        @Test
        @DisplayName("a line of its own above a key and between pairs — also an ERROR (a `BLOCK` comment on the key)")
        void ownLineIsRejected() {
            assertThat(error("# TCONF(dd.MM.yyyy)\nstart_date: 07.03.2026\n", SCHEMA).report())
                .contains("ITS OWN LINE");
            assertThat(error("retries: 3\n# CONST(>0)\ntransport_protocol: https\n", SCHEMA).report())
                .contains("ITS OWN LINE");
        }

        @Test
        @DisplayName("a marker on a LIST ELEMENT — notation and rules concern the WHOLE value of a field")
        void sequenceElementIsRejected() {
            assertThat(error("hosts:\n  - api.example.com   # TCONF(trim)\n", SCHEMA).report())
                .contains("LIST ELEMENT");
        }

        @Test
        @DisplayName("a section without a close, a second one of the same kind and an empty one — all LOUD")
        void brokenSectionsAreLoud() {
            assertThat(error("retries: 3   # CONST(>0\n", SCHEMA).report())
                .contains("is not closed");
            assertThat(error("retries: 3   # CONST(>0) CONST(<10)\n", SCHEMA).report())
                .contains("a second time");
            assertThat(error("retries: 3   # TCONF(a) TCONF(b)\n", SCHEMA).report())
                .contains("a second time");
            assertThat(error("retries: 3   # CONST()\n", SCHEMA).report())
                .contains("means nothing");
        }

        @Test
        @DisplayName("a bad rule in `CONST(…)` is a SOURCE error with a file position, not a surprise in validation")
        void brokenChainPointsAtTheFile() {
            assertThat(error("retries: 3   # CONST(<2>0)\n", SCHEMA).report())
                .contains("application.yaml:1")
                .contains("missing separator");
        }
    }

    @Nested
    @DisplayName("the limits of a marker: the type comes from the form, and a rule from code wins")
    class Boundaries {

        @Test
        @DisplayName("a scalar outside the schema is `str`, so an ORDER rule on it is a MODEL error (not a silent result)")
        void orderRuleOnAScalarIsAModelError() {
            assertThat(report("retries: 3   # CONST(>0)\n", NONE).errors())
                .extracting(ConfigError::kind)
                .as("without a declaration in code a scalar is text — `\"10\" < \"2\"` is a trap, not a comparison")
                .containsExactly(ConfigError.Kind.RULE_MISMATCH);
        }

        @Test
        @DisplayName("a rule from a comment REPLACES the rules from code — the file wins over a `constrained(…)` list")
        void commentRuleReplacesTheCode() {
            ConfigDefinition schema = ConfigDefinition.of(
                ConfigField.required("retries", ConfigType.INT, ConfigInt.of(3))
                    .constrained(ConfigConstraint.Const(">=100")));

            assertThat(report("retries: 5   # CONST(>=1)\n", schema).errors())
                .as("`>=100` from code was REPLACED by the rule `>=1` from the file — 5 passes").isEmpty();

            assertThat(report("retries: 0   # CONST(>=1)\n", schema).errors())
                .extracting(ConfigError::kind)
                .as("and the rule from the file really does apply")
                .containsExactly(ConfigError.Kind.VALIDATION_FAILED);
        }
    }
}
