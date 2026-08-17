package com.behsazan.schemaforge.physical.postgresql;

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

/** PostgreSQL physical candidates backed only by documented defaults or explicit source/profile evidence. */
public final class PostgreSqlPhysicalRenderer implements PhysicalCommentRenderer {
    private static final Set<String> ON_OFF = Set.of("ON", "OFF");
    private static final Set<String> GIST_BUFFERING = Set.of("ON", "OFF", "AUTO");
    private static final Set<String> INDEX_METHODS = Set.of("BTREE", "HASH", "GIST", "SPGIST", "GIN", "BRIN");
    private static final Set<String> FILLFACTOR_METHODS = Set.of("BTREE", "HASH", "GIST", "SPGIST");

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

        PhysicalSourceOptions.find(table,
                "POSTGRESQL_TABLE_PARALLEL_WORKERS", "TABLE_PARALLEL_WORKERS", "PARALLEL_WORKERS")
                .ifPresentOrElse(raw -> {
                    try {
                        int value = Integer.parseInt(raw);
                        if (value < 0) {
                            PhysicalSourceOptions.addSourceIssue(lines, "POSTGRESQL",
                                    "PARALLEL_WORKERS=" + raw
                                            + " must be a non-negative integer; source value was not normalized.");
                            storageOptions.add("parallel_workers = <PARALLEL_WORKERS>");
                        } else {
                            PhysicalSourceOptions.addSourceRetained(lines, "POSTGRESQL_TABLE_PARALLEL_WORKERS", raw);
                            storageOptions.add("parallel_workers = " + value);
                        }
                    } catch (NumberFormatException exception) {
                        PhysicalSourceOptions.addSourceIssue(lines, "POSTGRESQL",
                                "PARALLEL_WORKERS=" + raw
                                        + " must be a non-negative integer; source value was not normalized.");
                        storageOptions.add("parallel_workers = <PARALLEL_WORKERS>");
                    }
                }, () -> lines.add("-- parallel_workers is relation/workload specific; when unset PostgreSQL derives it from relation size."));

