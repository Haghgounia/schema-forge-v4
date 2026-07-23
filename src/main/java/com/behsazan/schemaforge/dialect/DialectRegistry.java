package com.behsazan.schemaforge.dialect;

import com.behsazan.schemaforge.model.DatabaseProduct;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class DialectRegistry {
    private final Map<DatabaseProduct, DatabaseDialect> dialects;

    public DialectRegistry(List<DatabaseDialect> dialectList) {
        EnumMap<DatabaseProduct, DatabaseDialect> map = new EnumMap<>(DatabaseProduct.class);
        for (DatabaseDialect dialect : dialectList) {
            map.put(dialect.product(), dialect);
        }
        this.dialects = Map.copyOf(map);
    }

    public DatabaseDialect require(DatabaseProduct product) {
        DatabaseDialect dialect = dialects.get(product);
        if (dialect == null) {
            throw new IllegalArgumentException("Unsupported database product: " + product);
        }
        return dialect;
    }
}
