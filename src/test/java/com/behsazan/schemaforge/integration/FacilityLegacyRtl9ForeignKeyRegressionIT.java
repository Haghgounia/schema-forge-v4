package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.snapshot.CanonicalSchemaSnapshot;
import com.behsazan.schemaforge.snapshot.CanonicalSnapshotJsonStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * First qualification contract for explicit Facility RTL9 dependency recovery.
 *
 * <p>The preceding read-only probe found 29 populated dependency rows across 15 documents.
 * Twenty-four rows contain an exact local field plus an explicit TABLE.COLUMN or
 * SCHEMA.TABLE.COLUMN target and are therefore expected to become canonical single-column
 * foreign keys.  Ambiguous/incomplete rows remain fail-closed.</p>
 */
class FacilityLegacyRtl9ForeignKeyRegressionIT {
    private static final String SNAPSHOT_DIR = "schemaforge.facility.snapshotDir";

    @Test
    void recoversOnlyQualifiedFacilityDependencyRows() throws Exception {
        Path root = requiredDirectory(SNAPSHOT_DIR);
        CanonicalSnapshotJsonStore store = new CanonicalSnapshotJsonStore();
        Map<String, CanonicalSchemaSnapshot.TableSnapshot> tables = new LinkedHashMap<>();

        int snapshots = 0;
        int foreignKeys = 0;
        int tablesWithForeignKeys = 0;
        int explicitSchemaForeignKeys = 0;
        int externalSchemaForeignKeys = 0;

        List<Path> files;
        try (var stream = Files.walk(root)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(".schema.json"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }

        for (Path file : files) {
            CanonicalSchemaSnapshot snapshot = store.readSnapshot(file);
            snapshots++;
            if (snapshot.schema() == null) {
                continue;
            }
            for (CanonicalSchemaSnapshot.TableSnapshot table : safeList(snapshot.schema().tables())) {
                tables.put(table.name().toUpperCase(Locale.ROOT), table);
                List<CanonicalSchemaSnapshot.ForeignKeySnapshot> fks = safeList(table.foreignKeys());
                if (!fks.isEmpty()) {
                    tablesWithForeignKeys++;
                }
                for (CanonicalSchemaSnapshot.ForeignKeySnapshot fk : fks) {
                    foreignKeys++;
                    if (fk.schemaExplicit()) {
                        explicitSchemaForeignKeys++;
                    }
                    if (fk.referencedSchema() != null
                            && !fk.referencedSchema().equalsIgnoreCase("TASSHMA")) {
                        externalSchemaForeignKeys++;
                    }
                    assertEquals(1, safeList(fk.columns()).size(),
                            "Qualified Facility dependency rows currently represent one local field each");
                    assertEquals(1, safeList(fk.referencedColumns()).size(),
                            "Qualified Facility dependency rows currently represent one referenced field each");
                }
            }
        }

        assertEquals(749, snapshots, "Facility snapshot count regression");
        assertEquals(24, foreignKeys, "Qualified dependency-row FK count changed");
        assertEquals(11, tablesWithForeignKeys, "Facility tables-with-FK count changed");
        assertEquals(22, explicitSchemaForeignKeys, "Explicit-schema FK count changed");
        assertEquals(5, externalSchemaForeignKeys, "External-schema FK count changed");

        assertForeignKey(tables, "CTFAVRGREQ", "INTCSTMRCD", "TASSHMA", "CTFCSTMR", "INTCSTMRID", true);
        assertForeignKey(tables, "CTFAVRGREQ", "EXTCSTMRCD", "TASSHMA", "CTFCSTMR", "CSTMRID", true);
        assertForeignKey(tables, "CTFAVRGREQ", "ORGUNITCD", "JAMSHMA", "JTDTORGANIZATION", "BRANCH", true);
        assertForeignKey(tables, "CTFBLKSTATUS", "FORMCODE", "TASSHMA", "CTFACTIVITY", "FORMCODE", false);
        assertForeignKey(tables, "CTFLGCSTPAY", "TRNSCTNCD", "TASSHMA", "CTFTRNSCTN", "TRNSCTNID", true);
        assertForeignKey(tables, "CTFPROSCRIPTIONPROCESS", "FCLTYTYPCD_ARZIRIALI", "TASSHMA", "CTFFCLTYTYP", "FCLTYTYPID", false);

        assertTrue(safeList(requireTable(tables, "CTFCHQASSIGNCHNGLOG").foreignKeys()).isEmpty(),
                "Bare same-name dependency evidence must remain fail-closed");
        assertTrue(safeList(requireTable(tables, "CTFFNNCDSHBRD").foreignKeys()).isEmpty(),
                "Descriptive dependency text must remain fail-closed");
        assertTrue(safeList(requireTable(tables, "CTFMEANREQRESULT").foreignKeys()).isEmpty(),
                "SCHEMA.TABLE dependency evidence without referenced column must remain fail-closed");

        System.out.println("Facility snapshots                : " + snapshots);
        System.out.println("Foreign keys                      : " + foreignKeys);
        System.out.println("Tables with foreign keys          : " + tablesWithForeignKeys);
        System.out.println("Explicit-schema foreign keys      : " + explicitSchemaForeignKeys);
        System.out.println("External-schema foreign keys      : " + externalSchemaForeignKeys);
    }

    private static void assertForeignKey(
            Map<String, CanonicalSchemaSnapshot.TableSnapshot> tables,
            String owner,
            String localColumn,
            String referencedSchema,
            String referencedTable,
            String referencedColumn,
            boolean schemaExplicit) {
        CanonicalSchemaSnapshot.TableSnapshot table = requireTable(tables, owner);
        boolean found = safeList(table.foreignKeys()).stream().anyMatch(fk ->
                safeList(fk.columns()).equals(List.of(localColumn))
                        && referencedSchema.equalsIgnoreCase(fk.referencedSchema())
                        && referencedTable.equalsIgnoreCase(fk.referencedTable())
                        && safeList(fk.referencedColumns()).equals(List.of(referencedColumn))
                        && fk.schemaExplicit() == schemaExplicit);
        assertTrue(found, "Expected FK was not recovered: " + owner + "." + localColumn
                + " -> " + referencedSchema + "." + referencedTable + "." + referencedColumn);
    }

    private static CanonicalSchemaSnapshot.TableSnapshot requireTable(
            Map<String, CanonicalSchemaSnapshot.TableSnapshot> tables,
            String name) {
        CanonicalSchemaSnapshot.TableSnapshot table = tables.get(name.toUpperCase(Locale.ROOT));
        assertNotNull(table, "Expected Facility table was not found: " + name);
        return table;
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
}
