package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.reporting.CanonicalSnapshotCatalogExcelWriter;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit one-file catalog export for a directory of canonical *.schema.json snapshots. */
class CanonicalSnapshotDirectoryCatalogExportIT {
    private static final String SNAPSHOT_DIR = "schemaforge.catalog.snapshotDir";
    private static final String OUTPUT_FILE = "schemaforge.catalog.outputFile";

    @Test
    void exportsAllTablesAndFieldsIntoOneWorkbook() throws Exception {
        String snapshotDir = trimToNull(System.getProperty(SNAPSHOT_DIR));
        if (snapshotDir == null) {
            throw new IllegalArgumentException("Required system property is missing: -D" + SNAPSHOT_DIR + "=<directory>");
        }
        Path root = Path.of(snapshotDir).toAbsolutePath().normalize();
        String configuredOutput = trimToNull(System.getProperty(OUTPUT_FILE));
        Path output = configuredOutput == null
                ? root.resolve("schemaforge-table-field-catalog.xlsx")
                : Path.of(configuredOutput).toAbsolutePath().normalize();

        CanonicalSnapshotCatalogExcelWriter.Result result =
                new CanonicalSnapshotCatalogExcelWriter().write(root, output);

        assertTrue(result.snapshotFiles() > 0, "No canonical snapshots were exported");
        assertTrue(result.tables() > 0, "No tables were exported");
        assertTrue(result.fields() > 0, "No fields were exported");

        System.out.println("Snapshot files              : " + result.snapshotFiles());
        System.out.println("Tables                      : " + result.tables());
        System.out.println("Fields                      : " + result.fields());
        System.out.println("Unresolved datatypes        : " + result.unresolvedDatatypes());
        System.out.println("Unresolved character length: " + result.unresolvedCharacterLengths());
        System.out.println("Catalog workbook            : " + result.outputFile());
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
