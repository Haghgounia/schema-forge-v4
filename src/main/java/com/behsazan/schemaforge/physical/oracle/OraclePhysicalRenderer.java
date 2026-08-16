package com.behsazan.schemaforge.physical.oracle;

import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.physical.PhysicalCommentBlocks;
import com.behsazan.schemaforge.physical.PhysicalCommentRenderer;
import com.behsazan.schemaforge.physical.PhysicalSourceOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
        var sourcePctfree = sourceInteger(table, 0, 99, "ORACLE_PCTFREE", "TABLE_PCTFREE", "PCTFREE");
        var sourcePctused = sourceInteger(table, 0, 99, "ORACLE_PCTUSED", "TABLE_PCTUSED", "PCTUSED");
        boolean sourcePairConflict = sourcePctfree.isPresent() && sourcePctused.isPresent()
                && sourcePctfree.get() + sourcePctused.get() > 100;
        boolean defaultPctfreeConflict = pctfreeSource.isEmpty() && sourcePctused.isPresent()
                && compression.pctfreeDefaultKnown()
                && compression.pctfreeDefault() + sourcePctused.get() > 100;

        if (sourcePairConflict) {
            lines.add("-- [SOURCE PHYSICAL ISSUE][ORACLE] PCTFREE=" + sourcePctfree.get()
                    + " and PCTUSED=" + sourcePctused.get()
                    + " exceed Oracle's combined maximum of 100; neither source value was normalized.");
            lines.add("PCTFREE <PCTFREE>");
        } else if (pctfreeSource.isPresent()) {
            lines.add(PhysicalSourceOptions.integerClause(
                    lines, table, "ORACLE", "PCTFREE", compression.pctfreeDefault(), 0, 99, "PCTFREE",
                    "ORACLE_PCTFREE", "TABLE_PCTFREE", "PCTFREE"));
        } else if (defaultPctfreeConflict) {
            lines.add("-- [SOURCE PHYSICAL ISSUE][ORACLE] Source PCTUSED=" + sourcePctused.get()
                    + " conflicts with the documented PCTFREE default " + compression.pctfreeDefault()
                    + "; choose PCTFREE explicitly for MSSM rather than silently changing the source value.");
            lines.add("PCTFREE <PCTFREE>");
        } else if (compression.pctfreeDefaultKnown()) {
            lines.add("PCTFREE " + compression.pctfreeDefault());
        } else {
            lines.add("-- [SOURCE PHYSICAL REVIEW][ORACLE] Table compression is unresolved, so a PCTFREE default was not inferred.");
            lines.add("PCTFREE <PCTFREE>");
        }

        lines.add(PhysicalSourceOptions.integerClause(
                lines, table, "ORACLE", "INITRANS", 1, 1, 255, "INITRANS",
                "ORACLE_INITRANS", "TABLE_INITRANS", "INITRANS"));

        PhysicalSourceOptions.find(table, "ORACLE_PCTUSED", "TABLE_PCTUSED", "PCTUSED")
                .ifPresentOrElse(raw -> {
                            lines.add("-- [SOURCE PHYSICAL REVIEW][ORACLE] PCTUSED applies to manual segment space management; ASSM ignores it.");
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
        lines.add("-- LOGGING/NOLOGGING is workload/recovery policy; no value is invented by Phase 1.");
        if (!activePlacementPresent) {
            lines.add("TABLESPACE <TABLE_TABLESPACE>");
        }
        return PhysicalCommentBlocks.block("ORACLE TABLE PHYSICAL OPTIONS", lines);
    }

    private java.util.Optional<Integer> sourceInteger(
            Table table, int minimum, int maximum, String... keys) {
        var source = PhysicalSourceOptions.find(table, keys);
        if (source.isEmpty()) {
            return java.util.Optional.empty();
        }
        try {
            int value = Integer.parseInt(source.get());
            return value >= minimum && value <= maximum
                    ? java.util.Optional.of(value)
                    : java.util.Optional.empty();
        } catch (NumberFormatException exception) {
            return java.util.Optional.empty();
        }
    }

    private CompressionDecision tableCompression(List<String> lines, Table table) {
        var source = PhysicalSourceOptions.find(table, "ORACLE_TABLE_COMPRESSION", "TABLE_COMPRESSION");
        if (source.isEmpty()) {
            return new CompressionDecision("NOCOMPRESS", 10, true);
        }

        String raw = source.get();
        String value = raw.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (value.equals("NOCOMPRESS")) {
            lines.add("-- [SOURCE PHYSICAL] ORACLE_TABLE_COMPRESSION=" + raw + " retained for DBA review.");
            return new CompressionDecision(value, 10, true);
        }
        if (value.equals("COMPRESS") || value.equals("COMPRESS BASIC")
                || value.equals("ROW STORE COMPRESS") || value.equals("ROW STORE COMPRESS BASIC")) {
            lines.add("-- [SOURCE PHYSICAL] ORACLE_TABLE_COMPRESSION=" + raw + " retained for DBA review.");
            return new CompressionDecision(value, 0, true);
        }
        if (value.equals("ROW STORE COMPRESS ADVANCED") || value.equals("COMPRESS FOR OLTP")) {
            lines.add("-- [SOURCE PHYSICAL] ORACLE_TABLE_COMPRESSION=" + raw + " retained for DBA review.");
            if (value.equals("COMPRESS FOR OLTP")) {
                lines.add("-- [SOURCE PHYSICAL REVIEW][ORACLE] COMPRESS FOR OLTP is legacy-compatible syntax; source was preserved rather than normalized.");
            }
            return new CompressionDecision(value, 10, true);
        }
        if (HCC_COMPRESSION.matcher(value).matches()) {
            lines.add("-- [SOURCE PHYSICAL] ORACLE_TABLE_COMPRESSION=" + raw + " retained for DBA review.");
            lines.add("-- [SOURCE PHYSICAL REVIEW][ORACLE] Hybrid Columnar Compression requires compatible Oracle storage; capability was not verified offline.");
            return new CompressionDecision(value, 0, true);
        }

        lines.add("-- [SOURCE PHYSICAL ISSUE][ORACLE] ORACLE_TABLE_COMPRESSION=" + raw
                + " uses unsupported/unknown table-compression syntax; source value was not normalized.");
        return new CompressionDecision("<TABLE_COMPRESSION>", 10, false);
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
            Table table, List<Identifier> keyColumns, boolean activePlacementPresent, boolean knownUniqueConstraint) {
        List<String> lines = new ArrayList<>();
        lines.add(PhysicalSourceOptions.integerClause(
                lines, table, "ORACLE", "PCTFREE", 10, 0, 99, "INDEX_PCTFREE",
                "ORACLE_INDEX_PCTFREE", "INDEX_PCTFREE"));
        lines.add(PhysicalSourceOptions.integerClause(
                lines, table, "ORACLE", "INITRANS", 2, 1, 255, "INDEX_INITRANS",
                "ORACLE_INDEX_INITRANS", "INDEX_INITRANS"));
        lines.add(indexCompression(lines, table, keyColumns, knownUniqueConstraint));
        lines.add("-- LOGGING/NOLOGGING is workload/recovery policy; no value is invented by Phase 1.");
        if (!activePlacementPresent) {
            lines.add("TABLESPACE <INDEX_TABLESPACE>");
        }
        return PhysicalCommentBlocks.block("ORACLE INDEX PHYSICAL OPTIONS", lines);
    }

    private String indexCompression(
            List<String> lines, Table table, List<Identifier> keyColumns, boolean knownUniqueConstraint) {
        var source = PhysicalSourceOptions.find(table, "ORACLE_INDEX_COMPRESSION", "INDEX_COMPRESSION");
        if (source.isEmpty()) {
            return "NOCOMPRESS";
        }

        String raw = source.get();
        String value = raw.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        int keyCount = keyColumns == null ? 0 : keyColumns.size();

        if (value.equals("NOCOMPRESS")) {
            lines.add("-- [SOURCE PHYSICAL] ORACLE_INDEX_COMPRESSION=" + raw + " retained for DBA review.");
            return value;
        }
        if (value.equals("COMPRESS")) {
            if (knownUniqueConstraint && keyCount < 2) {
                return invalidIndexCompression(lines, raw,
                        "prefix compression on a unique single-column index is not valid");
            }
            lines.add("-- [SOURCE PHYSICAL] ORACLE_INDEX_COMPRESSION=" + raw + " retained for DBA review.");
            if (!knownUniqueConstraint) {
                lines.add("-- [SOURCE PHYSICAL REVIEW][ORACLE] COMPRESS prefix limits depend on index uniqueness; verify for this index.");
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
            lines.add("-- [SOURCE PHYSICAL] ORACLE_INDEX_COMPRESSION=" + raw + " retained for DBA review.");
            if (!knownUniqueConstraint) {
                lines.add("-- [SOURCE PHYSICAL REVIEW][ORACLE] If this is a UNIQUE index, COMPRESS n must not include all key columns.");
            }
            return value;
        }

        if (value.equals("COMPRESS ADVANCED") || value.equals("COMPRESS ADVANCED HIGH")) {
            if (keyCount < 1) {
                return invalidIndexCompression(lines, raw, "advanced compression requires at least one index key column");
            }
            lines.add("-- [SOURCE PHYSICAL] ORACLE_INDEX_COMPRESSION=" + raw + " retained for DBA review.");
            return value;
        }
        if (value.equals("COMPRESS ADVANCED LOW")) {
            if (keyCount < 2) {
                return invalidIndexCompression(lines, raw,
                        "COMPRESS ADVANCED LOW requires at least two key columns");
            }
            lines.add("-- [SOURCE PHYSICAL] ORACLE_INDEX_COMPRESSION=" + raw + " retained for DBA review.");
            return value;
        }

        return invalidIndexCompression(lines, raw, "unsupported Oracle index-compression syntax");
    }

    private String invalidIndexCompression(List<String> lines, String raw, String reason) {
        lines.add("-- [SOURCE PHYSICAL ISSUE][ORACLE] ORACLE_INDEX_COMPRESSION=" + raw
                + " was not emitted: " + reason + "; source value was not normalized.");
        return "<INDEX_COMPRESSION>";
    }


    private record CompressionDecision(String clause, int pctfreeDefault, boolean pctfreeDefaultKnown) {
    }
}
