package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.application.CollisionSafeScriptTargetAllocator;
import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.application.OutputFileNamer;
import com.behsazan.schemaforge.application.PreparedSchema;
import com.behsazan.schemaforge.application.SchemaPreparationService;
import com.behsazan.schemaforge.dialect.Dialect;
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
 * fast development path for repeated Oracle/PostgreSQL/SQL Server/Db2 for z/OS dialect corrections.</p>
 */
class CanonicalJsonDirectoryToDdlIT {
    private static final String INPUT_DIR = "schemaforge.snapshot.ddl.inputDir";
    private static final String OUTPUT_DIR = "schemaforge.snapshot.ddl.outputDir";
    private static final String PLATFORMS = "schemaforge.snapshot.ddl.platforms";
    private static final String FAIL_ON_ERRORS = "schemaforge.snapshot.ddl.failOnErrors";
    private static final String CLEAN_OUTPUT = "schemaforge.snapshot.ddl.cleanOutput";

    private static final List<DatabasePlatform> DEFAULT_PLATFORMS = List.of(
            DatabasePlatform.ORACLE, DatabasePlatform.POSTGRESQL,
            DatabasePlatform.SQLSERVER, DatabasePlatform.DB2_ZOS);

    private final CanonicalSnapshotJsonStore store = new CanonicalSnapshotJsonStore();
    private final CanonicalSnapshotMapper mapper = new CanonicalSnapshotMapper();
    private final SchemaPreparationService preparationService = new SchemaPreparationService();
    private final OutputFileNamer outputFileNamer = new OutputFileNamer();
    private final OracleDdlSanityChecker oracleSanityChecker = new OracleDdlSanityChecker();
    private final PostgreSqlDdlSanityChecker postgreSqlSanityChecker = new PostgreSqlDdlSanityChecker();
    private final SqlServerOfflineDdlValidator sqlServerValidator = new SqlServerOfflineDdlValidator();
    private final Db2ZosOfflineDdlValidator db2ZosValidator = new Db2ZosOfflineDdlValidator();

    @Test
    void recursivelyGeneratesConfiguredDatabaseScriptsFromCanonicalJson() throws Exception {
        Path inputRoot = requiredDirectory(INPUT_DIR);
        Path outputRoot = outputDirectory(inputRoot);
        List<DatabasePlatform> platforms = configuredPlatforms();
        boolean failOnErrors = Boolean.parseBoolean(System.getProperty(FAIL_ON_ERRORS, "false"));
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

        SnapshotSelection snapshotSelection = selectSnapshots(inputRoot, snapshots);
        List<Path> selectedSnapshots = snapshotSelection.selected();

        String timestamp = outputFileNamer.timestamp();
        List<String> summary = new ArrayList<>();
        summary.add("snapshot,source,platform,status,validation_issue_count,output_file,error");
        List<String> issues = new ArrayList<>();
        issues.add("snapshot,source,platform,stage,location,code,message,fragment");
        List<String> outputCollisions = new ArrayList<>();
        outputCollisions.add("snapshot,source,platform,original_output,resolved_output,reason");

        Map<DatabasePlatform, CollisionSafeScriptTargetAllocator> targetAllocators =
                new EnumMap<>(DatabasePlatform.class);
        Map<DatabasePlatform, Set<Path>> writtenTargets = new EnumMap<>(DatabasePlatform.class);
        Map<DatabasePlatform, Integer> generated = new EnumMap<>(DatabasePlatform.class);
        Map<DatabasePlatform, Integer> generatedWithIssues = new EnumMap<>(DatabasePlatform.class);
        Map<DatabasePlatform, Integer> failed = new EnumMap<>(DatabasePlatform.class);
        Map<DatabasePlatform, Dialect> dialects = new EnumMap<>(DatabasePlatform.class);
        platforms.forEach(platform -> {
            generated.put(platform, 0);
            generatedWithIssues.put(platform, 0);
            failed.put(platform, 0);
            targetAllocators.put(platform, new CollisionSafeScriptTargetAllocator(outputFileNamer));
            writtenTargets.put(platform, new LinkedHashSet<>());
            try {
                Files.createDirectories(outputRoot.resolve(platform.commandLineName()));
                Dialect dialect = DialectFactory.create(platform);
                verifyDialectInvariants(platform, dialect);
                dialects.put(platform, dialect);
            } catch (Exception exception) {
                throw new IllegalStateException("Cannot initialize DDL generation for " + platform, exception);
            }
        });

        int snapshotFailures = 0;
        int staleParserSnapshots = 0;
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
            } catch (Exception exception) {
                snapshotFailures++;
                String message = exception.getClass().getSimpleName() + ": " + safeMessage(exception);
                for (DatabasePlatform platform : platforms) {
                    failed.compute(platform, (key, value) -> value + 1);
                    summary.add(csvLine(relativeSnapshot, source, platform.commandLineName(),
                            "SNAPSHOT_FAILED", "0", "", message));
                    issues.add(csvLine(relativeSnapshot, source, platform.commandLineName(),
                            "SNAPSHOT", "", "SNAPSHOT_FAILED", message, ""));
                }
                continue;
            }

