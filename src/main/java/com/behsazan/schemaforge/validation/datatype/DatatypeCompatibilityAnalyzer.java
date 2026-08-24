package com.behsazan.schemaforge.validation.datatype;

import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.dialect.db2zos.Db2ZosDialect;
import com.behsazan.schemaforge.dialect.db2zos.Db2ZosTypeMapper;
import com.behsazan.schemaforge.dialect.oracle.OracleDialect;
import com.behsazan.schemaforge.dialect.mysql.MySqlDialect;
import com.behsazan.schemaforge.dialect.mysql.MySqlTypeMapper;
import com.behsazan.schemaforge.dialect.postgresql.PostgreSqlDialect;
import com.behsazan.schemaforge.dialect.postgresql.PostgreSqlTypeMapper;
import com.behsazan.schemaforge.dialect.sqlserver.SqlServerDialect;
import com.behsazan.schemaforge.dialect.sqlserver.SqlServerTypeMapper;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.specification.validation.ValidationIssue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Reports target-database datatype mappings that are lossy, context-sensitive,
 * or impossible to perform without inventing source semantics.
 *
 * <p>The analyzer never changes the canonical datatype. It only makes the
 * dialect mapping decision visible to callers and generated SQL.</p>
 */
public final class DatatypeCompatibilityAnalyzer {
    private static final Set<String> EXACT_NUMERIC = Set.of("NUMBER", "NUMERIC", "DECIMAL", "DEC");
    private static final Set<String> ORACLE_TIMESTAMP = Set.of(
            "TIMESTAMP", "TIMESTAMP WITH TIME ZONE", "TIMESTAMP_WITH_TIME_ZONE",
            "TIMESTAMP WITH LOCAL TIME ZONE", "TIMESTAMP_WITH_LOCAL_TIME_ZONE");
    private static final Set<String> MYSQL_VARIABLE_CHARACTER = Set.of(
            "VARCHAR", "VARCHAR2", "NVARCHAR", "NVARCHAR2");
    private static final Set<String> MYSQL_FIXED_CHARACTER = Set.of(
            "CHAR", "NCHAR", "CHARACTER");
    private static final Set<String> MYSQL_BINARY = Set.of("RAW", "VARBINARY");
    private static final Set<String> MYSQL_TEMPORAL = Set.of("TIMESTAMP", "DATETIME", "TIME");
    private static final Set<String> MYSQL_TIMESTAMP_WITH_TIME_ZONE = Set.of(
            "TIMESTAMP WITH TIME ZONE", "TIMESTAMP_WITH_TIME_ZONE");
    private static final Set<String> MYSQL_TIMESTAMP_WITH_LOCAL_TIME_ZONE = Set.of(
            "TIMESTAMP WITH LOCAL TIME ZONE", "TIMESTAMP_WITH_LOCAL_TIME_ZONE");
    private static final Set<String> MYSQL_UNSUPPORTED_ROWID = Set.of("ROWID", "UROWID");
    private static final Set<String> MYSQL_SUPPORTED_SIMPLE = Set.of(
            "INT", "INTEGER", "BINARY_INTEGER", "PLS_INTEGER", "BIGINT", "SMALLINT", "TINYINT",
            "BINARY_DOUBLE", "DOUBLE", "DOUBLE PRECISION", "BINARY_FLOAT", "FLOAT", "REAL",
            "CLOB", "NCLOB", "LONG", "TEXT", "BLOB", "LONG RAW", "LONG_RAW",
            "DATE", "BOOLEAN", "BOOL", "JSON", "XMLTYPE", "XML");
    private static final Set<String> SQLSERVER_TEMPORAL = Set.of(
            "TIMESTAMP", "TIMESTAMP WITH TIME ZONE", "TIMESTAMP_WITH_TIME_ZONE",
            "TIMESTAMP WITH LOCAL TIME ZONE", "TIMESTAMP_WITH_LOCAL_TIME_ZONE",
            "DATETIME2", "DATETIMEOFFSET", "TIME");

