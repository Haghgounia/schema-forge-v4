package com.behsazan.schemaforge.physical.db2zos;

import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.physical.PhysicalCommentBlocks;
import com.behsazan.schemaforge.physical.PhysicalCommentRenderer;
import com.behsazan.schemaforge.physical.PhysicalSourceOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Db2 for z/OS Phase-1 physical/table options based on vendor defaults and review placeholders. */
public final class Db2ZosPhysicalRenderer implements PhysicalCommentRenderer {
    private static final Set<String> YES_NO = Set.of("YES", "NO");
    private static final Set<String> GBPCACHE = Set.of("CHANGED", "ALL", "NONE");
    private static final Set<String> PADDED = Set.of("PADDED", "NOT PADDED");
    private static final Pattern PIECESIZE = Pattern.compile("(\\d+)\\s*([KMG])", Pattern.CASE_INSENSITIVE);

    @Override
    public String tableOptions(Table table, boolean activePlacementPresent) {
        List<String> lines = new ArrayList<>();
        if (!activePlacementPresent) {
            lines.add("IN <DATABASE>.<TABLESPACE>");
        }
        lines.add("-- Table-space FREEPAGE/PCTFREE/COMPRESS/BUFFERPOOL/DSSIZE are CREATE TABLESPACE concerns and are not invented by table DDL.");
        lines.add("-- AUDIT, DATA CAPTURE, CCSID, VOLATILE, APPEND and RESTRICT ON DROP are non-storage table semantics; they are intentionally excluded from the Physical Phase-1 block.");
        return PhysicalCommentBlocks.block("DB2/ZOS TABLE PHYSICAL OPTIONS", lines);
    }

    @Override
    public String indexOptions(Table table, List<Identifier> keyColumns, boolean activePlacementPresent) {
        List<String> lines = new ArrayList<>();
        boolean varyingKey = containsVaryingLengthCharacterKey(table, keyColumns);
        if (varyingKey) {
            String padding = PhysicalSourceOptions.enumClause(
                    lines, table, "DB2/ZOS", "PADDED", "<PADDED_OR_NOT_PADDED>",
                    "PADDED_OR_NOT_PADDED", PADDED,
                    "DB2_INDEX_PADDING", "INDEX_PADDING");
            if (padding.startsWith("<")) {
                lines.add("-- Varying-length key detected - choose according to PADIX/subsystem and DBA policy.");
                lines.add("<PADDED_OR_NOT_PADDED>");
            } else {
                lines.add(padding);
            }
        } else {
            PhysicalSourceOptions.find(table, "DB2_INDEX_PADDING", "INDEX_PADDING")
                    .ifPresent(raw -> lines.add("-- [SOURCE PHYSICAL ISSUE][DB2/ZOS] INDEX_PADDING="
                            + raw + " is irrelevant because this index key has no varying-length string column; "
                            + "Db2 ignores PADDED/NOT PADDED in that case, so it was not emitted."));
        }

        lines.add(PhysicalSourceOptions.sourceOrPlaceholder(
                lines, table, "USING STOGROUP ", "STOGROUP",
                "DB2_INDEX_STOGROUP", "INDEX_STOGROUP"));
        lines.add("    " + PhysicalSourceOptions.sourceIntegerOrPlaceholder(
                lines, table, "DB2/ZOS", "PRIQTY ", "PRIQTY",
                value -> value == -1 || value > 0, "a positive integer or -1",
                "DB2_INDEX_PRIQTY", "INDEX_PRIQTY"));
        lines.add("    " + PhysicalSourceOptions.sourceIntegerOrPlaceholder(
                lines, table, "DB2/ZOS", "SECQTY ", "SECQTY",
                value -> value >= -1, "a positive integer, 0, or -1",
                "DB2_INDEX_SECQTY", "INDEX_SECQTY"));
        lines.add("    ERASE " + PhysicalSourceOptions.enumClause(
                lines, table, "DB2/ZOS", "ERASE", "NO", "ERASE", YES_NO,
                "DB2_INDEX_ERASE", "INDEX_ERASE"));
        lines.add(PhysicalSourceOptions.integerClause(
                lines, table, "DB2/ZOS", "FREEPAGE", 0, 0, 255, "FREEPAGE",
                "DB2_INDEX_FREEPAGE", "INDEX_FREEPAGE"));
        lines.add(PhysicalSourceOptions.integerClause(
                lines, table, "DB2/ZOS", "PCTFREE", 10, 0, 99, "PCTFREE",
                "DB2_INDEX_PCTFREE", "INDEX_PCTFREE"));
        lines.add("GBPCACHE " + PhysicalSourceOptions.enumClause(
                lines, table, "DB2/ZOS", "GBPCACHE", "CHANGED", "GBPCACHE", GBPCACHE,
                "DB2_INDEX_GBPCACHE", "INDEX_GBPCACHE"));
        lines.add("COMPRESS " + PhysicalSourceOptions.enumClause(
                lines, table, "DB2/ZOS", "COMPRESS", "NO", "COMPRESS", YES_NO,
                "DB2_INDEX_COMPRESS", "INDEX_COMPRESS"));
        lines.add(PhysicalSourceOptions.sourceOrPlaceholder(
                lines, table, "BUFFERPOOL ", "BUFFERPOOL",
                "DB2_INDEX_BUFFERPOOL", "INDEX_BUFFERPOOL"));
        lines.add("CLOSE " + PhysicalSourceOptions.enumClause(
                lines, table, "DB2/ZOS", "CLOSE", "YES", "CLOSE", YES_NO,
                "DB2_INDEX_CLOSE", "INDEX_CLOSE"));
        String pieceSize = pieceSize(lines, table);
        if (pieceSize != null) {
            lines.add(pieceSize);
        } else {
            lines.add("-- PIECESIZE is data-set/table-space capacity specific; source/profile only.");
        }
        lines.add("-- DEFINE/DEFER are deployment choices; COPY is recovery policy; CLUSTER is data-organization design. They are not auto-selected in Phase 1.");
        return PhysicalCommentBlocks.block("DB2/ZOS INDEX PHYSICAL OPTIONS", lines);
    }

