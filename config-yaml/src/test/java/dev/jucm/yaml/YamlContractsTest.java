package dev.jucm.yaml;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.common.ScalarStyle;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.schema.FailsafeSchema;
import org.snakeyaml.engine.v2.schema.JsonSchema;
import org.snakeyaml.engine.v2.schema.Schema;

/**
 * Pins of the YAML library behaviour that our syntax rests on.
 *
 * <p>These are not tests of our own logic: they are a <b>record of measured behaviour of a foreign
 * library</b>. Each case pins one fact, so that a version bump which changes it <b>lights up</b>
 * instead of quietly moving the ground under the loader.
 *
 * <p>Two facts are worth stating explicitly: (1) we read with the {@link FailsafeSchema} schema, so
 * {@code ${b}} does not get an {@code !ENV_VARIABLE} tag (the engine's own ENV interpolation written
 * in our syntax!), and (2) keys stay text — without the {@code Integer}/{@code Date} coercion that
 * {@code load()} would apply.
 */
class YamlContractsTest {

    private static final Compose COMPOSE = new Compose(
        LoadSettings.builder().setSchema(new FailsafeSchema()).setParseComments(true).build());

    private static Node compose(String document) {
        return new Compose(LoadSettings.builder().setSchema(new FailsafeSchema()).build())
            .composeString(document).orElseThrow();
    }

    private static Node valueNode(String document) {
        return ((MappingNode) compose(document)).getValue().get(0).getValueNode();
    }

    private static Node keyNode(String document) {
        return ((MappingNode) compose(document)).getValue().get(0).getKeyNode();
    }

    private static String rawText(Node node) {
        return ((ScalarNode) node).getValue();
    }

    private static String shortTag(Node node) {
        return node.getTag().getValue().replace("tag:yaml.org,2002:", "");
    }

    private static String tagUnder(Schema schema, String document) {
        Node node = new Compose(LoadSettings.builder().setSchema(schema).build())
            .composeString(document).orElseThrow();
        return shortTag(((MappingNode) node).getValue().get(0).getValueNode());
    }

    @Test
    @DisplayName("every control code of ours stays raw text (tag `str`) — YAML computes nothing")
    void everyControlCodeStaysPlainText() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("x: ${a}", "${a}");
        cases.put("x: ${?a}", "${?a}");
        cases.put("x: ${a ?? b}", "${a ?? b}");
        cases.put("x: $${a}", "$${a}");
        cases.put("x: ${a}${b}", "${a}${b}");
        cases.put("x: pre_${a}_post", "pre_${a}_post");
        cases.put("x: $ ${a}", "$ ${a}");
        cases.put("url: http://localhost:${port}/api", "http://localhost:${port}/api");
        cases.put("url: '${a}'", "${a}");
        cases.put("url: http://x/#${a}", "http://x/#${a}");   // `#` with NO space before it = part of the scalar

