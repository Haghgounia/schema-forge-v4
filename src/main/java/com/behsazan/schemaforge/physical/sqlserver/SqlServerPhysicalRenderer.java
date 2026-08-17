package com.behsazan.schemaforge.physical.sqlserver;

import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.physical.PhysicalCommentBlocks;
import com.behsazan.schemaforge.physical.PhysicalCommentRenderer;
import com.behsazan.schemaforge.physical.PhysicalSourceOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** SQL Server Phase-1 physical defaults/candidates. */
public final class SqlServerPhysicalRenderer implements PhysicalCommentRenderer {
    private static final Set<String> COMPRESSION = Set.of("NONE", "ROW", "PAGE");
    private static final Set<String> ON_OFF = Set.of("ON", "OFF");

    @Override
    public String tableOptions(Table table, boolean activePlacementPresent) {
        List<String> lines = new ArrayList<>();
        if (!activePlacementPresent) {
            lines.add("ON [<TABLE_FILEGROUP>]");
        }
        String compression = PhysicalSourceOptions.enumClause(
                lines, table, "SQLSERVER", "DATA_COMPRESSION", "NONE",
                "TABLE_DATA_COMPRESSION", COMPRESSION,
                "SQLSERVER_TABLE_DATA_COMPRESSION", "TABLE_DATA_COMPRESSION");
        var xmlSource = PhysicalSourceOptions.find(table,
                "SQLSERVER_TABLE_XML_COMPRESSION", "TABLE_XML_COMPRESSION");
        List<String> tableOptions = new ArrayList<>();
        tableOptions.add("DATA_COMPRESSION = " + compression);
        if (xmlSource.isPresent()) {
            String xmlCompression = PhysicalSourceOptions.enumClause(
                    lines, table, "SQLSERVER", "XML_COMPRESSION", "<TABLE_XML_COMPRESSION>",
                    "TABLE_XML_COMPRESSION", ON_OFF,
                    "SQLSERVER_TABLE_XML_COMPRESSION", "TABLE_XML_COMPRESSION");
            tableOptions.add("XML_COMPRESSION = " + xmlCompression);
            if (!hasXmlColumn(table)) {
                PhysicalSourceOptions.addSourceReview(lines, "SQLSERVER",
                        "XML_COMPRESSION is source/profile-driven but no canonical XML column is visible; verify source type/version before activation.");
            }
        }
        if (xmlSource.isEmpty()) {
            // Preserve the Phase-1 SQL text shape when the new SQL Server 2022+
            // option is not present. This keeps existing generated artifacts and
            // golden/regression expectations stable.
            lines.add("WITH (DATA_COMPRESSION = " + compression + ")");
            lines.add("-- XML_COMPRESSION is SQL Server 2022+ and remains source/profile-only.");
        } else {
            lines.add("WITH (");
            for (int i = 0; i < tableOptions.size(); i++) {
                lines.add("    " + tableOptions.get(i) + (i < tableOptions.size() - 1 ? "," : ""));
            }
            lines.add(")");
        }
        return PhysicalCommentBlocks.block("SQL SERVER TABLE PHYSICAL OPTIONS", lines);
    }

    @Override
    public String indexOptions(Table table, List<Identifier> keyColumns, boolean activePlacementPresent) {
        return renderIndexOptions(table, null, keyColumns, activePlacementPresent, false);
    }

    @Override
    public String indexOptions(
            Table table, List<Identifier> keyColumns, boolean activePlacementPresent, boolean uniqueIndex) {
        return renderIndexOptions(table, null, keyColumns, activePlacementPresent, uniqueIndex);
    }

    @Override
    public String indexOptions(
            Table table, Index index, List<Identifier> keyColumns, boolean activePlacementPresent) {
        return renderIndexOptions(table, index, keyColumns, activePlacementPresent, false);
    }

    @Override
    public String indexOptions(
            Table table, Index index, List<Identifier> keyColumns,
            boolean activePlacementPresent, boolean uniqueIndex) {
        return renderIndexOptions(table, index, keyColumns, activePlacementPresent, uniqueIndex);
    }

    @Override
    public String constraintIndexOptions(Table table, List<Identifier> keyColumns, boolean activePlacementPresent) {
        return renderIndexOptions(table, null, keyColumns, activePlacementPresent, true);
    }

    @Override
    public String constraintIndexOptions(
            Table table, Index index, List<Identifier> keyColumns, boolean activePlacementPresent) {
        return renderIndexOptions(table, index, keyColumns, activePlacementPresent, true);
    }

