package com.behsazan.schemaforge.physical.sqlserver;

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
        lines.add("WITH (DATA_COMPRESSION = " + compression + ")");
        lines.add("-- XML_COMPRESSION is version/type-specific and is not emitted generically in Phase 1.");
        return PhysicalCommentBlocks.block("SQL SERVER TABLE PHYSICAL OPTIONS", lines);
    }

    @Override
    public String indexOptions(Table table, List<Identifier> keyColumns, boolean activePlacementPresent) {
        return renderIndexOptions(table, keyColumns, activePlacementPresent, false);
    }

    @Override
    public String indexOptions(
            Table table, List<Identifier> keyColumns, boolean activePlacementPresent, boolean uniqueIndex) {
        return renderIndexOptions(table, keyColumns, activePlacementPresent, uniqueIndex);
    }

    @Override
    public String constraintIndexOptions(Table table, List<Identifier> keyColumns, boolean activePlacementPresent) {
        return renderIndexOptions(table, keyColumns, activePlacementPresent, true);
    }

    private String renderIndexOptions(
            Table table, List<Identifier> keyColumns, boolean activePlacementPresent, boolean knownUniqueIndex) {
        List<String> lines = new ArrayList<>();
        String fillfactor = PhysicalSourceOptions.integerClause(
                lines, table, "SQLSERVER", "FILLFACTOR =", 0, 0, 100, "INDEX_FILLFACTOR",
                "SQLSERVER_INDEX_FILLFACTOR", "INDEX_FILLFACTOR");
        String padIndex = PhysicalSourceOptions.enumClause(
                lines, table, "SQLSERVER", "PAD_INDEX", "OFF", "PAD_INDEX", ON_OFF,
                "SQLSERVER_INDEX_PAD_INDEX", "INDEX_PAD_INDEX");
        String compression = PhysicalSourceOptions.enumClause(
                lines, table, "SQLSERVER", "DATA_COMPRESSION", "NONE",
                "INDEX_DATA_COMPRESSION", COMPRESSION,
                "SQLSERVER_INDEX_DATA_COMPRESSION", "INDEX_DATA_COMPRESSION");

        String ignoreDupKey = PhysicalSourceOptions.enumClause(
                lines, table, "SQLSERVER", "IGNORE_DUP_KEY", "OFF", "IGNORE_DUP_KEY", ON_OFF,
                "SQLSERVER_INDEX_IGNORE_DUP_KEY", "INDEX_IGNORE_DUP_KEY");
        if ("ON".equals(ignoreDupKey) && !knownUniqueIndex) {
            PhysicalSourceOptions.addSourceReview(lines, "SQLSERVER", "IGNORE_DUP_KEY=ON is valid only for a UNIQUE index; verify uniqueness before activation.");
        }
        String statisticsNoRecompute = PhysicalSourceOptions.enumClause(
                lines, table, "SQLSERVER", "STATISTICS_NORECOMPUTE", "OFF", "STATISTICS_NORECOMPUTE", ON_OFF,
                "SQLSERVER_INDEX_STATISTICS_NORECOMPUTE", "INDEX_STATISTICS_NORECOMPUTE");
        String allowRowLocks = PhysicalSourceOptions.enumClause(
                lines, table, "SQLSERVER", "ALLOW_ROW_LOCKS", "ON", "ALLOW_ROW_LOCKS", ON_OFF,
                "SQLSERVER_INDEX_ALLOW_ROW_LOCKS", "INDEX_ALLOW_ROW_LOCKS");
        String allowPageLocks = PhysicalSourceOptions.enumClause(
                lines, table, "SQLSERVER", "ALLOW_PAGE_LOCKS", "ON", "ALLOW_PAGE_LOCKS", ON_OFF,
                "SQLSERVER_INDEX_ALLOW_PAGE_LOCKS", "INDEX_ALLOW_PAGE_LOCKS");

        List<String> options = new ArrayList<>();
        options.add("PAD_INDEX = " + padIndex);
        options.add(fillfactor);
        options.add("IGNORE_DUP_KEY = " + ignoreDupKey);
        options.add("STATISTICS_NORECOMPUTE = " + statisticsNoRecompute);
        options.add("ALLOW_ROW_LOCKS = " + allowRowLocks);
        options.add("ALLOW_PAGE_LOCKS = " + allowPageLocks);
        options.add("DATA_COMPRESSION = " + compression);

        var optimizeForSequentialKey = PhysicalSourceOptions.find(table,
                "SQLSERVER_INDEX_OPTIMIZE_FOR_SEQUENTIAL_KEY", "INDEX_OPTIMIZE_FOR_SEQUENTIAL_KEY");
        optimizeForSequentialKey.ifPresent(raw -> options.add("OPTIMIZE_FOR_SEQUENTIAL_KEY = "
                + PhysicalSourceOptions.enumClause(
                        lines, table, "SQLSERVER", "OPTIMIZE_FOR_SEQUENTIAL_KEY", "OFF",
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
        if (!activePlacementPresent) {
            lines.add("ON [<INDEX_FILEGROUP>]");
        }
        return PhysicalCommentBlocks.block("SQL SERVER INDEX PHYSICAL OPTIONS", lines);
    }
}
