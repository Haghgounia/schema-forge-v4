package com.behsazan.schemaforge.artifact;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.OutputFileNamer;
import com.behsazan.schemaforge.diagram.DiagramExportOptions;
import com.behsazan.schemaforge.diagram.DiagramScope;

import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Authoritative package-relative naming and layout policy for generated SchemaForge artifacts.
 *
 * <p>Flyway migration file names remain owned by the migration subsystem; this policy owns only
 * their canonical package directory.</p>
 */
public final class ArtifactNamingPolicy {
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("\\d{8}_\\d{6}_\\d{3}");
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss_SSS", Locale.ROOT);

    private final OutputFileNamer sqlNamer;

    public ArtifactNamingPolicy() {
        this(Clock.systemDefaultZone());
    }

    public ArtifactNamingPolicy(Clock clock) {
        this(new OutputFileNamer(Objects.requireNonNull(clock, "clock must not be null")));
    }

    public ArtifactNamingPolicy(OutputFileNamer sqlNamer) {
        this.sqlNamer = Objects.requireNonNull(sqlNamer, "sqlNamer must not be null");
    }

    public String timestamp() {
        return sqlNamer.timestamp();
    }

    /** Formats one captured request time with the authoritative C5 timestamp grammar. */
    public static String timestamp(OffsetDateTime generatedAt) {
        return Objects.requireNonNull(generatedAt, "generatedAt must not be null")
                .toLocalDateTime()
                .format(TIMESTAMP_FORMATTER);
    }

    public static void validateTimestamp(String timestamp) {
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        if (!TIMESTAMP_PATTERN.matcher(timestamp).matches()) {
            throw new IllegalArgumentException(
                    "timestamp must match yyyyMMdd_HHmmss_SSS: " + timestamp);
        }
    }

    public Path ddlRelativePath(String logicalName, DatabasePlatform platform, String timestamp) {
        return Path.of("ddl", platform.commandLineName(), ddlFileName(logicalName, platform, timestamp));
    }

    public String ddlFileName(String logicalName, DatabasePlatform platform, String timestamp) {
        return sqlNamer.scriptFileName(logicalName, platform, OutputFileNamer.ScriptKind.DDL, timestamp);
    }

    public Path migrationDirectory(DatabasePlatform platform) {
        return Path.of("migration", platform.commandLineName());
    }

    public Path crudRelativePath(String logicalName, DatabasePlatform platform, String timestamp) {
        return Path.of("crud", platform.commandLineName(), crudFileName(logicalName, platform, timestamp));
    }

    public String crudFileName(String logicalName, DatabasePlatform platform, String timestamp) {
        return sqlNamer.scriptFileName(logicalName, platform, OutputFileNamer.ScriptKind.CRUD, timestamp);
    }

    public Path canonicalJsonRelativePath(String sourceBaseName, String timestamp) {
        return Path.of("model", logicalName(sourceBaseName) + "_" + checked(timestamp) + ".schema.json");
    }

    public Path comparisonRelativePath(
            String logicalName, DatabasePlatform platform, String timestamp) {
        String fileName = logicalName(logicalName)
                + "_" + checked(timestamp)
                + "." + platform.commandLineName()
                + ".compare.xlsx";
        return Path.of("comparison", platform.commandLineName(), fileName);
    }

    public Path mermaidErRelativePath(String sourceBaseName, String timestamp) {
        return Path.of("diagram", "mermaid", "tables",
                logicalName(sourceBaseName) + "_" + checked(timestamp) + ".er.mmd");
    }

    public Path mermaidConceptualRelativePath(String sourceBaseName, String timestamp) {
        return Path.of("diagram", "mermaid", "tables",
                logicalName(sourceBaseName) + "_" + checked(timestamp) + ".conceptual-erd.mmd");
    }

    public Path graphvizErRelativePath(String sourceBaseName, String timestamp) {
        return Path.of("diagram", "graphviz", "tables",
                logicalName(sourceBaseName) + "_" + checked(timestamp) + ".er.dot");
    }

    public Path graphvizConceptualRelativePath(String sourceBaseName, String timestamp) {
        return Path.of("diagram", "graphviz", "tables",
                logicalName(sourceBaseName) + "_" + checked(timestamp) + ".conceptual-erd.dot");
    }

