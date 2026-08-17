package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.snapshot.CanonicalSchemaSnapshot;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotJsonStore;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotManifest;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotMapper;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotVersions;
import com.behsazan.schemaforge.snapshot.SnapshotFileHash;
import com.behsazan.schemaforge.specification.parser.SpecificationSource;
import com.behsazan.schemaforge.specification.parser.WordSpecificationParser;
import com.behsazan.schemaforge.specification.parser.legacy.LegacyWordSpecificationParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Explicit recursive importer that materializes Word specifications as versioned canonical JSON snapshots.
 *
 * <p>Unchanged documents are not parsed again. A snapshot is reused only when its SHA-256 source hash,
 * snapshot version, canonical model version and parser-pipeline version all match the current runtime.
 * This turns the expensive Word parsing stage into an incremental cache refresh rather than a mandatory
 * step before every dialect experiment.</p>
 */
class WordDirectoryToCanonicalJsonIT {
    private static final String INPUT_DIR = "schemaforge.snapshot.word.inputDir";
    private static final String OUTPUT_DIR = "schemaforge.snapshot.outputDir";
    private static final String LEGACY_SCHEMA = "schemaforge.snapshot.legacySchema";
    private static final String FORCE_REFRESH = "schemaforge.snapshot.forceRefresh";
    private static final String FAIL_ON_ERRORS = "schemaforge.snapshot.failOnErrors";
    private static final String PARSER_MODE = "schemaforge.snapshot.parserMode";
    private static final String SOURCE_MANIFEST = "schemaforge.snapshot.sourceManifest";
    private static final String DB2_SYSCOLUMNS_FILE = "schemaforge.snapshot.db2SysColumnsFile";

    private LegacyWordSpecificationParser legacyParser = new LegacyWordSpecificationParser();
    private final CanonicalSnapshotMapper mapper = new CanonicalSnapshotMapper();
    private final CanonicalSnapshotJsonStore store = new CanonicalSnapshotJsonStore();

