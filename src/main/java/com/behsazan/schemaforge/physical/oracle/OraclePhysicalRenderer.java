package com.behsazan.schemaforge.physical.oracle;

import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.physical.PhysicalCommentBlocks;
import com.behsazan.schemaforge.physical.PhysicalCommentRenderer;
import com.behsazan.schemaforge.physical.PhysicalSourceOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Oracle Phase-1 physical defaults/candidates. Existing TS_/ITS_ placement remains active. */
public final class OraclePhysicalRenderer implements PhysicalCommentRenderer {
    private static final Pattern PREFIX_COMPRESSION = Pattern.compile("COMPRESS\\s+(\\d+)");
    private static final Pattern HCC_COMPRESSION = Pattern.compile(
            "(?:COLUMN STORE )?COMPRESS FOR (?:QUERY|ARCHIVE)(?: (?:LOW|HIGH))?"
                    + "|COLUMN STORE COMPRESS");
    @Override
    public String tableOptions(Table table, boolean activePlacementPresent) {
        List<String> lines = new ArrayList<>();

        CompressionDecision compression = tableCompression(lines, table);

        var pctfreeSource = PhysicalSourceOptions.find(table, "ORACLE_PCTFREE", "TABLE_PCTFREE", "PCTFREE");
        var sourcePctfree = PhysicalSourceOptions.findIntegerInRange(
                table, 0, 99, "ORACLE_PCTFREE", "TABLE_PCTFREE", "PCTFREE");
        var sourcePctused = PhysicalSourceOptions.findIntegerInRange(
                table, 0, 99, "ORACLE_PCTUSED", "TABLE_PCTUSED", "PCTUSED");
        boolean sourcePairConflict = sourcePctfree.isPresent() && sourcePctused.isPresent()
                && sourcePctfree.get() + sourcePctused.get() > 100;
        boolean defaultPctfreeConflict = pctfreeSource.isEmpty() && sourcePctused.isPresent()
                && compression.pctfreeDefaultKnown()
                && compression.pctfreeDefault() + sourcePctused.get() > 100;

        if (sourcePairConflict) {
            PhysicalSourceOptions.addSourceIssue(lines, "ORACLE", "PCTFREE=" + sourcePctfree.get()
                    + " and PCTUSED=" + sourcePctused.get()
                    + " exceed Oracle's combined maximum of 100; neither source value was normalized.");
            lines.add("PCTFREE <PCTFREE>");
        } else if (pctfreeSource.isPresent()) {
            lines.add(PhysicalSourceOptions.integerClause(
                    lines, table, "ORACLE", "PCTFREE", compression.pctfreeDefault(), 0, 99, "PCTFREE",
                    "ORACLE_PCTFREE", "TABLE_PCTFREE", "PCTFREE"));
        } else if (defaultPctfreeConflict) {
            PhysicalSourceOptions.addSourceIssue(lines, "ORACLE", "Source PCTUSED=" + sourcePctused.get()
                    + " conflicts with the documented PCTFREE default " + compression.pctfreeDefault()
                    + "; choose PCTFREE explicitly for MSSM rather than silently changing the source value.");
            lines.add("PCTFREE <PCTFREE>");
        } else if (compression.pctfreeDefaultKnown()) {
            lines.add("PCTFREE " + compression.pctfreeDefault());
        } else {
            PhysicalSourceOptions.addSourceReview(lines, "ORACLE", "Table compression is unresolved, so a PCTFREE default was not inferred.");
            lines.add("PCTFREE <PCTFREE>");
        }

        lines.add(PhysicalSourceOptions.integerClause(
                lines, table, "ORACLE", "INITRANS", 1, 1, 255, "INITRANS",
                "ORACLE_INITRANS", "TABLE_INITRANS", "INITRANS"));

        PhysicalSourceOptions.find(table, "ORACLE_PCTUSED", "TABLE_PCTUSED", "PCTUSED")
                .ifPresentOrElse(raw -> {
                            PhysicalSourceOptions.addSourceReview(lines, "ORACLE", "PCTUSED applies to manual segment space management; ASSM ignores it.");
                            if (sourcePairConflict) {
                                lines.add("PCTUSED <PCTUSED>");
                            } else {
                                lines.add(PhysicalSourceOptions.integerClause(
                                        lines, table, "ORACLE", "PCTUSED", 40, 0, 99, "PCTUSED",
                                        "ORACLE_PCTUSED", "TABLE_PCTUSED", "PCTUSED"));
                            }
                        },
                        () -> lines.add("-- PCTUSED intentionally omitted: with ASSM it is ignored; review only for MSSM tablespaces."));

        lines.add(compression.clause());
        lines.add(tableLogging(lines, table));
        lines.add(tableParallel(lines, table));
        lines.add(segmentCreation(lines, table));
        if (!activePlacementPresent) {
            lines.add("TABLESPACE <TABLE_TABLESPACE>");
        }
        return PhysicalCommentBlocks.block("ORACLE TABLE PHYSICAL OPTIONS", lines);
    }

