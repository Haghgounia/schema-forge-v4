package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.artifact.ArtifactDescriptor;
import com.behsazan.schemaforge.artifact.ArtifactGenerationContext;
import com.behsazan.schemaforge.artifact.ArtifactNamingPolicy;
import com.behsazan.schemaforge.artifact.ArtifactPaths;
import com.behsazan.schemaforge.artifact.ArtifactRequestStatus;
import com.behsazan.schemaforge.artifact.ArtifactStatus;
import com.behsazan.schemaforge.artifact.ArtifactType;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Table;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Writes one request-level, human-readable summary for REST archive generation.
 *
 * <p>Table existence classification is derived from the migration outcomes already recorded in
 * the request ledger. The writer therefore never performs an extra metadata query and never
 * guesses when metadata is unavailable.</p>
 */
public final class GenerationSummaryReportWriter {
    private static final String PRODUCER = "GenerationSummaryReportWriter";

    private final ArtifactNamingPolicy artifactNamingPolicy;

    public GenerationSummaryReportWriter(ArtifactNamingPolicy artifactNamingPolicy) {
        this.artifactNamingPolicy = Objects.requireNonNull(
                artifactNamingPolicy, "artifactNamingPolicy must not be null");
    }

    public Path write(
            Path output,
            ArtifactGenerationContext context,
            DatabaseSchema schema,
            Set<DatabasePlatform> platforms) throws IOException {
        return write(output, context, List.of(schema), platforms, Map.of(), null);
    }

    public Path write(
            Path output,
            ArtifactGenerationContext context,
            List<DatabaseSchema> schemas,
            Set<DatabasePlatform> platforms,
            Map<String, String> requestFacts,
            ArtifactRequestStatus requestedStatus) throws IOException {
        Objects.requireNonNull(output, "output must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(schemas, "schemas must not be null");
        Objects.requireNonNull(platforms, "platforms must not be null");
        Objects.requireNonNull(requestFacts, "requestFacts must not be null");

        List<ArtifactDescriptor> descriptors = context.ledger().snapshot();
        List<TableRef> extractedTables = schemas.stream()
                .flatMap(schema -> schema.tables().stream().map(table -> TableRef.of(schema, table)))
                .toList();

        StringBuilder report = new StringBuilder();
        line(report, "SchemaForge Generation Summary");
        line(report, "==============================");
        blank(report);
        field(report, "Generation ID", context.generationId());
        field(report, "Generated At", context.generatedAt().toString());
        field(report, "Request Type", context.origin().name());
        field(report, "Request Status", requestStatus(descriptors, requestedStatus).name());
        field(report, "Source File", context.sourceName().isBlank() ? "(unspecified)" : context.sourceName());
        requestFacts.forEach((name, value) -> field(report, name, value));
        field(report, "Extracted Tables", Integer.toString(extractedTables.size()));
        blank(report);

        section(report, "Extracted Tables", '-');
        appendTableList(report, extractedTables);

        blank(report);
        section(report, "Database Summary", '=');
        if (platforms.isEmpty()) {
            line(report, "No database platforms were selected.");
        } else {
            for (DatabasePlatform platform : DatabasePlatform.values()) {
                if (!platforms.contains(platform)) continue;
                blank(report);
                appendPlatformSection(report, platform, extractedTables, descriptors);
            }
        }

        Path relativePath = artifactNamingPolicy.generationSummaryRelativePath();
        Path reportPath = output.resolve(relativePath);
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report.toString(), StandardCharsets.UTF_8);
        context.ledger().generated(
                context,
                ArtifactType.SUMMARY_REPORT,
                null,
                "generation-summary",
                ArtifactPaths.relative(output, reportPath),
                "text/plain",
                PRODUCER);
        return reportPath;
    }

