package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.snapshot.CanonicalSchemaSnapshot;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotJsonStore;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotVersions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression contract for the 749-document Facility legacy RTL9 corpus.
 *
 * <p>This test intentionally runs against generated canonical snapshots rather than Word files,
 * so it is fast enough to use after every parser refresh.  It protects the qualified table/field
 * baseline and the PK/index recovery contract without bundling private Facility documents in the
 * source tree.</p>
 */
class FacilityLegacyRtl9SnapshotRegressionIT {
    private static final String SNAPSHOT_DIR = "schemaforge.facility.snapshotDir";
    private static final String UNRESOLVED_CHARACTER_LENGTH =
            "schemaforge.recovery.unresolvedCharacterLength";

    @Test
    void preservesQualifiedFacilityCorpusAndPkIndexContract() throws Exception {
        Path root = requiredDirectory(SNAPSHOT_DIR);
        List<Path> files;
        try (var stream = Files.walk(root)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(".schema.json"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }

        CanonicalSnapshotJsonStore store = new CanonicalSnapshotJsonStore();
        Statistics stats = new Statistics();
        Map<String, CanonicalSchemaSnapshot.TableSnapshot> tablesByName = new LinkedHashMap<>();
        List<String> unexpectedParserVersions = new ArrayList<>();

        for (Path file : files) {
            CanonicalSchemaSnapshot snapshot = store.readSnapshot(file);
            stats.snapshots++;
            if (!CanonicalSnapshotVersions.PARSER_VERSION.equals(snapshot.parserVersion())) {
                unexpectedParserVersions.add(file.getFileName() + "=" + snapshot.parserVersion());
            }
            CanonicalSchemaSnapshot.SchemaSnapshot schema = snapshot.schema();
            if (schema == null) {
                continue;
            }

            countIssueCodes(schema.metadata(), stats);
            for (CanonicalSchemaSnapshot.TableSnapshot table : safeList(schema.tables())) {
                stats.tables++;
                tablesByName.put(table.name().toUpperCase(Locale.ROOT), table);
                List<CanonicalSchemaSnapshot.ColumnSnapshot> columns = safeList(table.columns());
                stats.fields += columns.size();
                for (CanonicalSchemaSnapshot.ColumnSnapshot column : columns) {
                    CanonicalSchemaSnapshot.DataTypeSnapshot type = column.dataType();
                    if (type != null && "MISSING_DATA_TYPE".equalsIgnoreCase(type.name())) {
                        stats.unresolvedDatatypes++;
                    }
                    if (type != null && type.length() == null
                            && isCharacterType(type.name())
                            && Boolean.parseBoolean(safeMap(column.physicalOptions())
                                    .get(UNRESOLVED_CHARACTER_LENGTH))) {
                        stats.unresolvedCharacterLengths++;
                    }
                }

                if (table.primaryKey() != null) {
                    stats.primaryKeys++;
                    if (safeList(table.primaryKey().columns()).size() > 1) {
                        stats.compositePrimaryKeys++;
                    }
                }

                List<CanonicalSchemaSnapshot.IndexSnapshot> indexes = safeList(table.indexes());
                if (!indexes.isEmpty()) {
                    stats.tablesWithIndexes++;
                }
                for (CanonicalSchemaSnapshot.IndexSnapshot index : indexes) {
                    stats.indexes++;
                    if ("UNIQUE".equalsIgnoreCase(index.type())) {
                        stats.uniqueIndexes++;
                    } else {
                        stats.normalIndexes++;
                    }
                    if (safeList(index.columns()).size() > 1) {
                        stats.compositeIndexes++;
                    }
                }
            }
        }

        assertEquals(749, stats.snapshots, "Facility snapshot count regression");
        assertEquals(749, stats.tables, "Facility table count regression");
        assertEquals(12_237, stats.fields, "Facility field count regression");
        assertEquals(50, stats.unresolvedDatatypes, "Facility unresolved datatype baseline changed");
        assertEquals(10, stats.unresolvedCharacterLengths,
                "Facility unresolved character-length baseline changed");

        assertEquals(202, stats.primaryKeys, "Facility primary-key count regression");
        assertEquals(86, stats.compositePrimaryKeys, "Facility composite primary-key count regression");
        assertEquals(170, stats.indexes, "Facility index count regression");
        assertEquals(152, stats.normalIndexes, "Facility normal-index count regression");
        assertEquals(18, stats.uniqueIndexes, "Facility unique-index count regression");
        assertEquals(49, stats.compositeIndexes, "Facility composite-index count regression");
        assertEquals(107, stats.tablesWithIndexes, "Facility table-with-index count regression");

        assertEquals(23, stats.indexTypeNotPresent,
                "Missing index-type evidence should remain fail-closed");
        assertEquals(9, stats.ambiguousCompositeKey,
                "Ambiguous composite-key evidence should remain fail-closed");
        assertEquals(20, stats.indexFieldUnresolved,
                "Unresolved index-field evidence baseline changed");
        assertEquals(0, stats.indexTypeUnrecognized,
                "Known Facility index labels should not remain in the generic unrecognized bucket");
        assertTrue(unexpectedParserVersions.isEmpty(),
                "Snapshots are stale or mixed parser versions: " + unexpectedParserVersions.stream().limit(5).toList());

        assertRepresentativeContracts(tablesByName);

        System.out.println("Facility snapshots                : " + stats.snapshots);
        System.out.println("Tables                            : " + stats.tables);
        System.out.println("Fields                            : " + stats.fields);
        System.out.println("Unresolved datatypes              : " + stats.unresolvedDatatypes);
        System.out.println("Unresolved character lengths      : " + stats.unresolvedCharacterLengths);
        System.out.println("Primary keys                      : " + stats.primaryKeys);
        System.out.println("Composite primary keys            : " + stats.compositePrimaryKeys);
        System.out.println("Indexes                           : " + stats.indexes);
        System.out.println("Normal indexes                    : " + stats.normalIndexes);
        System.out.println("Unique indexes                    : " + stats.uniqueIndexes);
        System.out.println("Composite indexes                 : " + stats.compositeIndexes);
        System.out.println("Tables with indexes               : " + stats.tablesWithIndexes);
        System.out.println("Index type not present            : " + stats.indexTypeNotPresent);
        System.out.println("Ambiguous composite-key rows      : " + stats.ambiguousCompositeKey);
        System.out.println("Index field unresolved rows       : " + stats.indexFieldUnresolved);
        System.out.println("Generic unrecognized index types  : " + stats.indexTypeUnrecognized);
    }

    private static void assertRepresentativeContracts(
            Map<String, CanonicalSchemaSnapshot.TableSnapshot> tablesByName) {
        CanonicalSchemaSnapshot.TableSnapshot contract = requireTable(tablesByName, "CTFCNTRCT");
        assertEquals(List.of("CNTRCTID"), safeList(contract.primaryKey().columns()));
        assertEquals(3, safeList(contract.indexes()).size());
        assertTrue(hasIndex(contract, List.of("PRPSLTYPCD"), "NORMAL"));
        assertTrue(hasIndex(contract, List.of("PRPSLTYPCD", "CNTRCTORGUNITCD"), "NORMAL"));
        assertTrue(hasIndex(contract, List.of("INTCSTMRCD"), "NORMAL"));

        CanonicalSchemaSnapshot.TableSnapshot collateralType = requireTable(tablesByName, "CTFCLTRLTYP");
        assertEquals(List.of("CLTRLTYPID"), safeList(collateralType.primaryKey().columns()));

        CanonicalSchemaSnapshot.TableSnapshot disaster = requireTable(tablesByName, "CTFDSSTR");
        assertEquals(List.of("DSSTRID"), safeList(disaster.primaryKey().columns()));

        CanonicalSchemaSnapshot.TableSnapshot proposalType = requireTable(tablesByName, "CTFPRPSLTYP");
        assertTrue(proposalType.primaryKey() == null);
        assertTrue(safeList(proposalType.indexes()).isEmpty());

        CanonicalSchemaSnapshot.TableSnapshot genericEnglishIndex =
                requireTable(tablesByName, "CTFMRFCLTYISTLMTLOG");
        assertTrue(hasIndex(genericEnglishIndex, List.of("CNTRCTCD", "OPRTYPE"), "NORMAL"));

        CanonicalSchemaSnapshot.TableSnapshot genericPersianIndex =
                requireTable(tablesByName, "CTFPYMRTGAMNTCLTTRNLG");
        assertTrue(hasIndex(genericPersianIndex, List.of("INTCSTMRCD"), "NORMAL"));
        assertTrue(hasIndex(genericPersianIndex, List.of("ACCNO"), "NORMAL"));
    }

    private static boolean hasIndex(
            CanonicalSchemaSnapshot.TableSnapshot table,
            List<String> expectedColumns,
            String expectedType) {
        return safeList(table.indexes()).stream().anyMatch(index -> {
            if (!expectedType.equalsIgnoreCase(index.type())) {
                return false;
            }
            List<String> actual = safeList(index.columns()).stream()
                    .map(CanonicalSchemaSnapshot.IndexColumnSnapshot::column)
                    .toList();
            return expectedColumns.equals(actual);
        });
    }

    private static CanonicalSchemaSnapshot.TableSnapshot requireTable(
            Map<String, CanonicalSchemaSnapshot.TableSnapshot> tablesByName,
            String name) {
        CanonicalSchemaSnapshot.TableSnapshot table = tablesByName.get(name.toUpperCase(Locale.ROOT));
        assertNotNull(table, "Expected representative Facility table was not found: " + name);
        return table;
    }

    private static void countIssueCodes(Map<String, String> metadata, Statistics stats) {
        for (String value : safeMap(metadata).values()) {
            if (value == null || value.isBlank()) {
                continue;
            }
            for (String line : value.split("\\R")) {
                if (line.contains("LEGACY_INDEX_SECTION_TYPE_NOT_PRESENT")) {
                    stats.indexTypeNotPresent++;
                }
                if (line.contains("LEGACY_INDEX_SECTION_COMPOSITE_KEY_AMBIGUOUS")) {
                    stats.ambiguousCompositeKey++;
                }
                if (line.contains("LEGACY_INDEX_SECTION_FIELD_UNRESOLVED")) {
                    stats.indexFieldUnresolved++;
                }
                if (line.contains("LEGACY_INDEX_SECTION_TYPE_UNRECOGNIZED")) {
                    stats.indexTypeUnrecognized++;
                }
            }
        }
    }

    private static boolean isCharacterType(String value) {
        if (value == null) return false;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return normalized.equals("CHAR") || normalized.equals("CHARACTER")
                || normalized.equals("VARCHAR") || normalized.equals("VARCHAR2");
    }

    private static Path requiredDirectory(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Required system property is missing: -D" + property + "=<snapshot-directory>");
        }
        Path path = Path.of(value.trim()).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException(
                    "System property " + property + " must point to an existing directory: " + path);
        }
        return path;
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static <K, V> Map<K, V> safeMap(Map<K, V> values) {
        return values == null ? Map.of() : values;
    }

    private static final class Statistics {
        int snapshots;
        int tables;
        int fields;
        int unresolvedDatatypes;
        int unresolvedCharacterLengths;
        int primaryKeys;
        int compositePrimaryKeys;
        int indexes;
        int normalIndexes;
        int uniqueIndexes;
        int compositeIndexes;
        int tablesWithIndexes;
        int indexTypeNotPresent;
        int ambiguousCompositeKey;
        int indexFieldUnresolved;
        int indexTypeUnrecognized;
    }
}
