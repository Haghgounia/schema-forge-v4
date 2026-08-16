package com.behsazan.schemaforge.dialect.oracle;

import com.behsazan.schemaforge.dialect.Dialect;
import com.behsazan.schemaforge.dialect.DialectFeature;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.LengthSemantics;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Oracle-specific type and identifier rendering. */
public final class OracleDialect implements Dialect {
    static final int MAX_NUMBER_PRECISION = 38;
    static final int MAX_NUMBER_SCALE = 127;
    static final int MAX_TIMESTAMP_PRECISION = 9;
    static final int MAX_VARCHAR2_STANDARD_LENGTH = 4000;
    static final int MAX_NVARCHAR2_STANDARD_LENGTH = 2000;
    static final int MAX_CHAR_STANDARD_LENGTH = 2000;
    static final int MAX_RAW_STANDARD_LENGTH = 2000;
    private static final Set<DialectFeature> FEATURES = Set.of(
            DialectFeature.SEQUENCE,
            DialectFeature.IDENTITY_COLUMN,
            DialectFeature.GENERATED_COLUMN,
            DialectFeature.TABLE_COMMENT,
            DialectFeature.COLUMN_COMMENT,
            DialectFeature.GRANT,
            DialectFeature.EXPRESSION_INDEX,
            DialectFeature.DEFERRABLE_CONSTRAINT);

    @Override
    public Set<DialectFeature> supportedFeatures() {
        return FEATURES;
    }


    @Override
    public String sqlType(Column column) {
        Objects.requireNonNull(column, "column must not be null");
        DataType type = column.dataType();
        String name = type.name().normalized();

        String oracleName = switch (name) {
            case "VARCHAR", "VARCHAR2" -> "VARCHAR2";
            case "NVARCHAR", "NVARCHAR2" -> "NVARCHAR2";
            case "NUMERIC", "DECIMAL", "NUMBER" -> "NUMBER";
            case "INT", "INTEGER", "BIGINT", "SMALLINT" -> "NUMBER";
            case "DOUBLE", "DOUBLE PRECISION" -> "BINARY_DOUBLE";
            case "REAL" -> "BINARY_FLOAT";
            case "TIMESTAMP_WITH_TIME_ZONE" -> "TIMESTAMP WITH TIME ZONE";
            case "TIMESTAMP_WITH_LOCAL_TIME_ZONE" -> "TIMESTAMP WITH LOCAL TIME ZONE";
            case "LONG_RAW" -> "LONG RAW";
            default -> name.toUpperCase(Locale.ROOT);
        };

        if (type.length() != null) {
            String largeObjectType = largeObjectFallback(oracleName, type.length());
            if (largeObjectType != null) {
                return largeObjectType;
            }
            String semantics = renderLengthSemantics(oracleName, type.lengthSemantics());
            return oracleName + "(" + type.length() + semantics + ")";
        }
        if (type.precision() != null) {
            int precision = boundedPrecision(oracleName, type.precision());
            Integer scale = type.scale();
            if (oracleName.equals("NUMBER") && scale != null) {
                scale = Math.min(scale, MAX_NUMBER_SCALE);
            }
            if (oracleName.startsWith("TIMESTAMP")) {
                String suffix = oracleName.substring("TIMESTAMP".length());
                return "TIMESTAMP(" + precision + ")" + suffix;
            }
            if (scale != null) {
                return oracleName + "(" + precision + "," + scale + ")";
            }
            return oracleName + "(" + precision + ")";
        }
        return oracleName;
    }

    private String largeObjectFallback(String oracleName, int length) {
        if (oracleName.equals("VARCHAR2") && length > MAX_VARCHAR2_STANDARD_LENGTH) {
            return "CLOB";
        }
        if (oracleName.equals("NVARCHAR2") && length > MAX_NVARCHAR2_STANDARD_LENGTH) {
            return "NCLOB";
        }
        if (oracleName.equals("CHAR") && length > MAX_CHAR_STANDARD_LENGTH) {
            return "CLOB";
        }
        if (oracleName.equals("NCHAR") && length > MAX_NVARCHAR2_STANDARD_LENGTH) {
            return "NCLOB";
        }
        if (oracleName.equals("RAW") && length > MAX_RAW_STANDARD_LENGTH) {
            return "BLOB";
        }
        return null;
    }

    private int boundedPrecision(String oracleName, int precision) {
        if (oracleName.equals("NUMBER")) {
            return Math.min(precision, MAX_NUMBER_PRECISION);
        }
        if (oracleName.startsWith("TIMESTAMP")) {
            return Math.min(precision, MAX_TIMESTAMP_PRECISION);
        }
        return precision;
    }

    private String renderLengthSemantics(String oracleName, LengthSemantics semantics) {
        boolean characterType = oracleName.equals("VARCHAR2")
                || oracleName.equals("NVARCHAR2")
                || oracleName.equals("CHAR")
                || oracleName.equals("NCHAR");
        if (!characterType) {
            return "";
        }
        return switch (semantics) {
            case BYTE -> " BYTE";
            case CHAR -> " CHAR";
            case DEFAULT -> usesDefaultCharacterSemantics(oracleName) ? " CHAR" : "";
        };
    }

    private boolean usesDefaultCharacterSemantics(String oracleName) {
        return oracleName.equals("VARCHAR2") || oracleName.equals("CHAR");
    }

    @Override
    public String quote(Identifier identifier) {
        Objects.requireNonNull(identifier, "identifier must not be null");
        return OracleIdentifierPolicy.render(identifier);
    }

