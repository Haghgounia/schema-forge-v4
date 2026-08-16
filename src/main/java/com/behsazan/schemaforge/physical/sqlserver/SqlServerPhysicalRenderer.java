package com.behsazan.schemaforge.physical.sqlserver;

import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.physical.PhysicalCommentBlocks;
import com.behsazan.schemaforge.physical.PhysicalCommentRenderer;

import java.util.ArrayList;
import java.util.List;

/** SQL Server Phase-1 physical defaults/candidates. */
public final class SqlServerPhysicalRenderer implements PhysicalCommentRenderer {
    @Override
    public String tableOptions(Table table, boolean activePlacementPresent) {
        List<String> lines = new ArrayList<>();
        if (!activePlacementPresent) {
            lines.add("ON [<TABLE_FILEGROUP>]");
        }
        lines.add("WITH (DATA_COMPRESSION = NONE)");
        return PhysicalCommentBlocks.block("SQL SERVER TABLE PHYSICAL OPTIONS", lines);
    }

    @Override
    public String indexOptions(Table table, List<Identifier> keyColumns, boolean activePlacementPresent) {
        List<String> lines = new ArrayList<>();
        lines.add("WITH (");
        lines.add("    PAD_INDEX = OFF,");
        lines.add("    FILLFACTOR = 0,");
        lines.add("    IGNORE_DUP_KEY = OFF,");
        lines.add("    STATISTICS_NORECOMPUTE = OFF,");
        lines.add("    ALLOW_ROW_LOCKS = ON,");
        lines.add("    ALLOW_PAGE_LOCKS = ON,");
        lines.add("    DATA_COMPRESSION = NONE");
        lines.add(")");
        if (!activePlacementPresent) {
            lines.add("ON [<INDEX_FILEGROUP>]");
        }
        return PhysicalCommentBlocks.block("SQL SERVER INDEX PHYSICAL OPTIONS", lines);
    }
}