    private CompressionDecision tableCompression(List<String> lines, Table table) {
        var source = PhysicalSourceOptions.find(table, "ORACLE_TABLE_COMPRESSION", "TABLE_COMPRESSION");
        if (source.isEmpty()) {
            return new CompressionDecision("NOCOMPRESS", 10, true);
        }

        String raw = source.get();
        String value = PhysicalSourceOptions.normalizedUpper(raw);
        if (value.equals("NOCOMPRESS")) {
            PhysicalSourceOptions.addSourceRetained(lines, "ORACLE_TABLE_COMPRESSION", raw);
            return new CompressionDecision(value, 10, true);
        }
        if (value.equals("COMPRESS") || value.equals("COMPRESS BASIC")
                || value.equals("ROW STORE COMPRESS") || value.equals("ROW STORE COMPRESS BASIC")) {
            PhysicalSourceOptions.addSourceRetained(lines, "ORACLE_TABLE_COMPRESSION", raw);
            return new CompressionDecision(value, 0, true);
        }
        if (value.equals("ROW STORE COMPRESS ADVANCED") || value.equals("COMPRESS FOR OLTP")) {
            PhysicalSourceOptions.addSourceRetained(lines, "ORACLE_TABLE_COMPRESSION", raw);
            if (value.equals("COMPRESS FOR OLTP")) {
                PhysicalSourceOptions.addSourceReview(lines, "ORACLE", "COMPRESS FOR OLTP is legacy-compatible syntax; source was preserved rather than normalized.");
            }
            return new CompressionDecision(value, 10, true);
        }
        if (HCC_COMPRESSION.matcher(value).matches()) {
            PhysicalSourceOptions.addSourceRetained(lines, "ORACLE_TABLE_COMPRESSION", raw);
            PhysicalSourceOptions.addSourceReview(lines, "ORACLE", "Hybrid Columnar Compression requires compatible Oracle storage; capability was not verified offline.");
            return new CompressionDecision(value, 0, true);
        }

        PhysicalSourceOptions.addSourceIssue(lines, "ORACLE", "ORACLE_TABLE_COMPRESSION=" + raw
                + " uses unsupported/unknown table-compression syntax; source value was not normalized.");
        return new CompressionDecision("<TABLE_COMPRESSION>", 10, false);
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
            boolean activePlacementPresent, boolean knownUniqueConstraint) {
        List<String> lines = new ArrayList<>();
        lines.add(PhysicalSourceOptions.integerClause(
                lines, index, table, "ORACLE", "PCTFREE", 10, 0, 99, "INDEX_PCTFREE",
                "ORACLE_INDEX_PCTFREE", "INDEX_PCTFREE"));
        lines.add(PhysicalSourceOptions.integerClause(
                lines, index, table, "ORACLE", "INITRANS", 2, 1, 255, "INDEX_INITRANS",
                "ORACLE_INDEX_INITRANS", "INDEX_INITRANS"));
        lines.add(indexCompression(lines, index, table, keyColumns, knownUniqueConstraint));
        lines.add(indexLogging(lines, index, table));
        lines.add(indexParallel(lines, index, table));
        if (!activePlacementPresent) {
            lines.add("TABLESPACE <INDEX_TABLESPACE>");
        }
        return PhysicalCommentBlocks.block("ORACLE INDEX PHYSICAL OPTIONS", lines);
    }