    public Path batchMermaidDirectory() {
        return Path.of("diagram", "mermaid", "batch");
    }

    public Path batchMermaidRelativePath(BatchMermaidArtifact artifact) {
        Objects.requireNonNull(artifact, "artifact must not be null");
        return batchMermaidDirectory().resolve(artifact.fileName());
    }

    public Path batchGraphvizDirectory() {
        return Path.of("diagram", "graphviz", "batch");
    }

    public Path batchGraphvizRelativePath(BatchGraphvizArtifact artifact) {
        Objects.requireNonNull(artifact, "artifact must not be null");
        return batchGraphvizDirectory().resolve(artifact.fileName());
    }

    public Path batchGenerationSummaryRelativePath() {
        return Path.of("reports", "batch-generation-summary.csv");
    }

    public Path batchGenerationErrorRelativePath() {
        return Path.of("reports", "batch-generation-errors.log");
    }

    public Path metadataCrudSummaryRelativePath(String sourceBaseName, String timestamp) {
        return Path.of("reports",
                logicalName(sourceBaseName) + "_" + checked(timestamp) + ".metadata-crud-summary.csv");
    }

    public Path reportsDirectory() {
        return Path.of("reports");
    }

    public Path runAllRelativePath(
            String sourceBaseName, DatabasePlatform platform, String timestamp) {
        String fileName = sqlNamer.scriptFileName(
                sourceBaseName, platform, OutputFileNamer.ScriptKind.RUN_ALL, checked(timestamp));
        return Path.of("scripts", platform.commandLineName(), fileName);
    }

    public Path manifestRelativePath() {
        return Path.of("manifest.json");
    }

    public String standaloneMermaidFileName(DiagramExportOptions options) {
        Objects.requireNonNull(options, "options must not be null");
        String selector = switch (options.scope()) {
            case TABLE, TABLE_WITH_DEPENDENCIES -> token(options.rootTable().toString());
            case SCHEMA -> token(options.schema().value());
            case SELECTED_TABLES -> "selected_" + options.selectedTables().size() + "_tables";
            case ALL -> "schema";
        };
        StringBuilder name = new StringBuilder(selector)
                .append("__")
                .append(options.type().name().toLowerCase(Locale.ROOT))
                .append('-')
                .append(options.scope().name().toLowerCase(Locale.ROOT).replace('_', '-'));
        if (options.scope() == DiagramScope.TABLE_WITH_DEPENDENCIES) {
            name.append("-depth-").append(options.dependencyDepth());
        }
        return name.append(".mmd").toString();
    }

    public enum BatchMermaidArtifact {
        ER("schema-er.mmd"),
        CONCEPTUAL_ERD("schema-conceptual-erd.mmd"),
        DEPENDENCY("schema-dependency.mmd"),
        ISSUES("issues.csv"),
        SUMMARY("summary.txt");

        private final String fileName;

        BatchMermaidArtifact(String fileName) {
            this.fileName = fileName;
        }

        public String fileName() {
            return fileName;
        }
    }

    public enum BatchGraphvizArtifact {
        CONCEPTUAL_ERD("schema-conceptual-erd.dot"),
        DEPENDENCY("schema-dependency.dot"),
        CLUSTERED("schema-clustered.dot"),
        COMPACT("schema-compact.dot"),
        OVERVIEW("schema-overview.dot"),
        ISSUES("issues.csv"),
        SUMMARY("summary.txt");

        private final String fileName;

        BatchGraphvizArtifact(String fileName) {
            this.fileName = fileName;
        }

        public String fileName() {
            return fileName;
        }
    }

    private static String token(String value) {
        String normalized = value == null ? "schema" : value.trim();
        if (normalized.isEmpty()) {
            return "schema";
        }
        return normalized.replaceAll("[^A-Za-z0-9_]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }

    private static String checked(String timestamp) {
        validateTimestamp(timestamp);
        return timestamp;
    }

    private static String logicalName(String value) {
        Objects.requireNonNull(value, "logicalName must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("logicalName must not be blank");
        }
        if (normalized.contains("/") || normalized.contains("\\")) {
            throw new IllegalArgumentException("logicalName must not contain a path: " + value);
        }
        return normalized;
    }
}
