package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.application.OutputFileNamer;
import com.behsazan.schemaforge.application.PreparedSchema;
import com.behsazan.schemaforge.application.SchemaPreparationService;
import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.specification.parser.SpecificationSource;
import com.behsazan.schemaforge.specification.parser.WordSpecificationParser;
import com.behsazan.schemaforge.specification.parser.legacy.LegacyWordSpecificationParser;
import com.behsazan.schemaforge.validation.db2zos.Db2ZosOfflineDdlValidator;
import com.behsazan.schemaforge.validation.oracle.OracleDdlSanityChecker;
import com.behsazan.schemaforge.validation.postgresql.PostgreSqlDdlSanityChecker;
import com.behsazan.schemaforge.validation.sqlserver.SqlServerOfflineDdlValidator;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Explicitly invoked recursive Legacy/standard Word batch generator for multiple DBMS dialects.
 *
 * <p>Each Word document is parsed and prepared exactly once. The same canonical model is then
 * rendered independently for Oracle, PostgreSQL, Microsoft SQL Server and Db2 for z/OS (or the configured
 * subset). Generated scripts are written under a per-platform directory while generation and
 * offline-validation findings are collected in CSV reports.</p>
 *
 * <p>This runner deliberately does not require every dialect to be clean. Its purpose is to
 * generate as much DDL as possible so dialect-specific defects can be measured before live
 * database execution. Set {@code schemaforge.word.failOnErrors=true} when a clean regression
 * gate is required.</p>
 */
class WordDirectoryMultiDatabaseGenerationIT {
    private static final String INPUT_DIR = "schemaforge.word.inputDir";
    private static final String OUTPUT_DIR = "schemaforge.word.outputDir";
    private static final String LEGACY_SCHEMA = "schemaforge.word.legacySchema";
    private static final String PLATFORMS = "schemaforge.word.platforms";
    private static final String FAIL_ON_ERRORS = "schemaforge.word.failOnErrors";
    private static final String PARSER_MODE = "schemaforge.word.parserMode";

    private static final List<DatabasePlatform> DEFAULT_PLATFORMS = List.of(
            DatabasePlatform.ORACLE,
            DatabasePlatform.POSTGRESQL,
            DatabasePlatform.SQLSERVER,
            DatabasePlatform.DB2_ZOS);

    private final LegacyWordSpecificationParser legacyParser = new LegacyWordSpecificationParser();
    private final SchemaPreparationService preparationService = new SchemaPreparationService();
    private final OutputFileNamer outputFileNamer = new OutputFileNamer();
    private final OracleDdlSanityChecker oracleSanityChecker = new OracleDdlSanityChecker();
    private final PostgreSqlDdlSanityChecker postgreSqlSanityChecker = new PostgreSqlDdlSanityChecker();
    private final SqlServerOfflineDdlValidator sqlServerValidator = new SqlServerOfflineDdlValidator();
    private final Db2ZosOfflineDdlValidator db2ZosValidator = new Db2ZosOfflineDdlValidator();