    private String renderIndexOptions(
            Table table, Index index, List<Identifier> keyColumns,
            boolean activePlacementPresent, boolean knownUniqueIndex) {
        List<String> lines = new ArrayList<>();
        String fillfactor = PhysicalSourceOptions.integerClause(
                lines, index, table, "SQLSERVER", "FILLFACTOR =", 0, 0, 100, "INDEX_FILLFACTOR",
                "SQLSERVER_INDEX_FILLFACTOR", "INDEX_FILLFACTOR");
        String padIndex = PhysicalSourceOptions.enumClause(
                lines, index, table, "SQLSERVER", "PAD_INDEX", "OFF", "PAD_INDEX", ON_OFF,
                "SQLSERVER_INDEX_PAD_INDEX", "INDEX_PAD_INDEX");
        String compression = PhysicalSourceOptions.enumClause(
                lines, index, table, "SQLSERVER", "DATA_COMPRESSION", "NONE",
                "INDEX_DATA_COMPRESSION", COMPRESSION,
                "SQLSERVER_INDEX_DATA_COMPRESSION", "INDEX_DATA_COMPRESSION");

        String ignoreDupKey = PhysicalSourceOptions.enumClause(
                lines, index, table, "SQLSERVER", "IGNORE_DUP_KEY", "OFF", "IGNORE_DUP_KEY", ON_OFF,
                "SQLSERVER_INDEX_IGNORE_DUP_KEY", "INDEX_IGNORE_DUP_KEY");
        if ("ON".equals(ignoreDupKey) && !knownUniqueIndex) {
            PhysicalSourceOptions.addSourceReview(lines, "SQLSERVER", "IGNORE_DUP_KEY=ON is valid only for a UNIQUE index; verify uniqueness before activation.");
        }
        String statisticsNoRecompute = PhysicalSourceOptions.enumClause(
                lines, index, table, "SQLSERVER", "STATISTICS_NORECOMPUTE", "OFF", "STATISTICS_NORECOMPUTE", ON_OFF,
                "SQLSERVER_INDEX_STATISTICS_NORECOMPUTE", "INDEX_STATISTICS_NORECOMPUTE");
        var statisticsIncrementalSource = PhysicalSourceOptions.find(index, table,
                "SQLSERVER_INDEX_STATISTICS_INCREMENTAL", "INDEX_STATISTICS_INCREMENTAL");
        String statisticsIncremental = statisticsIncrementalSource.isPresent()
                ? PhysicalSourceOptions.enumClause(
                        lines, index, table, "SQLSERVER", "STATISTICS_INCREMENTAL", "<STATISTICS_INCREMENTAL>",
                        "STATISTICS_INCREMENTAL", ON_OFF,
                        "SQLSERVER_INDEX_STATISTICS_INCREMENTAL", "INDEX_STATISTICS_INCREMENTAL")
                : null;
        if ("ON".equals(statisticsIncremental) && index != null
                && index.predicate() != null && !index.predicate().isBlank()) {
            PhysicalSourceOptions.addSourceIssue(lines, "SQLSERVER",
                    "STATISTICS_INCREMENTAL=ON is not supported for filtered indexes; value replaced by a review placeholder.");
            statisticsIncremental = "<STATISTICS_INCREMENTAL>";
        }
        String allowRowLocks = PhysicalSourceOptions.enumClause(
                lines, index, table, "SQLSERVER", "ALLOW_ROW_LOCKS", "ON", "ALLOW_ROW_LOCKS", ON_OFF,
                "SQLSERVER_INDEX_ALLOW_ROW_LOCKS", "INDEX_ALLOW_ROW_LOCKS");
        String allowPageLocks = PhysicalSourceOptions.enumClause(
                lines, index, table, "SQLSERVER", "ALLOW_PAGE_LOCKS", "ON", "ALLOW_PAGE_LOCKS", ON_OFF,
                "SQLSERVER_INDEX_ALLOW_PAGE_LOCKS", "INDEX_ALLOW_PAGE_LOCKS");

        var xmlCompressionSource = PhysicalSourceOptions.find(index, table,
                "SQLSERVER_INDEX_XML_COMPRESSION", "INDEX_XML_COMPRESSION");
        String xmlCompression = xmlCompressionSource.isPresent()
                ? PhysicalSourceOptions.enumClause(
                        lines, index, table, "SQLSERVER", "XML_COMPRESSION", "<INDEX_XML_COMPRESSION>",
                        "INDEX_XML_COMPRESSION", ON_OFF,
                        "SQLSERVER_INDEX_XML_COMPRESSION", "INDEX_XML_COMPRESSION")
                : null;
        if (xmlCompressionSource.isPresent() && !hasXmlColumn(table)) {
            PhysicalSourceOptions.addSourceReview(lines, "SQLSERVER",
                    "Index XML_COMPRESSION is source/profile-driven but no canonical XML column is visible; verify index/table context before activation.");
        }

        // Organization is deliberately object-scoped: a table-level fallback could
        // incorrectly make several indexes CLUSTERED even though SQL Server allows only one.
        var organizationSource = PhysicalSourceOptions.find(index, null,
                "SQLSERVER_INDEX_ORGANIZATION", "INDEX_ORGANIZATION");
        organizationSource.ifPresent(raw -> {
            String normalized = raw.trim().toUpperCase();
            if (!Set.of("CLUSTERED", "NONCLUSTERED").contains(normalized)) {
                PhysicalSourceOptions.addSourceIssue(lines, "SQLSERVER",
                        "INDEX_ORGANIZATION=" + raw + " is invalid; expected CLUSTERED or NONCLUSTERED.");
            } else if (index != null
                    && (index.type() == com.behsazan.schemaforge.domain.enums.IndexType.CLUSTERED
                    || index.type() == com.behsazan.schemaforge.domain.enums.IndexType.NONCLUSTERED)) {
                String canonical = index.type().name();
                if (!canonical.equals(normalized)) {
                    PhysicalSourceOptions.addSourceIssue(lines, "SQLSERVER",
                            "INDEX_ORGANIZATION=" + normalized + " conflicts with canonical IndexType=" + canonical + "; canonical evidence wins.");
                }
            }
        });

        List<String> options = new ArrayList<>();
        options.add("PAD_INDEX = " + padIndex);
        options.add(fillfactor);
        options.add("IGNORE_DUP_KEY = " + ignoreDupKey);
        options.add("STATISTICS_NORECOMPUTE = " + statisticsNoRecompute);
        if (statisticsIncremental != null) options.add("STATISTICS_INCREMENTAL = " + statisticsIncremental);
        options.add("ALLOW_ROW_LOCKS = " + allowRowLocks);
        options.add("ALLOW_PAGE_LOCKS = " + allowPageLocks);
        options.add("DATA_COMPRESSION = " + compression);
        if (xmlCompression != null) options.add("XML_COMPRESSION = " + xmlCompression);

        var optimizeForSequentialKey = PhysicalSourceOptions.find(index, table,
                "SQLSERVER_INDEX_OPTIMIZE_FOR_SEQUENTIAL_KEY", "INDEX_OPTIMIZE_FOR_SEQUENTIAL_KEY");
        optimizeForSequentialKey.ifPresent(raw -> options.add("OPTIMIZE_FOR_SEQUENTIAL_KEY = "
                + PhysicalSourceOptions.enumClause(
                        lines, index, table, "SQLSERVER", "OPTIMIZE_FOR_SEQUENTIAL_KEY", "OFF",
                        "OPTIMIZE_FOR_SEQUENTIAL_KEY", ON_OFF,
                        "SQLSERVER_INDEX_OPTIMIZE_FOR_SEQUENTIAL_KEY",
                        "INDEX_OPTIMIZE_FOR_SEQUENTIAL_KEY")));

        lines.add("WITH (");
        for (int i = 0; i < options.size(); i++) {
            lines.add("    " + options.get(i) + (i < options.size() - 1 ? "," : ""));
        }
        lines.add(")");
        if (optimizeForSequentialKey.isEmpty()) {
            lines.add("-- OPTIMIZE_FOR_SEQUENTIAL_KEY is SQL Server 2019+ and workload-specific; source/profile only.");
        }
        if (statisticsIncrementalSource.isEmpty()) {
            lines.add("-- STATISTICS_INCREMENTAL defaults OFF; source/profile only because ON is meaningful only in supported partition/statistics contexts.");
        }
        if (xmlCompressionSource.isEmpty()) {
            lines.add("-- XML_COMPRESSION is SQL Server 2022+ and remains source/profile-only.");
        }
        if (organizationSource.isEmpty() && index != null
                && index.type() != com.behsazan.schemaforge.domain.enums.IndexType.CLUSTERED
                && index.type() != com.behsazan.schemaforge.domain.enums.IndexType.NONCLUSTERED) {
            lines.add("-- Index organization is unspecified; keep the SQL Server default unless source/profile evidence requires otherwise.");
        }
        if (!activePlacementPresent) {
            lines.add("ON [<INDEX_FILEGROUP>]");
        }
        return PhysicalCommentBlocks.block("SQL SERVER INDEX PHYSICAL OPTIONS", lines);
    }

    private static boolean hasXmlColumn(Table table) {
        return table != null && table.columns().stream()
                .anyMatch(column -> "XML".equalsIgnoreCase(column.dataType().name().normalized()));
    }
}
