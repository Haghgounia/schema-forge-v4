package com.behsazan.schemaforge.integration;

import com.behsazan.schemaforge.domain.enums.IndexType;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.specification.parser.legacy.LegacyWordSpecificationParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB2 LUW P8.3 targeted source-evidence probe for CTACCOUNTTYPE.ACCTYPE.
 *
 * <p>P8.2 proved that two historical generated versions and historical canonical snapshots
 * declare ACCTYPE as the primary key, while the selected final generated version does not.
 * This probe reparses only CTAccountType Word sources with the current legacy parser so the
 * project can distinguish a current-source extraction defect from an intentional historical
 * key removal. It is read-only and never mutates snapshots, generated SQL, or the live DB2 catalog.</p>
 */
class Db2LuwAccountTypeSourceEvidenceP83IT {
    private static final String WORD_ROOT = "schemaforge.db2luw.p8.wordRoot";
    private static final String DB2_SYSCOLUMNS_FILE = "schemaforge.db2luw.p7.db2SysColumnsFile";
    private static final String OUTPUT_DIR = "schemaforge.db2luw.p8.p83ReportDir";
    private static final String LEGACY_SCHEMA = "schemaforge.db2luw.p8.legacySchema";
    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT);

    @Test
    void probesCurrentAndHistoricalCtAccountTypeWordKeyEvidence() throws Exception {
        String configuredRoot = trimToNull(System.getProperty(WORD_ROOT));
        Assumptions.assumeTrue(configuredRoot != null,
                "Set -D" + WORD_ROOT + "=<legacy Word root> to run P8.3 source-evidence probe.");
        Path wordRoot = Path.of(configuredRoot).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(wordRoot), WORD_ROOT + " must be an existing directory: " + wordRoot);

        String schema = System.getProperty(LEGACY_SCHEMA, "TSTSHMA").trim();
        LegacyWordSpecificationParser parser = parser();

        List<Path> candidates;
        try (var paths = Files.walk(wordRoot)) {
            candidates = paths.filter(Files::isRegularFile)
                    .filter(Db2LuwAccountTypeSourceEvidenceP83IT::isWordDocument)
                    .filter(path -> path.getFileName().toString().toUpperCase(Locale.ROOT).contains("CTACCOUNTTYPE"))
                    .sorted(Comparator.comparing(path -> normalize(wordRoot.relativize(path))))
                    .toList();
        }
        assertTrue(!candidates.isEmpty(), "No CTAccountType Word documents found below " + wordRoot);

        List<Row> rows = new ArrayList<>();
        for (Path document : candidates) {
            String relative = normalize(wordRoot.relativize(document));
            try {
                DatabaseSchema parsed = parser.parse(wordRoot, document, schema);
                boolean found = false;
                for (Table table : parsed.tables()) {
                    if (!"CTACCOUNTTYPE".equalsIgnoreCase(table.qualifiedName().name().value())) continue;
                    found = true;
                    String pkName = table.primaryKey().map(pk -> pk.name() == null ? "" : pk.name().value()).orElse("");
                    String pkColumns = table.primaryKey()
                            .map(pk -> pk.columns().stream().map(id -> id.value()).reduce((a, b) -> a + "|" + b).orElse(""))
                            .orElse("");
                    boolean acctypePk = table.primaryKey()
                            .map(pk -> pk.columns().size() == 1 && "ACCTYPE".equalsIgnoreCase(pk.columns().get(0).value()))
                            .orElse(false);
                    String uniqueKeys = table.uniqueKeys().stream().map(Db2LuwAccountTypeSourceEvidenceP83IT::formatUk)
                            .reduce((a, b) -> a + ";" + b).orElse("");
                    String uniqueIndexes = table.indexes().stream()
                            .filter(index -> index.type() == IndexType.UNIQUE)
                            .map(Db2LuwAccountTypeSourceEvidenceP83IT::formatIndex)
                            .reduce((a, b) -> a + ";" + b).orElse("");
                    rows.add(new Row(relative, sourceClass(relative), "PARSED",
                            table.qualifiedName().toString(), table.columns().size(), pkName, pkColumns,
                            acctypePk, uniqueKeys, uniqueIndexes, table.foreignKeys().size(), ""));
                }
                if (!found) {
                    rows.add(new Row(relative, sourceClass(relative), "NO_CTACCOUNTTYPE_TABLE",
                            "", 0, "", "", false, "", "", 0, ""));
                }
            } catch (Exception exception) {
                rows.add(new Row(relative, sourceClass(relative), "PARSE_FAILED",
                        "", 0, "", "", false, "", "", 0, safeMessage(exception)));
            }
        }

        Path reportDir = reportDirectory();
        Files.createDirectories(reportDir);
        Path csv = reportDir.resolve("db2luw-p8.3-ctaccounttype-source-evidence.csv");
        Path summary = reportDir.resolve("db2luw-p8.3-ctaccounttype-source-evidence-summary.txt");
        writeCsv(csv, rows);

        long parsed = rows.stream().filter(row -> "PARSED".equals(row.status())).count();
        long current = rows.stream().filter(row -> "CURRENT_CANDIDATE".equals(row.sourceClass())).count();
        long historical = rows.stream().filter(row -> "HISTORICAL".equals(row.sourceClass())).count();
        long currentPk = rows.stream().filter(row -> "CURRENT_CANDIDATE".equals(row.sourceClass()) && row.acctypePrimaryKey()).count();
        long historicalPk = rows.stream().filter(row -> "HISTORICAL".equals(row.sourceClass()) && row.acctypePrimaryKey()).count();

        String decision;
        if (currentPk > 0) {
            decision = "CURRENT_SOURCE_DECLARES_ACCTYPE_PK__RECOVERY_OR_FINAL_SELECTION_DEFECT";
        } else if (historicalPk > 0 && current > 0) {
            decision = "CURRENT_SOURCE_DOES_NOT_DECLARE_ACCTYPE_PK__HISTORICAL_KEY_MUST_NOT_BE_FORCED";
        } else if (historicalPk > 0) {
            decision = "ONLY_HISTORICAL_SOURCE_EVIDENCE_AVAILABLE";
        } else {
            decision = "NO_WORD_SOURCE_ACCTYPE_PK_EVIDENCE";
        }

        String text = """
                DB2 LUW P8.3 CTACCOUNTTYPE Source Evidence
                ==========================================
                Word root                    : %s
                Candidate documents          : %d
                Parsed CTACCOUNTTYPE rows    : %d
                Current-source candidates    : %d
                Historical-source candidates : %d
                Current ACCTYPE PK evidence  : %d
                Historical ACCTYPE PK evidence: %d
                Parser version               : %s

                Decision                     : %s
                Mutation policy              : EVIDENCE ONLY; NO GENERATED/CANONICAL/DB2 MUTATION
                Report directory             : %s
                """.formatted(wordRoot, candidates.size(), parsed, current, historical,
                currentPk, historicalPk, LegacyWordSpecificationParser.PARSER_VERSION, decision, reportDir);
        Files.writeString(summary, text, StandardCharsets.UTF_8);
        System.out.println(text);

        assertTrue(parsed > 0, "No CTACCOUNTTYPE Word source was successfully parsed; see " + csv);
    }

    private static LegacyWordSpecificationParser parser() {
        String configured = trimToNull(System.getProperty(DB2_SYSCOLUMNS_FILE));
        if (configured == null) return new LegacyWordSpecificationParser();
        Path file = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException(DB2_SYSCOLUMNS_FILE + " must point to a file: " + file);
        }
        return LegacyWordSpecificationParser.withDb2SysColumns(file);
    }

    private static String formatUk(UniqueKey uk) {
        String name = uk.name() == null ? "" : uk.name().value();
        String cols = uk.columns().stream().map(id -> id.value()).reduce((a, b) -> a + "|" + b).orElse("");
        return name + "(" + cols + ")";
    }

    private static String formatIndex(Index index) {
        String cols = index.columns().stream()
                .map(col -> col.expressionBased() ? "EXPR" : col.column().value())
                .reduce((a, b) -> a + "|" + b).orElse("");
        return index.name().value() + "(" + cols + ")";
    }

    private static String sourceClass(String relative) {
        String value = relative.toUpperCase(Locale.ROOT);
        if (value.contains("OLD DOCUMENT") || value.contains("/OLD/") || value.contains("\\OLD\\")
                || value.contains("_OLD_") || value.contains("OTHERS_OLD") || value.contains("OLD1_")) {
            return "HISTORICAL";
        }
        return "CURRENT_CANDIDATE";
    }

    private static boolean isWordDocument(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".doc") || name.endsWith(".docx");
    }

    private static Path reportDirectory() {
        String configured = trimToNull(System.getProperty(OUTPUT_DIR));
        Path root = configured == null
                ? Path.of("target", "db2luw-p8.3-ctaccounttype-source-evidence")
                : Path.of(configured);
        return root.toAbsolutePath().normalize().resolve(LocalDateTime.now().format(RUN_ID));
    }

    private static void writeCsv(Path file, List<Row> rows) throws Exception {
        StringBuilder out = new StringBuilder();
        out.append("source,source_class,status,table,column_count,pk_name,pk_columns,acctype_primary_key,unique_keys,unique_indexes,foreign_key_count,error\n");
        for (Row row : rows) {
            csv(out, row.source()); out.append(',');
            csv(out, row.sourceClass()); out.append(',');
            csv(out, row.status()); out.append(',');
            csv(out, row.table()); out.append(',');
            out.append(row.columnCount()).append(',');
            csv(out, row.pkName()); out.append(',');
            csv(out, row.pkColumns()); out.append(',');
            out.append(row.acctypePrimaryKey()).append(',');
            csv(out, row.uniqueKeys()); out.append(',');
            csv(out, row.uniqueIndexes()); out.append(',');
            out.append(row.foreignKeyCount()).append(',');
            csv(out, row.error()); out.append('\n');
        }
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
    }

    private static void csv(StringBuilder out, String value) {
        String safe = value == null ? "" : value;
        out.append('"').append(safe.replace("\"", "\"\"")).append('"');
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null ? throwable.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record Row(String source, String sourceClass, String status, String table, int columnCount,
                       String pkName, String pkColumns, boolean acctypePrimaryKey, String uniqueKeys,
                       String uniqueIndexes, int foreignKeyCount, String error) {}
}
