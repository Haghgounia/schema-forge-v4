package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.OutputFileNamer;
import com.behsazan.schemaforge.application.PreparedSchema;
import com.behsazan.schemaforge.application.SchemaPreparationService;
import com.behsazan.schemaforge.dialect.oracle.OracleDialect;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.specification.parser.SpecificationSource;
import com.behsazan.schemaforge.specification.parser.WordSpecificationParser;
import com.behsazan.schemaforge.specification.parser.legacy.LegacyWordSpecificationParser;
import com.behsazan.schemaforge.validation.oracle.OracleDdlSanityChecker;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Explicitly invoked batch test/runner.
 *
 * <p>It recursively scans an input directory, selects the standard parser for
 * current SchemaForge DOCX documents and falls back to the legacy parser for
 * old DOC/DOCX documents. For every accepted table document it emits exactly
 * one Oracle SQL script and no other database-platform artifact.</p>
 *
 * <p>Run explicitly:</p>
 * <pre>
 * mvnw.cmd -Dtest=WordDirectoryOracleGenerationIT \
 *   -Dschemaforge.word.inputDir=D:\\input \
 *   -Dschemaforge.word.outputDir=D:\\oracle-output \
 *   -Dschemaforge.word.legacySchema=DPS test
 * </pre>
 */
class WordDirectoryOracleGenerationIT {
    private static final String INPUT_DIR = "schemaforge.word.inputDir";
    private static final String OUTPUT_DIR = "schemaforge.word.outputDir";
    private static final String LEGACY_SCHEMA = "schemaforge.word.legacySchema";

    private final LegacyWordSpecificationParser legacyParser = new LegacyWordSpecificationParser();
    private final SchemaPreparationService preparationService = new SchemaPreparationService();
    private final OutputFileNamer outputFileNamer = new OutputFileNamer();
    private final OracleDdlSanityChecker sanityChecker = new OracleDdlSanityChecker();

    @Test
    void recursivelyGeneratesOracleScriptForEveryWordTableDocument() throws Exception {
        Path inputRoot = requiredDirectory(INPUT_DIR);
        Path outputRoot = outputDirectory(inputRoot);
        String legacySchema = trimToNull(System.getProperty(LEGACY_SCHEMA));
        Files.createDirectories(outputRoot);

        List<Path> documents;
        try (var paths = Files.walk(inputRoot)) {
            documents = paths.filter(Files::isRegularFile)
                    .filter(WordDirectoryOracleGenerationIT::isWordDocument)
                    .sorted(Comparator.comparing(path -> normalize(inputRoot.relativize(path))))
                    .toList();
        }

        String timestamp = outputFileNamer.timestamp();
        int generated = 0;
        int skipped = 0;
        int failed = 0;
        List<String> failures = new ArrayList<>();

        for (Path document : documents) {
            String relative = normalize(inputRoot.relativize(document));
            try {
                DatabaseSchema parsed = parse(inputRoot, document, legacySchema);
                if (parsed.tables().isEmpty()) {
                    skipped++;
                    System.out.println("[SKIPPED] " + relative + " - no table model");
                    continue;
                }

                PreparedSchema prepared = preparationService.prepare(parsed);
                String sql = new DdlGenerator(new OracleDialect())
                        .generate(prepared.schema(), prepared.validationReport());

                Path relativeParent = inputRoot.relativize(document).getParent();
                Path targetDirectory = relativeParent == null
                        ? outputRoot
                        : Files.createDirectories(outputRoot.resolve(relativeParent));
                String baseName = stripExtension(document.getFileName().toString());
                String fileName = outputFileNamer.scriptFileName(
                        baseName, DatabasePlatform.ORACLE,
                        OutputFileNamer.ScriptKind.DDL, timestamp);
                Path target = targetDirectory.resolve(fileName);
                sanityChecker.requireValid(sql, relative);
                Files.writeString(target, sql, StandardCharsets.UTF_8);
                generated++;
                System.out.println("[GENERATED] " + relative + " -> " + target);
            } catch (NoTableDocumentException exception) {
                skipped++;
                System.out.println("[SKIPPED] " + relative + " - " + exception.getMessage());
            } catch (Exception exception) {
                failed++;
                String message = exception.getClass().getSimpleName() + ": "
                        + (exception.getMessage() == null ? "" : exception.getMessage());
                failures.add(relative + " | " + message);
                System.out.println("[FAILED] " + relative + " - " + message);
            }
        }

        System.out.println("Word files : " + documents.size());
        System.out.println("Generated  : " + generated);
        System.out.println("Skipped    : " + skipped);
        System.out.println("Failed     : " + failed);
        System.out.println("Output     : " + outputRoot.toAbsolutePath());
        failures.forEach(value -> System.out.println("Failure    : " + value));

        assertTrue(generated > 0,
                "No Oracle script was generated. Check input documents and legacy schema property.");
    }

    private DatabaseSchema parse(Path inputRoot, Path document, String legacySchema) throws Exception {
        String lower = document.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".doc")) {
            return parseLegacy(inputRoot, document, legacySchema);
        }

        Exception standardFailure;
        try (InputStream input = Files.newInputStream(document)) {
            return new WordSpecificationParser().parse(
                    new SpecificationSource(document.getFileName().toString(), input));
        } catch (Exception exception) {
            standardFailure = exception;
        }

        try {
            return parseLegacy(inputRoot, document, legacySchema);
        } catch (IllegalArgumentException legacyFailure) {
            String legacyMessage = legacyFailure.getMessage() == null ? "" : legacyFailure.getMessage();
            if (legacyMessage.startsWith("No legacy table definition was accepted")) {
                throw new NoTableDocumentException(
                        "standard parser: " + safeMessage(standardFailure)
                                + "; legacy parser: " + legacyMessage);
            }
            legacyFailure.addSuppressed(standardFailure);
            throw legacyFailure;
        }
    }

    private DatabaseSchema parseLegacy(Path inputRoot, Path document, String legacySchema) {
        if (legacySchema == null) {
            throw new IllegalArgumentException("System property " + LEGACY_SCHEMA
                    + " is required for legacy documents");
        }
        return legacyParser.parse(inputRoot, document, legacySchema);
    }

    private static Path requiredDirectory(String propertyName) {
        String value = trimToNull(System.getProperty(propertyName));
        if (value == null) {
            throw new IllegalArgumentException("Missing system property: " + propertyName);
        }
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Input directory does not exist: " + path);
        }
        return path;
    }

    private static Path outputDirectory(Path inputRoot) {
        String value = trimToNull(System.getProperty(OUTPUT_DIR));
        return value == null
                ? inputRoot.resolve("schemaforge-oracle-output").toAbsolutePath().normalize()
                : Path.of(value).toAbsolutePath().normalize();
    }

    private static boolean isWordDocument(Path path) {
        String name = path.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".doc") && !lower.endsWith(".docx")) {
            return false;
        }
        if (name.startsWith("~$") || name.startsWith("._") || name.startsWith(".")) {
            return false;
        }
        for (Path segment : path) {
            if ("__MACOSX".equalsIgnoreCase(segment.toString())) {
                return false;
            }
        }
        return true;
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String safeMessage(Exception exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }

    private static final class NoTableDocumentException extends Exception {
        private NoTableDocumentException(String message) {
            super(message);
        }
    }
}
