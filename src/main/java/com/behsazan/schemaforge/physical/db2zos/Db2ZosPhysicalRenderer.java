package com.behsazan.schemaforge.physical.db2zos;

import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.physical.PhysicalCommentBlocks;
import com.behsazan.schemaforge.physical.PhysicalCommentRenderer;
import com.behsazan.schemaforge.physical.PhysicalSourceOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Db2 for z/OS physical/table-space and index options based on explicit source/profile evidence. */
public final class Db2ZosPhysicalRenderer implements PhysicalCommentRenderer {
    private static final Set<String> YES_NO = Set.of("YES", "NO");
    private static final Set<String> GBPCACHE = Set.of("CHANGED", "ALL", "NONE");
    private static final Set<String> PADDED = Set.of("PADDED", "NOT PADDED");
    private static final Set<String> LOCKSIZE = Set.of("ANY", "TABLESPACE", "PAGE", "ROW");
    private static final Set<String> LOGGING = Set.of("LOGGED", "NOT LOGGED");
    private static final Set<String> COMPRESS = Set.of("NO", "YES", "YES FIXEDLENGTH", "YES HUFFMAN");
    private static final Pattern PIECESIZE = Pattern.compile("(\\d+)\\s*([KMG])", Pattern.CASE_INSENSITIVE);
    private static final Pattern DSSIZE = Pattern.compile("(\\d+)\\s*G", Pattern.CASE_INSENSITIVE);
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_$#@]+", Pattern.CASE_INSENSITIVE);

    @Override
    public String tableOptions(Table table, boolean activePlacementPresent) {
        List<String> lines = new ArrayList<>();
        if (!activePlacementPresent) {
            lines.add("-- DBA SITE PROFILE: TABLE PLACEMENT=<DATABASE>.<TABLESPACE>");
        }

        String explicitProfile = table.physicalOptions().entrySet().stream()
                .filter(entry -> entry.getKey().toUpperCase(Locale.ROOT).startsWith("DB2_TABLESPACE_"))
                .sorted(java.util.Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(" | "));
        if (!explicitProfile.isBlank()) {
            lines.add("-- SOURCE TABLESPACE PROFILE: " + explicitProfile);
        }

        List<String> diagnostics = new ArrayList<>();
        sourceIdentifierOrPlaceholder(diagnostics, table, "BUFFERPOOL ", "BUFFERPOOL",
                "DB2_TABLESPACE_BUFFERPOOL", "TABLESPACE_BUFFERPOOL", "DB2_BUFFERPOOL");
        dssize(diagnostics, table);
        segsize(diagnostics, table);
        PhysicalSourceOptions.integerClause(diagnostics, table, "DB2/ZOS", "FREEPAGE", 0, 0, 255,
                "FREEPAGE", "DB2_TABLESPACE_FREEPAGE", "TABLESPACE_FREEPAGE");
        addPctfree(diagnostics, table);
        PhysicalSourceOptions.enumClause(diagnostics, table, "DB2/ZOS", "TABLESPACE_COMPRESS", "NO",
                "COMPRESS", COMPRESS, "DB2_TABLESPACE_COMPRESS", "TABLESPACE_COMPRESS");
        PhysicalSourceOptions.enumClause(diagnostics, table, "DB2/ZOS", "TABLESPACE_GBPCACHE", "CHANGED",
                "GBPCACHE", GBPCACHE, "DB2_TABLESPACE_GBPCACHE", "TABLESPACE_GBPCACHE");
        PhysicalSourceOptions.enumClause(diagnostics, table, "DB2/ZOS", "TABLESPACE_CLOSE", "YES",
                "CLOSE", YES_NO, "DB2_TABLESPACE_CLOSE", "TABLESPACE_CLOSE");
        String lockSize = PhysicalSourceOptions.enumClause(diagnostics, table, "DB2/ZOS",
                "TABLESPACE_LOCKSIZE", "<LOCKSIZE>", "LOCKSIZE", LOCKSIZE,
                "DB2_TABLESPACE_LOCKSIZE", "TABLESPACE_LOCKSIZE");
        lockmax(diagnostics, table, lockSize);
        addMemberCluster(diagnostics, table);
        insertAlgorithm(diagnostics, table);
        addUsing(diagnostics, table);
        diagnostics.stream()
                .filter(line -> line.startsWith("-- [SOURCE PHYSICAL ISSUE]")
                        || line.startsWith("-- [SOURCE PHYSICAL REVIEW]"))
                .forEach(lines::add);

        lines.add("-- DBA TABLESPACE POLICY: STOGROUP/BUFFERPOOL/DSSIZE/SEGSIZE/LOCKSIZE/space allocation belong to CREATE/ALTER TABLESPACE.");
        lines.add("-- DBA TABLE ATTRIBUTES: CCSID and WITH RESTRICT ON DROP require explicit source/profile policy.");
        return PhysicalCommentBlocks.block("DB2/ZOS DBA PHYSICAL REVIEW", lines);
    }

