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
}