    @Test
    void recursivelyCachesWordDocumentsAsCanonicalJsonSnapshots() throws Exception {
        Path inputRoot = requiredDirectory(INPUT_DIR);
        Path outputRoot = outputDirectory(inputRoot);
        String legacySchema = trimToNull(System.getProperty(LEGACY_SCHEMA));
        boolean forceRefresh = Boolean.parseBoolean(System.getProperty(FORCE_REFRESH, "false"));
        boolean failOnErrors = Boolean.parseBoolean(System.getProperty(FAIL_ON_ERRORS, "false"));
        ParserMode parserMode = configuredParserMode();
        Path db2SysColumnsFile = optionalFile(DB2_SYSCOLUMNS_FILE);
        if (db2SysColumnsFile != null) {
            legacyParser = LegacyWordSpecificationParser.withDb2SysColumns(db2SysColumnsFile);
            System.out.println("Db2 SYSCOLUMNS    : " + db2SysColumnsFile);
        }
        Files.createDirectories(outputRoot);

        List<Path> documents;
        try (var paths = Files.walk(inputRoot)) {
            documents = paths.filter(Files::isRegularFile)
                    .filter(WordDirectoryToCanonicalJsonIT::isWordDocument)
                    .filter(path -> !path.toAbsolutePath().normalize().startsWith(outputRoot))
                    .sorted(Comparator.comparing(path -> normalize(inputRoot.relativize(path))))
                    .toList();
        }
        int documentsDiscovered = documents.size();
        Path sourceManifest = optionalFile(SOURCE_MANIFEST);
        if (sourceManifest != null) {
            Set<String> selectedSources = failedSources(sourceManifest);
            documents = documents.stream()
                    .filter(path -> selectedSources.contains(normalize(inputRoot.relativize(path))))
                    .toList();
            System.out.println("Source manifest    : " + sourceManifest);
            System.out.println("Manifest failures  : " + selectedSources.size());
            System.out.println("Documents selected : " + documents.size());
        }

        String generatedAt = Instant.now().toString();
        List<CanonicalSnapshotManifest.Entry> entries = new ArrayList<>();
        int written = 0;
        int cacheHits = 0;
        int skipped = 0;
        int failures = 0;

        for (Path document : documents) {
            String relative = normalize(inputRoot.relativize(document));
            Path target = snapshotPath(inputRoot, outputRoot, document);
            String sha256;
            try {
                sha256 = SnapshotFileHash.sha256(document);
            } catch (Exception exception) {
                failures++;
                entries.add(entry(relative, "", target, outputRoot, "HASH_FAILED", "", 0, 0,
                        exception.getClass().getSimpleName() + ": " + safeMessage(exception)));
                continue;
            }

            if (!forceRefresh && Files.isRegularFile(target)) {
                try {
                    CanonicalSchemaSnapshot cached = store.readSnapshot(target);
                    if (cacheHit(cached, relative, sha256)) {
                        cacheHits++;
                        int tableCount = safeTables(cached).size();
                        int columnCount = safeTables(cached).stream()
                                .mapToInt(table -> table.columns() == null ? 0 : table.columns().size()).sum();
                        entries.add(entry(relative, sha256, target, outputRoot, "CACHE_HIT",
                                cached.source().parserId(), tableCount, columnCount, ""));
                        System.out.println("[CACHE-HIT] " + relative);
                        continue;
                    }
                } catch (Exception invalidCache) {
                    System.out.println("[CACHE-REFRESH] " + relative + " - " + safeMessage(invalidCache));
                }
            }

            try {
                ParseOutcome outcome = parse(inputRoot, document, legacySchema, parserMode);
                if (outcome.schema().tables().isEmpty()) {
                    skipped++;
                    entries.add(entry(relative, sha256, target, outputRoot, "SKIPPED_NO_TABLE",
                            outcome.parserId(), 0, 0, "No table model"));
                    continue;
                }

                CanonicalSchemaSnapshot.SourceSnapshot source = new CanonicalSchemaSnapshot.SourceSnapshot(
                        relative,
                        document.getFileName().toString(),
                        sha256,
                        Files.size(document),
                        Files.getLastModifiedTime(document).toInstant().toString(),
                        outcome.parserId());
                CanonicalSchemaSnapshot snapshot = mapper.toSnapshot(outcome.schema(), source, generatedAt);
                store.writeSnapshot(target, snapshot);
                written++;
                int columns = outcome.schema().tables().stream().mapToInt(table -> table.columns().size()).sum();
                entries.add(entry(relative, sha256, target, outputRoot, "WRITTEN", outcome.parserId(),
                        outcome.schema().tables().size(), columns, ""));
                System.out.println("[WRITTEN] " + relative);
            } catch (NoTableDocumentException exception) {
                skipped++;
                entries.add(entry(relative, sha256, target, outputRoot, "SKIPPED_NO_TABLE", "", 0, 0,
                        exception.getMessage()));
                System.out.println("[SKIPPED] " + relative);
            } catch (Exception exception) {
                failures++;
                String message = exception.getClass().getSimpleName() + ": " + safeMessage(exception);
                entries.add(entry(relative, sha256, target, outputRoot, "PARSE_FAILED", "", 0, 0, message));
                System.out.println("[FAILED] " + relative + " - " + message);
            }
        }

        CanonicalSnapshotManifest manifest = new CanonicalSnapshotManifest(
                CanonicalSnapshotVersions.SNAPSHOT_VERSION,
                CanonicalSnapshotVersions.MODEL_VERSION,
                CanonicalSnapshotVersions.PARSER_VERSION,
                generatedAt,
                normalize(inputRoot),
                normalize(outputRoot),
                documents.size(), written, cacheHits, skipped, failures, List.copyOf(entries));
        Path manifestPath = outputRoot.resolve("manifest.json");
        store.write(manifestPath, manifest);

        System.out.println("Parser mode       : " + parserMode.name().toLowerCase(Locale.ROOT));
        System.out.println("Word documents    : " + documentsDiscovered);
        if (sourceManifest != null) {
            System.out.println("Documents selected : " + documents.size());
        }
        System.out.println("Snapshots written : " + written);
        System.out.println("Cache hits        : " + cacheHits);
        System.out.println("Skipped no table  : " + skipped);
        System.out.println("Failures          : " + failures);
        System.out.println("Snapshot root     : " + outputRoot);
        System.out.println("Manifest          : " + manifestPath);

        if (sourceManifest == null) {
            assertTrue(written + cacheHits > 0, "No canonical snapshot was written or reused");
        } else {
            assertTrue(written + cacheHits + skipped + failures == documents.size(),
                    "Targeted snapshot probe did not account for every selected document");
        }
        if (failOnErrors) {
            assertTrue(failures == 0, "Word-to-JSON snapshot run produced failures; see manifest.json");
        }
    }