        cases.forEach((document, expected) -> {
            assertThat(rawText(valueNode(document))).as(document).isEqualTo(expected);
            assertThat(shortTag(valueNode(document))).as(document).isEqualTo("str");
        });
    }

    @Test
    @DisplayName("`${b}` does NOT get an `!ENV_VARIABLE` tag — that is why we read with `FailsafeSchema`")
    void failsafeKeepsOurSyntaxOurs() {
        assertThat(tagUnder(new FailsafeSchema(), "x: ${b}"))
            .as("our syntax stays ours")
            .isEqualTo("str");
        assertThat(tagUnder(new JsonSchema(), "x: ${b}"))
            .as("…while the engine's default schema has its OWN ENV interpolation in the same syntax")
            .isEqualTo("!ENV_VARIABLE");
        assertThat(tagUnder(new JsonSchema(), "x: pre_${b}_post"))
            .as("the collision concerns only a lone `${…}` — and that is the form we use most")
            .isEqualTo("str");
        assertThat(tagUnder(new FailsafeSchema(), "x: 8080"))
            .as("Failsafe types nothing: the declaration decides the type, not YAML")
            .isEqualTo("str");
    }

    @Test
    @DisplayName("keys stay TEXT — without the `Integer` and `Date` coercion that `load()` would apply")
    void keysStayText() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("\"${a}\": 1", "${a}");
        cases.put("${a}: 1", "${a}");
        cases.put("pre_${a}: 1", "pre_${a}");
        cases.put("${a ?? b}: 1", "${a ?? b}");
        cases.put("2026-09-12: x", "2026-09-12");
        cases.put("8080: x", "8080");
        cases.put("true: x", "true");

        cases.forEach((document, expected) -> {
            assertThat(rawText(keyNode(document))).as(document).isEqualTo(expected);
            assertThat(shortTag(keyNode(document))).as(document).isEqualTo("str");
        });
    }

    @Test
    @DisplayName("TRAP: a `#` after a space eats the interpolation (a YAML comment)")
    void hashAfterSpaceEatsTheInterpolation() {
        assertThat(rawText(valueNode("url: http://x/ #${a}")))
            .as("unquoted, the comment cuts everything from `#` — the interpolation is GONE")
            .isEqualTo("http://x/");
        assertThat(rawText(valueNode("url: \"http://x/ #${a}\"")))
            .as("inside quotes `#` is an ordinary character, so the interpolation survives")
            .isEqualTo("http://x/ #${a}");
    }

    @Test
    @DisplayName("TRAP: a `{` at the start of a value makes a flow mapping, not text")
    void bracesAtTheStartMakeAFlowMapping() {
        assertThat(valueNode("x: {a}")).isInstanceOf(MappingNode.class);
        assertThat(valueNode("x: ${a}"))
            .as("`${a}` is safe only because it starts with `$`")
            .isInstanceOf(ScalarNode.class);
    }

    @Test
    @DisplayName("SPACES: a plain scalar trims the edges but keeps the inner ones")
    void plainScalarTrimsEdgesButKeepsInnerSpaces() {
        assertThat(rawText(valueNode("x: ${a} "))).isEqualTo("${a}");
        assertThat(rawText(valueNode("x:   ${a}"))).isEqualTo("${a}");
        assertThat(rawText(valueNode("x: ${year} ${month}"))).as("an inner space is content")
            .isEqualTo("${year} ${month}");
        assertThat(rawText(valueNode("x: ${year}   ${month}"))).as("three spaces too, without folding")
            .isEqualTo("${year}   ${month}");
    }

    @Test
    @DisplayName("SPACES: inside quotes the edges are KEPT; a line break folds into one space")
    void quotedAndFoldedScalars() {
        assertThat(rawText(valueNode("x: \" ${a} \""))).isEqualTo(" ${a} ");
        assertThat(rawText(valueNode("x: ' ${a} '"))).isEqualTo(" ${a} ");
        assertThat(rawText(valueNode("x: ${year}\n   ${month}\n"))).isEqualTo("${year} ${month}");
        assertThat(rawText(valueNode("x: ${year}\n\n   ${month}\n"))).isEqualTo("${year}\n${month}");
    }

    @Test
    @DisplayName("SPACES: block styles differ in the ending — `|` keeps the newline, `|-` trims it, `>` folds")
    void blockScalarsDifferInTrailingNewline() {
        assertThat(rawText(valueNode("x: |\n  ${year} ${month}\n"))).isEqualTo("${year} ${month}\n");
        assertThat(rawText(valueNode("x: |-\n  ${year} ${month}\n"))).isEqualTo("${year} ${month}");
        assertThat(rawText(valueNode("x: >\n  ${year}\n  ${month}\n"))).isEqualTo("${year} ${month}\n");
    }

    @Test
    @DisplayName("an unterminated `${a` is not a YAML error — OUR parser has to report it")
    void unterminatedBracesAreNotAYamlError() {
        assertThat(rawText(valueNode("x: ${a"))).isEqualTo("${a");
        assertThat(rawText(valueNode("x: '${a'"))).isEqualTo("${a");
    }

    @Test
    @DisplayName("comments ARE in the tree (that is why we are on the engine) — and do not mix with the value")
    void commentsLiveInTheTree() {
        Node mapping = COMPOSE.composeString("# header\nport: 8080  # next to the value\n# tail\n").orElseThrow();
        MappingNode root = (MappingNode) mapping;
        NodeTuple first = root.getValue().get(0);

        assertThat(first.getKeyNode().getBlockComments()).as("the comment above the key").isNotNull();
        assertThat(first.getValueNode().getInLineComments()).as("the comment in the value line").isNotNull();
        assertThat(root.getEndComments()).as("the tail comment on the MAP").isNotNull();
        assertThat(rawText(first.getValueNode())).isEqualTo("8080");   // the value is untouched
        assertThat(((ScalarNode) first.getValueNode()).getScalarStyle()).isEqualTo(ScalarStyle.PLAIN);
    }
}
