package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.application.CollisionSafeScriptTargetAllocator;
import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.application.OutputFileNamer;
import com.behsazan.schemaforge.application.PreparedSchema;
import com.behsazan.schemaforge.application.SchemaPreparationService;
import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.snapshot.CanonicalSchemaSnapshot;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotJsonStore;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotMapper;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotVersions;
import com.behsazan.schemaforge.validation.db2zos.Db2ZosOfflineDdlValidator;
import com.behsazan.schemaforge.validation.oracle.OracleDdlSanityChecker;
import com.behsazan.schemaforge.validation.postgresql.PostgreSqlDdlSanityChecker;
import com.behsazan.schemaforge.validation.sqlserver.SqlServerOfflineDdlValidator;
import com.behsazan.schemaforge.validation.datatype.DatatypeCompatibilityAnalyzer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Explicit batch generator that renders DBMS-specific DDL directly from canonical JSON snapshots.
 *
 * <p>No Word document is opened by this runner. Each compatible snapshot is mapped back to the
 * canonical domain model, prepared once, and then rendered for the configured dialects. This is the
 * fast development and acceptance path for repeated five-DBMS dialect corrections without reopening Word.</p>
 */
class CanonicalJsonDirectoryToDdlIT {
    private static final String INPUT_DIR = "schemaforge.snapshot.ddl.inputDir";
    private static final String OUTPUT_DIR = "schemaforge.snapshot.ddl.outputDir";
    private static final String PLATFORMS = "schemaforge.snapshot.ddl.platforms";
    private static final String FAIL_ON_ERRORS = "schemaforge.snapshot.ddl.failOnErrors";
    private static final String FAIL_ON_WARNINGS = "schemaforge.snapshot.ddl.failOnWarnings";
    private static final String FAIL_ON_REGRESSION = "schemaforge.snapshot.ddl.failOnRegression";
    private static final String EXPECTED_MIN_SUCCESSFUL_PREFIX = "schemaforge.snapshot.ddl.expectedMinSuccessful.";
    private static final String EXPECTED_MAX_BLOCKED_PREFIX = "schemaforge.snapshot.ddl.expectedMaxBlockedMapping.";
    private static final String EXPECTED_MAX_WARNINGS_PREFIX = "schemaforge.snapshot.ddl.expectedMaxWarningScripts.";
    private static final String ALLOWED_BLOCKING_CODES_PREFIX = "schemaforge.snapshot.ddl.allowedBlockingCodes.";
    private static final String CLEAN_OUTPUT = "schemaforge.snapshot.ddl.cleanOutput";
    private static final String EXPECTED_MIN_SNAPSHOTS = "schemaforge.snapshot.ddl.expectedMinSnapshots";

    private static final List<DatabasePlatform> DEFAULT_PLATFORMS =
            List.copyOf(java.util.Arrays.asList(DatabasePlatform.values()));

    private final CanonicalSnapshotJsonStore store = new CanonicalSnapshotJsonStore();
    private final CanonicalSnapshotMapper mapper = new CanonicalSnapshotMapper();
    private final SchemaPreparationService preparationService = new SchemaPreparationService();
    private final OutputFileNamer outputFileNamer = new OutputFileNamer();
    private final OracleDdlSanityChecker oracleSanityChecker = new OracleDdlSanityChecker();
    private final PostgreSqlDdlSanityChecker postgreSqlSanityChecker = new PostgreSqlDdlSanityChecker();
    private final SqlServerOfflineDdlValidator sqlServerValidator = new SqlServerOfflineDdlValidator();
    private final Db2ZosOfflineDdlValidator db2ZosValidator = new Db2ZosOfflineDdlValidator();
    private final DatatypeCompatibilityAnalyzer datatypeCompatibilityAnalyzer = new DatatypeCompatibilityAnalyzer();

