package dev.jucm.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Constraints: a **chain of conditions in one text**, checked after a successful cast — the dictionary,
 * the joining, the shape and the order.
 */
class ConfigConstraintTest {

    private static final ConfigSource CLI = ConfigSource.of(Layer.CLI, "--transport-protocol");

    private static ConfigConstraint.Const chain(String chain) {
        return ConfigConstraint.Const(chain);
    }

    @Test
    @DisplayName("`transport_protocol` accepts only `http` or `https` — anything else is VALIDATION_FAILED")
    void closedSetChoiceIsValidated() {
        ConfigDefinition definition = ConfigDefinition.of(
            ConfigField.required("transport_protocol", ConfigType.STR, ConfigString.of("https"))
                .constrained(chain("in[http,https]")));

        ConfigValidator.Report ok = ConfigValidator.build(definition,
            Config.root(definition).apply(ConfigPatch.empty()
                .with("transport_protocol", ConfigString.of("https"), CLI)));
        assertThat(ok.hasErrors()).isFalse();

        ConfigValidator.Report bad = ConfigValidator.build(definition,
            Config.root(definition).apply(ConfigPatch.empty()
                .with("transport_protocol", ConfigString.of("ftp"), CLI)));

        assertThat(bad.errors()).hasSize(1);
        ConfigError error = bad.errors().get(0);
        assertThat(error.kind()).as("the type matches, the rule does not")
            .isEqualTo(ConfigError.Kind.VALIDATION_FAILED);
        assertThat(error.report())
            .as("the message shows the WHOLE chain, because the chain is the rule")
            .contains("does not satisfy the rule: in[http,https]")
            .contains("path `transport_protocol`")
            .contains("value `ftp`")
            .contains("CLI:--transport-protocol");
    }

    @Test
    @DisplayName("order: cast first, then constraints — a bad value gives ONE error, not two")
    void castFailureDoesNotAlsoTriggerConstraint() {
        // the field is declared as INT, so the value cannot pass the cast — the rule has nothing to check
        ConfigDefinition asInt = ConfigDefinition.of(
            ConfigField.required("transport_protocol", ConfigType.INT, ConfigInt.of(1))
                .constrained(chain("in[1,2]")));

        ConfigValidator.Report report = ConfigValidator.build(asInt,
            Config.root(asInt).apply(ConfigPatch.empty()
                .with("transport_protocol", ConfigString.of("https"), CLI)));

        assertThat(report.errors()).extracting(ConfigError::kind).containsExactly(ConfigError.Kind.CAST_FAILED);
    }

    @Test
    @DisplayName("a condition works on numbers too — membership compares the notation")
    void worksForNonStringValues() {
        assertThat(chain("in[1,2,3]").accepts(ConfigInt.of(2))).isTrue();
        assertThat(chain("in[1,2,3]").accepts(ConfigInt.of(7))).isFalse();
    }

    @Test
    @DisplayName("the constraint sees the value AFTER the cast: `010` on an `int` field is `8` — the list is "
        + "written canonically")
    void constraintSeesTheCastValue() {
        ConfigDefinition canonical = ConfigDefinition.of(
            ConfigField.required("port", ConfigType.INT, ConfigInt.of(1))
                .constrained(chain("in[8,80]")));

        ConfigValidator.Report ok = ConfigValidator.build(canonical, Config.root(canonical)
            .apply(ConfigPatch.empty().with("port", ConfigString.of("010"), CLI)));

        assertThat(ok.errors()).as("the cast turned `010` into 8, so the rule sees `8`").isEmpty();

        ConfigDefinition written = ConfigDefinition.of(
            ConfigField.required("port", ConfigType.INT, ConfigInt.of(1))
                .constrained(chain("in[010]")));
        ConfigValidator.Report bad = ConfigValidator.build(written, Config.root(written)
            .apply(ConfigPatch.empty().with("port", ConfigString.of("010"), CLI)));

        assertThat(bad.errors()).extracting(ConfigError::kind)
            .as("the text written in the source is NOT what the rule sees")
            .containsExactly(ConfigError.Kind.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("`in[]` offers nothing to choose from — the declaration is rejected")
    void emptyChoiceIsMeaningless() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> chain("in[]"))
            .withMessageContaining("with an empty element means nothing");
    }

    @Test
    @DisplayName("exit code: a failed validation from the CLI is 43 (Y=3, decade 4)")
    void constraintFailureHasItsOwnCode() {
        ConfigError error = new ConfigError.ValidationFailed(ConfigPath.of("transport_protocol"),
            "in[http,https]", ConfigString.of("ftp"), CLI);

        assertThat(ConfigExitCode.of(error, ConfigExitCode.Pipeline.STANDARD)).isEqualTo(43);
    }