    public DatatypeCompatibilityAssessment analyze(DatabaseSchema schema, Dialect dialect) {
        Objects.requireNonNull(schema, "schema must not be null");
        Objects.requireNonNull(dialect, "dialect must not be null");

        List<ValidationIssue> issues = new ArrayList<>();
        for (var table : schema.tables()) {
            for (var column : table.columns()) {
                DataType type = column.dataType();
                String sourceName = type.name().normalized().toUpperCase(Locale.ROOT);
                String path = "tables." + table.qualifiedName().name().value()
                        + ".columns." + column.name().value();

                if (dialect instanceof OracleDialect) {
                    analyzeOracle(type, sourceName, path, issues);
                } else if (dialect instanceof PostgreSqlDialect) {
                    analyzePostgreSql(type, sourceName, path, issues);
                } else if (dialect instanceof SqlServerDialect) {
                    analyzeSqlServer(type, sourceName, path, issues);
                } else if (dialect instanceof MySqlDialect) {
                    analyzeMySql(type, sourceName, path, issues);
                } else if (dialect instanceof Db2ZosDialect) {
                    analyzeDb2Zos(type, sourceName, path, issues);
                }
            }
        }
        return new DatatypeCompatibilityAssessment(issues);
    }

    private void analyzeOracle(
            DataType type, String sourceName, String path, List<ValidationIssue> issues) {
        if (EXACT_NUMERIC.contains(sourceName)
                && type.precision() != null
                && type.precision() > OracleDialect.MAX_NUMBER_PRECISION) {
            error(issues, "ORACLE_DECIMAL_PRECISION_UNSUPPORTED", path,
                    "Canonical " + renderType(sourceName, type)
                            + " exceeds Oracle NUMBER precision " + OracleDialect.MAX_NUMBER_PRECISION
                            + "; no target precision is invented or clamped.");
        }
        if (EXACT_NUMERIC.contains(sourceName)
                && type.scale() != null
                && type.scale() > OracleDialect.MAX_NUMBER_SCALE) {
            warning(issues, "ORACLE_DECIMAL_SCALE_BOUNDED", path,
                    "Canonical " + renderType(sourceName, type)
                            + " exceeds the current Oracle NUMBER scale bound " + OracleDialect.MAX_NUMBER_SCALE
                            + "; the source value requires review.");
        }
        if (ORACLE_TIMESTAMP.contains(sourceName)
                && type.precision() != null
                && type.precision() > OracleDialect.MAX_TIMESTAMP_PRECISION) {
            warning(issues, "ORACLE_TEMPORAL_PRECISION_BOUNDED", path,
                    "Canonical " + sourceName + "(" + type.precision() + ") exceeds Oracle TIMESTAMP precision "
                            + OracleDialect.MAX_TIMESTAMP_PRECISION + "; the current dialect renders precision "
                            + OracleDialect.MAX_TIMESTAMP_PRECISION + ".");
        }
        addOracleLargeObjectFallback(type, sourceName, path, issues);
    }

    private void analyzePostgreSql(
            DataType type, String sourceName, String path, List<ValidationIssue> issues) {
        if (ORACLE_TIMESTAMP.contains(sourceName)
                && type.precision() != null
                && type.precision() > PostgreSqlTypeMapper.MAX_TEMPORAL_PRECISION) {
            warning(issues, "POSTGRESQL_TEMPORAL_PRECISION_BOUNDED", path,
                    "Canonical " + sourceName + "(" + type.precision()
                            + ") exceeds PostgreSQL temporal precision "
                            + PostgreSqlTypeMapper.MAX_TEMPORAL_PRECISION
                            + "; the current dialect renders precision "
                            + PostgreSqlTypeMapper.MAX_TEMPORAL_PRECISION + ".");
        }
    }

    private void analyzeSqlServer(
            DataType type, String sourceName, String path, List<ValidationIssue> issues) {
        if (EXACT_NUMERIC.contains(sourceName) && type.precision() == null) {
            error(issues, "SQLSERVER_EXACT_NUMERIC_PRECISION_REQUIRED", path,
                    "Canonical " + sourceName
                            + " has no explicit precision/scale; SQL Server DECIMAL/NUMERIC requires a fixed "
                            + "precision and scale, so no lossless target mapping is selected.");
        }
        if (EXACT_NUMERIC.contains(sourceName)
                && type.precision() != null
                && type.precision() > SqlServerTypeMapper.MAX_DECIMAL_PRECISION) {
            error(issues, "SQLSERVER_DECIMAL_PRECISION_UNSUPPORTED", path,
                    "Canonical " + renderType(sourceName, type)
                            + " exceeds SQL Server DECIMAL precision "
                            + SqlServerTypeMapper.MAX_DECIMAL_PRECISION
                            + "; no target precision is invented or clamped.");
        }
        if (SQLSERVER_TEMPORAL.contains(sourceName)
                && type.precision() != null
                && type.precision() > SqlServerTypeMapper.MAX_TEMPORAL_PRECISION) {
            warning(issues, "SQLSERVER_TEMPORAL_PRECISION_BOUNDED", path,
                    "Canonical " + sourceName + "(" + type.precision()
                            + ") exceeds SQL Server temporal precision "
                            + SqlServerTypeMapper.MAX_TEMPORAL_PRECISION
                            + "; the current dialect renders precision "
                            + SqlServerTypeMapper.MAX_TEMPORAL_PRECISION + ".");
        }
    }


