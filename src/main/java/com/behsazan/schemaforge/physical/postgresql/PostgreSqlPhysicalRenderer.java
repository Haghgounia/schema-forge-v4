package com.behsazan.schemaforge.physical.postgresql;

import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.physical.PhysicalCommentBlocks;
import com.behsazan.schemaforge.physical.PhysicalCommentRenderer;
import com.behsazan.schemaforge.physical.PhysicalSourceOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** PostgreSQL Phase-1 physical defaults/candidates. TOAST/column compression stays outside Phase 1. */
public final class PostgreSqlPhysicalRenderer implements PhysicalCommentRenderer {
    private static final Set<String> ON_OFF = Set.of("ON", "OFF");

    @Override
    public String tableOptions(Table table, boolean activePlacementPresent) {
        List<String> lines = new ArrayList<>();
        String fillfactor = PhysicalSourceOptions.integerClause(
                lines, table, "POSTGRESQL", "fillfactor =", 100, 10, 100, "TABLE_FILLFACTOR",
                "POSTGRESQL_TABLE_FILLFACTOR", "TABLE_FILLFACTOR");
        List<String> storageOptions = new ArrayList<>();
        storageOptions.add(fillfactor);

        PhysicalSourceOptions.find(table,
                "POSTGRESQL_TOAST_TUPLE_TARGET", "TABLE_TOAST_TUPLE_TARGET", "TOAST_TUPLE_TARGET")
                .ifPresentOrElse(raw -> {
                    try {
                        int value = Integer.parseInt(raw);
                        if (value < 128) {
                            PhysicalSourceOptions.addSourceIssue(lines, "POSTGRESQL",
                                    "TOAST_TUPLE_TARGET=" + raw
                                            + " is below PostgreSQL's minimum of 128 bytes; source value was not normalized.");
                            storageOptions.add("toast_tuple_target = <TOAST_TUPLE_TARGET>");
                        } else {
                            PhysicalSourceOptions.addSourceRetained(lines, "POSTGRESQL_TOAST_TUPLE_TARGET", raw);
                            PhysicalSourceOptions.addSourceReview(lines, "POSTGRESQL",
                                    "toast_tuple_target upper bound depends on server block size; offline capability was not assumed.");
                            storageOptions.add("toast_tuple_target = " + value);
                        }
                    } catch (NumberFormatException exception) {
                        PhysicalSourceOptions.addSourceIssue(lines, "POSTGRESQL",
                                "TOAST_TUPLE_TARGET=" + raw
                                        + " must be an integer; source value was not normalized.");
                        storageOptions.add("toast_tuple_target = <TOAST_TUPLE_TARGET>");
                    }
                }, () -> lines.add("-- toast_tuple_target is row-shape/block-size specific; source/profile only."));

        lines.add("WITH (" + String.join(", ", storageOptions) + ")");
        if (!activePlacementPresent) {
            lines.add("TABLESPACE <TABLE_TABLESPACE>");
        }
        lines.add("-- Column STORAGE/COMPRESSION and autovacuum settings are separate storage/operational policies and are not invented by Phase 1.");
        return PhysicalCommentBlocks.block("POSTGRESQL TABLE PHYSICAL OPTIONS", lines);
    }

    @Override
    public String indexOptions(Table table, List<Identifier> keyColumns, boolean activePlacementPresent) {
        return renderIndexOptions(table, activePlacementPresent, false);
    }

    @Override
    public String constraintIndexOptions(Table table, List<Identifier> keyColumns, boolean activePlacementPresent) {
        return renderIndexOptions(table, activePlacementPresent, true);
    }

    private String renderIndexOptions(Table table, boolean activePlacementPresent, boolean constraintIndex) {
        List<String> lines = new ArrayList<>();
        String fillfactor = PhysicalSourceOptions.integerClause(
                lines, table, "POSTGRESQL", "fillfactor =", 90, 10, 100, "INDEX_FILLFACTOR",
                "POSTGRESQL_INDEX_FILLFACTOR", "INDEX_FILLFACTOR");
        String options = fillfactor;
        if (PhysicalSourceOptions.find(table,
                "POSTGRESQL_INDEX_DEDUPLICATE_ITEMS", "INDEX_DEDUPLICATE_ITEMS").isPresent()) {
            String deduplicate = PhysicalSourceOptions.enumClause(
                    lines, table, "POSTGRESQL", "deduplicate_items", "ON",
                    "INDEX_DEDUPLICATE_ITEMS", ON_OFF,
                    "POSTGRESQL_INDEX_DEDUPLICATE_ITEMS", "INDEX_DEDUPLICATE_ITEMS");
            options += ", deduplicate_items = "
                    + (deduplicate.startsWith("<") ? deduplicate : deduplicate.toLowerCase(Locale.ROOT));
        } else {
            lines.add("-- B-tree deduplicate_items is access-method/workload specific; source/profile only.");
        }
        lines.add("WITH (" + options + ")");
        if (!activePlacementPresent) {
            lines.add(constraintIndex
                    ? "USING INDEX TABLESPACE <INDEX_TABLESPACE>"
                    : "TABLESPACE <INDEX_TABLESPACE>");
        }
        return PhysicalCommentBlocks.block("POSTGRESQL INDEX PHYSICAL OPTIONS", lines);
    }
}
