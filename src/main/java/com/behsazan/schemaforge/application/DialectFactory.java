package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import com.behsazan.schemaforge.dialect.oracle.OracleDialect;
import com.behsazan.schemaforge.dialect.postgresql.PostgreSqlDialect;

import java.util.Objects;

/** Creates a DDL dialect for the requested database platform. */
public final class DialectFactory {
    private DialectFactory() {
    }

    public static Dialect create(DatabasePlatform platform) {
        Objects.requireNonNull(platform, "platform must not be null");
        return switch (platform) {
            case ORACLE -> new OracleDialect();
            case POSTGRESQL -> new PostgreSqlDialect(resolveNumericMappingStrategy());
        };
    }

    private static NumericMappingStrategy resolveNumericMappingStrategy() {
        String configured = System.getProperty("schemaforge.numeric-mapping.strategy");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("SCHEMAFORGE_NUMERIC_MAPPING_STRATEGY");
        }
        return NumericMappingStrategy.parse(configured);
    }
}

