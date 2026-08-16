package com.behsazan.schemaforge.physical.db2zos;

import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.physical.PhysicalCommentBlocks;
import com.behsazan.schemaforge.physical.PhysicalCommentRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Db2 for z/OS Phase-1 physical/table options based on existing-bank DDL style and vendor defaults. */
public final class Db2ZosPhysicalRenderer implements PhysicalCommentRenderer {
    @Override
    public String tableOptions(Table table, boolean activePlacementPresent) {
        List<String> lines = new ArrayList<>();
        if (!activePlacementPresent) {
            lines.add("IN <DATABASE>.<TABLESPACE>");
        }
        lines.add("AUDIT NONE");
        lines.add("DATA CAPTURE NONE");
        lines.add("WITH RESTRICT ON DROP");
        lines.add("CCSID UNICODE");
        lines.add("NOT VOLATILE");
        lines.add("APPEND NO");
        return PhysicalCommentBlocks.block("DB2/ZOS TABLE OPTIONS", lines);
    }

    @Override
    public String indexOptions(Table table, List<Identifier> keyColumns, boolean activePlacementPresent) {
        List<String> lines = new ArrayList<>();
        if (containsVaryingLengthCharacterKey(table, keyColumns)) {
            lines.add("-- Varying-length key detected - choose according to subsystem/DBA policy.");
            lines.add("<PADDED_OR_NOT_PADDED>");
        }
        lines.add("USING STOGROUP <STOGROUP>");
        lines.add("    PRIQTY <PRIQTY>");
        lines.add("    SECQTY <SECQTY>");
        lines.add("    ERASE NO");
        lines.add("FREEPAGE 0");
        lines.add("PCTFREE 10");
        lines.add("GBPCACHE CHANGED");
        lines.add("COMPRESS NO");
        lines.add("BUFFERPOOL <BUFFERPOOL>");
        lines.add("CLOSE YES");
        return PhysicalCommentBlocks.block("DB2/ZOS INDEX PHYSICAL OPTIONS", lines);
    }

    private boolean containsVaryingLengthCharacterKey(Table table, List<Identifier> keyColumns) {
        for (Identifier key : keyColumns) {
            Column column = table.findColumn(key.value()).orElse(null);
            if (column == null) {
                continue;
            }
            String type = column.dataType().name().normalized().toUpperCase(Locale.ROOT);
            if (type.equals("VARCHAR") || type.equals("VARCHAR2")
                    || type.equals("NVARCHAR") || type.equals("NVARCHAR2")
                    || type.equals("VARGRAPHIC")) {
                return true;
            }
        }
        return false;
    }
}