    @Override
    public String indexOptions(Table table, List<Identifier> keyColumns, boolean activePlacementPresent) {
        return renderIndexOptions(table, null, keyColumns, activePlacementPresent, false);
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
    public String constraintIndexOptions(
            Table table, Index index, List<Identifier> keyColumns, boolean activePlacementPresent) {
        return renderIndexOptions(table, index, keyColumns, activePlacementPresent, true);
    }

    private String renderIndexOptions(
            Table table, Index index, List<Identifier> keyColumns,
            boolean activePlacementPresent, boolean uniqueIndex) {
        String nl = System.lineSeparator();
        List<String> active = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        List<String> dba = new ArrayList<>();

        boolean varyingKey = containsVaryingLengthCharacterKey(table, keyColumns);
        Optional<String> paddingSource = PhysicalSourceOptions.find(index, table,
                "DB2_INDEX_PADDING", "INDEX_PADDING");
        if (varyingKey) {
            if (paddingSource.isPresent()) {
                String padding = PhysicalSourceOptions.enumClause(
                        diagnostics, index, table, "DB2/ZOS", "PADDED", "<PADDED_OR_NOT_PADDED>",
                        "PADDED_OR_NOT_PADDED", PADDED, "DB2_INDEX_PADDING", "INDEX_PADDING");
                if (!padding.startsWith("<")) active.add(padding);
            } else {
                dba.add("PADDING=<PADIX/subsystem policy>");
            }
        }

        Optional<String> stogroup = PhysicalSourceOptions.find(index, table,
                "DB2_INDEX_STOGROUP", "INDEX_STOGROUP");
        if (stogroup.isPresent()) {
            String raw = stogroup.get();
            if (IDENTIFIER.matcher(raw).matches()) {
                active.add("USING STOGROUP " + raw);
                String priqty = PhysicalSourceOptions.sourceIntegerOrPlaceholder(
                        diagnostics, index, table, "DB2/ZOS", "PRIQTY ", "PRIQTY",
                        value -> value == -1 || value > 0, "a positive integer or -1",
                        "DB2_INDEX_PRIQTY", "INDEX_PRIQTY");
                if (!priqty.contains("<")) active.add("  " + priqty);
                String secqty = PhysicalSourceOptions.sourceIntegerOrPlaceholder(
                        diagnostics, index, table, "DB2/ZOS", "SECQTY ", "SECQTY",
                        value -> value >= -1, "a positive integer, 0, or -1",
                        "DB2_INDEX_SECQTY", "INDEX_SECQTY");
                if (!secqty.contains("<")) active.add("  " + secqty);
                String erase = PhysicalSourceOptions.enumClause(
                        diagnostics, index, table, "DB2/ZOS", "ERASE", "NO", "ERASE", YES_NO,
                        "DB2_INDEX_ERASE", "INDEX_ERASE");
                if (!erase.startsWith("<")) active.add("  ERASE " + erase);
            } else {
                PhysicalSourceOptions.addSourceIssue(diagnostics, "DB2/ZOS",
                        "DB2_INDEX_STOGROUP=" + raw + " is not a safe Db2 identifier; value was not emitted.");
            }
        } else {
            dba.add("STOGROUP/PRIQTY/SECQTY=<site storage policy>");
            if (PhysicalSourceOptions.find(index, table, "DB2_INDEX_PRIQTY", "INDEX_PRIQTY").isPresent()) {
                String priqty = PhysicalSourceOptions.sourceIntegerOrPlaceholder(
                        diagnostics, index, table, "DB2/ZOS", "PRIQTY ", "PRIQTY",
                        value -> value == -1 || value > 0, "a positive integer or -1",
                        "DB2_INDEX_PRIQTY", "INDEX_PRIQTY");
                if (!priqty.contains("<")) {
                    PhysicalSourceOptions.addSourceReview(diagnostics, "DB2/ZOS",
                            priqty + " was supplied without STOGROUP evidence and was not emitted.");
                }
            }
            if (PhysicalSourceOptions.find(index, table, "DB2_INDEX_SECQTY", "INDEX_SECQTY").isPresent()) {
                String secqty = PhysicalSourceOptions.sourceIntegerOrPlaceholder(
                        diagnostics, index, table, "DB2/ZOS", "SECQTY ", "SECQTY",
                        value -> value >= -1, "a positive integer, 0, or -1",
                        "DB2_INDEX_SECQTY", "INDEX_SECQTY");
                if (!secqty.contains("<")) {
                    PhysicalSourceOptions.addSourceReview(diagnostics, "DB2/ZOS",
                            secqty + " was supplied without STOGROUP evidence and was not emitted.");
                }
            }
        }

        String freepage = PhysicalSourceOptions.integerClause(
                diagnostics, index, table, "DB2/ZOS", "FREEPAGE", 0, 0, 255, "FREEPAGE",
                "DB2_INDEX_FREEPAGE", "INDEX_FREEPAGE");
        if (!freepage.contains("<")) active.add(freepage);
        String pctfree = PhysicalSourceOptions.integerClause(
                diagnostics, index, table, "DB2/ZOS", "PCTFREE", 10, 0, 99, "PCTFREE",
                "DB2_INDEX_PCTFREE", "INDEX_PCTFREE");
        if (!pctfree.contains("<")) active.add(pctfree);
        String gbpcache = PhysicalSourceOptions.enumClause(
                diagnostics, index, table, "DB2/ZOS", "GBPCACHE", "CHANGED", "GBPCACHE", GBPCACHE,
                "DB2_INDEX_GBPCACHE", "INDEX_GBPCACHE");
        if (!gbpcache.startsWith("<")) active.add("GBPCACHE " + gbpcache);
        Optional<String> cluster = PhysicalSourceOptions.find(index, table,
                "DB2_INDEX_CLUSTER", "INDEX_CLUSTER");
        if (cluster.isPresent()) {
            String normalized = PhysicalSourceOptions.normalizedUpper(cluster.get());
            if (Set.of("YES", "CLUSTER").contains(normalized)) active.add("CLUSTER");
            else if (Set.of("NO", "NOT CLUSTER").contains(normalized)) active.add("NOT CLUSTER");
            else PhysicalSourceOptions.addSourceIssue(diagnostics, "DB2/ZOS",
                    "DB2_INDEX_CLUSTER=" + cluster.get() + " must be YES/NO or CLUSTER/NOT CLUSTER; value was not emitted.");
        } else {
            dba.add("CLUSTER=<data-organization policy>");
        }

        String compress = PhysicalSourceOptions.enumClause(
                diagnostics, index, table, "DB2/ZOS", "COMPRESS", "NO", "COMPRESS", YES_NO,
                "DB2_INDEX_COMPRESS", "INDEX_COMPRESS");
        if (!compress.startsWith("<")) active.add("COMPRESS " + compress);

        Optional<String> nullKeysSource = PhysicalSourceOptions.find(index, table,
                "DB2_INDEX_NULL_KEYS", "INDEX_NULL_KEYS");
        String nullKeys = "INCLUDE NULL KEYS";
        if (nullKeysSource.isPresent()) {
            String normalized = PhysicalSourceOptions.normalizedUpper(nullKeysSource.get());
            if (normalized.equals("INCLUDE") || normalized.equals("YES")) normalized = "INCLUDE NULL KEYS";
            if (normalized.equals("EXCLUDE") || normalized.equals("NO")) normalized = "EXCLUDE NULL KEYS";
            if (!Set.of("INCLUDE NULL KEYS", "EXCLUDE NULL KEYS").contains(normalized)) {
                PhysicalSourceOptions.addSourceIssue(diagnostics, "DB2/ZOS",
                        "DB2_INDEX_NULL_KEYS=" + nullKeysSource.get()
                                + " must be INCLUDE NULL KEYS or EXCLUDE NULL KEYS; value was not emitted.");
                nullKeys = null;
            } else if (uniqueIndex && normalized.equals("EXCLUDE NULL KEYS")) {
                PhysicalSourceOptions.addSourceIssue(diagnostics, "DB2/ZOS",
                        "EXCLUDE NULL KEYS is not valid for a UNIQUE enforcing index; value was not emitted.");
                nullKeys = null;
            } else {
                nullKeys = normalized;
            }
        }
        if (nullKeys != null) active.add(nullKeys);

        Optional<String> bufferpool = PhysicalSourceOptions.find(index, table,
                "DB2_INDEX_BUFFERPOOL", "INDEX_BUFFERPOOL");
        if (bufferpool.isPresent()) {
            if (IDENTIFIER.matcher(bufferpool.get()).matches()) active.add("BUFFERPOOL " + bufferpool.get());
            else PhysicalSourceOptions.addSourceIssue(diagnostics, "DB2/ZOS",
                    "DB2_INDEX_BUFFERPOOL=" + bufferpool.get() + " is not a safe Db2 identifier; value was not emitted.");
        } else {
            dba.add("BUFFERPOOL=<site/workload policy>");
        }

        String close = PhysicalSourceOptions.enumClause(
                diagnostics, index, table, "DB2/ZOS", "CLOSE", "YES", "CLOSE", YES_NO,
                "DB2_INDEX_CLOSE", "INDEX_CLOSE");
        if (!close.startsWith("<")) active.add("CLOSE " + close);

        String pieceSize = pieceSize(diagnostics, index, table);
        if (pieceSize != null && !pieceSize.contains("<")) {
            dba.add("PIECESIZE=" + pieceSize.substring("PIECESIZE ".length()) + " (source; DBA capacity policy)");
        } else if (pieceSize == null) {
            dba.add("PIECESIZE=<capacity policy>");
        }

        Optional<String> copy = PhysicalSourceOptions.find(index, table,
                "DB2_INDEX_COPY", "INDEX_COPY");
        if (copy.isPresent()) {
            String value = PhysicalSourceOptions.enumClause(
                    diagnostics, index, table, "DB2/ZOS", "COPY", "NO", "COPY", YES_NO,
                    "DB2_INDEX_COPY", "INDEX_COPY");
            if (!value.startsWith("<")) active.add("COPY " + value);
        } else {
            dba.add("COPY=<recovery policy>");
        }

        diagnostics.stream()
                .filter(line -> line.startsWith("-- [SOURCE PHYSICAL ISSUE]")
                        || line.startsWith("-- [SOURCE PHYSICAL REVIEW]"))
                .forEach(dba::add);

        StringBuilder out = new StringBuilder();
        for (String clause : active) {
            out.append(nl).append("  ").append(clause);
        }
        if (!dba.isEmpty()) {
            List<String> compact = new ArrayList<>();
            List<String> settings = dba.stream().filter(line -> !line.startsWith("-- [SOURCE")).toList();
            if (!settings.isEmpty()) compact.add("-- DBA SITE/RECOVERY SETTINGS: " + String.join(" | ", settings));
            dba.stream().filter(line -> line.startsWith("-- [SOURCE")).forEach(compact::add);
            out.append(PhysicalCommentBlocks.block("DB2/ZOS DBA PHYSICAL REVIEW", compact));
        }
        return out.toString();
    }

    private void addPctfree(List<String> lines, Table table) {
        Optional<String> pctRaw = PhysicalSourceOptions.find(table,
                "DB2_TABLESPACE_PCTFREE", "TABLESPACE_PCTFREE");
        Optional<String> updRaw = PhysicalSourceOptions.find(table,
                "DB2_TABLESPACE_PCTFREE_FOR_UPDATE", "TABLESPACE_PCTFREE_FOR_UPDATE");

        Integer pct = parseInteger(pctRaw, 0, 99);
        Integer upd = parseInteger(updRaw, -1, 99);
        boolean pctInvalid = pctRaw.isPresent() && pct == null;
        boolean updInvalid = updRaw.isPresent() && upd == null;
        int effectivePct = pct != null ? pct : (pctRaw.isEmpty() ? 5 : -1);
        boolean sumInvalid = effectivePct >= 0 && upd != null && upd >= 0 && effectivePct + upd > 99;

        if (pctInvalid) {
            PhysicalSourceOptions.addSourceIssue(lines, "DB2/ZOS", "DB2_TABLESPACE_PCTFREE="
                    + pctRaw.get() + " must be an integer in 0..99; source value was not normalized.");
        }
        if (updInvalid) {
            PhysicalSourceOptions.addSourceIssue(lines, "DB2/ZOS", "DB2_TABLESPACE_PCTFREE_FOR_UPDATE="
                    + updRaw.get() + " must be an integer in -1..99; source value was not normalized.");
        }
        if (sumInvalid) {
            PhysicalSourceOptions.addSourceIssue(lines, "DB2/ZOS", "TABLESPACE PCTFREE=" + effectivePct
                    + " plus FOR UPDATE=" + upd + " exceeds the Db2 maximum combined value of 99; "
                    + "source values were not normalized.");
        }

        if (pct != null && !sumInvalid) {
            PhysicalSourceOptions.addSourceRetained(lines, "DB2_TABLESPACE_PCTFREE", pctRaw.orElse(Integer.toString(pct)));
            lines.add("PCTFREE " + pct);
        } else if (!pctRaw.isPresent()) {
            lines.add("PCTFREE 5");
        } else {
            lines.add("PCTFREE <PCTFREE>");
        }

        if (upd != null && !sumInvalid) {
            PhysicalSourceOptions.addSourceRetained(lines, "DB2_TABLESPACE_PCTFREE_FOR_UPDATE", updRaw.orElse(Integer.toString(upd)));
            lines.add("    FOR UPDATE " + upd);
        } else if (updRaw.isPresent()) {
            lines.add("    FOR UPDATE <PCTFREE_FOR_UPDATE>");
        } else {
            lines.add("-- FOR UPDATE default is subsystem-controlled by PCTFREE_UPD; no value was invented.");
        }
    }

    private String segsize(List<String> lines, Table table) {
        Optional<String> source = PhysicalSourceOptions.find(table,
                "DB2_TABLESPACE_SEGSIZE", "TABLESPACE_SEGSIZE");
        if (source.isEmpty()) {
            return "SEGSIZE <SEGSIZE>";
        }
        Integer value = parseInteger(source, 4, 64);
        if (value != null && value % 4 == 0) {
            PhysicalSourceOptions.addSourceRetained(lines, "DB2_TABLESPACE_SEGSIZE", source.get());
            return "SEGSIZE " + value;
        }
        PhysicalSourceOptions.addSourceIssue(lines, "DB2/ZOS", "DB2_TABLESPACE_SEGSIZE=" + source.get()
                + " must be a multiple of 4 in the range 4..64; source value was not normalized.");
        return "SEGSIZE <SEGSIZE>";
    }

    private String dssize(List<String> lines, Table table) {
        Optional<String> source = PhysicalSourceOptions.find(table,
                "DB2_TABLESPACE_DSSIZE", "TABLESPACE_DSSIZE");
        if (source.isEmpty()) {
            return null;
        }
        String raw = source.get();
        Matcher matcher = DSSIZE.matcher(raw.trim());
        if (!matcher.matches()) {
            PhysicalSourceOptions.addSourceIssue(lines, "DB2/ZOS", "DB2_TABLESPACE_DSSIZE=" + raw
                    + " must be an integer G value; source value was not normalized.");
            return "DSSIZE <DSSIZE>";
        }
        try {
            int value = Integer.parseInt(matcher.group(1));
            if (value < 1 || value > 1024) {
                throw new NumberFormatException();
            }
            PhysicalSourceOptions.addSourceRetained(lines, "DB2_TABLESPACE_DSSIZE", raw);
            if (value > 4) {
                PhysicalSourceOptions.addSourceReview(lines, "DB2/ZOS",
                        "DSSIZE above 4G requires appropriate DFSMS extended format/extended addressability.");
            }
            if (!isPowerOfTwo(value) || value > 256) {
                PhysicalSourceOptions.addSourceReview(lines, "DB2/ZOS",
                        "This DSSIZE is valid only in contexts such as PBR with PAGENUM RELATIVE; PBG/PBR-absolute rules differ.");
            }
            return "DSSIZE " + value + " G";
        } catch (NumberFormatException exception) {
            PhysicalSourceOptions.addSourceIssue(lines, "DB2/ZOS", "DB2_TABLESPACE_DSSIZE=" + raw
                    + " is outside the supported 1..1024 G review range; source value was not normalized.");
            return "DSSIZE <DSSIZE>";
        }
    }

    private String lockmax(List<String> lines, Table table, String lockSize) {
        Optional<String> source = PhysicalSourceOptions.find(table,
                "DB2_TABLESPACE_LOCKMAX", "TABLESPACE_LOCKMAX");
        if (source.isEmpty()) {
            lines.add("-- LOCKMAX default/interaction depends on LOCKSIZE; no value was invented.");
            return "LOCKMAX <LOCKMAX>";
        }
        String raw = source.get();
        String normalized = PhysicalSourceOptions.normalizedUpper(raw);
        if ("SYSTEM".equals(normalized)) {
            if ("TABLESPACE".equals(lockSize)) {
                PhysicalSourceOptions.addSourceIssue(lines, "DB2/ZOS",
                        "LOCKMAX SYSTEM is incompatible with LOCKSIZE TABLESPACE; source value was not normalized.");
                return "LOCKMAX <LOCKMAX>";
            }
            PhysicalSourceOptions.addSourceRetained(lines, "DB2_TABLESPACE_LOCKMAX", raw);
            return "LOCKMAX SYSTEM";
        }
        try {
            int value = Integer.parseInt(raw);
            if (value < 0 || ("TABLESPACE".equals(lockSize) && value != 0)) {
                throw new NumberFormatException();
            }
            PhysicalSourceOptions.addSourceRetained(lines, "DB2_TABLESPACE_LOCKMAX", raw);
            return "LOCKMAX " + value;
        } catch (NumberFormatException exception) {
            PhysicalSourceOptions.addSourceIssue(lines, "DB2/ZOS", "DB2_TABLESPACE_LOCKMAX=" + raw
                    + " must be SYSTEM or an integer in 0..2147483647; LOCKSIZE TABLESPACE permits only 0/omission.");
            return "LOCKMAX <LOCKMAX>";
        }
    }

    private void addMemberCluster(List<String> lines, Table table) {
        Optional<String> source = PhysicalSourceOptions.find(table,
                "DB2_TABLESPACE_MEMBER_CLUSTER", "TABLESPACE_MEMBER_CLUSTER");
        if (source.isEmpty()) {
            lines.add("-- MEMBER CLUSTER is a data-organization choice; no value was inferred.");
            return;
        }
        String normalized = PhysicalSourceOptions.normalizedUpper(source.get());
        if (!YES_NO.contains(normalized)) {
            PhysicalSourceOptions.addSourceIssue(lines, "DB2/ZOS", "DB2_TABLESPACE_MEMBER_CLUSTER="
                    + source.get() + " must be YES or NO; source value was not normalized.");
            lines.add("<MEMBER_CLUSTER>");
            return;
        }
        PhysicalSourceOptions.addSourceRetained(lines, "DB2_TABLESPACE_MEMBER_CLUSTER", source.get());
        if ("YES".equals(normalized)) {
            lines.add("MEMBER CLUSTER");
        } else {
            lines.add("-- MEMBER CLUSTER omitted by explicit source/profile value NO.");
        }
    }

    private String insertAlgorithm(List<String> lines, Table table) {
        Optional<String> source = PhysicalSourceOptions.find(table,
                "DB2_TABLESPACE_INSERT_ALGORITHM", "TABLESPACE_INSERT_ALGORITHM");
        if (source.isEmpty()) {
            return "INSERT ALGORITHM 0";
        }
        Integer value = parseInteger(source, 0, 2);
        if (value == null) {
            PhysicalSourceOptions.addSourceIssue(lines, "DB2/ZOS", "DB2_TABLESPACE_INSERT_ALGORITHM="
                    + source.get() + " must be 0, 1 or 2; source value was not normalized.");
            return "INSERT ALGORITHM <INSERT_ALGORITHM>";
        }
        PhysicalSourceOptions.addSourceRetained(lines, "DB2_TABLESPACE_INSERT_ALGORITHM", source.get());
        if (PhysicalSourceOptions.find(table, "DB2_TABLESPACE_MEMBER_CLUSTER", "TABLESPACE_MEMBER_CLUSTER")
                .map(PhysicalSourceOptions::normalizedUpper).filter("YES"::equals).isEmpty()) {
            PhysicalSourceOptions.addSourceReview(lines, "DB2/ZOS",
                    "INSERT ALGORITHM is used only where MEMBER CLUSTER is applicable; MEMBER CLUSTER was not confirmed YES.");
        }
        return "INSERT ALGORITHM " + value;
    }

    private void addUsing(List<String> lines, Table table) {
        Optional<String> stogroup = PhysicalSourceOptions.find(table,
                "DB2_TABLESPACE_STOGROUP", "TABLESPACE_STOGROUP");
        if (stogroup.isPresent()) {
            String raw = stogroup.get();
            if (IDENTIFIER.matcher(raw).matches()) {
                PhysicalSourceOptions.addSourceRetained(lines, "DB2_TABLESPACE_STOGROUP", raw);
                lines.add("USING STOGROUP " + raw);
            } else {
                PhysicalSourceOptions.addSourceIssue(lines, "DB2/ZOS", "DB2_TABLESPACE_STOGROUP=" + raw
                        + " is not a simple Db2 identifier; source value was not normalized.");
                lines.add("USING STOGROUP <STOGROUP>");
            }
        } else {
            lines.add("USING STOGROUP <STOGROUP>");
        }
        lines.add("    " + PhysicalSourceOptions.sourceIntegerOrPlaceholder(
                lines, table, "DB2/ZOS", "PRIQTY ", "PRIQTY",
                value -> value == -1 || value > 0, "a positive integer or -1",
                "DB2_TABLESPACE_PRIQTY", "TABLESPACE_PRIQTY"));
        lines.add("    " + PhysicalSourceOptions.sourceIntegerOrPlaceholder(
                lines, table, "DB2/ZOS", "SECQTY ", "SECQTY",
                value -> value >= -1, "a positive integer, 0, or -1",
                "DB2_TABLESPACE_SECQTY", "TABLESPACE_SECQTY"));
        lines.add("    ERASE " + PhysicalSourceOptions.enumClause(
                lines, table, "DB2/ZOS", "TABLESPACE_ERASE", "NO", "ERASE", YES_NO,
                "DB2_TABLESPACE_ERASE", "TABLESPACE_ERASE"));

        Optional<String> define = PhysicalSourceOptions.find(table,
                "DB2_TABLESPACE_DEFINE", "TABLESPACE_DEFINE");
        if (define.map(PhysicalSourceOptions::normalizedUpper).filter("NO"::equals).isPresent()
                && stogroup.isEmpty()) {
            PhysicalSourceOptions.addSourceReview(lines, "DB2/ZOS",
                    "DEFINE NO is applicable to Db2-managed data sets; confirm USING STOGROUP before activation.");
        }
    }

    private String sourceIdentifierOrPlaceholder(
            List<String> lines, Table table, String prefix, String placeholder, String... keys) {
        Optional<String> source = PhysicalSourceOptions.find(table, keys);
        if (source.isEmpty()) {
            return prefix + "<" + placeholder + ">";
        }
        String raw = source.get();
        if (IDENTIFIER.matcher(raw).matches()) {
            PhysicalSourceOptions.addSourceRetained(lines, keys[0], raw);
            return prefix + raw;
        }
        PhysicalSourceOptions.addSourceIssue(lines, "DB2/ZOS", keys[0] + "=" + raw
                + " is not a simple Db2 identifier; source value was not normalized.");
        return prefix + "<" + placeholder + ">";
    }

    private Integer parseInteger(Optional<String> source, int min, int max) {
        if (source.isEmpty()) {
            return null;
        }
        try {
            int value = Integer.parseInt(source.get());
            return value >= min && value <= max ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private boolean isPowerOfTwo(int value) {
        return value > 0 && (value & (value - 1)) == 0;
    }

    private String pieceSize(List<String> lines, Index index, Table table) {
        var source = PhysicalSourceOptions.find(index, table, "DB2_INDEX_PIECESIZE", "INDEX_PIECESIZE");
        if (source.isEmpty()) {
            return null;
        }

        String raw = source.get();
        Matcher matcher = PIECESIZE.matcher(raw.trim());
        if (!matcher.matches()) {
            PhysicalSourceOptions.addSourceIssue(lines, "DB2/ZOS", "INDEX_PIECESIZE=" + raw
                    + " must be a power-of-two value followed by K, M or G; source value was not normalized.");
            return "PIECESIZE <PIECESIZE>";
        }

        long value;
        try {
            value = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            PhysicalSourceOptions.addSourceIssue(lines, "DB2/ZOS", "INDEX_PIECESIZE=" + raw
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
            PhysicalSourceOptions.addSourceIssue(lines, "DB2/ZOS", "INDEX_PIECESIZE=" + raw
                    + " is not a supported Db2 PIECESIZE value; source value was not normalized.");
            return "PIECESIZE <PIECESIZE>";
        }

        PhysicalSourceOptions.addSourceRetained(lines, "DB2_INDEX_PIECESIZE", raw);
        PhysicalSourceOptions.addSourceReview(lines, "DB2/ZOS", "PIECESIZE applicability/default depends on table-space size and index organization; offline context was not assumed.");
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
