package com.behsazan.schemaforge.physical.mysql;

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

/** MySQL physical table/index candidates for the Phase-1 DBA review contract. */
public final class MySqlPhysicalRenderer implements PhysicalCommentRenderer {
    private static final Set<String> ROW_FORMATS = Set.of(
            "DEFAULT", "DYNAMIC", "FIXED", "COMPRESSED", "REDUNDANT", "COMPACT");
    private static final Set<String> INDEX_TYPES = Set.of("BTREE", "HASH", "FULLTEXT", "RTREE");

    @Override
    public String tableOptions(Table table, boolean activePlacementPresent) {
        List<String> lines = new ArrayList<>();

        var engine = PhysicalSourceOptions.find(table, "MYSQL_ENGINE", "ENGINE");
        if (engine.isPresent()) {
            PhysicalSourceOptions.addSourceRetained(lines, "MYSQL_ENGINE", engine.get());
            lines.add("ENGINE=" + engine.get());
        } else {
            lines.add("ENGINE=InnoDB");
            lines.add("-- InnoDB is the MySQL 8.4 default storage engine; keep source/profile evidence authoritative when present.");
        }

        var collation = PhysicalSourceOptions.find(table, "MYSQL_COLLATION", "TABLE_COLLATION", "COLLATION");
        if (collation.isPresent()) {
            PhysicalSourceOptions.addSourceRetained(lines, "MYSQL_COLLATION", collation.get());
            lines.add("DEFAULT COLLATE=" + collation.get());
        } else {
            lines.add("DEFAULT COLLATE=<TABLE_COLLATION>");
            lines.add("-- Collation is environment/schema-policy dependent and is never invented by SchemaForge.");
        }

        var rowFormat = PhysicalSourceOptions.find(table, "MYSQL_ROW_FORMAT", "ROW_FORMAT");
        if (rowFormat.isPresent()) {
            String raw = rowFormat.get();
            String normalized = raw.trim().toUpperCase(Locale.ROOT);
            if (ROW_FORMATS.contains(normalized)) {
                PhysicalSourceOptions.addSourceRetained(lines, "MYSQL_ROW_FORMAT", raw);
                lines.add("ROW_FORMAT=" + normalized);
            } else {
                PhysicalSourceOptions.addSourceIssue(lines, "MYSQL",
                        "MYSQL_ROW_FORMAT=" + raw + " is not a supported MySQL table ROW_FORMAT; source value was not normalized.");
                lines.add("ROW_FORMAT=<ROW_FORMAT>");
            }
        } else {
            lines.add("ROW_FORMAT=DYNAMIC");
            lines.add("-- DYNAMIC is the InnoDB default row format in MySQL 8.4; review if ENGINE is not InnoDB.");
        }

        if (!activePlacementPresent) {
            lines.add("TABLESPACE <GENERAL_TABLESPACE>");
            lines.add("-- Optional InnoDB general tablespace; omit for ordinary file-per-table deployment.");
        }
        return PhysicalCommentBlocks.block("MYSQL TABLE PHYSICAL OPTIONS", lines);
    }

    @Override
    public String indexOptions(Table table, List<Identifier> keyColumns, boolean activePlacementPresent) {
        return renderIndexOptions(table, null);
    }

    @Override
    public String indexOptions(Table table, Index index, List<Identifier> keyColumns, boolean activePlacementPresent) {
        return renderIndexOptions(table, index);
    }

    @Override
    public String indexOptions(Table table, List<Identifier> keyColumns, boolean activePlacementPresent, boolean uniqueIndex) {
        return renderIndexOptions(table, null);
    }

    @Override
    public String indexOptions(Table table, Index index, List<Identifier> keyColumns, boolean activePlacementPresent, boolean uniqueIndex) {
        return renderIndexOptions(table, index);
    }

    @Override
    public String constraintIndexOptions(Table table, List<Identifier> keyColumns, boolean activePlacementPresent) {
        return renderIndexOptions(table, null);
    }

    @Override
    public String constraintIndexOptions(Table table, Index index, List<Identifier> keyColumns, boolean activePlacementPresent) {
        return renderIndexOptions(table, index);
    }

    private String renderIndexOptions(Table table, Index index) {
        List<String> lines = new ArrayList<>();
        var type = PhysicalSourceOptions.find(index, table, "MYSQL_INDEX_TYPE", "INDEX_TYPE", "INDEX_ACCESS_METHOD");
        if (type.isPresent()) {
            String raw = type.get();
            String normalized = raw.trim().toUpperCase(Locale.ROOT);
            if (INDEX_TYPES.contains(normalized)) {
                PhysicalSourceOptions.addSourceRetained(lines, "MYSQL_INDEX_TYPE", raw);
                lines.add("USING " + normalized);
                if (!Set.of("BTREE", "HASH").contains(normalized)) {
                    lines.add("-- FULLTEXT/RTREE are specialized index families; verify canonical index semantics before activation.");
                }
            } else {
                PhysicalSourceOptions.addSourceIssue(lines, "MYSQL",
                        "MYSQL_INDEX_TYPE=" + raw + " is not a recognized MySQL index type; source value was not normalized.");
                lines.add("USING <INDEX_TYPE>");
            }
        } else {
            lines.add("USING BTREE");
            lines.add("-- BTREE is the normal access method for InnoDB PRIMARY/UNIQUE/INDEX objects.");
        }
        lines.add("-- MySQL does not provide independent per-index TABLESPACE placement in CREATE INDEX.");
        return PhysicalCommentBlocks.block("MYSQL INDEX PHYSICAL OPTIONS", lines);
    }
}
