package com.behsazan.schemaforge.dialect;

import com.behsazan.schemaforge.domain.model.Column;

/**
 * Optional dialect contract for database-specific foreign-key column type compatibility.
 *
 * <p>Most canonical FK validation is DBMS-neutral. Dialects that impose stricter rules can
 * implement this contract so integrated deployment is blocked before SQL reaches the database.
 * Historical per-table generation is intentionally unaffected.</p>
 */
public interface ForeignKeyTypeCompatibilityPolicy {

    /** Returns a stable comparable SQL type signature for one FK column. */
    String foreignKeyComparableType(Column column);
}
