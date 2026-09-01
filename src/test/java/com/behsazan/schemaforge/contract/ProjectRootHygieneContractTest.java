package com.behsazan.schemaforge.contract;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectRootHygieneContractTest {

    private static final Pattern RELEASE_NOTE_FILE =
            Pattern.compile(
                    "(?i)(README-R.*\\.txt|.*-CHANGED-FILES\\.txt)"
            );

    @Test
    void releaseSpecificFilesMustNotExistInProjectRoot() throws IOException {

        Path projectRoot = locateProjectRoot();

        List<String> violations;

        try (var stream = Files.list(projectRoot)) {
            violations = stream
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> RELEASE_NOTE_FILE.matcher(name).matches())
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }

        assertTrue(
                violations.isEmpty(),
                () -> """
                        Release-specific files must not exist in project root.

                        Move these files to:
                          docs/release/

                        Violations:
                        %s
                        """.formatted(String.join(System.lineSeparator(), violations))
        );
    }

    private Path locateProjectRoot() {

        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize();

        while (current != null) {

            if (Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }

            current = current.getParent();
        }

        throw new IllegalStateException(
                "Cannot locate SchemaForge project root containing pom.xml"
        );
    }
}