    private ParseOutcome parse(
            Path inputRoot, Path document, String legacySchema, ParserMode parserMode) throws Exception {
        if (parserMode == ParserMode.LEGACY) {
            return new ParseOutcome(parseLegacy(inputRoot, document, legacySchema), "legacy-word");
        }

        String lower = document.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".doc")) {
            if (parserMode == ParserMode.STANDARD) {
                throw new IllegalArgumentException(
                        "Standard parser mode accepts .docx only: " + document.getFileName());
            }
            try {
                return new ParseOutcome(parseLegacy(inputRoot, document, legacySchema), "legacy-word");
            } catch (IllegalArgumentException legacyFailure) {
                String legacyMessage = legacyFailure.getMessage() == null ? "" : legacyFailure.getMessage();
                if (legacyMessage.startsWith("No legacy table definition was accepted")) {
                    throw new NoTableDocumentException(legacyMessage);
                }
                throw legacyFailure;
            }
        }

        Exception standardFailure;
        try (InputStream input = Files.newInputStream(document)) {
            DatabaseSchema schema = new WordSpecificationParser().parse(
                    new SpecificationSource(document.getFileName().toString(), input));
            return new ParseOutcome(schema, "standard-word");
        } catch (Exception exception) {
            if (parserMode == ParserMode.STANDARD) {
                throw exception;
            }
            standardFailure = exception;
        }

        try {
            return new ParseOutcome(parseLegacy(inputRoot, document, legacySchema), "legacy-word-fallback");
        } catch (IllegalArgumentException legacyFailure) {
            String legacyMessage = legacyFailure.getMessage() == null ? "" : legacyFailure.getMessage();
            if (legacyMessage.startsWith("No legacy table definition was accepted")) {
                throw new NoTableDocumentException(
                        "standard parser: " + safeMessage(standardFailure) + "; legacy parser: " + legacyMessage);
            }
            legacyFailure.addSuppressed(standardFailure);
            throw legacyFailure;
        }
    }

    private static Path optionalFile(String property) {
        String value = trimToNull(System.getProperty(property));
        if (value == null) return null;
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("System property " + property
                    + " must point to an existing file: " + path);
        }
        return path;
    }

    private static Set<String> failedSources(Path manifestPath) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        CanonicalSnapshotManifest manifest = objectMapper.readValue(
                manifestPath.toFile(), CanonicalSnapshotManifest.class);
        if (manifest.entries() == null) return Set.of();
        return manifest.entries().stream()
                .filter(entry -> "PARSE_FAILED".equalsIgnoreCase(entry.status()))
                .map(CanonicalSnapshotManifest.Entry::source)
                .filter(source -> source != null && !source.isBlank())
                .map(source -> source.replace('\\', '/'))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
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

    private DatabaseSchema parseLegacy(Path inputRoot, Path document, String legacySchema) {
        if (legacySchema == null) {
            throw new IllegalArgumentException("System property " + LEGACY_SCHEMA + " is required for legacy documents");
        }
        return legacyParser.parse(inputRoot, document, legacySchema);
    }

    private static boolean cacheHit(CanonicalSchemaSnapshot snapshot, String relative, String sha256) {
        return CanonicalSnapshotVersions.cacheCompatible(snapshot)
                && snapshot.source() != null
                && relative.equals(snapshot.source().relativePath())
                && sha256.equalsIgnoreCase(snapshot.source().sha256());
    }

    private static List<CanonicalSchemaSnapshot.TableSnapshot> safeTables(CanonicalSchemaSnapshot snapshot) {
        return snapshot.schema() == null || snapshot.schema().tables() == null
                ? List.of() : snapshot.schema().tables();
    }

    private static CanonicalSnapshotManifest.Entry entry(
            String source, String sha256, Path snapshot, Path outputRoot, String status, String parserId,
            int tableCount, int columnCount, String error) {
        return new CanonicalSnapshotManifest.Entry(
                source, sha256, normalize(outputRoot.relativize(snapshot)), status, parserId,
                tableCount, columnCount, error == null ? "" : error);
    }

    private static Path snapshotPath(Path inputRoot, Path outputRoot, Path document) {
        Path relative = inputRoot.relativize(document);
        Path parent = relative.getParent();
        Path directory = parent == null ? outputRoot : outputRoot.resolve(parent);
        return directory.resolve(document.getFileName().toString() + ".schema.json");
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
                ? inputRoot.resolve("schemaforge-canonical-json").toAbsolutePath().normalize()
                : Path.of(value).toAbsolutePath().normalize();
    }

    private static boolean isWordDocument(Path path) {
        String name = path.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".doc") && !lower.endsWith(".docx")) return false;
        if (name.startsWith("~$") || name.startsWith("._") || name.startsWith(".")) return false;
        for (Path segment : path) {
            if ("__MACOSX".equalsIgnoreCase(segment.toString())) return false;
        }
        return true;
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

    /** Parser result plus the concrete parser path recorded in source audit metadata. */
    private record ParseOutcome(DatabaseSchema schema, String parserId) {
    }

    /** Signals that neither Word parser accepted a table definition. */
    private static final class NoTableDocumentException extends Exception {
        private NoTableDocumentException(String message) {
            super(message);
        }
    }
}
