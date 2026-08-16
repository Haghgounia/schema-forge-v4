package com.behsazan.schemaforge.physical.postgresql;

import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.physical.PhysicalCommentBlocks;
import com.behsazan.schemaforge.physical.PhysicalCommentRenderer;

import java.util.ArrayList;
import java.util.List;

/** PostgreSQL Phase-1 physical defaults/candidates. TOAST/column compression stays outside Phase 1. */
public final class PostgreSqlPhysicalRenderer implements PhysicalCommentRenderer {
    @Override
    public String tableOptions(Table table, boolean activePlacementPresent) {
        List<String> lines = new ArrayList<>();
        lines.add("WITH (fillfactor = 100)");
        if (!activePlacementPresent) {
            lines.add("TABLESPACE <TABLE_TABLESPACE>");
        }
        lines.add("-- Compression remains DB/default per-column TOAST behavior in Phase 1.");
        return PhysicalCommentBlocks.block("POSTGRESQL TABLE PHYSICAL OPTIONS", lines);
    }

    @Override
    public String indexOptions(Table table, List<Identifier> keyColumns, boolean activePlacementPresent) {
        List<String> lines = new ArrayList<>();
        lines.add("WITH (fillfactor = 90)");
        if (!activePlacementPresent) {
            lines.add("TABLESPACE <INDEX_TABLESPACE>");
        }
        return PhysicalCommentBlocks.block("POSTGRESQL INDEX PHYSICAL OPTIONS", lines);
    }
}
