package com.bootshift.tests.wiring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that every command the documentation shows a user typing actually exists.
 *
 * <p>Documentation drifts from a CLI silently: nothing fails when a command is renamed or never
 * built. Three cases were live in this repository at once — a whole `provenance` command with four
 * subcommands that was never implemented, a `verify-license` command documented with sample output,
 * and two nested commands presented as top-level. All three read perfectly.
 *
 * <p>The command surface is derived from the CLI sources rather than from a list someone maintains,
 * because a maintained list is the same problem one level up.
 */
class DocumentedCommandsExistTest {

    private static final Path ROOT = Path.of("").toAbsolutePath().getParent();

    private static final Pattern DECLARED = Pattern.compile("name = \"([a-z0-9-]+)\"");
    /**
     * A {@code bootshift <command>} invocation on ONE line.
     *
     * <p>Horizontal whitespace only. {@code \\s} spans newlines, and since the repository directory
     * is itself named {@code bootshift}, a snippet reading {@code cd bootshift} followed by
     * {@code mvn ...} on the next line matched as though {@code mvn} were a subcommand.
     */
    private static final Pattern INVOCATION =
            Pattern.compile("bootshift[ \\t]+([a-z][a-z0-9-]*)(?:[ \\t]+([a-z][a-z0-9-]*))?");

    /**
     * Words that follow a command as an argument or a value rather than as a nested command.
     * Kept explicit so a genuinely missing nested command cannot hide behind a loose rule.
     */
    private static final Set<String> ARGUMENT_WORDS = Set.of(
            "auto", "production", "development", "differential", "runtime", "tests", "build");

    private Set<String> declaredCommands() throws IOException {
        Set<String> declared = new LinkedHashSet<>();
        Path cli = ROOT.resolve("apps/migration-cli/src/main/java/com/bootshift/cli");
        try (Stream<Path> stream = Files.list(cli)) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = DECLARED.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    declared.add(m.group(1));
                }
            }
        }
        declared.add("help");
        return declared;
    }

    /**
     * Documents that describe the CLI to a user.
     *
     * <p>The build-and-review report is deliberately excluded: it names commands that did *not*
     * exist, as findings. A document that says "there is no such command" must be allowed to say so.
     */
    private List<Path> userFacingDocs() throws IOException {
        List<Path> docs = new ArrayList<>();
        docs.add(ROOT.resolve("README.md"));
        for (Path dir : List.of(ROOT.resolve(".claude/agents"), ROOT.resolve(".claude/skills"))) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(p -> p.toString().endsWith(".md")).forEach(docs::add);
            }
        }
        return docs;
    }

    @Test
    @DisplayName("every bootshift command shown in the documentation is declared by the CLI")
    void documentedCommandsAreDeclared() throws IOException {
        Set<String> declared = declaredCommands();
        assertThat(declared).contains("run", "inventory", "graph", "explain", "verify");

        List<String> undeclared = new ArrayList<>();
        for (Path doc : userFacingDocs()) {
            String text = Files.readString(doc, StandardCharsets.UTF_8);
            Matcher m = INVOCATION.matcher(text);
            while (m.find()) {
                String first = m.group(1);
                String second = m.group(2);
                if (!declared.contains(first)) {
                    undeclared.add(doc.getFileName() + ": bootshift " + first);
                    continue;
                }
                if (second != null && !declared.contains(second)
                        && !ARGUMENT_WORDS.contains(second) && second.length() > 3) {
                    undeclared.add(doc.getFileName() + ": bootshift " + first + " " + second);
                }
            }
        }

        assertThat(undeclared)
                .as("documentation must not show a user typing a command that does not exist")
                .isEmpty();
    }

    @Test
    @DisplayName("the nested commands the documentation marks as nested really are nested")
    void nestedCommandsAreNested() throws IOException {
        String graph = Files.readString(
                ROOT.resolve("apps/migration-cli/src/main/java/com/bootshift/cli/GraphCommand.java"),
                StandardCharsets.UTF_8);
        String inspection = Files.readString(
                ROOT.resolve("apps/migration-cli/src/main/java/com/bootshift/cli/InspectionCommands.java"),
                StandardCharsets.UTF_8);

        assertThat(graph).contains("name = \"file\"");
        assertThat(graph).contains("name = \"blast-radius\"");
        assertThat(graph).contains("name = \"symbol\"");
        assertThat(inspection).contains("ExplainCommand.Impact.class");
        assertThat(inspection).contains("ExplainCommand.Change.class");
    }
}