    @Nested
    @DisplayName("CHAIN: the separator IS the logic, and evaluation runs strictly left to right (NO precedence)")
    class Chain {

        @Test
        @DisplayName("`<2&>0` — two conditions joined with AND")
        void andChain() {
            assertThat(chain("<2&>0").accepts(ConfigInt.of(1))).isTrue();
            assertThat(chain("<2&>0").accepts(ConfigInt.of(0))).as("`>0` does not hold").isFalse();
            assertThat(chain("<2&>0").accepts(ConfigInt.of(3))).as("`<2` does not hold").isFalse();
        }

        @Test
        @DisplayName("NO precedence: `&` does NOT bind tighter than `|` — the order of the text decides")
        void evaluationIsStrictlyLeftToRight() {
            // ((v<10) | (v>5)) & (v!=7)  -> for 7: (T|T)&F = FALSE
            assertThat(chain("<10|>5&!=7").accepts(ConfigInt.of(7)))
                .as("if `&` bound tighter the result would be (v<10) | ((v>5)&(v!=7)) = TRUE")
                .isFalse();

            // ((v>1) & (v<10)) | (v!=7)  -> for 7: (T&T)|F = TRUE
            assertThat(chain(">1&<10|!=7").accepts(ConfigInt.of(7))).isTrue();
        }

