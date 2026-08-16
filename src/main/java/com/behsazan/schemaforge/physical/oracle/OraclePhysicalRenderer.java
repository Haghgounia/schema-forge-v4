package com.behsazan.schemaforge.physical.oracle;

import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.physical.PhysicalCommentBlocks;
import com.behsazan.schemaforge.physical.PhysicalCommentRenderer;

import java.util.ArrayList;
import java.util.List;

/** Oracle Phase-1 physical defaults/candidates. Existing TS_/ITS_ placement remains active. */
public final class OraclePhysicalRenderer implements PhysicalCommentRenderer {
    @Override
    public String tableOptions(Table table, boolean activePlacementPresent) {
        List<String> lines = new ArrayList<>();
        lines.add("PCTFREE 10");
        lines.add("INITRANS 1");
        lines.add("-- Compression default: NOCOMPRESS (no clause emitted).");
        if (!activePlacementPresent) {
            lines.add("TABLESPACE <TABLE_TABLESPACE>");
        }
        return PhysicalCommentBlocks.block("ORACLE TABLE PHYSICAL OPTIONS", lines);
    }

    @Override
    public String indexOptions(Table table, List<Identifier> keyColumns, boolean activePlacementPresent) {
        List<String> lines = new ArrayList<>();
        lines.add("PCTFREE 10");
        lines.add("INITRANS 2");
        lines.add("-- Compression default: NOCOMPRESS (no clause emitted).");
        if (!activePlacementPresent) {
            lines.add("TABLESPACE <INDEX_TABLESPACE>");
        }
        return PhysicalCommentBlocks.block("ORACLE INDEX PHYSICAL OPTIONS", lines);
    }
}
