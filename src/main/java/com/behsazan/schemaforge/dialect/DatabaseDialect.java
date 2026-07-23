package com.behsazan.schemaforge.dialect;

import com.behsazan.schemaforge.model.ColumnDefinition;
import com.behsazan.schemaforge.model.DatabaseProduct;

public interface DatabaseDialect {
    DatabaseProduct product();
    String normalizeIdentifier(String identifier);
    String sqlType(ColumnDefinition column);
    String quoteIdentifier(String identifier);
}