    @Test
    void recursivelyGeneratesConfiguredDatabaseScriptsForEveryWordTableDocument() throws Exception {
        Path inputRoot = requiredDirectory(INPUT_DIR);
        Path outputRoot = outputDirectory(inputRoot);
        String legacySchema = trimToNull(System.getProperty(LEGACY_SCHEMA));
        List<DatabasePlatform> platforms = configuredPlatforms();
        ParserMode parserMode = configuredParserMode();
        boolean failOnErrors = Boolean.parseBoolean(System.getProperty(FAIL_ON_ERRORS, "false"));
        Files.createDirectories(outputRoot);

        List<Path> documents;
        try (var paths = Files.walk(inputRoot)) {
            documents = paths.filter(Files::isRegularFile)
                    .filter(WordDirectoryMultiDatabaseGenerationIT::isWordDocument)
                    .filter(path -> !path.toAbsolutePath().normalize().startsWith(outputRoot))
                    .sorted(Comparator.comparing(path -> normalize(inputRoot.relativize(path))))
                    .toList();
        }

        String timestamp = outputFileNamer.timestamp();
        List<String> summary = new ArrayList<>();
        summary.add("document,platform,status,model_valid,validation_issue_count,output_file,error");
        List<String> issues = new ArrayList<>();
        issues.add("document,platform,stage,location,code,message,fragment");

        Map<DatabasePlatform, Integer> generated = new EnumMap<>(DatabasePlatform.class);
        Map<DatabasePlatform, Integer> generatedWithIssues = new EnumMap<>(DatabasePlatform.class);
        Map<DatabasePlatform, Integer> failed = new EnumMap<>(DatabasePlatform.class);
        platforms.forEach(platform -> {
            generated.put(platform, 0);
            generatedWithIssues.put(platform, 0);
            failed.put(platform, 0);
            try {
                Files.createDirectories(outputRoot.resolve(platform.commandLineName()));
            } catch (Exception exception) {
                throw new IllegalStateException("Cannot create output directory for " + platform, exception);
            }
        });

        int skipped = 0;
        int parseFailures = 0;

        for (Path document : documents) {
            String relative = normalize(inputRoot.relativize(document));
            PreparedSchema prepared;
            try {
                DatabaseSchema parsed = parse(inputRoot, document, legacySchema, parserMode);
                if (parsed.tables().isEmpty()) {
                    skipped++;
                    for (DatabasePlatform platform : platforms) {
                        summary.add(csvLine(relative, platform.commandLineName(), "SKIPPED_NO_TABLE",
                                "", "0", "", "No table model"));
                    }
                    System.out.println("[SKIPPED] " + relative + " - no table model");
                    continue;
                }
                prepared = preparationService.prepare(parsed);
            } catch (NoTableDocumentException exception) {
                skipped++;
                for (DatabasePlatform platform : platforms) {
                    summary.add(csvLine(relative, platform.commandLineName(), "SKIPPED_NO_TABLE",
                            "", "0", "", exception.getMessage()));
                }
                System.out.println("[SKIPPED] " + relative + " - " + exception.getMessage());
                continue;
            } catch (Exception exception) {
                parseFailures++;
                String message = exception.getClass().getSimpleName() + ": " + safeMessage(exception);
                for (DatabasePlatform platform : platforms) {
                    failed.compute(platform, (key, value) -> value + 1);
                    summary.add(csvLine(relative, platform.commandLineName(), "PARSE_FAILED",
                            "", "0", "", message));
                    issues.add(csvLine(relative, platform.commandLineName(), "PARSE", "", "PARSE_FAILED",
                            message, ""));
                }
                System.out.println("[PARSE-FAILED] " + relative + " - " + message);
                continue;
            }

            for (DatabasePlatform platform : platforms) {
                generatePlatform(inputRoot, outputRoot, document, relative, prepared, platform, timestamp,
                        summary, issues, generated, generatedWithIssues, failed);
            }
        }

        Path reportDirectory = Files.createDirectories(outputRoot.resolve("reports"));
        Path summaryFile = reportDirectory.resolve("word-multidb-generation-summary_" + timestamp + ".csv");
        Path issueFile = reportDirectory.resolve("word-multidb-generation-issues_" + timestamp + ".csv");
        Path textFile = reportDirectory.resolve("word-multidb-generation-summary_" + timestamp + ".txt");
        Files.writeString(summaryFile, String.join(System.lineSeparator(), summary) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        Files.writeString(issueFile, String.join(System.lineSeparator(), issues) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        Files.writeString(textFile, textSummary(inputRoot, outputRoot, documents.size(), skipped, parseFailures,
                parserMode, platforms, generated, generatedWithIssues, failed, timestamp), StandardCharsets.UTF_8);

        int totalGenerated = generated.values().stream().mapToInt(Integer::intValue).sum()
                + generatedWithIssues.values().stream().mapToInt(Integer::intValue).sum();
        int totalFailed = failed.values().stream().mapToInt(Integer::intValue).sum();

        System.out.println("Parser mode       : " + parserMode.name().toLowerCase(Locale.ROOT));
        System.out.println("Word files        : " + documents.size());
        System.out.println("Skipped           : " + skipped);
        System.out.println("Parse failures    : " + parseFailures);
        for (DatabasePlatform platform : platforms) {
            System.out.println(platform.commandLineName() + " generated       : " + generated.get(platform));
            System.out.println(platform.commandLineName() + " with issues     : " + generatedWithIssues.get(platform));
            System.out.println(platform.commandLineName() + " failed          : " + failed.get(platform));
        }
        System.out.println("Output            : " + outputRoot.toAbsolutePath());
        System.out.println("Summary report    : " + summaryFile.toAbsolutePath());
        System.out.println("Validation issues : " + issueFile.toAbsolutePath());

        assertTrue(totalGenerated > 0,
                "No SQL script was generated. Check input documents, platform selection and legacy schema property.");
        if (failOnErrors) {
            assertTrue(totalFailed == 0 && issues.size() == 1,
                    "Multi-database generation produced failures or validation issues. See: " + issueFile);
        }
    }

    private void generatePlatform(
            Path inputRoot,
            Path outputRoot,
            Path document,
            String relative,
            PreparedSchema prepared,
            DatabasePlatform platform,
            String timestamp,
            List<String> summary,
            List<String> issues,
            Map<DatabasePlatform, Integer> generated,
            Map<DatabasePlatform, Integer> generatedWithIssues,
            Map<DatabasePlatform, Integer> failed) {

        Path target = null;
        try {
            Dialect dialect = DialectFactory.create(platform);
            String sql = new DdlGenerator(dialect).generate(prepared.schema(), prepared.validationReport());

            Path relativeParent = inputRoot.relativize(document).getParent();
            Path platformRoot = outputRoot.resolve(platform.commandLineName());
            Path targetDirectory = relativeParent == null
                    ? platformRoot
                    : Files.createDirectories(platformRoot.resolve(relativeParent));
            String baseName = stripExtension(document.getFileName().toString());
            String fileName = outputFileNamer.scriptFileName(
                    baseName, platform, OutputFileNamer.ScriptKind.DDL, timestamp);
            target = targetDirectory.resolve(fileName);

            List<ValidationFinding> validationFindings = validate(platform, sql);
            Files.writeString(target, sql, StandardCharsets.UTF_8);

            if (validationFindings.isEmpty()) {
                generated.compute(platform, (key, value) -> value + 1);
                summary.add(csvLine(relative, platform.commandLineName(), "GENERATED",
                        Boolean.toString(prepared.validationReport().valid()), "0",
                        normalize(outputRoot.relativize(target)), ""));
                System.out.println("[GENERATED][" + platform.commandLineName() + "] " + relative);
            } else {
                generatedWithIssues.compute(platform, (key, value) -> value + 1);
                summary.add(csvLine(relative, platform.commandLineName(), "GENERATED_WITH_ISSUES",
                        Boolean.toString(prepared.validationReport().valid()),
                        Integer.toString(validationFindings.size()), normalize(outputRoot.relativize(target)), ""));
                for (ValidationFinding finding : validationFindings) {
                    issues.add(csvLine(relative, platform.commandLineName(), "STATIC_VALIDATION",
                            finding.location(), finding.code(), finding.message(), finding.fragment()));
                }
                System.out.println("[VALIDATION-ISSUES][" + platform.commandLineName() + "] "
                        + relative + " - " + validationFindings.size());
            }
        } catch (Exception exception) {
            failed.compute(platform, (key, value) -> value + 1);
            String message = exception.getClass().getSimpleName() + ": " + safeMessage(exception);
            summary.add(csvLine(relative, platform.commandLineName(), "GENERATION_FAILED",
                    Boolean.toString(prepared.validationReport().valid()), "0",
                    target == null ? "" : normalize(outputRoot.relativize(target)), message));
            issues.add(csvLine(relative, platform.commandLineName(), "GENERATION", "", "GENERATION_FAILED",
                    message, ""));
            System.out.println("[FAILED][" + platform.commandLineName() + "] " + relative + " - " + message);
        }
    }

    private List<ValidationFinding> validate(DatabasePlatform platform, String sql) {
        return switch (platform) {
            case ORACLE -> oracleSanityChecker.inspect(sql).stream()
                    .map(issue -> new ValidationFinding(
                            "line " + issue.lineNumber(), issue.code(), issue.message(), issue.fragment()))
                    .toList();
            case POSTGRESQL -> postgreSqlSanityChecker.inspect(sql).stream()
                    .map(issue -> new ValidationFinding(
                            "statement " + issue.statementNumber(), issue.code(), issue.message(), issue.fragment()))
                    .toList();
            case SQLSERVER -> sqlServerValidator.validate(sql).issues().stream()
                    .map(issue -> new ValidationFinding(
                            "statement " + issue.statementNumber(), issue.code(), issue.message(), ""))
                    .toList();
            case DB2_ZOS -> db2ZosValidator.validate(sql).issues().stream()
                    .map(issue -> new ValidationFinding(
                            "statement " + issue.statementNumber(), issue.code(), issue.message(), ""))
                    .toList();
            case MYSQL -> List.of(); // MySQL offline validator is introduced after logical P1.
        };
    }

    private DatabaseSchema parse(
            Path inputRoot, Path document, String legacySchema, ParserMode parserMode) throws Exception {
        if (parserMode == ParserMode.LEGACY) {
            return parseLegacy(inputRoot, document, legacySchema);
        }

        String lower = document.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".doc")) {
            if (parserMode == ParserMode.STANDARD) {
                throw new IllegalArgumentException(
                        "Standard parser mode accepts .docx only: " + document.getFileName());
            }
            return parseLegacy(inputRoot, document, legacySchema);
        }

        Exception standardFailure;
        try (InputStream input = Files.newInputStream(document)) {
            return new WordSpecificationParser().parse(
                    new SpecificationSource(document.getFileName().toString(), input));
        } catch (Exception exception) {
            if (parserMode == ParserMode.STANDARD) {
                throw exception;
            }
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

    private static List<DatabasePlatform> configuredPlatforms() {
        String value = trimToNull(System.getProperty(PLATFORMS));
        if (value == null) {
            return DEFAULT_PLATFORMS;
        }
        Set<DatabasePlatform> result = new LinkedHashSet<>();
        for (String token : value.split("[,;\\s]+")) {
            if (!token.isBlank()) {
                DatabasePlatform platform = DatabasePlatform.parse(token);
                result.add(platform);
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("System property " + PLATFORMS + " does not select any platform");
        }
        return List.copyOf(result);
    }


    private static ParserMode configuredParserMode() {
        String value = trimToNull(System.getProperty(PARSER_MODE));
        if (value == null) return ParserMode.AUTO;
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "auto" -> ParserMode.AUTO;
            case "standard", "new", "new-word" -> ParserMode.STANDARD;
            case "legacy", "legacy-word" -> ParserMode.LEGACY;
            default -> throw new IllegalArgumentException(
                    "Unsupported " + PARSER_MODE + ": " + value
                            + ". Supported values: auto, standard, legacy");
        };
    }

    private enum ParserMode {
        AUTO, STANDARD, LEGACY
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
                ? inputRoot.resolve("schemaforge-multidb-output").toAbsolutePath().normalize()
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

    private static String textSummary(
            Path inputRoot,
            Path outputRoot,
            int documents,
            int skipped,
            int parseFailures,
            ParserMode parserMode,
            List<DatabasePlatform> platforms,
            Map<DatabasePlatform, Integer> generated,
            Map<DatabasePlatform, Integer> generatedWithIssues,
            Map<DatabasePlatform, Integer> failed,
            String timestamp) {

        StringBuilder result = new StringBuilder();
        result.append("SchemaForge multi-database Word generation summary").append(System.lineSeparator());
        result.append("===============================================").append(System.lineSeparator());
        result.append("Run timestamp      : ").append(timestamp).append(System.lineSeparator());
        result.append("Input directory    : ").append(inputRoot.toAbsolutePath()).append(System.lineSeparator());
        result.append("Output directory   : ").append(outputRoot.toAbsolutePath()).append(System.lineSeparator());
        result.append("Parser mode        : ").append(parserMode.name().toLowerCase(Locale.ROOT)).append(System.lineSeparator());
        result.append("Documents          : ").append(documents).append(System.lineSeparator());
        result.append("Skipped            : ").append(skipped).append(System.lineSeparator());
        result.append("Parse failures     : ").append(parseFailures).append(System.lineSeparator());
        for (DatabasePlatform platform : platforms) {
            result.append(System.lineSeparator()).append(platform.commandLineName()).append(System.lineSeparator());
            result.append("  Generated        : ").append(generated.get(platform)).append(System.lineSeparator());
            result.append("  With issues      : ").append(generatedWithIssues.get(platform)).append(System.lineSeparator());
            result.append("  Failed           : ").append(failed.get(platform)).append(System.lineSeparator());
        }
        return result.toString();
    }

    private static String csvLine(String... values) {
        List<String> escaped = new ArrayList<>(values.length);
        for (String value : values) {
            String text = value == null ? "" : value;
            escaped.add("\"" + text.replace("\"", "\"\"") + "\"");
        }
        return String.join(",", escaped);
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
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

    /** One normalized static-validation finding independent of the source DBMS validator. */
    private record ValidationFinding(String location, String code, String message, String fragment) {
    }

    /** Signals that neither standard nor legacy parsing found an accepted table specification. */
    private static final class NoTableDocumentException extends Exception {
        private NoTableDocumentException(String message) {
            super(message);
        }
    }
}