        lines.add("WITH (" + String.join(", ", storageOptions) + ")");
        if (!activePlacementPresent) {
            lines.add("TABLESPACE <TABLE_TABLESPACE>");
        }
        lines.add("-- Column STORAGE/COMPRESSION and autovacuum settings are separate storage/operational policies and are not invented by this phase.");
        return PhysicalCommentBlocks.block("POSTGRESQL TABLE PHYSICAL OPTIONS", lines);
    }

    @Override
    public String indexOptions(Table table, List<Identifier> keyColumns, boolean activePlacementPresent) {
        return renderIndexOptions(table, null, activePlacementPresent, false);
    }

    @Override
    public String indexOptions(
            Table table, Index index, List<Identifier> keyColumns, boolean activePlacementPresent) {
        return renderIndexOptions(table, index, activePlacementPresent, false);
    }

    @Override
    public String indexOptions(
            Table table, Index index, List<Identifier> keyColumns,
            boolean activePlacementPresent, boolean uniqueIndex) {
        return renderIndexOptions(table, index, activePlacementPresent, false);
    }

    @Override
    public String constraintIndexOptions(Table table, List<Identifier> keyColumns, boolean activePlacementPresent) {
        return renderIndexOptions(table, null, activePlacementPresent, true);
    }

    @Override
    public String constraintIndexOptions(
            Table table, Index index, List<Identifier> keyColumns, boolean activePlacementPresent) {
        return renderIndexOptions(table, index, activePlacementPresent, true);
    }

    private String renderIndexOptions(
            Table table, Index index, boolean activePlacementPresent, boolean constraintIndex) {
        List<String> lines = new ArrayList<>();
        Optional<String> method = indexMethod(lines, index, table);
        List<String> options = new ArrayList<>();

        if (method.isEmpty() || method.filter("BTREE"::equals).isPresent()) {
            options.add(PhysicalSourceOptions.integerClause(
                    lines, index, table, "POSTGRESQL", "fillfactor =", 90, 10, 100, "INDEX_FILLFACTOR",
                    "POSTGRESQL_INDEX_FILLFACTOR", "INDEX_FILLFACTOR"));
        } else if (method.filter(FILLFACTOR_METHODS::contains).isPresent()) {
            PhysicalSourceOptions.find(index, table, "POSTGRESQL_INDEX_FILLFACTOR", "INDEX_FILLFACTOR")
                    .ifPresentOrElse(raw -> addPositiveRangeOption(lines, options, "fillfactor", raw, 10, 100,
                                    "INDEX_FILLFACTOR", "POSTGRESQL_INDEX_FILLFACTOR"),
                            () -> lines.add("-- fillfactor default varies by this index access method; source/profile only."));
        } else if (PhysicalSourceOptions.find(index, table,
                "POSTGRESQL_INDEX_FILLFACTOR", "INDEX_FILLFACTOR").isPresent()) {
            String raw = PhysicalSourceOptions.find(index, table,
                    "POSTGRESQL_INDEX_FILLFACTOR", "INDEX_FILLFACTOR").orElseThrow();
            PhysicalSourceOptions.addSourceIssue(lines, "POSTGRESQL",
                    "INDEX_FILLFACTOR=" + raw + " is not supported by the explicit " + method.orElse("UNKNOWN")
                            + " access method; source value was not normalized.");
        }

        Optional<String> deduplicateRaw = PhysicalSourceOptions.find(index, table,
                "POSTGRESQL_INDEX_DEDUPLICATE_ITEMS", "INDEX_DEDUPLICATE_ITEMS");
        if (deduplicateRaw.isPresent()) {
            if (method.isPresent() && !"BTREE".equals(method.get())) {
                PhysicalSourceOptions.addSourceIssue(lines, "POSTGRESQL",
                        "INDEX_DEDUPLICATE_ITEMS=" + deduplicateRaw.get()
                                + " applies only to B-tree indexes, but explicit access method is " + method.get() + ".");
                options.add("deduplicate_items = <INDEX_DEDUPLICATE_ITEMS>");
            } else {
                String deduplicate = PhysicalSourceOptions.enumClause(
                        lines, index, table, "POSTGRESQL", "deduplicate_items", "ON",
                        "INDEX_DEDUPLICATE_ITEMS", ON_OFF,
                        "POSTGRESQL_INDEX_DEDUPLICATE_ITEMS", "INDEX_DEDUPLICATE_ITEMS");
                options.add("deduplicate_items = "
                        + (deduplicate.startsWith("<") ? deduplicate : deduplicate.toLowerCase(Locale.ROOT)));
            }
        } else if (method.isEmpty() || method.filter("BTREE"::equals).isPresent()) {
            lines.add("-- B-tree deduplicate_items is access-method/workload specific; source/profile only.");
        }

        addMethodSpecificOptions(lines, options, index, table, method);

        if (!options.isEmpty()) {
            lines.add("WITH (" + String.join(", ", options) + ")");
        }
        if (!activePlacementPresent) {
            lines.add(constraintIndex
                    ? "USING INDEX TABLESPACE <INDEX_TABLESPACE>"
                    : "TABLESPACE <INDEX_TABLESPACE>");
        }
        return PhysicalCommentBlocks.block("POSTGRESQL INDEX PHYSICAL OPTIONS", lines);
    }

    private Optional<String> indexMethod(List<String> lines, Index index, Table table) {
        Optional<String> raw = PhysicalSourceOptions.find(index, table,
                "POSTGRESQL_INDEX_METHOD", "INDEX_METHOD", "INDEX_ACCESS_METHOD");
        if (raw.isEmpty()) {
            lines.add("-- Index access method is unspecified; PostgreSQL defaults CREATE INDEX to B-tree unless source DDL says otherwise.");
            return Optional.empty();
        }
        String normalized = PhysicalSourceOptions.normalizedUpper(raw.get()).replace("-", "");
        if (!INDEX_METHODS.contains(normalized)) {
            PhysicalSourceOptions.addSourceIssue(lines, "POSTGRESQL",
                    "INDEX_METHOD=" + raw.get() + " is not one of " + INDEX_METHODS + "; source value was not normalized.");
            return Optional.empty();
        }
        PhysicalSourceOptions.addSourceRetained(lines, "POSTGRESQL_INDEX_METHOD", raw.get());
        lines.add("-- Index access method (source/profile): " + normalized.toLowerCase(Locale.ROOT));
        return Optional.of(normalized);
    }

    private void addMethodSpecificOptions(
            List<String> lines, List<String> options, Index index, Table table, Optional<String> method) {
        addMethodEnumOption(lines, options, index, table, method, "GIST", "buffering", GIST_BUFFERING,
                "POSTGRESQL_GIST_BUFFERING", "GIST_BUFFERING");
        addMethodEnumOption(lines, options, index, table, method, "GIN", "fastupdate", ON_OFF,
                "POSTGRESQL_GIN_FASTUPDATE", "GIN_FASTUPDATE");
        addMethodPositiveIntegerOption(lines, options, index, table, method, "GIN", "gin_pending_list_limit",
                "GIN_PENDING_LIST_LIMIT", "POSTGRESQL_GIN_PENDING_LIST_LIMIT", "GIN_PENDING_LIST_LIMIT");
        addMethodPositiveIntegerOption(lines, options, index, table, method, "BRIN", "pages_per_range",
                "BRIN_PAGES_PER_RANGE", "POSTGRESQL_BRIN_PAGES_PER_RANGE", "BRIN_PAGES_PER_RANGE");
        addMethodEnumOption(lines, options, index, table, method, "BRIN", "autosummarize", ON_OFF,
                "POSTGRESQL_BRIN_AUTOSUMMARIZE", "BRIN_AUTOSUMMARIZE");
    }

    private void addMethodEnumOption(
            List<String> lines, List<String> options, Index index, Table table, Optional<String> method,
            String requiredMethod, String optionName, Set<String> accepted, String... keys) {
        Optional<String> raw = PhysicalSourceOptions.find(index, table, keys);
        if (raw.isEmpty()) return;
        if (method.isPresent() && !requiredMethod.equals(method.get())) {
            PhysicalSourceOptions.addSourceIssue(lines, "POSTGRESQL",
                    keys[0] + "=" + raw.get() + " applies only to " + requiredMethod
                            + " indexes, but explicit access method is " + method.get() + ".");
            options.add(optionName + " = <" + keys[0].replace("POSTGRESQL_", "") + ">");
            return;
        }
        if (method.isEmpty()) {
            PhysicalSourceOptions.addSourceReview(lines, "POSTGRESQL",
                    optionName + " requires a " + requiredMethod + " index; access method was not explicitly captured.");
        }
        String normalized = PhysicalSourceOptions.normalizedUpper(raw.get());
        if (!accepted.contains(normalized)) {
            PhysicalSourceOptions.addSourceIssue(lines, "POSTGRESQL",
                    keys[0] + "=" + raw.get() + " is not one of " + accepted + "; source value was not normalized.");
            options.add(optionName + " = <" + keys[0].replace("POSTGRESQL_", "") + ">");
            return;
        }
        PhysicalSourceOptions.addSourceRetained(lines, keys[0], raw.get());
        options.add(optionName + " = " + normalized.toLowerCase(Locale.ROOT));
    }

    private void addMethodPositiveIntegerOption(
            List<String> lines, List<String> options, Index index, Table table, Optional<String> method,
            String requiredMethod, String optionName, String placeholder, String... keys) {
        Optional<String> raw = PhysicalSourceOptions.find(index, table, keys);
        if (raw.isEmpty()) return;
        if (method.isPresent() && !requiredMethod.equals(method.get())) {
            PhysicalSourceOptions.addSourceIssue(lines, "POSTGRESQL",
                    keys[0] + "=" + raw.get() + " applies only to " + requiredMethod
                            + " indexes, but explicit access method is " + method.get() + ".");
            options.add(optionName + " = <" + placeholder + ">");
            return;
        }
        if (method.isEmpty()) {
            PhysicalSourceOptions.addSourceReview(lines, "POSTGRESQL",
                    optionName + " requires a " + requiredMethod + " index; access method was not explicitly captured.");
        }
        try {
            int value = Integer.parseInt(raw.get());
            if (value > 0) {
                PhysicalSourceOptions.addSourceRetained(lines, keys[0], raw.get());
                options.add(optionName + " = " + value);
                return;
            }
        } catch (NumberFormatException ignored) {
            // surfaced below
        }
        PhysicalSourceOptions.addSourceIssue(lines, "POSTGRESQL",
                keys[0] + "=" + raw.get() + " must be a positive integer; source value was not normalized.");
        options.add(optionName + " = <" + placeholder + ">");
    }

    private void addPositiveRangeOption(
            List<String> lines, List<String> options, String optionName, String raw,
            int min, int max, String placeholder, String sourceKey) {
        try {
            int value = Integer.parseInt(raw);
            if (value >= min && value <= max) {
                PhysicalSourceOptions.addSourceRetained(lines, sourceKey, raw);
                options.add(optionName + " = " + value);
                return;
            }
        } catch (NumberFormatException ignored) {
            // surfaced below
        }
        PhysicalSourceOptions.addSourceIssue(lines, "POSTGRESQL",
                sourceKey + "=" + raw + " is outside the accepted " + min + ".." + max
                        + " integer range; source value was not normalized.");
        options.add(optionName + " = <" + placeholder + ">");
    }
}