    @Test
    void recursivelyGeneratesConfiguredDatabaseScriptsFromCanonicalJson() throws Exception {
        Path inputRoot = requiredDirectory(INPUT_DIR);
        Path outputRoot = outputDirectory(inputRoot);
        List<DatabasePlatform> platforms = configuredPlatforms();
        NumericMappingStrategy numericMappingStrategy = DialectFactory.configuredNumericMappingStrategy();
        boolean failOnErrors = Boolean.parseBoolean(System.getProperty(FAIL_ON_ERRORS, "false"));
        boolean failOnWarnings = Boolean.parseBoolean(System.getProperty(FAIL_ON_WARNINGS, "false"));
        boolean failOnRegression = Boolean.parseBoolean(System.getProperty(FAIL_ON_REGRESSION, "false"));
        int expectedMinSnapshots = Integer.parseInt(System.getProperty(EXPECTED_MIN_SNAPSHOTS, "1"));
        boolean cleanOutput = Boolean.parseBoolean(System.getProperty(CLEAN_OUTPUT, "false"));
        Files.createDirectories(outputRoot);
        if (cleanOutput) {
            cleanPlatformOutputDirectories(inputRoot, outputRoot, platforms);
        }

        List<Path> snapshots;
        try (var paths = Files.walk(inputRoot)) {
            snapshots = paths.filter(Files::isRegularFile)
                    .filter(CanonicalJsonDirectoryToDdlIT::isSnapshot)
                    .filter(path -> !path.toAbsolutePath().normalize().startsWith(outputRoot))
                    .sorted(Comparator.comparing(path -> normalize(inputRoot.relativize(path))))
                    .toList();
        }

        assertTrue(snapshots.size() >= expectedMinSnapshots,
                "Canonical corpus is smaller than expected: discovered=" + snapshots.size()
                        + ", expectedMin=" + expectedMinSnapshots + ", input=" + inputRoot);

        SnapshotSelection snapshotSelection = selectSnapshots(inputRoot, snapshots);
        List<Path> selectedSnapshots = snapshotSelection.selected();

        String timestamp = outputFileNamer.timestamp();
        List<String> summary = new ArrayList<>();
        summary.add("snapshot,source,platform,status,validation_issue_count,output_file,error");
        List<String> issues = new ArrayList<>();
        issues.add("snapshot,source,platform,severity,stage,location,code,message,fragment");
        List<String> outputCollisions = new ArrayList<>();
        outputCollisions.add("snapshot,source,platform,original_output,resolved_output,reason");

        Map<DatabasePlatform, CollisionSafeScriptTargetAllocator> targetAllocators =
                new EnumMap<>(DatabasePlatform.class);
        Map<DatabasePlatform, Set<Path>> writtenTargets = new EnumMap<>(DatabasePlatform.class);
        Map<DatabasePlatform, Integer> generated = new EnumMap<>(DatabasePlatform.class);
        Map<DatabasePlatform, Integer> generatedWithWarnings = new EnumMap<>(DatabasePlatform.class);
        Map<DatabasePlatform, Integer> generatedWithErrors = new EnumMap<>(DatabasePlatform.class);
        Map<DatabasePlatform, Integer> blockedByMapping = new EnumMap<>(DatabasePlatform.class);
        Map<DatabasePlatform, Integer> failed = new EnumMap<>(DatabasePlatform.class);
        Map<DatabasePlatform, Set<String>> blockingCodes = new EnumMap<>(DatabasePlatform.class);
        Map<DatabasePlatform, Dialect> dialects = new EnumMap<>(DatabasePlatform.class);
        platforms.forEach(platform -> {
            generated.put(platform, 0);
            generatedWithWarnings.put(platform, 0);
            generatedWithErrors.put(platform, 0);
            blockedByMapping.put(platform, 0);
            failed.put(platform, 0);
            blockingCodes.put(platform, new LinkedHashSet<>());
            targetAllocators.put(platform, new CollisionSafeScriptTargetAllocator(outputFileNamer));
            writtenTargets.put(platform, new LinkedHashSet<>());
            try {
                Files.createDirectories(outputRoot.resolve(platform.commandLineName()));
                Dialect dialect = DialectFactory.create(platform, numericMappingStrategy);
                verifyDialectInvariants(platform, dialect);
                dialects.put(platform, dialect);
            } catch (Exception exception) {
                throw new IllegalStateException("Cannot initialize DDL generation for " + platform, exception);
            }
        });

        int snapshotFailures = 0;
        int staleParserSnapshots = 0;
        int processedSnapshots = 0;
        int canonicalWarnings = 0;
        int canonicalErrors = 0;
        CorpusStats corpusStats = CorpusStats.empty();
        for (Path snapshotPath : selectedSnapshots) {
            String relativeSnapshot = normalize(inputRoot.relativize(snapshotPath));
            CanonicalSchemaSnapshot snapshot;
            PreparedSchema prepared;
            String source = "";
            try {
                snapshot = store.readSnapshot(snapshotPath);
                source = snapshot.source() == null ? "" : snapshot.source().relativePath();
                if (!CanonicalSnapshotVersions.parserCurrent(snapshot)) {
                    staleParserSnapshots++;
                }
                DatabaseSchema schema = mapper.toDomainPersistedSource(snapshot);
                prepared = preparationService.prepare(schema);
                corpusStats = corpusStats.plus(CorpusStats.from(schema));
                processedSnapshots++;
                for (var issue : prepared.validationReport().issues()) {
                    boolean error = "ERROR".equalsIgnoreCase(issue.severity());
                    if (error) canonicalErrors++; else canonicalWarnings++;
                    issues.add(csvLine(relativeSnapshot, source, "", issue.severity(),
                            "CANONICAL_VALIDATION", issue.path(), issue.code(), issue.message(), ""));
                }
            } catch (Exception exception) {
                snapshotFailures++;
                String message = exception.getClass().getSimpleName() + ": " + safeMessage(exception);
                for (DatabasePlatform platform : platforms) {
                    failed.compute(platform, (key, value) -> value + 1);
                    summary.add(csvLine(relativeSnapshot, source, platform.commandLineName(),
                            "SNAPSHOT_FAILED", "0", "", message));
                    issues.add(csvLine(relativeSnapshot, source, platform.commandLineName(),
                            "ERROR", "SNAPSHOT", "", "SNAPSHOT_FAILED", message, ""));
                }
                continue;
            }

            for (DatabasePlatform platform : platforms) {
                generate(inputRoot, outputRoot, snapshotPath, relativeSnapshot, snapshot, prepared, platform,
                        dialects.get(platform), timestamp, summary, issues, outputCollisions,
                        targetAllocators.get(platform), writtenTargets.get(platform),
                        generated, generatedWithWarnings, generatedWithErrors, blockedByMapping, failed, blockingCodes);
            }
        }

        Path reportDirectory = Files.createDirectories(outputRoot.resolve("reports"));
        Path summaryFile = reportDirectory.resolve("canonical-json-ddl-summary_" + timestamp + ".csv");
        Path issueFile = reportDirectory.resolve("canonical-json-ddl-issues_" + timestamp + ".csv");
        Path duplicateFile = reportDirectory.resolve("canonical-json-ddl-duplicates_" + timestamp + ".csv");
        Path collisionFile = reportDirectory.resolve("canonical-json-ddl-output-collisions_" + timestamp + ".csv");
        Path textFile = reportDirectory.resolve("canonical-json-ddl-summary_" + timestamp + ".txt");
        Files.writeString(summaryFile, String.join(System.lineSeparator(), summary) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        Files.writeString(issueFile, String.join(System.lineSeparator(), issues) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        List<String> duplicateLines = new ArrayList<>();
        duplicateLines.add("duplicate_snapshot,kept_snapshot,normalized_source,sha256");
        for (DuplicateSnapshot duplicate : snapshotSelection.duplicates()) {
            duplicateLines.add(csvLine(duplicate.duplicateSnapshot(), duplicate.keptSnapshot(),
                    duplicate.normalizedSource(), duplicate.sha256()));
        }
        Files.writeString(duplicateFile, String.join(System.lineSeparator(), duplicateLines) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        Files.writeString(collisionFile, String.join(System.lineSeparator(), outputCollisions) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        Files.writeString(textFile, textSummary(inputRoot, outputRoot, snapshots.size(), selectedSnapshots.size(),
                snapshotSelection.duplicates().size(), outputCollisions.size() - 1, snapshotFailures,
                staleParserSnapshots, processedSnapshots, canonicalWarnings, canonicalErrors,
                corpusStats, numericMappingStrategy,
                platforms, generated, generatedWithWarnings, generatedWithErrors, blockedByMapping, failed),
                StandardCharsets.UTF_8);

        for (DatabasePlatform platform : platforms) {
            int successful = generated.get(platform) + generatedWithWarnings.get(platform)
                    + generatedWithErrors.get(platform);
            if (writtenTargets.get(platform).size() != successful) {
                throw new IllegalStateException("Generated SQL file count mismatch for " + platform.commandLineName()
                        + ": successful=" + successful + ", uniqueFiles=" + writtenTargets.get(platform).size());
            }
        }

        int totalGenerated = generated.values().stream().mapToInt(Integer::intValue).sum()
                + generatedWithWarnings.values().stream().mapToInt(Integer::intValue).sum()
                + generatedWithErrors.values().stream().mapToInt(Integer::intValue).sum();
        int totalFailed = failed.values().stream().mapToInt(Integer::intValue).sum();

        System.out.println("Snapshots discovered : " + snapshots.size());
        System.out.println("Snapshots selected   : " + selectedSnapshots.size());
        System.out.println("Exact duplicates     : " + snapshotSelection.duplicates().size());
        System.out.println("Output collisions    : " + (outputCollisions.size() - 1));
        System.out.println("Snapshot failures    : " + snapshotFailures);
        System.out.println("Stale parser sources : " + staleParserSnapshots);
        System.out.println("Numeric mapping       : " + numericMappingStrategy);
        System.out.println("Canonical warnings    : " + canonicalWarnings);
        System.out.println("Canonical errors      : " + canonicalErrors);
        System.out.println("Tables                : " + corpusStats.tables());
        System.out.println("Columns               : " + corpusStats.columns());
        System.out.println("Primary keys          : " + corpusStats.primaryKeys());
        System.out.println("Foreign keys          : " + corpusStats.foreignKeys());
        System.out.println("Unique keys           : " + corpusStats.uniqueKeys());
        System.out.println("Indexes               : " + corpusStats.indexes());
        System.out.println("Checks                : " + corpusStats.checks());
        System.out.println("Sequences             : " + corpusStats.sequences());
        System.out.println("Identity columns      : " + corpusStats.identityColumns());
        System.out.println("Defaulted columns     : " + corpusStats.defaultedColumns());
        for (DatabasePlatform platform : platforms) {
            System.out.println(platform.commandLineName() + " generated       : " + generated.get(platform));
            System.out.println(platform.commandLineName() + " with warnings   : " + generatedWithWarnings.get(platform));
            System.out.println(platform.commandLineName() + " with errors     : " + generatedWithErrors.get(platform));
            System.out.println(platform.commandLineName() + " blocked mapping : " + blockedByMapping.get(platform));
            System.out.println(platform.commandLineName() + " failed          : " + failed.get(platform));
        }
        System.out.println("Output            : " + outputRoot);
        System.out.println("Summary report    : " + summaryFile);
        System.out.println("Validation issues : " + issueFile);
        System.out.println("Duplicates report : " + duplicateFile);
        System.out.println("Collisions report : " + collisionFile);

        assertTrue(totalGenerated > 0, "No SQL was generated from canonical JSON snapshots");
        if (failOnErrors) {
            int blockingMappings = blockedByMapping.values().stream().mapToInt(Integer::intValue).sum();
            int validationErrors = generatedWithErrors.values().stream().mapToInt(Integer::intValue).sum();
            assertTrue(snapshotFailures == 0 && canonicalErrors == 0 && totalFailed == 0
                            && blockingMappings == 0 && validationErrors == 0,
                    "JSON-to-DDL corpus gate found blocking errors; see " + issueFile);
            for (DatabasePlatform platform : platforms) {
                int successful = generated.get(platform) + generatedWithWarnings.get(platform)
                        + generatedWithErrors.get(platform);
                assertTrue(successful == processedSnapshots,
                        "Not every processed snapshot generated DDL for " + platform.commandLineName()
                                + ": processed=" + processedSnapshots + ", generated=" + successful);
            }
        }
        if (failOnRegression) {
            assertTrue(snapshotFailures == 0 && canonicalErrors == 0 && totalFailed == 0,
                    "JSON-to-DDL regression gate found snapshot/canonical/generation failures; see " + issueFile);
            for (DatabasePlatform platform : platforms) {
                String platformName = platform.commandLineName();
                int successful = generated.get(platform) + generatedWithWarnings.get(platform)
                        + generatedWithErrors.get(platform);
                int expectedMinSuccessful = requiredNonNegativeInt(EXPECTED_MIN_SUCCESSFUL_PREFIX + platformName);
                int expectedMaxBlocked = requiredNonNegativeInt(EXPECTED_MAX_BLOCKED_PREFIX + platformName);
                int expectedMaxWarnings = requiredNonNegativeInt(EXPECTED_MAX_WARNINGS_PREFIX + platformName);
                Set<String> allowedCodes = configuredCsvSet(ALLOWED_BLOCKING_CODES_PREFIX + platformName);

                assertTrue(successful >= expectedMinSuccessful,
                        "DDL regression for " + platformName + ": successful=" + successful
                                + ", expectedMin=" + expectedMinSuccessful);
                assertTrue(blockedByMapping.get(platform) <= expectedMaxBlocked,
                        "Mapping-blocker regression for " + platformName + ": blocked="
                                + blockedByMapping.get(platform) + ", expectedMax=" + expectedMaxBlocked);
                assertTrue(generatedWithWarnings.get(platform) <= expectedMaxWarnings,
                        "Warning-script regression for " + platformName + ": warnings="
                                + generatedWithWarnings.get(platform) + ", expectedMax=" + expectedMaxWarnings);
                assertTrue(generatedWithErrors.get(platform) == 0,
                        "Generated SQL validation errors are not accepted for " + platformName + ": "
                                + generatedWithErrors.get(platform));

                Set<String> unexpectedBlockingCodes = new LinkedHashSet<>(blockingCodes.get(platform));
                unexpectedBlockingCodes.removeAll(allowedCodes);
                assertTrue(unexpectedBlockingCodes.isEmpty(),
                        "Unexpected blocking mapping code(s) for " + platformName + ": "
                                + unexpectedBlockingCodes + "; allowed=" + allowedCodes);

                int accounted = successful + blockedByMapping.get(platform) + failed.get(platform);
                assertTrue(accounted == processedSnapshots,
                        "Per-platform corpus accounting mismatch for " + platformName + ": processed="
                                + processedSnapshots + ", accounted=" + accounted);
            }
        }
        if (failOnWarnings) {
            int warningScripts = generatedWithWarnings.values().stream().mapToInt(Integer::intValue).sum();
            assertTrue(canonicalWarnings == 0 && warningScripts == 0,
                    "JSON-to-DDL corpus gate found warning-bearing scripts; see " + issueFile);
        }
    }

    private void generate(
            Path inputRoot,
            Path outputRoot,
            Path snapshotPath,
            String relativeSnapshot,
            CanonicalSchemaSnapshot snapshot,
            PreparedSchema prepared,
            DatabasePlatform platform,
            Dialect dialect,
            String timestamp,
            List<String> summary,
            List<String> issues,
            List<String> outputCollisions,
            CollisionSafeScriptTargetAllocator targetAllocator,
            Set<Path> writtenTargets,
            Map<DatabasePlatform, Integer> generated,
            Map<DatabasePlatform, Integer> generatedWithWarnings,
            Map<DatabasePlatform, Integer> generatedWithErrors,
            Map<DatabasePlatform, Integer> blockedByMapping,
            Map<DatabasePlatform, Integer> failed,
            Map<DatabasePlatform, Set<String>> blockingCodes) {

        String source = snapshot.source() == null ? "" : snapshot.source().relativePath();
        Path target = null;
        MappingAssessment mappingAssessment = mappingAssessment(dialect, prepared.schema());
        if (mappingAssessment.fatal()) {
            blockedByMapping.compute(platform, (key, value) -> value + 1);
            for (ValidationFinding finding : mappingAssessment.findings()) {
                if (finding.error() && finding.code() != null && !finding.code().isBlank()) {
                    blockingCodes.get(platform).add(finding.code());
                }
                issues.add(csvLine(relativeSnapshot, source, platform.commandLineName(), finding.severity(),
                        finding.stage(), finding.location(), finding.code(), finding.message(), finding.fragment()));
            }
            summary.add(csvLine(relativeSnapshot, source, platform.commandLineName(),
                    "GENERATION_BLOCKED_BY_MAPPING", Integer.toString(mappingAssessment.findings().size()), "",
                    "Fatal dialect mapping finding(s): " + mappingAssessment.findings().size()));
            return;
        }
        try {
            String sql = new DdlGenerator(dialect).generate(prepared.schema(), prepared.validationReport());
            Path sourcePath = safeSourcePath(source, snapshotPath.getFileName().toString());
            Path relativeParent = sourcePath.getParent();
            Path platformRoot = Files.createDirectories(outputRoot.resolve(platform.commandLineName()));
            Path targetDirectory = relativeParent == null
                    ? platformRoot : Files.createDirectories(platformRoot.resolve(relativeParent));
            String sourceFileName = snapshot.source() == null || snapshot.source().fileName() == null
                    ? stripSnapshotSuffix(snapshotPath.getFileName().toString()) : snapshot.source().fileName();
            String logicalName = stripExtension(sourceFileName);
            String sourceIdentity = relativeSnapshot + "|" + source + "|"
                    + (snapshot.source() == null || snapshot.source().sha256() == null
                    ? "" : snapshot.source().sha256());
            CollisionSafeScriptTargetAllocator.Allocation allocation = targetAllocator.reserveDdl(
                    targetDirectory, logicalName, platform, timestamp, sourceIdentity);
            target = allocation.resolvedTarget();
            if (allocation.collisionResolved()) {
                outputCollisions.add(csvLine(relativeSnapshot, source, platform.commandLineName(),
                        normalize(outputRoot.relativize(allocation.requestedTarget())),
                        normalize(outputRoot.relativize(allocation.resolvedTarget())),
                        "OUTPUT_NAME_COLLISION"));
            }

            List<ValidationFinding> findings = new ArrayList<>(mappingAssessment.findings());
            findings.addAll(validate(platform, sql));
            Files.writeString(target, sql, StandardCharsets.UTF_8);
            if (!writtenTargets.add(target.toAbsolutePath().normalize())) {
                throw new IllegalStateException("DDL output target was written twice in one run: " + target);
            }
            if (findings.isEmpty()) {
                generated.compute(platform, (key, value) -> value + 1);
                summary.add(csvLine(relativeSnapshot, source, platform.commandLineName(), "GENERATED", "0",
                        normalize(outputRoot.relativize(target)), ""));
            } else {
                boolean hasError = findings.stream().anyMatch(ValidationFinding::error);
                if (hasError) {
                    generatedWithErrors.compute(platform, (key, value) -> value + 1);
                } else {
                    generatedWithWarnings.compute(platform, (key, value) -> value + 1);
                }
                summary.add(csvLine(relativeSnapshot, source, platform.commandLineName(),
                        hasError ? "GENERATED_WITH_ERRORS" : "GENERATED_WITH_WARNINGS",
                        Integer.toString(findings.size()), normalize(outputRoot.relativize(target)), ""));
                for (ValidationFinding finding : findings) {
                    issues.add(csvLine(relativeSnapshot, source, platform.commandLineName(), finding.severity(),
                            finding.stage(), finding.location(), finding.code(), finding.message(), finding.fragment()));
                }
            }
        } catch (Exception exception) {
            failed.compute(platform, (key, value) -> value + 1);
            String message = exception.getClass().getSimpleName() + ": " + safeMessage(exception);
            summary.add(csvLine(relativeSnapshot, source, platform.commandLineName(), "GENERATION_FAILED", "0",
                    target == null ? "" : normalize(outputRoot.relativize(target)), message));
            issues.add(csvLine(relativeSnapshot, source, platform.commandLineName(), "ERROR",
                    "GENERATION", "", "GENERATION_FAILED", message, ""));
        }
    }

    private void verifyDialectInvariants(DatabasePlatform platform, Dialect dialect) {
        if (platform != DatabasePlatform.POSTGRESQL) {
            return;
        }
        String probe = dialect.qualifyIndexName(
                com.behsazan.schemaforge.domain.valueobject.QualifiedName.of("TSTSHMA", "SCHEMAFORGE_PROBE"),
                "ix_schemaforge_probe");
        if (probe.contains(".")) {
            throw new IllegalStateException(
                    "PostgreSQL dialect regression: CREATE INDEX name is schema-qualified: " + probe);
        }
    }

    private void cleanPlatformOutputDirectories(
            Path inputRoot, Path outputRoot, List<DatabasePlatform> platforms) throws Exception {
        Path normalizedInput = inputRoot.toAbsolutePath().normalize();
        Path normalizedOutput = outputRoot.toAbsolutePath().normalize();
        if (normalizedInput.equals(normalizedOutput) || normalizedInput.startsWith(normalizedOutput)) {
            throw new IllegalArgumentException("Refusing to clean output because it contains the snapshot input directory: "
                    + normalizedOutput);
        }
        for (DatabasePlatform platform : platforms) {
            Path platformRoot = normalizedOutput.resolve(platform.commandLineName());
            if (!Files.exists(platformRoot)) {
                continue;
            }
            try (var paths = Files.walk(platformRoot)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private MappingAssessment mappingAssessment(Dialect dialect, DatabaseSchema schema) {
        var assessment = datatypeCompatibilityAnalyzer.analyze(schema, dialect);
        List<ValidationFinding> findings = assessment.issues().stream()
                .map(issue -> new ValidationFinding(
                        issue.severity(), "DIALECT_MAPPING", issue.path(), issue.code(), issue.message(), ""))
                .toList();
        return new MappingAssessment(findings, assessment.blocking());
    }

    private List<ValidationFinding> validate(DatabasePlatform platform, String sql) {
        return switch (platform) {
            case ORACLE -> oracleSanityChecker.inspect(sql).stream()
                    .map(issue -> new ValidationFinding("ERROR", "STATIC_VALIDATION", "line " + issue.lineNumber(),
                            issue.code(), issue.message(), issue.fragment())).toList();
            case POSTGRESQL -> postgreSqlSanityChecker.inspect(sql).stream()
                    .map(issue -> new ValidationFinding("ERROR", "STATIC_VALIDATION",
                            "statement " + issue.statementNumber(), issue.code(),
                            issue.message(), issue.fragment())).toList();
            case SQLSERVER -> sqlServerValidator.validate(sql).issues().stream()
                    .map(issue -> new ValidationFinding(issue.severity(), "STATIC_VALIDATION",
                            "statement " + issue.statementNumber(), issue.code(), issue.message(), "")).toList();
            case DB2_ZOS -> db2ZosValidator.validate(sql).issues().stream()
                    .map(issue -> new ValidationFinding(issue.severity(), "STATIC_VALIDATION",
                            "statement " + issue.statementNumber(), issue.code(), issue.message(), "")).toList();
            case DB2_LUW -> List.of(); // Dedicated Db2 LUW offline validator follows core P1.
            case MYSQL -> basicMySqlValidation(sql);
        };
    }

    private static List<ValidationFinding> basicMySqlValidation(String sql) {
        List<ValidationFinding> findings = new ArrayList<>();
        if (sql == null || sql.isBlank()) {
            findings.add(new ValidationFinding("ERROR", "STATIC_VALIDATION", "script",
                    "MYSQL_EMPTY_SCRIPT", "Generated MySQL DDL is empty", ""));
            return findings;
        }
        String upper = sql.toUpperCase(Locale.ROOT);
        if (!upper.contains("CREATE TABLE")) {
            findings.add(new ValidationFinding("ERROR", "STATIC_VALIDATION", "script",
                    "MYSQL_CREATE_TABLE_MISSING", "Generated MySQL DDL does not contain CREATE TABLE", ""));
        }
        if (upper.contains("[ERROR]")) {
            findings.add(new ValidationFinding("ERROR", "STATIC_VALIDATION", "script",
                    "MYSQL_ERROR_MARKER", "Generated MySQL DDL contains an [ERROR] marker", ""));
        }
        return List.copyOf(findings);
    }

    private SnapshotSelection selectSnapshots(Path inputRoot, List<Path> discovered) {
        Map<String, Path> seen = new LinkedHashMap<>();
        List<Path> selected = new ArrayList<>();
        List<DuplicateSnapshot> duplicates = new ArrayList<>();
        for (Path path : discovered) {
            try {
                CanonicalSchemaSnapshot snapshot = store.readSnapshot(path);
                CanonicalSchemaSnapshot.SourceSnapshot source = snapshot.source();
                String sourcePath = source == null || source.relativePath() == null || source.relativePath().isBlank()
                        ? normalize(inputRoot.relativize(path)) : source.relativePath();
                String sha256 = source == null || source.sha256() == null ? "" : source.sha256().trim();
                if (sha256.isBlank()) {
                    selected.add(path);
                    continue;
                }
                String normalizedSource = canonicalSourcePath(sourcePath);
                String key = normalizedSource.toLowerCase(Locale.ROOT) + "|" + sha256.toLowerCase(Locale.ROOT);
                Path kept = seen.putIfAbsent(key, path);
                if (kept == null) {
                    selected.add(path);
                } else {
                    duplicates.add(new DuplicateSnapshot(
                            normalize(inputRoot.relativize(path)),
                            normalize(inputRoot.relativize(kept)),
                            normalizedSource,
                            sha256));
                }
            } catch (Exception unreadable) {
                // Keep unreadable snapshots in the normal flow so they are reported as SNAPSHOT_FAILED.
                selected.add(path);
            }
        }
        return new SnapshotSelection(List.copyOf(selected), List.copyOf(duplicates));
    }

    private static String canonicalSourcePath(String sourcePath) {
        String normalized = sourcePath == null ? "" : sourcePath.replace('\\', '/').trim();
        int slash = normalized.lastIndexOf('/');
        String parent = slash < 0 ? "" : normalized.substring(0, slash + 1);
        String fileName = slash < 0 ? normalized : normalized.substring(slash + 1);
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            fileName = fileName.substring(0, dot).stripTrailing() + fileName.substring(dot);
        } else {
            fileName = fileName.stripTrailing();
        }
        return parent + fileName;
    }

    private static Path safeSourcePath(String source, String fallback) {
        if (source == null || source.isBlank()) return Path.of(fallback);
        Path path = Path.of(source.replace('/', java.io.File.separatorChar)).normalize();
        if (path.isAbsolute() || path.startsWith("..")) {
            throw new IllegalArgumentException("Unsafe source path in canonical snapshot: " + source);
        }
        return path;
    }

    private static boolean isSnapshot(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".schema.json") && !name.equals("manifest.json");
    }

    private static List<DatabasePlatform> configuredPlatforms() {
        String value = trimToNull(System.getProperty(PLATFORMS));
        if (value == null) return DEFAULT_PLATFORMS;
        Set<DatabasePlatform> result = new LinkedHashSet<>();
        for (String token : value.split("[,;\\s]+")) {
            if (!token.isBlank()) {
                DatabasePlatform platform = DatabasePlatform.parse(token);
                result.add(platform);
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("No platform selected by " + PLATFORMS);
        return List.copyOf(result);
    }

    private static Path requiredDirectory(String propertyName) {
        String value = trimToNull(System.getProperty(propertyName));
        if (value == null) throw new IllegalArgumentException("Missing system property: " + propertyName);
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) throw new IllegalArgumentException("Input directory does not exist: " + path);
        return path;
    }

    private static Path outputDirectory(Path inputRoot) {
        String value = trimToNull(System.getProperty(OUTPUT_DIR));
        return value == null
                ? inputRoot.resolveSibling(inputRoot.getFileName() + "-ddl").toAbsolutePath().normalize()
                : Path.of(value).toAbsolutePath().normalize();
    }

    private static String textSummary(
            Path inputRoot, Path outputRoot, int snapshotsDiscovered, int snapshotsSelected, int duplicateSnapshots,
            int outputCollisions, int snapshotFailures, int staleParserSnapshots, int processedSnapshots,
            int canonicalWarnings, int canonicalErrors, CorpusStats corpusStats,
            NumericMappingStrategy numericMappingStrategy, List<DatabasePlatform> platforms,
            Map<DatabasePlatform, Integer> generated,
            Map<DatabasePlatform, Integer> withWarnings, Map<DatabasePlatform, Integer> withErrors,
            Map<DatabasePlatform, Integer> blockedByMapping,
            Map<DatabasePlatform, Integer> failed) {
        StringBuilder result = new StringBuilder();
        result.append("SchemaForge canonical JSON to DDL summary").append(System.lineSeparator());
        result.append("=========================================").append(System.lineSeparator());
        result.append("Snapshot directory : ").append(inputRoot).append(System.lineSeparator());
        result.append("Output directory   : ").append(outputRoot).append(System.lineSeparator());
        result.append("Snapshots discovered: ").append(snapshotsDiscovered).append(System.lineSeparator());
        result.append("Snapshots selected  : ").append(snapshotsSelected).append(System.lineSeparator());
        result.append("Exact duplicates    : ").append(duplicateSnapshots).append(System.lineSeparator());
        result.append("Output collisions   : ").append(outputCollisions).append(System.lineSeparator());
        result.append("Snapshot failures   : ").append(snapshotFailures).append(System.lineSeparator());
        result.append("Stale parser sources: ").append(staleParserSnapshots).append(System.lineSeparator());
        result.append("Processed snapshots  : ").append(processedSnapshots).append(System.lineSeparator());
        result.append("Numeric mapping       : ").append(numericMappingStrategy).append(System.lineSeparator());
        result.append("Canonical warnings    : ").append(canonicalWarnings).append(System.lineSeparator());
        result.append("Canonical errors      : ").append(canonicalErrors).append(System.lineSeparator());
        result.append("Tables                : ").append(corpusStats.tables()).append(System.lineSeparator());
        result.append("Columns               : ").append(corpusStats.columns()).append(System.lineSeparator());
        result.append("Primary keys          : ").append(corpusStats.primaryKeys()).append(System.lineSeparator());
        result.append("Foreign keys          : ").append(corpusStats.foreignKeys()).append(System.lineSeparator());
        result.append("Unique keys           : ").append(corpusStats.uniqueKeys()).append(System.lineSeparator());
        result.append("Indexes               : ").append(corpusStats.indexes()).append(System.lineSeparator());
        result.append("Checks                : ").append(corpusStats.checks()).append(System.lineSeparator());
        result.append("Sequences             : ").append(corpusStats.sequences()).append(System.lineSeparator());
        result.append("Identity columns      : ").append(corpusStats.identityColumns()).append(System.lineSeparator());
        result.append("Defaulted columns     : ").append(corpusStats.defaultedColumns()).append(System.lineSeparator());
        result.append("Parser freshness    : provenance warning only for persisted JSON sources; ")
                .append("snapshot/model contract remains the DDL eligibility gate")
                .append(System.lineSeparator());
        for (DatabasePlatform platform : platforms) {
            result.append(System.lineSeparator()).append(platform.commandLineName()).append(System.lineSeparator());
            result.append("  Generated        : ").append(generated.get(platform)).append(System.lineSeparator());
            result.append("  With warnings    : ").append(withWarnings.get(platform)).append(System.lineSeparator());
            result.append("  With errors      : ").append(withErrors.get(platform)).append(System.lineSeparator());
            result.append("  Blocked mapping  : ").append(blockedByMapping.get(platform)).append(System.lineSeparator());
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

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String stripSnapshotSuffix(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".schema.json")
                ? name.substring(0, name.length() - ".schema.json".length()) : name;
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int requiredNonNegativeInt(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required acceptance property: -D" + property + "=<non-negative integer>");
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Acceptance property must be an integer: -D" + property + "=" + value, exception);
        }
        if (parsed < 0) {
            throw new IllegalArgumentException("Acceptance property must be non-negative: -D" + property + "=" + parsed);
        }
        return parsed;
    }

    private static Set<String> configuredCsvSet(String property) {
        String value = System.getProperty(property, "").trim();
        if (value.isEmpty()) {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (String token : value.split(",")) {
            String normalized = token.trim();
            if (!normalized.isEmpty()) {
                values.add(normalized);
            }
        }
        return Set.copyOf(values);
    }

    private static String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    /** Selected canonical snapshots plus exact duplicate source/hash pairs skipped for DDL generation. */
    private record SnapshotSelection(List<Path> selected, List<DuplicateSnapshot> duplicates) {
    }

    private record DuplicateSnapshot(
            String duplicateSnapshot, String keptSnapshot, String normalizedSource, String sha256) {
    }

    /** Dialect-mapping findings plus whether generation must stop before emitting unsafe SQL. */
    private record MappingAssessment(List<ValidationFinding> findings, boolean fatal) {
    }

    /** Aggregate source-canonical statistics for the selected historical corpus. */
    private record CorpusStats(long tables, long columns, long primaryKeys, long foreignKeys,
                               long uniqueKeys, long indexes, long checks, long sequences,
                               long identityColumns, long defaultedColumns) {
        static CorpusStats empty() {
            return new CorpusStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        static CorpusStats from(DatabaseSchema schema) {
            long tables = schema.tables().size();
            long columns = schema.tables().stream().mapToLong(table -> table.columns().size()).sum();
            long primaryKeys = schema.tables().stream().filter(table -> table.primaryKey().isPresent()).count();
            long foreignKeys = schema.tables().stream().mapToLong(table -> table.foreignKeys().size()).sum();
            long uniqueKeys = schema.tables().stream().mapToLong(table -> table.uniqueKeys().size()).sum();
            long indexes = schema.tables().stream().mapToLong(table -> table.indexes().size()).sum();
            long checks = schema.tables().stream().mapToLong(table -> table.checkConstraints().size()).sum();
            long sequences = schema.sequences().size();
            long identityColumns = schema.tables().stream().flatMap(table -> table.columns().stream())
                    .filter(com.behsazan.schemaforge.domain.model.Column::identity).count();
            long defaultedColumns = schema.tables().stream().flatMap(table -> table.columns().stream())
                    .filter(column -> column.defaultValue().isPresent()).count();
            return new CorpusStats(tables, columns, primaryKeys, foreignKeys, uniqueKeys, indexes, checks, sequences,
                    identityColumns, defaultedColumns);
        }

        CorpusStats plus(CorpusStats other) {
            return new CorpusStats(tables + other.tables, columns + other.columns,
                    primaryKeys + other.primaryKeys, foreignKeys + other.foreignKeys,
                    uniqueKeys + other.uniqueKeys, indexes + other.indexes, checks + other.checks,
                    sequences + other.sequences, identityColumns + other.identityColumns,
                    defaultedColumns + other.defaultedColumns);
        }
    }

    /** One normalized validation finding independent from its DBMS-specific validator. */
    private record ValidationFinding(
            String severity, String stage, String location, String code, String message, String fragment) {
        boolean error() {
            return "ERROR".equalsIgnoreCase(severity);
        }
    }
}