    private String indexCompression(
            List<String> lines, Index index, Table table, List<Identifier> keyColumns, boolean knownUniqueConstraint) {
        var source = PhysicalSourceOptions.find(index, table, "ORACLE_INDEX_COMPRESSION", "INDEX_COMPRESSION");
        if (source.isEmpty()) {
            return "NOCOMPRESS";
        }

        String raw = source.get();
        String value = PhysicalSourceOptions.normalizedUpper(raw);
        int keyCount = keyColumns == null ? 0 : keyColumns.size();

        if (value.equals("NOCOMPRESS")) {
            PhysicalSourceOptions.addSourceRetained(lines, "ORACLE_INDEX_COMPRESSION", raw);
            return value;
        }
        if (value.equals("COMPRESS")) {
            if (knownUniqueConstraint && keyCount < 2) {
                return invalidIndexCompression(lines, raw,
                        "prefix compression on a unique single-column index is not valid");
            }
            PhysicalSourceOptions.addSourceRetained(lines, "ORACLE_INDEX_COMPRESSION", raw);
            if (!knownUniqueConstraint) {
                PhysicalSourceOptions.addSourceReview(lines, "ORACLE", "COMPRESS prefix limits depend on index uniqueness; verify for this index.");
            }
            return value;
        }

        Matcher prefix = PREFIX_COMPRESSION.matcher(value);
        if (prefix.matches()) {
            int prefixLength;
            try {
                prefixLength = Integer.parseInt(prefix.group(1));
            } catch (NumberFormatException exception) {
                return invalidIndexCompression(lines, raw, "prefix length is not a valid integer");
            }
            int maximum = knownUniqueConstraint ? keyCount - 1 : keyCount;
            if (prefixLength < 1 || maximum < 1 || prefixLength > maximum) {
                return invalidIndexCompression(lines, raw,
                        "prefix length is outside the supported range for the available key columns");
            }
            PhysicalSourceOptions.addSourceRetained(lines, "ORACLE_INDEX_COMPRESSION", raw);
            if (!knownUniqueConstraint) {
                PhysicalSourceOptions.addSourceReview(lines, "ORACLE", "If this is a UNIQUE index, COMPRESS n must not include all key columns.");
            }
            return value;
        }

        if (value.equals("COMPRESS ADVANCED") || value.equals("COMPRESS ADVANCED HIGH")) {
            if (keyCount < 1) {
                return invalidIndexCompression(lines, raw, "advanced compression requires at least one index key column");
            }
            PhysicalSourceOptions.addSourceRetained(lines, "ORACLE_INDEX_COMPRESSION", raw);
            return value;
        }
        if (value.equals("COMPRESS ADVANCED LOW")) {
            if (keyCount < 2) {
                return invalidIndexCompression(lines, raw,
                        "COMPRESS ADVANCED LOW requires at least two key columns");
            }
            PhysicalSourceOptions.addSourceRetained(lines, "ORACLE_INDEX_COMPRESSION", raw);
            return value;
        }

        return invalidIndexCompression(lines, raw, "unsupported Oracle index-compression syntax");
    }

    private String invalidIndexCompression(List<String> lines, String raw, String reason) {
        PhysicalSourceOptions.addSourceIssue(lines, "ORACLE", "ORACLE_INDEX_COMPRESSION=" + raw
                + " was not emitted: " + reason + "; source value was not normalized.");
        return "<INDEX_COMPRESSION>";
    }

    private String tableLogging(List<String> lines, Table table) {
        var source = PhysicalSourceOptions.find(
                table, "ORACLE_TABLE_LOGGING", "TABLE_LOGGING", "ORACLE_LOGGING");
        if (source.isEmpty()) {
            return "-- LOGGING/NOLOGGING intentionally unspecified: redo/recovery policy must come from source/profile.";
        }
        return oracleLogging(lines, source.get(), "ORACLE_TABLE_LOGGING");
    }

