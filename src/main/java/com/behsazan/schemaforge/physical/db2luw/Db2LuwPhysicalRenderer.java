package com.behsazan.schemaforge.physical.db2luw;

import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.physical.PhysicalCommentBlocks;
import com.behsazan.schemaforge.physical.PhysicalCommentRenderer;
import com.behsazan.schemaforge.physical.PhysicalSourceOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Db2 LUW physical table/index candidates backed by documented persistent catalog state. */
public final class Db2LuwPhysicalRenderer implements PhysicalCommentRenderer {
    private static final Set<String> ON_OFF = Set.of("ON", "OFF");
    private static final Set<String> YES_NO = Set.of("YES", "NO");
    private static final Set<String> TABLE_ORGANIZATION = Set.of("ROW", "COLUMN");
    private static final Set<String> ROW_COMPRESSION = Set.of("NO", "ADAPTIVE", "STATIC");
    private static final Set<String> REVERSE_SCANS = Set.of("ALLOW", "DISALLOW");
    private static final Set<String> PAGE_SPLIT = Set.of("HIGH", "LOW", "SYMMETRIC");

    @Override
    public String tableOptions(Table table, boolean activePlacementPresent) {
        List<String> lines = new ArrayList<>();

        lines.add(PhysicalSourceOptions.integerClause(
                lines, table, "DB2/LUW", "PCTFREE", 0, 0, 99, "TABLE_PCTFREE",
                "DB2_LUW_TABLE_PCTFREE", "TABLE_PCTFREE", "PCTFREE"));

        PhysicalSourceOptions.find(table, "DB2_LUW_APPEND", "TABLE_APPEND", "APPEND")
                .ifPresentOrElse(raw -> {
                    String value = PhysicalSourceOptions.normalizedUpper(raw);
                    if (ON_OFF.contains(value)) {
                        PhysicalSourceOptions.addSourceRetained(lines, "DB2_LUW_APPEND", raw);
                        lines.add("APPEND " + value);
                    } else {
                        PhysicalSourceOptions.addSourceIssue(lines, "DB2/LUW",
                                "DB2_LUW_APPEND=" + raw + " must be ON or OFF; source value was not normalized.");
                        lines.add("APPEND <ON|OFF>");
                    }
                }, () -> lines.add("-- APPEND is workload-specific; no value was inferred."));

        PhysicalSourceOptions.find(table, "DB2_LUW_VOLATILE", "TABLE_VOLATILE", "VOLATILE")
                .ifPresentOrElse(raw -> {
                    String value = PhysicalSourceOptions.normalizedUpper(raw);
                    if (YES_NO.contains(value)) {
                        PhysicalSourceOptions.addSourceRetained(lines, "DB2_LUW_VOLATILE", raw);
                        lines.add("YES".equals(value) ? "VOLATILE CARDINALITY" : "NOT VOLATILE CARDINALITY");
                    } else {
                        PhysicalSourceOptions.addSourceIssue(lines, "DB2/LUW",
                                "DB2_LUW_VOLATILE=" + raw + " must be YES or NO; source value was not normalized.");
                        lines.add("<VOLATILE|NOT VOLATILE> CARDINALITY");
                    }
                }, () -> lines.add("-- VOLATILE CARDINALITY is optimizer policy; source/profile only."));

        PhysicalSourceOptions.find(table, "DB2_LUW_TABLE_ORGANIZATION", "TABLE_ORGANIZATION")
                .ifPresentOrElse(raw -> {
                    String value = PhysicalSourceOptions.normalizedUpper(raw);
                    if (TABLE_ORGANIZATION.contains(value)) {
                        PhysicalSourceOptions.addSourceRetained(lines, "DB2_LUW_TABLE_ORGANIZATION", raw);
                        lines.add("ORGANIZE BY " + value);
                    } else {
                        PhysicalSourceOptions.addSourceIssue(lines, "DB2/LUW",
                                "DB2_LUW_TABLE_ORGANIZATION=" + raw + " must be ROW or COLUMN; source value was not normalized.");
                        lines.add("ORGANIZE BY <ROW|COLUMN>");
                    }
                }, () -> lines.add("-- Table organization is not guessed; ordinary LUW tables are commonly row-organized."));

        PhysicalSourceOptions.find(table, "DB2_LUW_VALUE_COMPRESSION", "TABLE_VALUE_COMPRESSION")
                .ifPresent(raw -> {
                    String value = PhysicalSourceOptions.normalizedUpper(raw);
                    if (YES_NO.contains(value)) {
                        PhysicalSourceOptions.addSourceRetained(lines, "DB2_LUW_VALUE_COMPRESSION", raw);
                        if ("YES".equals(value)) lines.add("VALUE COMPRESSION");
                        else lines.add("-- VALUE COMPRESSION disabled in source/profile.");
                    } else {
                        PhysicalSourceOptions.addSourceIssue(lines, "DB2/LUW",
                                "DB2_LUW_VALUE_COMPRESSION=" + raw + " must be YES or NO; source value was not normalized.");
                    }
                });

        PhysicalSourceOptions.find(table, "DB2_LUW_ROW_COMPRESSION", "TABLE_ROW_COMPRESSION")
                .ifPresentOrElse(raw -> {
                    String value = PhysicalSourceOptions.normalizedUpper(raw);
                    if (ROW_COMPRESSION.contains(value)) {
                        PhysicalSourceOptions.addSourceRetained(lines, "DB2_LUW_ROW_COMPRESSION", raw);
                        lines.add(switch (value) {
                            case "ADAPTIVE" -> "COMPRESS YES ADAPTIVE";
                            case "STATIC" -> "COMPRESS YES STATIC";
                            default -> "COMPRESS NO";
                        });
                    } else {
                        PhysicalSourceOptions.addSourceIssue(lines, "DB2/LUW",
                                "DB2_LUW_ROW_COMPRESSION=" + raw
                                        + " must be NO, ADAPTIVE or STATIC; source value was not normalized.");
                        lines.add("COMPRESS <NO|YES ADAPTIVE|YES STATIC>");
                    }
                }, () -> lines.add("-- Row compression is license/workload dependent; source/profile only."));

        PhysicalSourceOptions.find(table, "DB2_LUW_INDEX_TABLESPACE", "TABLE_INDEX_TABLESPACE")
                .ifPresentOrElse(raw -> {
                    PhysicalSourceOptions.addSourceRetained(lines, "DB2_LUW_INDEX_TABLESPACE", raw);
                    lines.add("INDEX IN " + raw);
                }, () -> lines.add("-- INDEX IN omitted: index placement follows the table/database defaults."));

        PhysicalSourceOptions.find(table, "DB2_LUW_LONG_TABLESPACE", "TABLE_LONG_TABLESPACE")
                .ifPresentOrElse(raw -> {
                    PhysicalSourceOptions.addSourceRetained(lines, "DB2_LUW_LONG_TABLESPACE", raw);
                    lines.add("LONG IN " + raw);
                }, () -> lines.add("-- LONG IN omitted: LOB/XML placement follows the table/database defaults."));

        if (!activePlacementPresent) {
            lines.add("IN <TABLE_TABLESPACE>");
        }
        return PhysicalCommentBlocks.block("DB2 LUW TABLE PHYSICAL OPTIONS", lines);
    }