        @Test
        @DisplayName("every element after the first MUST start with `&` or `|`")
        void separatorIsMandatory() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> chain("<2>0"))
                .withMessageContaining("looks like a missing separator");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> chain("<2&"))
                .withMessageContaining("a condition is missing");
        }

        @Test
        @DisplayName("a chain without a single condition says nothing — the declaration is rejected")
        void emptyChainIsMeaningless() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> chain("   "))
                .withMessageContaining("without conditions means nothing");
        }

        @Test
        @DisplayName("an unknown symbol is LOUD — the dictionary is closed, not \"let us see what comes out\"")
        void unknownSymbolIsLoud() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> chain("~<20"))
                .withMessageContaining("stand in backticks");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> chain(">1 && <2"))
                .withMessageContaining("unknown condition symbol");
        }

        @Test
        @DisplayName("a condition without an operand is rejected")
        void missingOperand() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> chain(">"))
                .withMessageContaining("without an operand means nothing");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> chain("<2&>"))
                .withMessageContaining("without an operand means nothing");
        }

        @Test
        @DisplayName("`\\<char>` protects a character: `=a\\&b` is ONE comparison, not two conditions")
        void escapeProtectsSeparator() {
            assertThat(chain("=a\\&b").accepts(ConfigString.of("a&b"))).isTrue();
            assertThat(chain("=a\\&b").accepts(ConfigString.of("a"))).isFalse();
            assertThat(chain("=a\\&b").conditions()).as("one comparison, not two").hasSize(1);
        }

        @Test
        @DisplayName("conditions and operators are visible for a report (the chain stays the notation contract)")
        void chainIsIntrospectable() {
            assertThat(chain("<2&>0").conditions()).containsExactly("<2", ">0");
            assertThat(chain("<2&>0").operators()).containsExactly("&");
            assertThat(chain("<2|>0&!=1").operators()).containsExactly("|", "&");
            assertThat(chain("<2&>0").chain()).isEqualTo("<2&>0");
        }
    }

    @Nested
    @DisplayName("ORDER conditions — they compare the typed value, never the notation")
    class Order {

        @Test
        @DisplayName("`<` / `<=`: the bound stays on the right-hand side")
        void lower() {
            assertThat(chain("<20").accepts(ConfigInt.of(19))).isTrue();
            assertThat(chain("<20").accepts(ConfigInt.of(20))).isFalse();
            assertThat(chain("<=20").accepts(ConfigInt.of(20))).isTrue();
            assertThat(chain("<20").accepts(ConfigFloat.of(19.5)))
                .as("a real value against an integer bound is compared as well").isTrue();
        }

        @Test
        @DisplayName("`>` / `>=`: the bound stays on the right-hand side")
        void greater() {
            assertThat(chain(">0").accepts(ConfigInt.of(1))).isTrue();
            assertThat(chain(">0").accepts(ConfigInt.of(0))).isFalse();
            assertThat(chain(">=0").accepts(ConfigInt.of(0))).isTrue();
        }

        @Test
        @DisplayName("a RANGE is TWO conditions — the four closures as before, with no kinds of their own")
        void rangesAreTwoConditions() {
            assertThat(chain(">1&<5").accepts(ConfigInt.of(3))).isTrue();
            assertThat(chain(">1&<5").accepts(ConfigInt.of(1))).as("both ends OPEN").isFalse();
            assertThat(chain(">1&<5").accepts(ConfigInt.of(5))).as("both ends OPEN").isFalse();

            assertThat(chain(">=1&<5").accepts(ConfigInt.of(1))).as("LEFT closed").isTrue();
            assertThat(chain(">=1&<5").accepts(ConfigInt.of(5))).isFalse();

            assertThat(chain(">1&<=5").accepts(ConfigInt.of(5))).as("RIGHT closed").isTrue();
            assertThat(chain(">1&<=5").accepts(ConfigInt.of(1))).isFalse();

            assertThat(chain(">=1&<=5").accepts(ConfigInt.of(1))).as("both closed").isTrue();
            assertThat(chain(">=1&<=5").accepts(ConfigInt.of(5))).isTrue();
        }

        @Test
        @DisplayName("an IMPOSSIBLE range (`>10&<1`) is a bad CONFIGURATION — there is no \"reversed bounds\" category")
        void impossibleRangeIsConfigurationFailure() {
            ConfigDefinition definition = ConfigDefinition.of(
                ConfigField.required("timeout", ConfigType.INT, ConfigInt.of(5))
                    .constrained(chain(">10&<1")));
            ConfigValidator.Report report = ConfigValidator.build(definition,
                Config.root(definition).apply(ConfigPatch.empty().with("timeout", ConfigInt.of(5), CLI)));

            assertThat(report.errors()).extracting(ConfigError::kind)
                .containsExactly(ConfigError.Kind.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("order requires a NUMBER — text as a bound is a declaration error, not a silent comparison")
        void orderBoundMustBeANumber() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> chain("<abc"))
                .withMessageContaining("requires a number or a moment in single quotes");
        }
    }

    @Nested
    @DisplayName("SHAPE conditions — they compare the notation (`display()`)")
    class Shape {

        @Test
        @DisplayName("`=` / `!=` compare the NOTATION: `8` is `8`, and `010` is not")
        void equalComparesDisplay() {
            assertThat(chain("=8").accepts(ConfigInt.of(8))).isTrue();
            assertThat(chain("=010").accepts(ConfigInt.of(8)))
                .as("the canonical form of a number is `8`, so the text `010` is not equal to the value")
                .isFalse();
            assertThat(chain("!=010").accepts(ConfigInt.of(8))).isTrue();
        }

        @Test
        @DisplayName("`!in[…]` is the complement of `in[…]`")
        void negatedList() {
            assertThat(chain("!in[a,b]").accepts(ConfigString.of("c"))).isTrue();
            assertThat(chain("!in[a,b]").accepts(ConfigString.of("a"))).isFalse();
        }
    }

    @Nested
    @DisplayName("regular expressions: `` ~`body` `` — the backtick closes the body, the dot is a regex dot")
    class Regex {

        @Test
        @DisplayName("the default rule of the `uri` type is a regex on the scheme — an address carrying `://`")
        void uriShape() {
            ConfigConstraint uri = ConfigType.URI.defaultConstraint().orElseThrow();

            assertThat(uri.accepts(ConfigString.of("https://api.openai.com"))).isTrue();
            assertThat(uri.accepts(ConfigString.of("base64://SGVsbG8="))).isTrue();
            assertThat(uri.accepts(ConfigString.of("api.openai.com"))).as("no `://`").isFalse();
            assertThat(uri.accepts(ConfigString.of("https:/api"))).isFalse();
            assertThat(uri.rule()).isEqualTo("~`^[a-z][a-z0-9+.\\-]*://.*`");
        }

        @Test
        @DisplayName("the dot IS a regex dot — that is the difference from the former `glob`")
        void dotIsRegexNow() {
            assertThat(chain("~`a.b`").accepts(ConfigString.of("a.b"))).isTrue();
            assertThat(chain("~`a.b`").accepts(ConfigString.of("axb")))
                .as("a regex accepts `a, anything, b` — that is why the address rule is a regex, not a glob")
                .isTrue();
        }

        @Test
        @DisplayName("the body is DELIMITED by backticks, so a `|` inside belongs to the expression")
        void bodyIsDelimitedSoOperatorsInsideAreRegex() {
            assertThat(chain("~`(a|b)`").conditions()).as("ONE element, not two").hasSize(1);
            assertThat(chain("~`(a|b)`").accepts(ConfigString.of("a"))).isTrue();
            assertThat(chain("~`(a|b)`").accepts(ConfigString.of("b"))).isTrue();
            assertThat(chain("~`(a|b)`").accepts(ConfigString.of("c"))).isFalse();
            assertThat(chain("~`(a|b)`&!=c").conditions()).hasSize(2);
        }

        @Test
        @DisplayName("a comma inside the quantifier `a{1,3}` is not a list separator — the body takes the whole")
        void regexTakesWholeBody() {
            assertThat(chain("~`a{1,3}`").accepts(ConfigString.of("aaa"))).isTrue();
            assertThat(chain("~`a{1,3}`").accepts(ConfigString.of("aaaa"))).isFalse();
        }

        @Test
        @DisplayName("a literal pipe in the body: `\\|` reaches the regex engine when it has to")
        void escapedPipeStaysLiteral() {
            assertThat(chain("~`a\\|b`").accepts(ConfigString.of("a|b"))).isTrue();
            assertThat(chain("~`a\\|b`").accepts(ConfigString.of("a"))).isFalse();
        }

        @Test
        @DisplayName("`!~` is the negation of a match")
        void negation() {
            assertThat(chain("!~`^http.*`").accepts(ConfigString.of("ftp://x"))).isTrue();
            assertThat(chain("!~`^http.*`").accepts(ConfigString.of("http://x"))).isFalse();
        }

        @Test
        @DisplayName("the body of a regex MUST stand in backticks, and MAY not be empty")
        void bodyMustBeInBackticks() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> chain("~abc"))
                .withMessageContaining("stand in backticks");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> chain("~`abc"))
                .withMessageContaining("not closed with a backtick");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> chain("~``"))
                .withMessageContaining("means nothing");
        }
    }

    @Nested
    @DisplayName("maps: keys and values")
    class Maps {

        private ConfigMap map() {
            return ConfigMap.of(new java.util.LinkedHashMap<>(java.util.Map.of(
                "host", ConfigString.of("example.org"),
                "port", ConfigInt.of(8080))));
        }

        @Test
        @DisplayName("`keys[…]`: the keys of the map MUST be a SUBSET of the list — nothing from outside")
        void keys() {
            assertThat(chain("keys[host,port]").accepts(map())).isTrue();
            assertThat(chain("keys[host]").accepts(map()))
                .as("`port` is a key outside the list, so the rule does not hold").isFalse();
        }

        @Test
        @DisplayName("`values[…]`: every value of the map must be on the list")
        void values() {
            assertThat(chain("values[example.org,8080]").accepts(map())).isTrue();
            assertThat(chain("values[example.org]").accepts(map())).isFalse();
        }
    }

    @Nested
    @DisplayName("MODEL ERROR: a condition contradicting the declared type of the field")
    class ModelMismatch {

        private ConfigValidator.Report report(String value) {
            ConfigDefinition definition = ConfigDefinition.of(
                ConfigField.required("timeout", ConfigType.STR, ConfigString.of("x"))
                    .constrained(chain("<20")));
            return ConfigValidator.build(definition, Config.root(definition).apply(ConfigPatch.empty()
                .with("timeout", ConfigString.of(value), CLI)));
        }

        @Test
        @DisplayName("`<20` on a `str` field is a RULE_MISMATCH — a declaration, not a configuration")
        void orderRuleOnTextIsAModelError() {
            ConfigValidator.Report report = report("30");

            assertThat(report.errors()).hasSize(1);
            assertThat(report.errors().get(0).kind())
                .as("this is not a bad value but a contradictory rule — the message says WHY")
                .isEqualTo(ConfigError.Kind.RULE_MISMATCH);
            assertThat(report.errors().get(0).message())
                .as("the message says WHICH operand and why — not \"a bad value\"")
                .contains("cannot judge this field").contains("compares numbers").contains("field is `str`");
            assertThat(ConfigError.valueOf(report.errors().get(0)))
                .as("the value is not the culprit, so there is nothing to show as `actual`").isEmpty();
        }

        @Test
        @DisplayName("a MIXED chain: the gate is computed for ALL conditions BEFORE the result")
        void mixedChainStillReportsTheModelError() {
            // `<5|in[1,2]` on a `str` field: if `||` short-circuited on its second branch, the declaration
            // error would hide — and it is a contradiction of the declaration, so it MUST stay visible
            ConfigDefinition definition = ConfigDefinition.of(
                ConfigField.required("x", ConfigType.STR, ConfigString.of("9"))
                    .constrained(chain("<5|in[1,2]")));
            ConfigValidator.Report report = ConfigValidator.build(definition,
                Config.root(definition).apply(ConfigPatch.empty()
                    .with("x", ConfigString.of("9"), CLI)));

            assertThat(report.errors()).extracting(ConfigError::kind)
                .containsExactly(ConfigError.Kind.RULE_MISMATCH);
        }

        @Test
        @DisplayName("a map condition on a `str` field is a model error too")
        void mapRuleOnTextIsAModelError() {
            ConfigDefinition definition = ConfigDefinition.of(
                ConfigField.required("extra", ConfigType.STR, ConfigString.of("x"))
                    .constrained(chain("keys[a]")));
            ConfigValidator.Report report = ConfigValidator.build(definition,
                Config.root(definition).apply(ConfigPatch.empty()
                    .with("extra", ConfigString.of("x"), CLI)));

            assertThat(report.errors()).hasSize(1);
            assertThat(report.errors().get(0).kind()).isEqualTo(ConfigError.Kind.RULE_MISMATCH);
        }

        @Test
        @DisplayName("`duration` is a STRING after the cast — order on it is a model error, not a silent text comparison")
        void orderOnDurationIsAModelError() {
            // `10s` < `9s` lexicographically, although 10 s > 9 s — that is why a duration has NO order:
            // the value is a string in canonical form (`1h30m` -> `90m`) and a string misleads
            ConfigDefinition definition = ConfigDefinition.of(
                ConfigField.required("timeout", ConfigType.DURATION, ConfigString.of("30s"))
                    .constrained(chain(">'2030-01-01T00:00:00Z'")));
            ConfigValidator.Report report = ConfigValidator.build(definition,
                Config.root(definition).apply(ConfigPatch.empty()
                    .with("timeout", ConfigString.of("1h30m"), CLI)));

            assertThat(report.errors()).extracting(ConfigError::kind)
                .containsExactly(ConfigError.Kind.RULE_MISMATCH);
        }

        @Test
        @DisplayName("`=[1,2]` on a `str` field is a model error — a list literal requires a list")
        void listLiteralOnTextIsAModelError() {
            ConfigDefinition definition = ConfigDefinition.of(
                ConfigField.required("x", ConfigType.STR, ConfigString.of("a"))
                    .constrained(chain("=[1,2]")));
            ConfigValidator.Report report = ConfigValidator.build(definition,
                Config.root(definition).apply(ConfigPatch.empty()
                    .with("x", ConfigString.of("a"), CLI)));

            assertThat(report.errors()).extracting(ConfigError::kind)
                .containsExactly(ConfigError.Kind.RULE_MISMATCH);
        }
    }

    @Nested
    @DisplayName("COLLECTION LITERALS: `=[…]` / `={…}` compare the shape of the WHOLE collection")
    class CollectionLiterals {

        private ConfigSeq seq(ConfigValue... elements) {
            return ConfigSeq.of(java.util.List.of(elements));
        }

        private ConfigMap map() {
            return ConfigMap.of(new java.util.LinkedHashMap<>(java.util.Map.of(
                "host", ConfigString.of("example.org"),
                "port", ConfigInt.of(8080))));
        }

        @Test
        @DisplayName("`=[1,2,3]` matches a list although `display()` writes `[1, 2, 3]` — with spaces")
        void listLiteralComparesCanonically() {
            ConfigSeq value = seq(ConfigInt.of(1), ConfigInt.of(2), ConfigInt.of(3));

            assertThat(value.display()).as("the canonical notation of a list carries spaces").isEqualTo("[1, 2, 3]");
            assertThat(chain("=[1,2,3]").accepts(value))
                .as("written without spaces, compared on the canonical form").isTrue();
            assertThat(chain("!=[1,2,3]").accepts(value)).isFalse();
            assertThat(chain("=[1,2,4]").accepts(value)).isFalse();
        }

        @Test
        @DisplayName("`={a:1}` — a map is compared by entries; an extra entry is already another map")
        void mapLiteralComparesCanonically() {
            assertThat(chain("={host: example.org, port: 8080}").accepts(map())).isTrue();
            assertThat(chain("={host: example.org}").accepts(map()))
                .as("shape is not a subset — a missing `port` is another map").isFalse();
            assertThat(chain("={port: 8080, host: example.org}").accepts(map()))
                .as("the order inside the literal does not change the shape").isTrue();
        }

        @Test
        @DisplayName("literals nest, and every level goes by its own canonical form")
        void nestedLiterals() {
            assertThat(chain("=[[1,2],3]").accepts(seq(seq(ConfigInt.of(1), ConfigInt.of(2)), ConfigInt.of(3))))
                .isTrue();
            ConfigMap nested = ConfigMap.of(new java.util.LinkedHashMap<>(java.util.Map.of(
                "a", seq(ConfigInt.of(1), ConfigInt.of(2)))));
            assertThat(nested.display()).isEqualTo("{a: [1, 2]}");
            assertThat(chain("={a: [1,2]}").accepts(nested)).isTrue();
        }

        @Test
        @DisplayName("`\\,` protects a comma INSIDE an element — without the escape it is two values, not one")
        void commaInsideElementIsEscaped() {
            ConfigSeq value = seq(ConfigString.of("a,b"));

            assertThat(value.display()).isEqualTo("[a,b]");
            assertThat(chain("=[a\\,b]").accepts(value)).isTrue();
            assertThat(chain("=[a,b]").accepts(value))
                .as("two elements `a` and `b` are a different shape than a single `a,b`").isFalse();
        }
    }

    @Nested
    @DisplayName("`c[…]` / `c{…}`: the value CONTAINS at least everything listed")
    class Containment {

        private ConfigSeq seq(ConfigValue... elements) {
            return ConfigSeq.of(java.util.List.of(elements));
        }

        private ConfigMap map() {
            return ConfigMap.of(new java.util.LinkedHashMap<>(java.util.Map.of(
                "host", ConfigString.of("example.org"),
                "port", ConfigInt.of(8080))));
        }

        @Test
        @DisplayName("on a sequence: every listed element must be there — extra ones do not hurt")
        void sequenceContainsElements() {
            ConfigSeq value = seq(ConfigInt.of(1), ConfigInt.of(2), ConfigInt.of(3));

            assertThat(chain("c[1,3]").accepts(value)).isTrue();
            assertThat(chain("c[1,4]").accepts(value)).as("`4` is absent").isFalse();
            assertThat(chain("c[1]").accepts(value)).as("at LEAST those — it may hold more").isTrue();
            assertThat(chain("!c[1,4]").accepts(value)).isTrue();
        }

        @Test
        @DisplayName("on a map `c[…]` goes by KEYS — values have `values[…]` of their own")
        void mapContainsKeys() {
            assertThat(chain("c[host,port]").accepts(map())).isTrue();
            assertThat(chain("c[host]").accepts(map())).isTrue();
            assertThat(chain("c[host,extra]").accepts(map())).isFalse();
        }

        @Test
        @DisplayName("on a map `c{…}` goes by ENTRIES: the key AND the value must match")
        void mapContainsEntries() {
            assertThat(chain("c{host: example.org}").accepts(map())).isTrue();
            assertThat(chain("c{host: example.org, port: 8080}").accepts(map())).isTrue();
            assertThat(chain("c{host: wrong}").accepts(map())).as("the key is there, the value differs").isFalse();
            assertThat(chain("c{extra: 1}").accepts(map())).isFalse();
            assertThat(chain("!c{host: wrong}").accepts(map())).isTrue();
        }

        @Test
        @DisplayName("\"contains\" requires a collection, and entries require a map")
        void containmentNeedsACollection() {
            assertThat(chain("c[1]").appliesTo(ConfigString.of("1"))).isFalse();
            assertThat(chain("c{a: 1}").appliesTo(seq(ConfigInt.of(1)))).as("entries require a map").isFalse();
        }

        @Test
        @DisplayName("`c[]` and `c{}` say nothing — the declaration is rejected")
        void emptyContainmentIsMeaningless() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> chain("c[]"))
                .withMessageContaining("means nothing");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> chain("c{}"))
                .withMessageContaining("means nothing");
        }
    }

    @Nested
    @DisplayName("ORDER ON TIME: `<'2030-01-01T00:00:00Z'` — a moment against a moment, NEVER text")
    class TimeOrder {

        private ConfigTimestamp at(String iso) {
            return ConfigTimestamp.of(java.time.Instant.parse(iso));
        }

        @Test
        @DisplayName("moments are compared as moments, and `=` compares the canonical notation")
        void comparesMoments() {
            assertThat(chain("<'2030-01-01T00:00:00Z'").accepts(at("2026-01-01T12:00:00Z"))).isTrue();
            assertThat(chain(">'2030-01-01T00:00:00Z'").accepts(at("2026-01-01T12:00:00Z"))).isFalse();
            assertThat(chain(">='2026-01-01T12:00:00Z'").accepts(at("2026-01-01T12:00:00Z"))).isTrue();
            assertThat(chain("='2026-01-01T12:00:00Z'").accepts(at("2026-01-01T12:00:00Z")))
                .as("`=` compares the canonical notation — and here both sides are canonical").isTrue();
        }

        @Test
        @DisplayName("THE SAME moment written differently: `13:00+02:00` == `11:00Z` — text would lose that")
        void timeIsNotText() {
            assertThat(chain("<'2026-01-01T13:00:00+02:00'").accepts(at("2026-01-01T11:00:00Z")))
                .as("as text `11:00:00Z` < `13:00:00+02:00`, but it is the SAME moment")
                .isFalse();
            assertThat(chain("<='2026-01-01T13:00:00+02:00'").accepts(at("2026-01-01T11:00:00Z"))).isTrue();
        }

        @Test
        @DisplayName("the operand decides the kind of comparison: a moment against a number and a number "
            + "against a moment are model errors")
        void operandTypeMustMatchTheValue() {
            assertThat(chain("<'2030-01-01T00:00:00Z'").appliesTo(ConfigInt.of(1))).isFalse();
            assertThat(chain("<20").appliesTo(at("2026-01-01T12:00:00Z")))
                .as("a number does not order a moment — 20 of what?").isFalse();
        }

        @Test
        @DisplayName("the operand of order on time MUST be canonical and closed on both sides")
        void operandMustBeCanonicalAndClosed() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> chain("<'01.01.2030'"))
                .withMessageContaining("requires the canonical form");
            assertThatIllegalArgumentException()
                .isThrownBy(() -> chain("<'2030-01-01T00:00:00Z"))
                .withMessageContaining("single quotes on both sides");
        }

        @Test
        @DisplayName("order on `duration` and `uri` cannot even be WRITTEN — and the message says why")
        void durationHasNoOrderAndSaysSo() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> chain("<'PT30S'"))
                .withMessageContaining("but got `PT30S`")
                .withMessageContaining("are STRINGS")
                .withMessageContaining("in[...]");
        }
    }

    @Nested
    @DisplayName("LOUD syntax errors: a literal is not a condition, and `![…]` no longer means \"not one of\"")
    class LoudSyntaxErrors {

        @Test
        @DisplayName("`[1,2,3]` names the symbol you meant — a collection literal is not a condition")
        void literalIsNotACondition() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> chain("[1,2,3]"))
                .withMessageContaining("a collection literal is not a condition")
                .withMessageContaining("in[...]");
        }

        @Test
        @DisplayName("`![a,b]` points at `!in[a,b]` — because `[…]` is a list literal now, not a list of allowed values")
        void oldNegatedListNamesTheReplacement() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> chain("![a,b]"))
                .withMessageContaining("write `!in[...]`");
        }

        @Test
        @DisplayName("an unknown symbol lists the whole dictionary")
        void unknownSymbolListsTheDictionary() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> chain("?x"))
                .withMessageContaining("in[")
                .withMessageContaining("c[");
        }
    }

    @Nested
    @DisplayName("RULE PARAMETERS AS INTERPOLATIONS: `<${min_port}` — resolved by the same resolver as values")
    class Parameters {

        private ConfigValidator.Report build(ConfigDefinition definition, String path, String value) {
            return ConfigValidator.build(definition, Config.root(definition).apply(ConfigPatch.empty()
                .with(path, ConfigString.of(value), CLI)));
        }

        @Test
        @DisplayName("a template: `${…}` PARSES, its parameters are visible — and it cannot compute yet")
        void templateKnowsItsParameters() {
            assertThat(chain("<${min_port}").parameters()).containsExactly("min_port");
            assertThat(chain("<${min_port}").conditions())
                .as("the template stays a template — the chain is the notation contract")
                .containsExactly("<${min_port}");
            assertThat(chain("=${host}").parameters()).containsExactly("host");
            assertThat(chain("in[${a}]").parameters())
                .as("a list too: substitution is TEXTUAL, so the element receives a ready value")
                .containsExactly("a");
            assertThat(chain("~`^${x}$`").parameters())
                .as("inside backticks `${…}` is literal regex text — the body is literal").isEmpty();
            assertThatIllegalStateException()
                .isThrownBy(() -> chain("<${min_port}").accepts(ConfigInt.of(1)))
                .withMessageContaining("this is a TEMPLATE");
        }

        @Test
        @DisplayName("`>${min_port}` resolves to a value and the rule REALLY holds")
        void parameterResolvesAndTheRuleHolds() {
            ConfigDefinition definition = ConfigDefinition.of(
                ConfigField.required("min_port", ConfigType.INT, ConfigInt.of(1024)),
                ConfigField.required("port", ConfigType.INT).constrained(chain(">${min_port}")));

            assertThat(build(definition, "port", "2000").errors()).isEmpty();

            ConfigValidator.Report bad = build(definition, "port", "100");
            assertThat(bad.errors()).extracting(ConfigError::kind).contains(ConfigError.Kind.VALIDATION_FAILED);
            assertThat(bad.errors().get(0).report())
                .as("the message shows the value that WAS in force").contains("1024");
        }

        @Test
        @DisplayName("an unresolved reference is a RESOLUTION error, not a broken constraint")
        void unresolvedReferenceIsResolution() {
            ConfigDefinition definition = ConfigDefinition.of(
                ConfigField.required("port", ConfigType.INT).constrained(chain(">${no_such_thing}")));

            assertThat(build(definition, "port", "100").errors()).extracting(ConfigError::kind)
                .containsExactly(ConfigError.Kind.UNRESOLVED_REFERENCE);
        }

        @Test
        @DisplayName("`in[http,https]` on an `int` field is a RULE_MISMATCH — the ELEMENTS of the list must be of the type")
        void membershipElementsMustBeOfTheType() {
            ConfigDefinition definition = ConfigDefinition.of(
                ConfigField.required("port", ConfigType.INT, ConfigInt.of(80))
                    .constrained(chain("in[http,https]")));

            assertThat(build(definition, "port", "80").errors()).extracting(ConfigError::kind)
                .as("a list of words can never contain a number — refused, not compared as text")
                .containsExactly(ConfigError.Kind.RULE_MISMATCH);
            assertThat(build(definition, "port", "80").errors().get(0).message())
                .contains("`http`").contains("`int`");
        }

        @Test
        @DisplayName("a LIST parameter brings ELEMENTS: `in[${allowed}]` on an `int` works when they are numbers")
        void listParameterBringsElements() {
            ConfigDefinition definition = ConfigDefinition.of(
                ConfigField.required("allowed", ConfigType.STR, ConfigString.of("80,443")),
                ConfigField.required("port", ConfigType.INT).constrained(chain("in[${allowed}]")));

            assertThat(build(definition, "port", "443").errors())
                .as("the parameter brought the list; `443` is one of its elements").isEmpty();
            assertThat(build(definition, "port", "8080").errors()).extracting(ConfigError::kind)
                .containsExactly(ConfigError.Kind.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("a parameter of another type is refused on the type, not on the text: `str` with `9000` is still a `str`")
        void parameterOfAnotherTypeIsRefused() {
            ConfigDefinition definition = ConfigDefinition.of(
                ConfigField.required("limit", ConfigType.STR, ConfigString.of("9000")),
                ConfigField.required("port", ConfigType.INT).constrained(chain(">=${limit}")));

            assertThat(build(definition, "port", "8080").errors()).extracting(ConfigError::kind)
                .as("comparing `int` with `str` is apples with cars — even when the text looks numeric")
                .containsExactly(ConfigError.Kind.RULE_MISMATCH);
        }

        @Test
        @DisplayName("a resolved value must fit the operand: a `str` parameter where a number belongs is a RULE_MISMATCH")
        void resolvedValueMustFitTheOperand() {
            ConfigDefinition definition = ConfigDefinition.of(
                ConfigField.required("limit", ConfigType.STR, ConfigString.of("abc")),
                ConfigField.required("port", ConfigType.INT).constrained(chain(">${limit}")));

            assertThat(build(definition, "port", "100").errors()).extracting(ConfigError::kind)
                .as("`str` compared with `int` is apples with cars — refused even when the text looks numeric")
                .containsExactly(ConfigError.Kind.RULE_MISMATCH);
            assertThat(build(definition, "port", "100").errors().get(0).message())
                .contains("`${limit}`").contains("`str`").contains("`int`");
        }

        @Test
        @DisplayName("a list and an equality with a parameter: `in[${a}]`, `=${host}` — they work the same")
        void listsAndEqualityToo() {
            ConfigDefinition list = ConfigDefinition.of(
                ConfigField.required("allowed", ConfigType.STR, ConfigString.of("x")),
                ConfigField.required("transport_protocol", ConfigType.STR, ConfigString.of("https"))
                    .constrained(chain("in[${allowed}]")));
            assertThat(build(list, "transport_protocol", "x").errors()).isEmpty();
            assertThat(build(list, "transport_protocol", "y").errors())
                .extracting(ConfigError::kind).containsExactly(ConfigError.Kind.VALIDATION_FAILED);

            ConfigDefinition equal = ConfigDefinition.of(
                ConfigField.required("host", ConfigType.STR, ConfigString.of("example.org")),
                ConfigField.required("endpoint", ConfigType.STR, ConfigString.of("example.org"))
                    .constrained(chain("=${host}")));
            assertThat(build(equal, "endpoint", "example.org").errors()).isEmpty();
            assertThat(build(equal, "endpoint", "other").errors())
                .extracting(ConfigError::kind).containsExactly(ConfigError.Kind.VALIDATION_FAILED);
        }
    }

    @Test
    @DisplayName("`allowedValues()`: the CONSTRAINT says what may be chosen — and only for `in[…]`")
    void allowedValuesOnlyForPositiveInList() {
        assertThat(new ConfigConstraint.Const("in[8080,443]").allowedValues())
            .as("the list stated plainly — that is the source for a choice field").contains(List.of("8080", "443"));
        assertThat(new ConfigConstraint.Const("in[a,b]&>5").allowedValues())
            .as("an `in[…]` element inside a chain gives the list too").contains(List.of("a", "b"));

        assertThat(new ConfigConstraint.Const("!in[a,b]").allowedValues())
            .as("`!in[…]` gives NO list: it admits every other text, so a choice would be a lie")
            .isEmpty();
        assertThat(new ConfigConstraint.Const(">1024").allowedValues())
            .as("an order condition gives no list").isEmpty();
        assertThat(new ConfigConstraint.Const("keys[a,b]").allowedValues())
            .as("a list of map KEYS does not describe the value of the field").isEmpty();
        assertThat(new ConfigConstraint.Const("~`^in[a-z]`").allowedValues())
            .as("`in[` inside a regex body is a literal, not an element").isEmpty();
    }
}
