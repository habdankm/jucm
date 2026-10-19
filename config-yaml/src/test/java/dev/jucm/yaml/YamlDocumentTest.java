package dev.jucm.yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A settings file that a PERSON maintains, changed by a TOOL: the comments survive, nothing else moves, and a file that was
 * not changed is not touched at all.
 *
 * <p>Each test names the failure it prevents. The first is the one that motivated the class: a configuration written by
 * `dump()` would come back without the comments somebody wrote about their own deployment.
 */
class YamlDocumentTest {

    private static final String MASWERK_LIKE = """
        # maswerk, written by `maswerk init`. JUCM reads this file as the layer between the defaults in code
        # and the environment: defaults < this file < ENV < CLI.
        #
        # The workspace a flagless run uses. It is founded when missing (ADR-0024).
        workspace_default: /home/mecat/.maswerk/maswerk_workspace

        # The protocol settings are NOT written here on purpose (ADR-0019), and a copy in a file is a second
        # place for one truth to go stale. Uncomment a line to override it.
        # chunking_separator: "\\u007f"
        max_whole_file_bytes: 2500
        """;

    @Test
    @DisplayName("adding a connection keeps every comment and every other line, in order")
    void a_tool_added_entry_does_not_eat_the_file() {
        YamlDocument document = YamlDocument.parse(MASWERK_LIKE);

        document.putScalar("jdbc:postgresql://${JIDL_DEV_HOST}:5432/jidl_sandbox?user=${MASWERK__JIDL__DB__DEV__USER}&password=${MASWERK__JIDL__DB__DEV__PASSWORD}",
            "jidl", "connections", "dev");
        String rendered = document.toYaml();

        // NOTHING WAS LOST: every original line is still there, in the same order.
        List<String> before = new ArrayList<>(MASWERK_LIKE.lines().toList());
        List<String> after = new ArrayList<>(rendered.lines().toList());
        List<String> lost = new ArrayList<>(before);
        lost.removeAll(after);
        assertThat(lost).as("lines the writer dropped").isEmpty();

        // AND THE COMMENTS ARE THE GUARANTEE THIS CLASS EXISTS FOR.
        assertThat(rendered).contains("# maswerk, written by `maswerk init`");
        assertThat(rendered).contains("# chunking_separator:");
        assertThat(rendered).contains("workspace_default: /home/mecat/.maswerk/maswerk_workspace");

        // THE NEW ENTRY IS THERE, on ONE line - a URI wrapped across lines is a URI a human cannot copy.
        List<String> added = new ArrayList<>(after);
        added.removeAll(before);
        assertThat(added).hasSize(3);   // jidl:, connections:, dev:
        assertThat(added).anySatisfy(line -> assertThat(line).contains("jdbc:postgresql://").contains("password="));
    }

    @Test
    @DisplayName("a value that is already there as a scalar is never replaced by a structure")
    void a_mapping_for_an_address_that_holds_a_scalar_is_refused() {
        YamlDocument document = YamlDocument.parse("jidl: plain-text\n");

        assertThatThrownBy(() -> document.putScalar("x", "jidl", "connections", "dev"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("never overwritten");
    }

    @Test
    @DisplayName("an unchanged document does not touch the file; a changed one writes atomically and keeps the previous version")
    void write_is_only_a_write_when_something_changed(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("maswerk.yaml");
        Files.writeString(file, MASWERK_LIKE);
        String original = Files.readString(file);

        boolean written = YamlDocument.read(file).writeIfChanged(file);
        assertThat(written).as("nothing changed, so nothing was written").isFalse();
        assertThat(Files.readString(file)).isEqualTo(original);

        YamlDocument document = YamlDocument.read(file);
        document.putScalar("jdbc:h2:mem:test", "jidl", "connections", "test");
        assertThat(document.writeIfChanged(file)).isTrue();

        assertThat(Files.readString(file)).contains("jidl:").contains("jdbc:h2:mem:test");
        List<Path> backups = Files.list(directory)
            .filter(path -> path.getFileName().toString().contains(".bak-"))
            .toList();
        assertThat(backups).as("the replaced version is kept").hasSize(1);
        assertThat(Files.readString(backups.get(0))).isEqualTo(original);

        // AND IT IS STILL A DOCUMENT: what was written can be read back and answers the same.
        assertThat(YamlDocument.read(file).scalar("jidl", "connections", "test")).contains("jdbc:h2:mem:test");
    }

    @Test
    @DisplayName("every change keeps a copy, and the copies do not grow without bound")
    void each_change_is_backed_up_and_old_copies_are_pruned(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("maswerk.yaml");
        Files.writeString(file, MASWERK_LIKE);

        for (int change = 1; change <= 13; change++) {
            YamlDocument document = YamlDocument.read(file);
            document.putScalar("jdbc:h2:mem:v" + change, "jidl", "connections", "test");
            assertThat(document.writeIfChanged(file)).isTrue();
        }

        List<Path> backups = Files.list(directory)
            .filter(path -> path.getFileName().toString().contains(".bak-"))
            .sorted()
            .toList();
        assertThat(backups).as("thirteen changes, the newest ten copies kept").hasSize(10);
        // THE NEWEST COPY holds what the file said BEFORE the last change: v12, not v13.
        Path newest = backups.get(backups.size() - 1);
        assertThat(Files.readString(newest)).contains("jdbc:h2:mem:v12");
    }

    @Test
    @DisplayName("removing an entry that is not there is not an error - the caller wanted it gone")
    void removal_is_idempotent() {
        YamlDocument document = YamlDocument.parse("jidl:\n  connections:\n    dev: jdbc:h2:mem:x\n");

        document.remove("jidl", "connections", "dev").remove("jidl", "connections", "never");

        assertThat(document.has("jidl", "connections", "dev")).isFalse();
        assertThat(document.keys("jidl", "connections")).isEmpty();
    }
}