    @Override
    public String defaultClause(Column column) {
        Objects.requireNonNull(column, "column must not be null");
        OracleDefaultExpressionPolicy.Decision decision =
                OracleDefaultExpressionPolicy.evaluate(column);
        return decision.accepted() ? " DEFAULT " + decision.expression() : "";
    }

    @Override
    public String generatedColumnClause(Column column) {
        return " AS (" + column.generatedExpression() + ") VIRTUAL";
    }

    @Override
    public boolean identityUsesNamedSequence() {
        return true;
    }

    @Override
    public String sequenceCacheClause(Integer cacheSize) {
        return cacheSize == null || cacheSize == 0 ? " NOCACHE" : " CACHE " + cacheSize;
    }

    @Override
    public String sequenceCycleClause(boolean cycle) {
        return cycle ? " CYCLE" : " NOCYCLE";
    }

    @Override
    public String sequenceTail() {
        return " NOORDER";
    }

    @Override
    public String constraintValidationClause() {
        return " ENABLE";
    }

    @Override
    public String defaultTableTablespace(QualifiedName tableName) {
        return tableName.schemaName()
                .map(schema -> "TS_" + schema.normalized())
                .orElse(null);
    }

    @Override
    public String defaultIndexTablespace(QualifiedName tableName) {
        return tableName.schemaName()
                .map(schema -> "ITS_" + schema.normalized())
                .orElse(null);
    }

    @Override
    public String primaryKeyConstraint(String constraintName, String tableName, String columns,
                                       String qualifiedIndexName, String indexTablespace, boolean deferrable, boolean initiallyDeferred) {
        String nl = System.lineSeparator();
        return "CONSTRAINT " + constraintName + " PRIMARY KEY (" + columns + ")"
                + nl + "USING INDEX (CREATE UNIQUE INDEX " + qualifiedIndexName
                + " ON " + tableName + "(" + columns + ")"
                + indexTablespaceClause(indexTablespace) + ")"
                + deferrabilityClause(deferrable, initiallyDeferred);
    }

    @Override
    public String uniqueConstraint(String constraintName, String tableName, String columns,
                                   String qualifiedIndexName, String indexTablespace, boolean deferrable, boolean initiallyDeferred) {
        String nl = System.lineSeparator();
        return "ALTER TABLE " + tableName
                + " ADD CONSTRAINT " + constraintName + " UNIQUE(" + columns + ")"
                + nl + " USING INDEX (CREATE UNIQUE INDEX " + qualifiedIndexName
                + " ON " + tableName + "(" + columns + ")"
                + indexTablespaceClause(indexTablespace) + ")"
                + deferrabilityClause(deferrable, initiallyDeferred)
                + statementTerminator();
    }

    @Override
    public String primaryKeyConstraintWithPhysical(
            String constraintName, String tableName, String columns,
            String qualifiedIndexName, String indexTablespace, String physicalIndexComment,
            boolean deferrable, boolean initiallyDeferred) {
        String nl = System.lineSeparator();
        return "CONSTRAINT " + constraintName + " PRIMARY KEY (" + columns + ")"
                + nl + "USING INDEX (CREATE UNIQUE INDEX " + qualifiedIndexName
                + " ON " + tableName + "(" + columns + ")"
                + physicalIndexComment
                + indexTablespaceClause(indexTablespace) + ")"
                + deferrabilityClause(deferrable, initiallyDeferred);
    }

    @Override
    public String uniqueConstraintWithPhysical(
            String constraintName, String tableName, String columns,
            String qualifiedIndexName, String indexTablespace, String physicalIndexComment,
            boolean deferrable, boolean initiallyDeferred) {
        String nl = System.lineSeparator();
        return "ALTER TABLE " + tableName
                + " ADD CONSTRAINT " + constraintName + " UNIQUE(" + columns + ")"
                + nl + " USING INDEX (CREATE UNIQUE INDEX " + qualifiedIndexName
                + " ON " + tableName + "(" + columns + ")"
                + physicalIndexComment
                + indexTablespaceClause(indexTablespace) + ")"
                + deferrabilityClause(deferrable, initiallyDeferred)
                + statementTerminator();
    }

    @Override
    public String scriptPreamble(String source, String schemaName) {
        String nl = System.lineSeparator();
        return "PROMPT ==============================================================" + nl
                + "PROMPT SchemaForge Offline Oracle DDL" + nl
                + (source == null ? "" : "PROMPT Source File : " + source + nl)
                + "PROMPT Schema      : " + schemaName + nl
                + "PROMPT ==============================================================" + nl
                + "SET DEFINE OFF;" + nl
                + "WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK;";
    }

    @Override
    public String warningLine(String text) {
        return "PROMPT " + text;
    }

    @Override
    public String schemaBootstrapStatement(Identifier schemaName) {
        Objects.requireNonNull(schemaName, "schemaName must not be null");
        String schema = quote(schemaName);
        String nl = System.lineSeparator();
        return "PROMPT [SCHEMA BOOTSTRAP] Oracle schema " + schema
                + " is created by CREATE USER and must be provisioned by a DBA." + nl
                + "-- Secure provisioning template; intentionally not executed by SchemaForge:" + nl
                + "-- CREATE USER " + schema + " IDENTIFIED BY \"<SECURE_PASSWORD>\"" + nl
                + "--   DEFAULT TABLESPACE TS_" + schema + " TEMPORARY TABLESPACE TEMP" + nl
                + "--   QUOTA UNLIMITED ON TS_" + schema + nl
                + "--   QUOTA UNLIMITED ON ITS_" + schema + ";" + nl
                + "-- GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE VIEW," + nl
                + "--       CREATE PROCEDURE, CREATE TRIGGER TO " + schema + ";";
    }
}