    @Override
    public String indexOptions(Table table, List<Identifier> keyColumns, boolean activePlacementPresent) {
        return renderIndexOptions(table, null, activePlacementPresent);
    }

    @Override
    public String indexOptions(Table table, Index index, List<Identifier> keyColumns, boolean activePlacementPresent) {
        return renderIndexOptions(table, index, activePlacementPresent);
    }

    @Override
    public String indexOptions(Table table, Index index, List<Identifier> keyColumns,
                               boolean activePlacementPresent, boolean uniqueIndex) {
        return renderIndexOptions(table, index, activePlacementPresent);
    }

    @Override
    public String constraintIndexOptions(Table table, Index index, List<Identifier> keyColumns,
                                         boolean activePlacementPresent) {
        return renderIndexOptions(table, index, activePlacementPresent);
    }

    private String renderIndexOptions(Table table, Index index, boolean activePlacementPresent) {
        List<String> lines = new ArrayList<>();
        lines.add(PhysicalSourceOptions.integerClause(
                lines, index, table, "DB2/LUW", "PCTFREE", 10, 0, 99, "INDEX_PCTFREE",
                "DB2_LUW_INDEX_PCTFREE", "INDEX_PCTFREE"));
        lines.add(PhysicalSourceOptions.integerClause(
                lines, index, table, "DB2/LUW", "MINPCTUSED", 0, 0, 99, "INDEX_MINPCTUSED",
                "DB2_LUW_INDEX_MINPCTUSED", "INDEX_MINPCTUSED"));

        PhysicalSourceOptions.find(index, table, "DB2_LUW_INDEX_REVERSE_SCANS", "INDEX_REVERSE_SCANS")
                .ifPresentOrElse(raw -> {
                    String value = PhysicalSourceOptions.normalizedUpper(raw);
                    if (REVERSE_SCANS.contains(value)) {
                        PhysicalSourceOptions.addSourceRetained(lines, "DB2_LUW_INDEX_REVERSE_SCANS", raw);
                        lines.add(value + " REVERSE SCANS");
                    } else {
                        PhysicalSourceOptions.addSourceIssue(lines, "DB2/LUW",
                                "DB2_LUW_INDEX_REVERSE_SCANS=" + raw
                                        + " must be ALLOW or DISALLOW; source value was not normalized.");
                        lines.add("<ALLOW|DISALLOW> REVERSE SCANS");
                    }
                }, () -> lines.add("-- Reverse-scan capability is source/profile controlled."));

        PhysicalSourceOptions.find(index, table, "DB2_LUW_INDEX_COMPRESSION", "INDEX_COMPRESSION")
                .ifPresentOrElse(raw -> {
                    String value = PhysicalSourceOptions.normalizedUpper(raw);
                    if (YES_NO.contains(value)) {
                        PhysicalSourceOptions.addSourceRetained(lines, "DB2_LUW_INDEX_COMPRESSION", raw);
                        lines.add("COMPRESS " + value);
                    } else {
                        PhysicalSourceOptions.addSourceIssue(lines, "DB2/LUW",
                                "DB2_LUW_INDEX_COMPRESSION=" + raw
                                        + " must be YES or NO; source value was not normalized.");
                        lines.add("COMPRESS <YES|NO>");
                    }
                }, () -> lines.add("-- Index compression default can inherit from table compression; no value was invented."));

        PhysicalSourceOptions.find(index, table, "DB2_LUW_INDEX_PAGE_SPLIT", "INDEX_PAGE_SPLIT")
                .ifPresentOrElse(raw -> {
                    String value = PhysicalSourceOptions.normalizedUpper(raw);
                    if (PAGE_SPLIT.contains(value)) {
                        PhysicalSourceOptions.addSourceRetained(lines, "DB2_LUW_INDEX_PAGE_SPLIT", raw);
                        lines.add("PAGE SPLIT " + value);
                    } else {
                        PhysicalSourceOptions.addSourceIssue(lines, "DB2/LUW",
                                "DB2_LUW_INDEX_PAGE_SPLIT=" + raw
                                        + " must be HIGH, LOW or SYMMETRIC; source value was not normalized.");
                        lines.add("PAGE SPLIT <HIGH|LOW|SYMMETRIC>");
                    }
                }, () -> lines.add("PAGE SPLIT SYMMETRIC"));

        if (!activePlacementPresent) {
            lines.add("IN <INDEX_TABLESPACE>");
        }
        return PhysicalCommentBlocks.block("DB2 LUW INDEX PHYSICAL OPTIONS", lines);
    }
}
