package com.behsazan.schemaforge.physical;

import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.Identifier;

import java.util.List;

/**
 * Renders non-executable, inline physical DDL candidates.
 *
 * <p>Existing/source-driven placement remains executable in the normal dialect
 * path. This renderer only contributes new physical guidance that a DBA may
 * review, edit and activate by removing the surrounding block comment.</p>
 */
public interface PhysicalCommentRenderer {

    String tableOptions(Table table, boolean activePlacementPresent);

    String indexOptions(Table table, List<Identifier> keyColumns, boolean activePlacementPresent);

    /**
     * Physical options for a standalone or explicitly created index when the
     * generator knows whether the index is UNIQUE. The default keeps backward
     * compatibility for renderers that do not need uniqueness context.
     */
    default String indexOptions(
            Table table, List<Identifier> keyColumns, boolean activePlacementPresent, boolean uniqueIndex) {
        return indexOptions(table, keyColumns, activePlacementPresent);
    }

    /**
     * Physical options for the backing index of a PRIMARY KEY / UNIQUE constraint.
     * Most dialects use the same syntax as CREATE INDEX; PostgreSQL needs
     * USING INDEX TABLESPACE instead of TABLESPACE for constraint placement.
     */
    default String constraintIndexOptions(
            Table table, List<Identifier> keyColumns, boolean activePlacementPresent) {
        return indexOptions(table, keyColumns, activePlacementPresent);
    }
}
