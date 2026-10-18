package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class ExternalGenerationAcceptanceSupport {
    static final String PLATFORMS = "schemaforge.e2e.platforms";
    static final String AUDIT_FIELDS = "schemaforge.e2e.auditFields";
    static final String TIMESTAMP_SEMANTICS = "schemaforge.e2e.timestampSemantics";
    static final String FAIL_ON_ERRORS = "schemaforge.e2e.failOnErrors";

    private ExternalGenerationAcceptanceSupport() {
    }

    static Path requiredFile(String property) {
        String raw = requiredProperty(property);
        Path path = Path.of(raw).toAbsolutePath().normalize();
        Assertions.assertTrue(Files.isRegularFile(path),
                () -> property + " must point to an existing file: " + path);
        return path;
    }

    static Path requiredDirectory(String property) {
        String raw = requiredProperty(property);
        Path path = Path.of(raw).toAbsolutePath().normalize();
        Assertions.assertTrue(Files.isDirectory(path),
                () -> property + " must point to an existing directory: " + path);
        return path;
    }

    static Path configuredOutputDirectory(String property, Path fallbackBase, String fallbackName) throws IOException {
        String raw = trimToNull(System.getProperty(property));
        Path output = raw == null
                ? fallbackBase.resolve(fallbackName).toAbsolutePath().normalize()
                : Path.of(raw).toAbsolutePath().normalize();
        Files.createDirectories(output);
        return output;
    }

    static List<String> configuredPlatforms() {
        String raw = System.getProperty(PLATFORMS, "all");
        List<String> values = new ArrayList<>();
        for (String token : raw.split(",")) {
            String value = token.trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        Assertions.assertFalse(values.isEmpty(), PLATFORMS + " must not be empty");
        DatabasePlatform.parseSelection(values);
        return List.copyOf(values);
    }

    static String configuredAuditFields() {
        String raw = System.getProperty(AUDIT_FIELDS, "source").trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (raw) {
            case "source", "created-updated", "created-last-modified", "none" -> raw;
            default -> throw new IllegalArgumentException(
                    "Unsupported " + AUDIT_FIELDS + ": " + raw
                            + ". Expected source, created-updated, created-last-modified, or none");
        };
    }

    static String configuredTimestampSemantics() {
        String raw = System.getProperty(TIMESTAMP_SEMANTICS, "source")
                .trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (raw) {
            case "source", "without-time-zone", "with-time-zone", "with-local-time-zone" -> raw;
            default -> throw new IllegalArgumentException(
                    "Unsupported " + TIMESTAMP_SEMANTICS + ": " + raw
                            + ". Expected source, without-time-zone, with-time-zone, or with-local-time-zone");
        };
    }

    static boolean failOnErrors() {
        return Boolean.parseBoolean(System.getProperty(FAIL_ON_ERRORS, "true"));
    }

    static Map<String, byte[]> unzip(byte[] archive) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    zip.transferTo(bytes);
                    entries.put(entry.getName(), bytes.toByteArray());
                }
                zip.closeEntry();
            }
        }
        return entries;
    }

    static JsonNode manifest(ObjectMapper mapper, Map<String, byte[]> entries) throws IOException {
        byte[] bytes = entries.get("manifest.json");
        Assertions.assertNotNull(bytes, "Generated archive must contain manifest.json");
        JsonNode manifest = mapper.readTree(bytes);
        Assertions.assertEquals("schemaforge-manifest/v1", manifest.path("manifestContract").asText());
        return manifest;
    }

    static void assertOrigin(JsonNode manifest, String expectedOrigin) {
        Assertions.assertEquals(expectedOrigin, manifest.path("source").path("origin").asText());
    }

    static void assertConfiguredGenerationOptions(
            JsonNode manifest,
            List<String> requestedPlatforms,
            String auditFields,
            String timestampSemantics) {
        JsonNode options = manifest.path("extensions").path("generationOptions");
        Assertions.assertEquals(timestampSemantics, options.path("timestampSemantics").asText(),
                "timestampSemantics must propagate into manifest");

        Set<DatabasePlatform> selected = DatabasePlatform.parseSelection(requestedPlatforms);
        List<String> expectedPlatforms = selected.size() == DatabasePlatform.values().length
                ? List.of("all")
                : DatabasePlatform.valuesAsList(selected);
        List<String> actualPlatforms = new ArrayList<>();
        options.path("platforms").forEach(node -> actualPlatforms.add(node.asText()));
        Assertions.assertEquals(expectedPlatforms, actualPlatforms, "platform selection must propagate into manifest");

        JsonNode audit = options.path("audit");
        Assertions.assertFalse(audit.isMissingNode(), "audit generation options must be present in manifest");
        boolean expectedInclude = auditFields.equals("created-updated") || auditFields.equals("created-last-modified");
        String expectedProfile = switch (auditFields) {
            case "created-updated" -> "CREATED_UPDATED";
            case "created-last-modified" -> "CREATED_LAST_MODIFIED";
            default -> "AUTO";
        };
        Assertions.assertEquals(expectedInclude, audit.path("includeAuditFields").asBoolean(),
                "auditFields include flag must propagate into manifest");
        Assertions.assertEquals(expectedProfile, audit.path("auditProfile").asText(),
                "auditFields profile must propagate into manifest");
    }

    static void assertRequestStatus(JsonNode manifest, boolean failOnErrors, String label) {
        String status = manifest.path("requestStatus").asText();
        Assertions.assertFalse(status.isBlank(), label + " manifest requestStatus must be present");
        if (failOnErrors) {
            Assertions.assertEquals("SUCCESS", status,
                    () -> label + " requestStatus is " + status
                            + "; rerun with -D" + FAIL_ON_ERRORS + "=false only when PARTIAL_SUCCESS is explicitly expected");
        }
    }

    static void assertManifestMatchesArchive(Map<String, byte[]> entries, JsonNode manifest) throws Exception {
        Map<String, JsonNode> generatedByPath = new LinkedHashMap<>();
        for (JsonNode artifact : manifest.path("artifacts")) {
            if (!"GENERATED".equals(artifact.path("status").asText())) {
                continue;
            }
            String path = artifact.path("path").asText();
            generatedByPath.put(path, artifact);
            if ("manifest.json".equals(path)) {
                Assertions.assertTrue(artifact.path("integrity").isNull());
                continue;
            }
            byte[] bytes = entries.get(path);
            Assertions.assertNotNull(bytes, () -> "Manifest generated artifact missing from archive: " + path);
            Assertions.assertEquals(bytes.length, artifact.path("integrity").path("sizeBytes").asLong(), path);
            Assertions.assertEquals(sha256(bytes), artifact.path("integrity").path("sha256").asText(), path);
            Assertions.assertEquals("SHA-256", artifact.path("integrity").path("algorithm").asText(), path);
        }
        Assertions.assertEquals(entries.keySet(), generatedByPath.keySet(),
                "Manifest generated artifact paths must match archive entries exactly");
    }

    static List<Map.Entry<String, byte[]>> canonicalJsonEntries(Map<String, byte[]> entries) {
        return entries.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith(".schema.json"))
                .toList();
    }

    static void assertEaIdentityPolicy(
            ObjectMapper mapper,
            Map<String, byte[]> entries,
            boolean primaryKeyAsIdentity) throws IOException {
        String expected = primaryKeyAsIdentity ? "PK_INFERENCE_COMPATIBILITY" : "EXPLICIT_ONLY";
        List<Map.Entry<String, byte[]>> canonical = canonicalJsonEntries(entries);
        Assertions.assertFalse(canonical.isEmpty(), "EA archive must contain at least one canonical schema JSON");
        for (Map.Entry<String, byte[]> entry : canonical) {
            JsonNode model = mapper.readTree(entry.getValue());
            Assertions.assertEquals(expected,
                    model.path("source").path("source.eaPrimaryKeyIdentityPolicy").asText(),
                    entry.getKey());
        }
    }

    static RunSummaryRow summaryRow(String corpus, Path input, Path output, JsonNode manifest, String detail) {
        JsonNode outcomes = manifest.path("artifactOutcomes");
        JsonNode validation = manifest.path("validation");
        JsonNode batch = manifest.path("extensions").path("batchInput");
        return new RunSummaryRow(
                corpus,
                input.toString(),
                input.getFileName() == null ? input.toString() : input.getFileName().toString(),
                output == null ? "" : output.toString(),
                manifest.path("requestStatus").asText("UNKNOWN"),
                manifest.path("models").size(),
                outcomes.path("generated").asLong(0),
                outcomes.path("blocked").asLong(0),
                outcomes.path("skipped").asLong(0),
                outcomes.path("failed").asLong(0),
                validation.path("errorCount").asLong(0),
                validation.path("warningCount").asLong(0),
                validation.path("recoveryWarningCount").asLong(0),
                batch.path("regularFileCount").asLong(-1),
                batch.path("processableDocumentCount").asLong(-1),
                batch.path("successCount").asLong(-1),
                batch.path("failedCount").asLong(-1),
                batch.path("skippedCount").asLong(-1),
                "",
                "",
                detail == null ? "" : detail);
    }

    static RunSummaryRow failureSummaryRow(String corpus, Path input, Path output, Throwable failure) {
        String exceptionClass = failure == null ? "" : failure.getClass().getName();
        String exceptionMessage = failure == null || trimToNull(failure.getMessage()) == null
                ? ""
                : failure.getMessage().replace('\n', ' ').replace('\r', ' ');
        return new RunSummaryRow(
                corpus,
                input.toString(),
                input.getFileName() == null ? input.toString() : input.getFileName().toString(),
                output == null ? "" : output.toString(),
                "HARNESS_ERROR",
                -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1,
                exceptionClass,
                exceptionMessage,
                conciseFailure(failure));
    }

    static void recordFailure(
            List<RunSummaryRow> rows,
            String corpus,
            Path input,
            Path output,
            Throwable failure) {
        for (int i = 0; i < rows.size(); i++) {
            RunSummaryRow row = rows.get(i);
            if (!row.corpus().equals(corpus)) {
                continue;
            }
            String exceptionClass = failure == null ? "" : failure.getClass().getName();
            String exceptionMessage = failure == null || trimToNull(failure.getMessage()) == null
                    ? ""
                    : failure.getMessage().replace('\n', ' ').replace('\r', ' ');
            String concise = conciseFailure(failure);
            String detail = trimToNull(row.detail()) == null ? concise : row.detail() + " | HARNESS: " + concise;
            rows.set(i, new RunSummaryRow(
                    row.corpus(), row.input(), row.inputName(), row.output(), row.status(),
                    row.models(), row.generated(), row.blocked(), row.skipped(), row.failed(),
                    row.validationErrors(), row.validationWarnings(), row.recoveryWarnings(),
                    row.regularFiles(), row.processableDocuments(), row.documentSuccess(),
                    row.documentFailed(), row.documentSkipped(), exceptionClass, exceptionMessage, detail));
            return;
        }
        rows.add(failureSummaryRow(corpus, input, output, failure));
    }

    static String blockingOutcomeSummary(JsonNode manifest) {
        List<String> reasons = new ArrayList<>();
        for (JsonNode artifact : manifest.path("artifacts")) {
            String status = artifact.path("status").asText();
            if (!"BLOCKED".equals(status) && !"FAILED".equals(status)) {
                continue;
            }
            String reason = trimToNull(artifact.path("outcomeReason").asText());
            if (reason != null && !reasons.contains(reason)) {
                reasons.add(reason);
            }
            if (reasons.size() == 3) {
                break;
            }
        }
        return String.join(" | ", reasons);
    }

    static Path writeSummaryCsv(Path outputRoot, String fileName, List<RunSummaryRow> rows) throws IOException {
        Path output = outputRoot.resolve(fileName);
        StringBuilder csv = new StringBuilder();
        csv.append("corpus,input,inputName,output,status,models,generated,blocked,skipped,failed,validationErrors,validationWarnings,recoveryWarnings,")
                .append("regularFiles,processableDocuments,documentSuccess,documentFailed,documentSkipped,exceptionClass,exceptionMessage,detail\n");
        for (RunSummaryRow row : rows) {
            csv.append(csv(row.corpus())).append(',')
                    .append(csv(row.input())).append(',')
                    .append(csv(row.inputName())).append(',')
                    .append(csv(row.output())).append(',')
                    .append(csv(row.status())).append(',')
                    .append(row.models()).append(',')
                    .append(row.generated()).append(',')
                    .append(row.blocked()).append(',')
                    .append(row.skipped()).append(',')
                    .append(row.failed()).append(',')
                    .append(row.validationErrors()).append(',')
                    .append(row.validationWarnings()).append(',')
                    .append(row.recoveryWarnings()).append(',')
                    .append(row.regularFiles()).append(',')
                    .append(row.processableDocuments()).append(',')
                    .append(row.documentSuccess()).append(',')
                    .append(row.documentFailed()).append(',')
                    .append(row.documentSkipped()).append(',')
                    .append(csv(row.exceptionClass())).append(',')
                    .append(csv(row.exceptionMessage())).append(',')
                    .append(csv(row.detail())).append('\n');
        }
        Files.writeString(output, csv.toString(), StandardCharsets.UTF_8);
        return output;
    }

    static String combinedFailureMessage(String heading, Path summary, List<String> failures) {
        StringBuilder message = new StringBuilder(heading)
                .append(". Summary: ").append(summary)
                .append(". Failure count: ").append(failures.size());
        int limit = Math.min(failures.size(), 8);
        for (int i = 0; i < limit; i++) {
            message.append(System.lineSeparator()).append(" - ").append(failures.get(i));
        }
        if (failures.size() > limit) {
            message.append(System.lineSeparator()).append(" - ... ")
                    .append(failures.size() - limit).append(" more; see summary CSV and generated manifests");
        }
        return message.toString();
    }

    static String safeBaseName(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    static Path stableEaOutputPath(Path outputRoot, Path inputRoot, Path input) throws Exception {
        Path relative = inputRoot.toAbsolutePath().normalize().relativize(input.toAbsolutePath().normalize());
        String relativeKey = relative.toString().replace('\\', '/');
        String suffix = sha256(relativeKey.getBytes(StandardCharsets.UTF_8)).substring(0, 12);
        return outputRoot.resolve(safeBaseName(input) + "-" + suffix + ".schemaforge-ea.zip");
    }

    static Path configuredPathForSummary(String property) {
        String raw = trimToNull(System.getProperty(property));
        if (raw == null) {
            return Path.of("__MISSING__" + property.replace('.', '_'));
        }
        return Path.of(raw).toAbsolutePath().normalize();
    }

    static boolean shouldFailGate(boolean failOnErrors, List<String> failures) {
        return failOnErrors && failures != null && !failures.isEmpty();
    }

    static String requiredProperty(String property) {
        String value = trimToNull(System.getProperty(property));
        if (value == null) {
            throw new IllegalArgumentException("Required system property is missing: -D" + property + "=<path>");
        }
        return value;
    }

    static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static String conciseFailure(Throwable failure) {
        if (failure == null) {
            return "";
        }
        String message = trimToNull(failure.getMessage());
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message.replace('\n', ' ').replace('\r', ' '));
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        return java.util.HexFormat.of().formatHex(digest);
    }

    record RunSummaryRow(
            String corpus,
            String input,
            String inputName,
            String output,
            String status,
            long models,
            long generated,
            long blocked,
            long skipped,
            long failed,
            long validationErrors,
            long validationWarnings,
            long recoveryWarnings,
            long regularFiles,
            long processableDocuments,
            long documentSuccess,
            long documentFailed,
            long documentSkipped,
            String exceptionClass,
            String exceptionMessage,
            String detail) {
    }
}
