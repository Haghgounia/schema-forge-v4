package com.behsazan.schemaforge.physical.mariadb;

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
import java.util.regex.Pattern;

/** MariaDB physical table/index candidates for the Phase-1 DBA review contract. */
public final class MariaDbPhysicalRenderer implements PhysicalCommentRenderer {
    private static final Set<String> INNODB_ROW_FORMATS = Set.of(
            "DEFAULT", "DYNAMIC", "COMPRESSED", "REDUNDANT", "COMPACT");
    private static final Set<String> INDEX_TYPES = Set.of("BTREE", "HASH", "RTREE");
    private static final Pattern SAFE_OPTION_TOKEN = Pattern.compile("[A-Za-z0-9_$-]+");

    @Override
    public String tableOptions(Table table, boolean activePlacementPresent) {
        List<String> lines = new ArrayList<>();

        var engine = PhysicalSourceOptions.find(table, "MARIADB_ENGINE");
        if (engine.isPresent()) {
            String raw = safe(engine.get());
            if (SAFE_OPTION_TOKEN.matcher(raw).matches()) {
                PhysicalSourceOptions.addSourceRetained(lines, "MARIADB_ENGINE", raw);
                lines.add("ENGINE=" + raw);
                if (!raw.equalsIgnoreCase("InnoDB")) {
                    PhysicalSourceOptions.addSourceReview(lines, "MARIADB",
                            "ENGINE=" + raw + " is source evidence, but SchemaForge FK/AUTO_INCREMENT "
                                    + "coverage is validated against InnoDB semantics; review before activation.");
                }
            } else {
                PhysicalSourceOptions.addSourceIssue(lines, "MARIADB",
                        "MARIADB_ENGINE contains characters outside the safe option-token grammar; "
                                + "source value was not activated.");
                lines.add("ENGINE=<MARIADB_ENGINE>");
            }
        } else {
            lines.add("ENGINE=InnoDB");
            lines.add("-- InnoDB is the SchemaForge MariaDB transactional baseline for FK and AUTO_INCREMENT semantics.");
        }

        var charset = PhysicalSourceOptions.find(table, "MARIADB_CHARACTER_SET", "MARIADB_CHARSET");
        if (charset.isPresent()) {
            String raw = safe(charset.get());
            if (SAFE_OPTION_TOKEN.matcher(raw).matches()) {
                PhysicalSourceOptions.addSourceRetained(lines, "MARIADB_CHARACTER_SET", raw);
                lines.add("DEFAULT CHARACTER SET=" + raw);
            } else {
                PhysicalSourceOptions.addSourceIssue(lines, "MARIADB",
                        "MARIADB_CHARACTER_SET contains characters outside the safe option-token grammar; "
                                + "source value was not activated.");
                lines.add("DEFAULT CHARACTER SET=<MARIADB_CHARACTER_SET>");
            }
        } else {
            lines.add("DEFAULT CHARACTER SET=<DATABASE_OR_DEPLOYMENT_POLICY>");
            lines.add("-- Character set is deployment policy and is never invented by SchemaForge.");
        }

        var collation = PhysicalSourceOptions.find(table, "MARIADB_COLLATION");
        if (collation.isPresent()) {
            String raw = safe(collation.get());
            if (SAFE_OPTION_TOKEN.matcher(raw).matches()) {
                PhysicalSourceOptions.addSourceRetained(lines, "MARIADB_COLLATION", raw);
                lines.add("DEFAULT COLLATE=" + raw);
            } else {
                PhysicalSourceOptions.addSourceIssue(lines, "MARIADB",
                        "MARIADB_COLLATION contains characters outside the safe option-token grammar; "
                                + "source value was not activated.");
                lines.add("DEFAULT COLLATE=<MARIADB_COLLATION>");
            }
        } else {
            lines.add("DEFAULT COLLATE=<DATABASE_OR_DEPLOYMENT_POLICY>");
            lines.add("-- Collation is deployment policy and is never invented by SchemaForge.");
        }

        var rowFormat = PhysicalSourceOptions.find(table, "MARIADB_ROW_FORMAT");
        if (rowFormat.isPresent()) {
            String raw = safe(rowFormat.get());
            String normalized = raw.trim().toUpperCase(Locale.ROOT);
            if (INNODB_ROW_FORMATS.contains(normalized)) {
                PhysicalSourceOptions.addSourceRetained(lines, "MARIADB_ROW_FORMAT", raw);
                lines.add("ROW_FORMAT=" + normalized);
            } else {
                PhysicalSourceOptions.addSourceIssue(lines, "MARIADB",
                        "MARIADB_ROW_FORMAT=" + raw + " is not in the InnoDB row-format contract "
                                + INNODB_ROW_FORMATS + "; source value was not normalized.");
                lines.add("ROW_FORMAT=<MARIADB_INNODB_ROW_FORMAT>");
            }
        } else {
            lines.add("ROW_FORMAT=DYNAMIC");
            lines.add("-- DYNAMIC is the conservative InnoDB row-format candidate for MariaDB review.");
        }

        var tablespace = PhysicalSourceOptions.find(table, "MARIADB_TABLESPACE");
        if (tablespace.isPresent()) {
            PhysicalSourceOptions.addSourceReview(lines, "MARIADB",
                    "MARIADB_TABLESPACE=" + safe(tablespace.get())
                            + " was not activated. MariaDB does not provide MySQL-style CREATE TABLESPACE "
                            + "provisioning for InnoDB; placement remains DBA-controlled.");
        }
        if (activePlacementPresent) {
            PhysicalSourceOptions.addSourceIssue(lines, "MARIADB",
                    "An active TABLESPACE clause reached the MariaDB physical renderer unexpectedly; "
                            + "SchemaForge M4 keeps InnoDB tablespace placement non-executable.");
        } else {
            lines.add("-- No executable MariaDB TABLESPACE placement is emitted by SchemaForge M4.");
        }
        return PhysicalCommentBlocks.block("MARIADB TABLE PHYSICAL OPTIONS", lines);
    }