    private static void appendPlatformSection(
            StringBuilder report,
            DatabasePlatform platform,
            List<TableRef> extractedTables,
            List<ArtifactDescriptor> descriptors) {
        line(report, platform.name());
        line(report, "-".repeat(platform.name().length()));

        Map<String, TableClassification> classifications = new LinkedHashMap<>();
        for (TableRef table : extractedTables) {
            classifications.put(table.normalizedName(), classify(platform, table, descriptors));
        }

        List<TableRef> existing = new ArrayList<>();
        List<TableRef> created = new ArrayList<>();
        List<TableRef> unknown = new ArrayList<>();
        for (TableRef table : extractedTables) {
            switch (classifications.get(table.normalizedName())) {
                case EXISTING -> existing.add(table);
                case NEW -> created.add(table);
                case UNKNOWN -> unknown.add(table);
            }
        }

        String metadataStatus = unknown.isEmpty()
                ? "AVAILABLE"
                : (unknown.size() == extractedTables.size() ? "UNAVAILABLE" : "PARTIAL");
        field(report, "Metadata Status", metadataStatus);
        if ("UNAVAILABLE".equals(metadataStatus)) {
            field(report, "Existing Tables", "UNKNOWN");
            field(report, "New Tables", "UNKNOWN");
            field(report, "Unknown Tables", Integer.toString(unknown.size()));
            field(report, "Classification", "NOT_PERFORMED");
            field(report, "Reason", "METADATA_UNAVAILABLE");
        } else {
            field(report, "Existing Tables", Integer.toString(existing.size()));
            field(report, "New Tables", Integer.toString(created.size()));
            field(report, "Unknown Tables", Integer.toString(unknown.size()));
            field(report, "Classification", unknown.isEmpty() ? "COMPLETE" : "PARTIAL");
        }

        blank(report);
        section(report, "Existing Tables", '-');
        if ("UNAVAILABLE".equals(metadataStatus)) {
            line(report, "(unknown - metadata unavailable)");
        } else {
            appendTableList(report, existing);
        }
        blank(report);
        section(report, "New Tables", '-');
        if ("UNAVAILABLE".equals(metadataStatus)) {
            line(report, "(unknown - metadata unavailable)");
        } else {
            appendTableList(report, created);
        }
        if (!unknown.isEmpty()) {
            blank(report);
            section(report, "Unknown Tables", '-');
            appendTableList(report, unknown);
        }
    }

    private static TableClassification classify(
            DatabasePlatform platform,
            TableRef table,
            List<ArtifactDescriptor> descriptors) {
        ArtifactDescriptor candidate = null;
        for (ArtifactDescriptor descriptor : descriptors) {
            if (descriptor.type() != ArtifactType.MIGRATION || descriptor.platform() != platform) continue;
            if (!normalize(descriptor.logicalName()).equals(table.normalizedName())) continue;
            candidate = descriptor;
        }
        if (candidate == null) return TableClassification.UNKNOWN;
        if (candidate.status() == ArtifactStatus.GENERATED) return TableClassification.EXISTING;

        String reason = candidate.outcomeReason().toUpperCase(Locale.ROOT);
        if (reason.startsWith("NO_SCHEMA_DIFF")) return TableClassification.EXISTING;
        if (reason.startsWith("LIVE_TABLE_NOT_FOUND") || reason.startsWith("LIVE_SCHEMA_NOT_FOUND")) {
            return TableClassification.NEW;
        }
        return TableClassification.UNKNOWN;
    }

    private static ArtifactRequestStatus requestStatus(
            List<ArtifactDescriptor> descriptors,
            ArtifactRequestStatus requestedStatus) {
        if (requestedStatus == ArtifactRequestStatus.PARTIAL_SUCCESS) {
            return ArtifactRequestStatus.PARTIAL_SUCCESS;
        }
        boolean partial = descriptors.stream().anyMatch(descriptor ->
                descriptor.status() == ArtifactStatus.BLOCKED || descriptor.status() == ArtifactStatus.FAILED);
        return partial ? ArtifactRequestStatus.PARTIAL_SUCCESS : ArtifactRequestStatus.SUCCESS;
    }

    private static void appendTableList(StringBuilder report, List<TableRef> tables) {
        if (tables.isEmpty()) {
            line(report, "(none)");
            return;
        }
        tables.forEach(table -> line(report, table.displayName()));
    }

    private static void section(StringBuilder report, String title, char underline) {
        line(report, title);
        line(report, String.valueOf(underline).repeat(Math.max(1, title.length())));
    }

    private static void field(StringBuilder report, String name, String value) {
        report.append(String.format(Locale.ROOT, "%-20s : %s", name, value)).append('\n');
    }

    private static void line(StringBuilder report, String value) {
        report.append(value).append('\n');
    }

    private static void blank(StringBuilder report) {
        report.append('\n');
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private enum TableClassification {
        EXISTING,
        NEW,
        UNKNOWN
    }

    private record TableRef(String displayName, String normalizedName) {
        static TableRef of(DatabaseSchema schema, Table table) {
            String schemaName = table.qualifiedName().schemaName()
                    .map(identifier -> identifier.value())
                    .orElse(schema.name().value());
            String display = schemaName + "." + table.qualifiedName().name().value();
            return new TableRef(display, normalize(display));
        }
    }
}
