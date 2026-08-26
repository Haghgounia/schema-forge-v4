package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import com.behsazan.schemaforge.dialect.db2zos.Db2ZosDialect;
import com.behsazan.schemaforge.dialect.db2luw.Db2LuwDialect;
import com.behsazan.schemaforge.dialect.mysql.MySqlDialect;
import com.behsazan.schemaforge.dialect.oracle.OracleDialect;
import com.behsazan.schemaforge.dialect.postgresql.PostgreSqlDialect;
import com.behsazan.schemaforge.dialect.sqlserver.SqlServerDialect;

import java.util.Objects;

/** Creates a DDL dialect for the requested database platform. */
public final class DialectFactory {
    private DialectFactory() {
    }

    public static Dialect create(DatabasePlatform platform) {
        return create(platform, configuredNumericMappingStrategy());
    }

    public static Dialect create(DatabasePlatform platform, NumericMappingStrategy strategy) {
        Objects.requireNonNull(platform, "platform must not be null");
        Objects.requireNonNull(strategy, "strategy must not be null");
        return switch (platform) {
            case ORACLE -> new OracleDialect(strategy);
            case POSTGRESQL -> new PostgreSqlDialect(strategy);
            case DB2_ZOS -> new Db2ZosDialect(strategy);
            case DB2_LUW -> new Db2LuwDialect(strategy);
            case SQLSERVER -> new SqlServerDialect(strategy);
            case MYSQL -> new MySqlDialect(strategy);
        };
    }

    public static NumericMappingStrategy configuredNumericMappingStrategy() {
        String configured = System.getProperty("schemaforge.numeric-mapping.strategy");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("SCHEMAFORGE_NUMERIC_MAPPING_STRATEGY");
        }
        return NumericMappingStrategy.parse(configured);
    }
}