    private String pieceSize(List<String> lines, Table table) {
        var source = PhysicalSourceOptions.find(table, "DB2_INDEX_PIECESIZE", "INDEX_PIECESIZE");
        if (source.isEmpty()) {
            return null;
        }

        String raw = source.get();
        Matcher matcher = PIECESIZE.matcher(raw.trim());
        if (!matcher.matches()) {
            lines.add("-- [SOURCE PHYSICAL ISSUE][DB2/ZOS] INDEX_PIECESIZE=" + raw
                    + " must be a power-of-two value followed by K, M or G; source value was not normalized.");
            return "PIECESIZE <PIECESIZE>";
        }

        long value;
        try {
            value = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            lines.add("-- [SOURCE PHYSICAL ISSUE][DB2/ZOS] INDEX_PIECESIZE=" + raw
                    + " is outside the supported numeric range; source value was not normalized.");
            return "PIECESIZE <PIECESIZE>";
        }
        String unit = matcher.group(2).toUpperCase(Locale.ROOT);
        boolean powerOfTwo = value > 0 && (value & (value - 1)) == 0;
        boolean inRange = switch (unit) {
            case "K" -> value >= 256 && value <= 268_435_456L;
            case "M" -> value >= 1 && value <= 262_144L;
            case "G" -> value >= 1 && value <= 256L;
            default -> false;
        };
        if (!powerOfTwo || !inRange) {
            lines.add("-- [SOURCE PHYSICAL ISSUE][DB2/ZOS] INDEX_PIECESIZE=" + raw
                    + " is not a supported Db2 PIECESIZE value; source value was not normalized.");
            return "PIECESIZE <PIECESIZE>";
        }

        lines.add("-- [SOURCE PHYSICAL] DB2_INDEX_PIECESIZE=" + raw + " retained for DBA review.");
        lines.add("-- [SOURCE PHYSICAL REVIEW][DB2/ZOS] PIECESIZE applicability/default depends on table-space size and index organization; offline context was not assumed.");
        return "PIECESIZE " + value + " " + unit;
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