    private void analyzeMySql(
            DataType type, String sourceName, String path, List<ValidationIssue> issues) {
        if (EXACT_NUMERIC.contains(sourceName)) {
            if (type.precision() == null) {
                error(issues, "MYSQL_EXACT_NUMERIC_PRECISION_REQUIRED", path,
                        "Canonical " + sourceName
                                + " has no explicit precision/scale; MySQL DECIMAL/NUMERIC has fixed precision "
                                + "and scale semantics, so no lossless target mapping is selected.");
                return;
            }
            if (type.precision() > MySqlTypeMapper.MAX_DECIMAL_PRECISION) {
                error(issues, "MYSQL_DECIMAL_PRECISION_UNSUPPORTED", path,
                        "Canonical " + renderType(sourceName, type)
                                + " exceeds MySQL DECIMAL precision "
                                + MySqlTypeMapper.MAX_DECIMAL_PRECISION
                                + "; no target precision is invented or clamped.");
            }
            if (type.scale() != null && type.scale() > MySqlTypeMapper.MAX_DECIMAL_SCALE) {
                error(issues, "MYSQL_DECIMAL_SCALE_UNSUPPORTED", path,
                        "Canonical " + renderType(sourceName, type)
                                + " exceeds MySQL DECIMAL scale " + MySqlTypeMapper.MAX_DECIMAL_SCALE
                                + "; no target scale is invented or clamped.");
            }
            return;
        }
        if ((MYSQL_VARIABLE_CHARACTER.contains(sourceName) || MYSQL_FIXED_CHARACTER.contains(sourceName))
                && type.length() == null) {
            error(issues, "MYSQL_CHARACTER_LENGTH_REQUIRED", path,
                    "Canonical " + sourceName
                            + " has no explicit length; SchemaForge does not invent a MySQL character length.");
            return;
        }
        if (MYSQL_BINARY.contains(sourceName) && type.length() == null) {
            error(issues, "MYSQL_BINARY_LENGTH_REQUIRED", path,
                    "Canonical " + sourceName
                            + " has no explicit length; SchemaForge does not invent a MySQL VARBINARY length.");
            return;
        }
        if (MYSQL_TEMPORAL.contains(sourceName)
                && type.precision() != null
                && type.precision() > MySqlTypeMapper.MAX_TEMPORAL_PRECISION) {
            error(issues, "MYSQL_TEMPORAL_PRECISION_UNSUPPORTED", path,
                    "Canonical " + sourceName + "(" + type.precision() + ") exceeds MySQL temporal precision "
                            + MySqlTypeMapper.MAX_TEMPORAL_PRECISION
                            + "; no target precision is invented or clamped.");
            return;
        }
        if (MYSQL_TIMESTAMP_WITH_TIME_ZONE.contains(sourceName)) {
            warning(issues, "MYSQL_TIMEZONE_TIMESTAMP_TEXT_ADAPTATION", path,
                    "Canonical " + sourceName
                            + " has no lossless native MySQL temporal mapping; generated MySQL DDL uses "
                            + "the explicit MYSQL-TSTZ-TEXT-001 VARCHAR(128) portability envelope. "
                            + "Migration/application values must preserve an explicit offset or region.");
            return;
        }
        if (MYSQL_TIMESTAMP_WITH_LOCAL_TIME_ZONE.contains(sourceName)) {
            error(issues, "MYSQL_LOCAL_TIMEZONE_TIMESTAMP_UNSUPPORTED", path,
                    "Canonical " + sourceName
                            + " has session-local timezone semantics for which the current MySQL logical dialect "
                            + "has no safe portability adaptation.");
            return;
        }
        if (MYSQL_UNSUPPORTED_ROWID.contains(sourceName)) {
            error(issues, "MYSQL_ROWID_UNSUPPORTED", path,
                    "Canonical " + sourceName
                            + " carries Oracle row locator semantics for which MySQL has no lossless logical mapping.");
            return;
        }
        if (MYSQL_VARIABLE_CHARACTER.contains(sourceName)
                || MYSQL_FIXED_CHARACTER.contains(sourceName)
                || MYSQL_BINARY.contains(sourceName)
                || MYSQL_TEMPORAL.contains(sourceName)
                || MYSQL_SUPPORTED_SIMPLE.contains(sourceName)) {
            return;
        }
        error(issues, "MYSQL_DATATYPE_UNSUPPORTED", path,
                "Canonical datatype " + renderType(sourceName, type)
                        + " is outside the current MySQL logical datatype coverage.");
    }