            for (DatabasePlatform platform : platforms) {
                generate(inputRoot, outputRoot, snapshotPath, relativeSnapshot, snapshot, prepared, platform,
                        dialects.get(platform), timestamp, summary, issues, outputCollisions,
                        targetAllocators.get(platform), writtenTargets.get(platform),
                        generated, generatedWithIssues, failed);
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
                staleParserSnapshots, platforms, generated, generatedWithIssues, failed), StandardCharsets.UTF_8);

        for (DatabasePlatform platform : platforms) {
            int successful = generated.get(platform) + generatedWithIssues.get(platform);
            if (writtenTargets.get(platform).size() != successful) {
                throw new IllegalStateException("Generated SQL file count mismatch for " + platform.commandLineName()
                        + ": successful=" + successful + ", uniqueFiles=" + writtenTargets.get(platform).size());
            }
        }

        int totalGenerated = generated.values().stream().mapToInt(Integer::intValue).sum()
                + generatedWithIssues.values().stream().mapToInt(Integer::intValue).sum();
        int totalFailed = failed.values().stream().mapToInt(Integer::intValue).sum();

        System.out.println("Snapshots discovered : " + snapshots.size());
        System.out.println("Snapshots selected   : " + selectedSnapshots.size());
        System.out.println("Exact duplicates     : " + snapshotSelection.duplicates().size());
        System.out.println("Output collisions    : " + (outputCollisions.size() - 1));
        System.out.println("Snapshot failures    : " + snapshotFailures);
        System.out.println("Stale parser sources : " + staleParserSnapshots);
        for (DatabasePlatform platform : platforms) {
            System.out.println(platform.commandLineName() + " generated       : " + generated.get(platform));
            System.out.println(platform.commandLineName() + " with issues     : " + generatedWithIssues.get(platform));
            System.out.println(platform.commandLineName() + " failed          : " + failed.get(platform));
        }
        System.out.println("Output            : " + outputRoot);
        System.out.println("Summary report    : " + summaryFile);
        System.out.println("Validation issues : " + issueFile);
        System.out.println("Duplicates report : " + duplicateFile);
        System.out.println("Collisions report : " + collisionFile);

        assertTrue(totalGenerated > 0, "No SQL was generated from canonical JSON snapshots");
        if (failOnErrors) {
            assertTrue(totalFailed == 0 && issues.size() == 1,
                    "JSON-to-DDL generation produced failures or validation issues; see " + issueFile);
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
            Map<DatabasePlatform, Integer> generatedWithIssues,
            Map<DatabasePlatform, Integer> failed) {

        String source = snapshot.source() == null ? "" : snapshot.source().relativePath();
        Path target = null;
        MappingAssessment mappingAssessment = mappingAssessment(platform, prepared.schema());
        if (mappingAssessment.fatal()) {
            failed.compute(platform, (key, value) -> value + 1);
            for (ValidationFinding finding : mappingAssessment.findings()) {
                issues.add(csvLine(relativeSnapshot, source, platform.commandLineName(), finding.stage(),
                        finding.location(), finding.code(), finding.message(), finding.fragment()));
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
                generatedWithIssues.compute(platform, (key, value) -> value + 1);
                summary.add(csvLine(relativeSnapshot, source, platform.commandLineName(), "GENERATED_WITH_ISSUES",
                        Integer.toString(findings.size()), normalize(outputRoot.relativize(target)), ""));
                for (ValidationFinding finding : findings) {
                    issues.add(csvLine(relativeSnapshot, source, platform.commandLineName(), finding.stage(),
                            finding.location(), finding.code(), finding.message(), finding.fragment()));
                }
            }
        } catch (Exception exception) {
            failed.compute(platform, (key, value) -> value + 1);
            String message = exception.getClass().getSimpleName() + ": " + safeMessage(exception);
            summary.add(csvLine(relativeSnapshot, source, platform.commandLineName(), "GENERATION_FAILED", "0",
                    target == null ? "" : normalize(outputRoot.relativize(target)), message));
            issues.add(csvLine(relativeSnapshot, source, platform.commandLineName(), "GENERATION", "",
                    "GENERATION_FAILED", message, ""));
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

    private MappingAssessment mappingAssessment(DatabasePlatform platform, DatabaseSchema schema) {
        Set<String> exactNumeric = Set.of("NUMBER", "NUMERIC", "DECIMAL", "DEC");
        Set<String> temporal = Set.of(
                "TIMESTAMP", "TIMESTAMP WITH TIME ZONE", "TIMESTAMP_WITH_TIME_ZONE",
                "TIMESTAMP WITH LOCAL TIME ZONE", "TIMESTAMP_WITH_LOCAL_TIME_ZONE",
                "DATETIME2", "DATETIMEOFFSET", "TIME");
        List<ValidationFinding> findings = new ArrayList<>();
        boolean fatal = false;

        for (var table : schema.tables()) {
            for (var column : table.columns()) {
                var type = column.dataType();
                String sourceName = type.name().normalized().toUpperCase(Locale.ROOT);
                String location = table.qualifiedName() + "." + column.name().value();

                switch (platform) {
                    case ORACLE -> {
                        if (exactNumeric.contains(sourceName) && type.precision() != null && type.precision() > 38) {
                            int scale = type.scale() == null ? 0 : Math.min(type.scale(), 127);
                            findings.add(new ValidationFinding(
                                    "DIALECT_MAPPING", location, "ORACLE_DECIMAL_PRECISION_BOUNDED",
                                    "Canonical " + renderType(sourceName, type.precision(), type.scale())
                                            + " is rendered with Oracle NUMBER precision 38"
                                            + (type.scale() == null ? "" : " and scale " + scale), ""));
                        }
                        if (exactNumeric.contains(sourceName) && type.scale() != null && type.scale() > 127) {
                            findings.add(new ValidationFinding(
                                    "DIALECT_MAPPING", location, "ORACLE_DECIMAL_SCALE_BOUNDED",
                                    "Canonical " + renderType(sourceName, type.precision(), type.scale())
                                            + " is rendered with Oracle NUMBER scale 127", ""));
                        }
                        if (isOracleTimestamp(sourceName) && type.precision() != null && type.precision() > 9) {
                            findings.add(new ValidationFinding(
                                    "DIALECT_MAPPING", location, "ORACLE_TEMPORAL_PRECISION_BOUNDED",
                                    "Canonical " + sourceName + "(" + type.precision()
                                            + ") is rendered with Oracle TIMESTAMP precision 9", ""));
                        }
                        addOracleLargeObjectFallbackFinding(findings, location, sourceName, type.length());
                    }
                    case POSTGRESQL -> {
                        if (isPostgreSqlTimestamp(sourceName) && type.precision() != null) {
                            findings.add(new ValidationFinding(
                                    "DIALECT_MAPPING", location, "POSTGRESQL_TEMPORAL_PRECISION_DROPPED",
                                    "Canonical " + sourceName + "(" + type.precision()
                                            + ") is rendered without an explicit PostgreSQL temporal precision", ""));
                        }
                    }
                    case SQLSERVER -> {
                        if (exactNumeric.contains(sourceName) && type.precision() != null && type.precision() > 38) {
                            int scale = type.scale() == null ? 0 : type.scale();
                            findings.add(new ValidationFinding(
                                    "DIALECT_MAPPING", location, "SQLSERVER_DECIMAL_PRECISION_BOUNDED",
                                    "Canonical " + renderType(sourceName, type.precision(), type.scale())
                                            + " is rendered as DECIMAL(38," + scale + ") for SQL Server", ""));
                        }
                        if (temporal.contains(sourceName) && type.precision() != null && type.precision() > 7) {
                            String target = sourceName.startsWith("TIMESTAMP WITH")
                                    || sourceName.startsWith("TIMESTAMP_WITH") || sourceName.equals("DATETIMEOFFSET")
                                    ? "DATETIMEOFFSET" : sourceName.equals("TIME") ? "TIME" : "DATETIME2";
                            findings.add(new ValidationFinding(
                                    "DIALECT_MAPPING", location, "SQLSERVER_TEMPORAL_PRECISION_BOUNDED",
                                    "Canonical " + sourceName + "(" + type.precision()
                                            + ") is rendered as " + target + "(7) for SQL Server", ""));
                        }
                    }
                    case DB2_ZOS -> {
                        if (exactNumeric.contains(sourceName) && type.precision() == null) {
                            fatal = true;
                            findings.add(new ValidationFinding(
                                    "DIALECT_MAPPING", location, "DB2_NUMBER_PRECISION_REQUIRED",
                                    "Canonical " + sourceName
                                            + " has no explicit precision; Db2 z/OS cannot map it losslessly to DECIMAL", ""));
                        } else if (exactNumeric.contains(sourceName) && type.precision() > 31) {
                            fatal = true;
                            findings.add(new ValidationFinding(
                                    "DIALECT_MAPPING", location, "DB2_DECIMAL_PRECISION_UNSUPPORTED",
                                    "Canonical " + renderType(sourceName, type.precision(), type.scale())
                                            + " exceeds the Db2 z/OS DECIMAL precision limit 31", ""));
                        }
                    }
                }
            }
        }
        return new MappingAssessment(List.copyOf(findings), fatal);
    }

    private static boolean isOracleTimestamp(String sourceName) {
        return sourceName.equals("TIMESTAMP")
                || sourceName.equals("TIMESTAMP WITH TIME ZONE")
                || sourceName.equals("TIMESTAMP_WITH_TIME_ZONE")
                || sourceName.equals("TIMESTAMP WITH LOCAL TIME ZONE")
                || sourceName.equals("TIMESTAMP_WITH_LOCAL_TIME_ZONE");
    }

    private static boolean isPostgreSqlTimestamp(String sourceName) {
        return isOracleTimestamp(sourceName);
    }

    private static String renderType(String sourceName, Integer precision, Integer scale) {
        if (precision == null) return sourceName;
        return sourceName + "(" + precision + (scale == null ? "" : "," + scale) + ")";
    }

    private static void addOracleLargeObjectFallbackFinding(
            List<ValidationFinding> findings, String location, String sourceName, Integer length) {
        if (length == null) return;
        String target = null;
        int limit = 0;
        if ((sourceName.equals("VARCHAR") || sourceName.equals("VARCHAR2")) && length > 4000) {
            target = "CLOB";
            limit = 4000;
        } else if ((sourceName.equals("NVARCHAR") || sourceName.equals("NVARCHAR2")) && length > 2000) {
            target = "NCLOB";
            limit = 2000;
        } else if ((sourceName.equals("CHAR") || sourceName.equals("CHARACTER")) && length > 2000) {
            target = "CLOB";
            limit = 2000;
        } else if (sourceName.equals("NCHAR") && length > 2000) {
            target = "NCLOB";
            limit = 2000;
        } else if (sourceName.equals("RAW") && length > 2000) {
            target = "BLOB";
            limit = 2000;
        }
        if (target != null) {
            findings.add(new ValidationFinding(
                    "DIALECT_MAPPING", location, "ORACLE_LARGE_OBJECT_FALLBACK",
                    "Canonical " + sourceName + "(" + length + ") exceeds the conservative Oracle limit "
                            + limit + " and is rendered as " + target, ""));
        }
    }

    private List<ValidationFinding> validate(DatabasePlatform platform, String sql) {
        return switch (platform) {
            case ORACLE -> oracleSanityChecker.inspect(sql).stream()
                    .map(issue -> new ValidationFinding("STATIC_VALIDATION", "line " + issue.lineNumber(),
                            issue.code(), issue.message(), issue.fragment())).toList();
            case POSTGRESQL -> postgreSqlSanityChecker.inspect(sql).stream()
                    .map(issue -> new ValidationFinding("STATIC_VALIDATION",
                            "statement " + issue.statementNumber(), issue.code(),
                            issue.message(), issue.fragment())).toList();
            case SQLSERVER -> sqlServerValidator.validate(sql).issues().stream()
                    .map(issue -> new ValidationFinding("STATIC_VALIDATION",
                            "statement " + issue.statementNumber(), issue.code(), issue.message(), "")).toList();
            case DB2_ZOS -> db2ZosValidator.validate(sql).issues().stream()
                    .map(issue -> new ValidationFinding("STATIC_VALIDATION",
                            "statement " + issue.statementNumber(), issue.code(), issue.message(), "")).toList();
        };
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
            int outputCollisions, int snapshotFailures, int staleParserSnapshots, List<DatabasePlatform> platforms,
            Map<DatabasePlatform, Integer> generated,
            Map<DatabasePlatform, Integer> withIssues, Map<DatabasePlatform, Integer> failed) {
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
        result.append("Parser freshness    : provenance warning only for persisted JSON sources; ")
                .append("snapshot/model contract remains the DDL eligibility gate")
                .append(System.lineSeparator());
        for (DatabasePlatform platform : platforms) {
            result.append(System.lineSeparator()).append(platform.commandLineName()).append(System.lineSeparator());
            result.append("  Generated        : ").append(generated.get(platform)).append(System.lineSeparator());
            result.append("  With issues      : ").append(withIssues.get(platform)).append(System.lineSeparator());
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

    /** One normalized static-validation finding independent from its DBMS-specific validator. */
    private record ValidationFinding(String stage, String location, String code, String message, String fragment) {
    }
}
