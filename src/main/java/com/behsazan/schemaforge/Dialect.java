package com.behsazan.schemaforge;

import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.valueobject.Identifier;

/** DBMS-specific SQL rendering rules used by the generic DDL generator. */
public interface Dialect {
    String sqlType(Column column);

    String quote(Identifier identifier);

    default String name() {
        return getClass().getSimpleName().replace("Dialect", "");
    }

    default String statementTerminator() {
        return ";";
    }
}