    @Override
    public String indexOptions(Table table, List<Identifier> keyColumns, boolean activePlacementPresent) {
        return renderIndexOptions(table, null, activePlacementPresent);
    }

    @Override
    public String indexOptions(
            Table table, Index index, List<Identifier> keyColumns, boolean activePlacementPresent) {
        return renderIndexOptions(table, index, activePlacementPresent);
    }

    @Override
    public String indexOptions(
            Table table, List<Identifier> keyColumns, boolean activePlacementPresent, boolean uniqueIndex) {
        return renderIndexOptions(table, null, activePlacementPresent);
    }

    @Override
    public String indexOptions(
            Table table, Index index, List<Identifier> keyColumns,
            boolean activePlacementPresent, boolean uniqueIndex) {
        return renderIndexOptions(table, index, activePlacementPresent);
    }

    @Override
    public String constraintIndexOptions(
            Table table, List<Identifier> keyColumns, boolean activePlacementPresent) {
        return renderIndexOptions(table, null, activePlacementPresent);
    }

    @Override
    public String constraintIndexOptions(
            Table table, Index index, List<Identifier> keyColumns, boolean activePlacementPresent) {
        return renderIndexOptions(table, index, activePlacementPresent);
    }

    private String renderIndexOptions(Table table, Index index, boolean activePlacementPresent) {
        List<String> lines = new ArrayList<>();
        var type = PhysicalSourceOptions.find(index, table, "MARIADB_INDEX_TYPE");
        if (type.isPresent()) {
            String raw = safe(type.get());
            String normalized = raw.trim().toUpperCase(Locale.ROOT);
            if (INDEX_TYPES.contains(normalized)) {
                PhysicalSourceOptions.addSourceRetained(lines, "MARIADB_INDEX_TYPE", raw);
                lines.add("USING " + normalized);
                if (normalized.equals("RTREE")) {
                    PhysicalSourceOptions.addSourceReview(lines, "MARIADB",
                            "RTREE is specialized spatial-index evidence; verify canonical spatial semantics "
                                    + "before activating this physical candidate.");
                }
            } else {
                PhysicalSourceOptions.addSourceIssue(lines, "MARIADB",
                        "MARIADB_INDEX_TYPE=" + raw + " is not one of " + INDEX_TYPES
                                + "; source value was not normalized.");
                lines.add("USING <MARIADB_INDEX_TYPE>");
            }
        } else {
            lines.add("USING BTREE");
            lines.add("-- BTREE is the normal MariaDB/InnoDB access-method candidate for relational keys and indexes.");
        }
        if (activePlacementPresent) {
            PhysicalSourceOptions.addSourceIssue(lines, "MARIADB",
                    "Independent per-index TABLESPACE placement is not emitted by SchemaForge M4.");
        }
        lines.add("-- MariaDB CREATE INDEX has no SchemaForge-managed independent tablespace placement.");
        return PhysicalCommentBlocks.block("MARIADB INDEX PHYSICAL OPTIONS", lines);
    }

    private String safe(String raw) {
        return raw == null ? "" : raw.replace("*/", "* /")
                .replace('\r', ' ').replace('\n', ' ').trim();
    }
}
