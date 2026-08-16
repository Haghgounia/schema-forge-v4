package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.application.OutputFileNamer;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotVersions;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Explicit preflight inventory for the two production SchemaForge corpus sources.
 *
 * <p>This runner does not parse Word documents and does not generate DDL. It only counts the
 * new-format Word corpus and classifies/version-checks JSON files so a long bulk generation is not
 * started against an unexpected JSON contract.</p>
 *
 * <p>For JSON sources, parser freshness is reported separately from contract compatibility.
 * A persisted JSON corpus may be structurally readable even when it was produced by an older Word
 * parser. Such snapshots are DDL-eligible but are explicitly marked {@code STALE_PARSER} so their
 * provenance is not confused with a current Word-parser cache.</p>
 */
class CorpusInventoryIT {
    private static final String WORD_INPUT_DIR = "schemaforge.corpus.word.inputDir";
    private static final String JSON_INPUT_DIR = "schemaforge.corpus.json.inputDir";
    private static final String OUTPUT_DIR = "schemaforge.corpus.outputDir";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OutputFileNamer outputFileNamer = new OutputFileNamer();

    @Test
    void inventoriesWordAndJsonSourcesWithoutParsingWordDocuments() throws Exception {
        Path wordRoot = optionalDirectory(WORD_INPUT_DIR);
        Path jsonRoot = optionalDirectory(JSON_INPUT_DIR);
        assertTrue(wordRoot != null || jsonRoot != null,
                "At least one corpus input directory must be configured: "
                        + WORD_INPUT_DIR + " or " + JSON_INPUT_DIR);

        Path outputRoot = outputDirectory(wordRoot, jsonRoot);
        Files.createDirectories(outputRoot);
        String timestamp = outputFileNamer.timestamp();

        WordCounts wordCounts = inventoryWord(wordRoot);
        JsonInventory jsonInventory = inventoryJson(jsonRoot);

        Path jsonDetails = outputRoot.resolve("corpus-json-inventory_" + timestamp + ".csv");
        Path jsonVersions = outputRoot.resolve("corpus-json-version-summary_" + timestamp + ".csv");
        Path textSummary = outputRoot.resolve("corpus-inventory-summary_" + timestamp + ".txt");

        Files.writeString(jsonDetails,
                String.join(System.lineSeparator(), jsonInventory.details()) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        Files.writeString(jsonVersions,
                String.join(System.lineSeparator(), jsonInventory.versionSummary()) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        Files.writeString(textSummary,
                textSummary(wordRoot, jsonRoot, wordCounts, jsonInventory), StandardCharsets.UTF_8);

        System.out.println("New Word files          : " + wordCounts.total());
        System.out.println("  .docx                 : " + wordCounts.docx());
        System.out.println("  .doc                  : " + wordCounts.doc());
        System.out.println("JSON files              : " + jsonInventory.total());
        System.out.println("Canonical snapshots     : " + jsonInventory.canonical());
        System.out.println("  contract compatible   : " + jsonInventory.contractCompatible());
        System.out.println("  cache compatible      : " + jsonInventory.cacheCompatible());
        System.out.println("  stale parser          : " + jsonInventory.staleParser());
        System.out.println("  bulk DDL eligible     : " + jsonInventory.bulkDdlEligible());
        System.out.println("  incompatible contract : " + jsonInventory.incompatibleContract());
        System.out.println("Other JSON              : " + jsonInventory.other());
        System.out.println("Unreadable JSON         : " + jsonInventory.unreadable());
        System.out.println("Summary                 : " + textSummary.toAbsolutePath());
        System.out.println("JSON detail             : " + jsonDetails.toAbsolutePath());
        System.out.println("JSON version summary    : " + jsonVersions.toAbsolutePath());
    }

    private WordCounts inventoryWord(Path root) throws Exception {
        if (root == null) return new WordCounts(0, 0);
        int docx = 0;
        int doc = 0;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".docx")) docx++;
                else if (name.endsWith(".doc")) doc++;
            }
        }
        return new WordCounts(docx, doc);
    }

    private JsonInventory inventoryJson(Path root) throws Exception {
        List<String> details = new ArrayList<>();
        details.add("json_file,classification,snapshot_version,model_version,parser_version,"
                + "contract_compatible,cache_compatible,parser_current,bulk_ddl_eligible,status,source,error");
        Map<VersionKey, Integer> versions = new LinkedHashMap<>();
        if (root == null) {
            return new JsonInventory(0, 0, 0, 0, 0, 0, 0, 0, 0, details,
                    List.of("snapshot_version,model_version,parser_version,contract_compatible,"
                            + "cache_compatible,parser_current,bulk_ddl_eligible,status,count"));
        }

        int total = 0;
        int canonical = 0;
        int contractCompatible = 0;
        int cacheCompatible = 0;
        int staleParser = 0;
        int bulkDdlEligible = 0;
        int incompatibleContract = 0;
        int other = 0;
        int unreadable = 0;

        List<Path> files;
        try (var paths = Files.walk(root)) {
            files = paths.filter(Files::isRegularFile)
                    .filter(CorpusInventoryIT::isJson)
                    .sorted(Comparator.comparing(path -> normalize(root.relativize(path))))
                    .toList();
        }

        for (Path path : files) {
            total++;
            String relative = normalize(root.relativize(path));
            try {
                JsonNode node = objectMapper.readTree(path.toFile());
                if (isCanonicalSnapshot(node)) {
                    canonical++;
                    String snapshotVersion = text(node, "snapshotVersion");
                    String modelVersion = text(node, "modelVersion");
                    String parserVersion = text(node, "parserVersion");
                    boolean isContractCompatible = CanonicalSnapshotVersions.SNAPSHOT_VERSION.equals(snapshotVersion)
                            && CanonicalSnapshotVersions.MODEL_VERSION.equals(modelVersion);
                    boolean isParserCurrent = CanonicalSnapshotVersions.PARSER_VERSION.equals(parserVersion);
                    boolean isCacheCompatible = isContractCompatible && isParserCurrent;
                    boolean isBulkDdlEligible = isContractCompatible;
                    String status;
                    if (!isContractCompatible) {
                        incompatibleContract++;
                        status = "INCOMPATIBLE_CONTRACT";
                    } else if (isParserCurrent) {
                        contractCompatible++;
                        cacheCompatible++;
                        bulkDdlEligible++;
                        status = "CURRENT";
                    } else {
                        contractCompatible++;
                        staleParser++;
                        bulkDdlEligible++;
                        status = "STALE_PARSER";
                    }

                    String source = node.path("source").path("relativePath").asText("");
                    VersionKey key = new VersionKey(snapshotVersion, modelVersion, parserVersion,
                            isContractCompatible, isCacheCompatible, isParserCurrent, isBulkDdlEligible, status);
                    versions.merge(key, 1, Integer::sum);
                    details.add(csvLine(relative, "CANONICAL_SNAPSHOT", snapshotVersion, modelVersion,
                            parserVersion, Boolean.toString(isContractCompatible),
                            Boolean.toString(isCacheCompatible), Boolean.toString(isParserCurrent),
                            Boolean.toString(isBulkDdlEligible), status, source, ""));
                } else if (path.getFileName().toString().equalsIgnoreCase("manifest.json")) {
                    other++;
                    details.add(csvLine(relative, "MANIFEST", "", "", "", "", "", "", "", "", "", ""));
                } else {
                    other++;
                    details.add(csvLine(relative, "OTHER_JSON", "", "", "", "", "", "", "", "", "", ""));
                }
            } catch (Exception exception) {
                unreadable++;
                details.add(csvLine(relative, "UNREADABLE_JSON", "", "", "", "", "", "", "", "", "",
                        exception.getClass().getSimpleName() + ": " + safeMessage(exception)));
            }
        }

        List<String> versionSummary = new ArrayList<>();
        versionSummary.add("snapshot_version,model_version,parser_version,contract_compatible,"
                + "cache_compatible,parser_current,bulk_ddl_eligible,status,count");
        versions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> versionSummary.add(csvLine(
                        entry.getKey().snapshotVersion(), entry.getKey().modelVersion(),
                        entry.getKey().parserVersion(), Boolean.toString(entry.getKey().contractCompatible()),
                        Boolean.toString(entry.getKey().cacheCompatible()),
                        Boolean.toString(entry.getKey().parserCurrent()),
                        Boolean.toString(entry.getKey().bulkDdlEligible()), entry.getKey().status(),
                        Integer.toString(entry.getValue()))));

        return new JsonInventory(total, canonical, contractCompatible, cacheCompatible, staleParser,
                bulkDdlEligible, incompatibleContract, other, unreadable,
                List.copyOf(details), List.copyOf(versionSummary));
    }

    private static boolean isCanonicalSnapshot(JsonNode node) {
        return node != null && node.isObject()
                && node.has("snapshotVersion")
                && node.has("modelVersion")
                && node.has("parserVersion")
                && node.has("schema");
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("").trim();
    }

    private static boolean isJson(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json");
    }

    private static Path optionalDirectory(String property) {
        String value = trimToNull(System.getProperty(property));
        if (value == null) return null;
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Input directory does not exist for " + property + ": " + path);
        }
        return path;
    }

    private static Path outputDirectory(Path wordRoot, Path jsonRoot) {
        String configured = trimToNull(System.getProperty(OUTPUT_DIR));
        if (configured != null) return Path.of(configured).toAbsolutePath().normalize();
        Path basis = wordRoot != null ? wordRoot : jsonRoot;
        return basis.resolveSibling("schemaforge-corpus-reports").toAbsolutePath().normalize();
    }

    private static String textSummary(
            Path wordRoot, Path jsonRoot, WordCounts word, JsonInventory json) {
        return "SchemaForge corpus inventory" + System.lineSeparator()
                + "New Word root          : " + value(wordRoot) + System.lineSeparator()
                + "Legacy JSON root       : " + value(jsonRoot) + System.lineSeparator()
                + "New Word files         : " + word.total() + System.lineSeparator()
                + "  DOCX                 : " + word.docx() + System.lineSeparator()
                + "  DOC                  : " + word.doc() + System.lineSeparator()
                + "JSON files             : " + json.total() + System.lineSeparator()
                + "Canonical snapshots    : " + json.canonical() + System.lineSeparator()
                + "  Contract compatible  : " + json.contractCompatible() + System.lineSeparator()
                + "  Cache compatible     : " + json.cacheCompatible() + System.lineSeparator()
                + "  Stale parser         : " + json.staleParser() + System.lineSeparator()
                + "  Bulk DDL eligible    : " + json.bulkDdlEligible() + System.lineSeparator()
                + "  Incompatible contract: " + json.incompatibleContract() + System.lineSeparator()
                + "Other JSON             : " + json.other() + System.lineSeparator()
                + "Unreadable JSON        : " + json.unreadable() + System.lineSeparator()
                + "Expected snapshot      : " + CanonicalSnapshotVersions.SNAPSHOT_VERSION + System.lineSeparator()
                + "Expected model         : " + CanonicalSnapshotVersions.MODEL_VERSION + System.lineSeparator()
                + "Expected parser        : " + CanonicalSnapshotVersions.PARSER_VERSION + System.lineSeparator();
    }

    private static String value(Path path) {
        return path == null ? "<not configured>" : path.toAbsolutePath().toString();
    }

    private static String csvLine(String... values) {
        List<String> escaped = new ArrayList<>(values.length);
        for (String value : values) escaped.add(csv(value));
        return String.join(",", escaped);
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private record WordCounts(int docx, int doc) {
        int total() { return docx + doc; }
    }

    private record JsonInventory(
            int total,
            int canonical,
            int contractCompatible,
            int cacheCompatible,
            int staleParser,
            int bulkDdlEligible,
            int incompatibleContract,
            int other,
            int unreadable,
            List<String> details,
            List<String> versionSummary) {
    }

    private record VersionKey(
            String snapshotVersion,
            String modelVersion,
            String parserVersion,
            boolean contractCompatible,
            boolean cacheCompatible,
            boolean parserCurrent,
            boolean bulkDdlEligible,
            String status) implements Comparable<VersionKey> {
        @Override
        public int compareTo(VersionKey other) {
            int result = snapshotVersion.compareTo(other.snapshotVersion);
            if (result != 0) return result;
            result = modelVersion.compareTo(other.modelVersion);
            if (result != 0) return result;
            result = parserVersion.compareTo(other.parserVersion);
            if (result != 0) return result;
            return status.compareTo(other.status);
        }
    }
}