    private String indexLogging(List<String> lines, Index index, Table table) {
        var source = PhysicalSourceOptions.find(index, table, "ORACLE_INDEX_LOGGING", "INDEX_LOGGING");
        if (source.isEmpty()) {
            return "-- LOGGING/NOLOGGING intentionally unspecified: Oracle index logging is independent of the base table.";
        }
        return oracleLogging(lines, source.get(), "ORACLE_INDEX_LOGGING");
    }

    private String oracleLogging(List<String> lines, String raw, String retainedKey) {
        String normalized = PhysicalSourceOptions.normalizedUpper(raw);
        if (normalized.equals("LOGGING") || normalized.equals("NOLOGGING")) {
            PhysicalSourceOptions.addSourceRetained(lines, retainedKey, raw);
            return normalized;
        }
        PhysicalSourceOptions.addSourceIssue(lines, "ORACLE", retainedKey + "=" + raw
                + " must be LOGGING or NOLOGGING; source value was not normalized.");
        return "<LOGGING_OR_NOLOGGING>";
    }

    private String tableParallel(List<String> lines, Table table) {
        var source = PhysicalSourceOptions.find(
                table, "ORACLE_TABLE_PARALLEL", "TABLE_PARALLEL", "ORACLE_PARALLEL");
        return source.isEmpty() ? "NOPARALLEL" : oracleParallel(lines, source.get(), "ORACLE_TABLE_PARALLEL");
    }

    private String indexParallel(List<String> lines, Index index, Table table) {
        var source = PhysicalSourceOptions.find(index, table, "ORACLE_INDEX_PARALLEL", "INDEX_PARALLEL");
        return source.isEmpty() ? "NOPARALLEL" : oracleParallel(lines, source.get(), "ORACLE_INDEX_PARALLEL");
    }

    private String oracleParallel(List<String> lines, String raw, String retainedKey) {
        String normalized = PhysicalSourceOptions.normalizedUpper(raw);
        if (normalized.equals("NOPARALLEL") || normalized.equals("PARALLEL")
                || normalized.matches("PARALLEL [1-9][0-9]*")) {
            PhysicalSourceOptions.addSourceRetained(lines, retainedKey, raw);
            return normalized;
        }
        PhysicalSourceOptions.addSourceIssue(lines, "ORACLE", retainedKey + "=" + raw
                + " must be NOPARALLEL, PARALLEL, or PARALLEL <positive integer>; source value was not normalized.");
        return "<PARALLEL_CLAUSE>";
    }

    private String segmentCreation(List<String> lines, Table table) {
        var source = PhysicalSourceOptions.find(
                table, "ORACLE_TABLE_SEGMENT_CREATION", "ORACLE_SEGMENT_CREATION", "SEGMENT_CREATION");
        if (source.isEmpty()) {
            PhysicalSourceOptions.addSourceReview(lines, "ORACLE",
                    "SEGMENT CREATION is left unspecified so the database/session DEFERRED_SEGMENT_CREATION policy is not overridden.");
            return "SEGMENT CREATION <DEFERRED_OR_IMMEDIATE>";
        }

        String raw = source.get();
        String normalized = PhysicalSourceOptions.normalizedUpper(raw);
        Set<String> accepted = Set.of(
                "DEFERRED", "IMMEDIATE", "SEGMENT CREATION DEFERRED", "SEGMENT CREATION IMMEDIATE");
        if (!accepted.contains(normalized)) {
            PhysicalSourceOptions.addSourceIssue(lines, "ORACLE", "ORACLE_TABLE_SEGMENT_CREATION=" + raw
                    + " must be DEFERRED or IMMEDIATE; source value was not normalized.");
            return "SEGMENT CREATION <DEFERRED_OR_IMMEDIATE>";
        }

        PhysicalSourceOptions.addSourceRetained(lines, "ORACLE_TABLE_SEGMENT_CREATION", raw);
        if (normalized.endsWith("DEFERRED")) {
            PhysicalSourceOptions.addSourceReview(lines, "ORACLE",
                    "SEGMENT CREATION DEFERRED is subject to Oracle table/tablespace restrictions; capability was not inferred offline.");
            return "SEGMENT CREATION DEFERRED";
        }
        return "SEGMENT CREATION IMMEDIATE";
    }


    private record CompressionDecision(String clause, int pctfreeDefault, boolean pctfreeDefaultKnown) {
    }
}
