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
            String semantics = renderLengthSemantics(oracleName, type.lengthSemantics());
            return oracleName + "(" + type.length() + semantics + ")";
        }
        if (type.precision() != null) {
            if (type.scale() != null) {
                return oracleName + "(" + type.precision() + "," + type.scale() + ")";
            }
            return oracleName + "(" + type.precision() + ")";
        }
        return oracleName;
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
        return identifier.normalized();
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