    private void analyzeDb2Zos(
            DataType type, String sourceName, String path, List<ValidationIssue> issues) {
        if (EXACT_NUMERIC.contains(sourceName)) {
            if (type.precision() == null) {
                error(issues, "DB2_NUMBER_PRECISION_REQUIRED", path,
                        "Canonical " + sourceName
                                + " has no explicit precision; Db2 for z/OS cannot map it losslessly to DECIMAL.");
            } else if (type.precision() > Db2ZosTypeMapper.MAX_DECIMAL_PRECISION) {
                error(issues, "DB2_DECIMAL_PRECISION_UNSUPPORTED", path,
                        "Canonical " + renderType(sourceName, type)
                                + " exceeds Db2 for z/OS DECIMAL precision "
                                + Db2ZosTypeMapper.MAX_DECIMAL_PRECISION + ".");
            }
        }
        if (ORACLE_TIMESTAMP.contains(sourceName)
                && type.precision() != null
                && type.precision() > Db2ZosTypeMapper.MAX_TIMESTAMP_PRECISION) {
            error(issues, "DB2_TEMPORAL_PRECISION_UNSUPPORTED", path,
                    "Canonical " + sourceName + "(" + type.precision()
                            + ") exceeds Db2 for z/OS TIMESTAMP precision "
                            + Db2ZosTypeMapper.MAX_TIMESTAMP_PRECISION
                            + "; no target precision is invented.");
        }
    }

    private void addOracleLargeObjectFallback(
            DataType type, String sourceName, String path, List<ValidationIssue> issues) {
        if (type.length() == null) return;
        String target = null;
        int limit = 0;
        if ((sourceName.equals("VARCHAR") || sourceName.equals("VARCHAR2"))
                && type.length() > OracleDialect.MAX_VARCHAR2_STANDARD_LENGTH) {
            target = "CLOB";
            limit = OracleDialect.MAX_VARCHAR2_STANDARD_LENGTH;
        } else if ((sourceName.equals("NVARCHAR") || sourceName.equals("NVARCHAR2"))
                && type.length() > OracleDialect.MAX_NVARCHAR2_STANDARD_LENGTH) {
            target = "NCLOB";
            limit = OracleDialect.MAX_NVARCHAR2_STANDARD_LENGTH;
        } else if ((sourceName.equals("CHAR") || sourceName.equals("CHARACTER"))
                && type.length() > OracleDialect.MAX_CHAR_STANDARD_LENGTH) {
            target = "CLOB";
            limit = OracleDialect.MAX_CHAR_STANDARD_LENGTH;
        } else if (sourceName.equals("NCHAR")
                && type.length() > OracleDialect.MAX_NVARCHAR2_STANDARD_LENGTH) {
            target = "NCLOB";
            limit = OracleDialect.MAX_NVARCHAR2_STANDARD_LENGTH;
        } else if (sourceName.equals("RAW")
                && type.length() > OracleDialect.MAX_RAW_STANDARD_LENGTH) {
            target = "BLOB";
            limit = OracleDialect.MAX_RAW_STANDARD_LENGTH;
        }
        if (target != null) {
            warning(issues, "ORACLE_LARGE_OBJECT_FALLBACK", path,
                    "Canonical " + sourceName + "(" + type.length()
                            + ") exceeds the current conservative Oracle standard-length boundary "
                            + limit + " and is rendered as " + target
                            + "; this datatype-class change requires review.");
        }
    }

    private static void warning(
            List<ValidationIssue> issues, String code, String path, String message) {
        issues.add(new ValidationIssue("WARNING", code, path, message));
    }

    private static void error(
            List<ValidationIssue> issues, String code, String path, String message) {
        issues.add(new ValidationIssue("ERROR", code, path, message));
    }

    private static String renderType(String sourceName, DataType type) {
        if (type.precision() == null) return sourceName;
        return sourceName + "(" + type.precision()
                + (type.scale() == null ? "" : "," + type.scale()) + ")";
    }
}
