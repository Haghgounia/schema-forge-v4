package com.behsazan.schemaforge.metadata.repository;

import com.behsazan.schemaforge.domain.enums.IndexType;
import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.enums.SortDirection;
import com.behsazan.schemaforge.domain.model.CheckConstraint;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.IndexColumn;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.DefaultValue;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.LengthSemantics;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads Microsoft SQL Server metadata from the documented {@code sys.*} catalog views.
 *
 * <p>The repository is read-only and works within the database selected by the JDBC URL.</p>
 *
 * @since 4.3
 */
@Repository
@ConditionalOnProperty(prefix = "schemaforge.metadata.sqlserver", name = "enabled", havingValue = "true")
public class JdbcSqlServerMetadataRepository implements SqlServerMetadataRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcSqlServerMetadataRepository.class);

    static final String COLUMN_PROFILE_SQL = """
            SELECT C.name AS COLUMN_NAME,
                   UT.name AS USER_TYPE_NAME,
                   UTS.name AS USER_TYPE_SCHEMA,
                   ST.name AS SYSTEM_TYPE_NAME,
                   C.max_length AS MAX_LENGTH,
                   C.precision AS NUMERIC_PRECISION,
                   C.scale AS NUMERIC_SCALE,
                   COUNT(DISTINCT C.object_id) AS FREQUENCY
              FROM sys.columns C
              JOIN sys.objects O
                ON O.object_id = C.object_id
               AND O.type = 'U'
              JOIN sys.schemas OS
                ON OS.schema_id = O.schema_id
              JOIN sys.types UT
                ON UT.user_type_id = C.user_type_id
              JOIN sys.schemas UTS
                ON UTS.schema_id = UT.schema_id
              JOIN sys.types ST
                ON ST.system_type_id = C.system_type_id
               AND ST.user_type_id = ST.system_type_id
             WHERE OS.name NOT IN ('sys', 'INFORMATION_SCHEMA')
               AND UPPER(C.name) IN (:columnNames)
             GROUP BY C.name, UT.name, UTS.name, ST.name,
                      C.max_length, C.precision, C.scale
             ORDER BY C.name, FREQUENCY DESC
            """;

    static final String TABLE_SQL = """
            SELECT T.object_id AS TABLE_ID,
                   S.name AS SCHEMA_NAME,
                   T.name AS TABLE_NAME,
                   CAST(EP.value AS nvarchar(max)) AS COMMENTS,
                   STORAGE.DATA_SPACE_NAME
              FROM sys.tables T
              JOIN sys.schemas S
                ON S.schema_id = T.schema_id
              LEFT JOIN sys.extended_properties EP
                ON EP.class = 1
               AND EP.major_id = T.object_id
               AND EP.minor_id = 0
               AND EP.name = N'MS_Description'
              OUTER APPLY (
                    SELECT TOP (1) DS.name AS DATA_SPACE_NAME
                      FROM sys.indexes I
                      JOIN sys.data_spaces DS ON DS.data_space_id = I.data_space_id
                     WHERE I.object_id = T.object_id
                       AND I.index_id IN (0, 1)
                     ORDER BY I.index_id DESC
              ) STORAGE
             WHERE LOWER(S.name) = LOWER(:schemaName)
               AND LOWER(T.name) = LOWER(:tableName)
             ORDER BY CASE
                        WHEN S.name = :schemaName AND T.name = :tableName THEN 0
                        ELSE 1
                      END,
                      S.name,
                      T.name
            """;

    static final String COLUMNS_SQL = """
            SELECT C.column_id AS COLUMN_ID,
                   C.name AS COLUMN_NAME,
                   UT.name AS USER_TYPE_NAME,
                   UTS.name AS USER_TYPE_SCHEMA,
                   ST.name AS SYSTEM_TYPE_NAME,
                   C.max_length AS MAX_LENGTH,
                   C.precision AS NUMERIC_PRECISION,
                   C.scale AS NUMERIC_SCALE,
                   C.is_nullable AS IS_NULLABLE,
                   DC.definition AS DEFAULT_DEFINITION,
                   CAST(EP.value AS nvarchar(max)) AS COMMENTS,
                   CASE WHEN IC.object_id IS NULL THEN 0 ELSE 1 END AS IS_IDENTITY,
                   CC.definition AS COMPUTED_DEFINITION,
                   COALESCE(CC.is_persisted, 0) AS IS_PERSISTED
              FROM sys.columns C
              JOIN sys.types UT
                ON UT.user_type_id = C.user_type_id
              JOIN sys.schemas UTS
                ON UTS.schema_id = UT.schema_id
              JOIN sys.types ST
                ON ST.system_type_id = C.system_type_id
               AND ST.user_type_id = ST.system_type_id
              LEFT JOIN sys.default_constraints DC
                ON DC.object_id = C.default_object_id
              LEFT JOIN sys.identity_columns IC
                ON IC.object_id = C.object_id
               AND IC.column_id = C.column_id
              LEFT JOIN sys.computed_columns CC
                ON CC.object_id = C.object_id
               AND CC.column_id = C.column_id
              LEFT JOIN sys.extended_properties EP
                ON EP.class = 1
               AND EP.major_id = C.object_id
               AND EP.minor_id = C.column_id
               AND EP.name = N'MS_Description'
             WHERE C.object_id = :tableId
             ORDER BY C.column_id
            """;

    static final String KEY_CONSTRAINTS_SQL = """
            SELECT KC.name AS CONSTRAINT_NAME,
                   KC.type AS CONSTRAINT_TYPE,
                   C.name AS COLUMN_NAME,
                   IC.key_ordinal AS COLUMN_POSITION
              FROM sys.key_constraints KC
              JOIN sys.index_columns IC
                ON IC.object_id = KC.parent_object_id
               AND IC.index_id = KC.unique_index_id
               AND IC.key_ordinal > 0
              JOIN sys.columns C
                ON C.object_id = IC.object_id
               AND C.column_id = IC.column_id
             WHERE KC.parent_object_id = :tableId
               AND KC.type IN ('PK', 'UQ')
             ORDER BY KC.name, IC.key_ordinal
            """;

    static final String FOREIGN_KEYS_SQL = """
            SELECT FK.name AS CONSTRAINT_NAME,
                   FKC.constraint_column_id AS COLUMN_POSITION,
                   CHILD_COLUMN.name AS COLUMN_NAME,
                   PARENT_SCHEMA.name AS REFERENCED_SCHEMA,
                   PARENT_TABLE.name AS REFERENCED_TABLE,
                   PARENT_COLUMN.name AS REFERENCED_COLUMN,
                   FK.delete_referential_action_desc AS DELETE_ACTION,
                   FK.update_referential_action_desc AS UPDATE_ACTION
              FROM sys.foreign_keys FK
              JOIN sys.foreign_key_columns FKC
                ON FKC.constraint_object_id = FK.object_id
              JOIN sys.columns CHILD_COLUMN
                ON CHILD_COLUMN.object_id = FKC.parent_object_id
               AND CHILD_COLUMN.column_id = FKC.parent_column_id
              JOIN sys.tables PARENT_TABLE
                ON PARENT_TABLE.object_id = FKC.referenced_object_id
              JOIN sys.schemas PARENT_SCHEMA
                ON PARENT_SCHEMA.schema_id = PARENT_TABLE.schema_id
              JOIN sys.columns PARENT_COLUMN
                ON PARENT_COLUMN.object_id = FKC.referenced_object_id
               AND PARENT_COLUMN.column_id = FKC.referenced_column_id
             WHERE FK.parent_object_id = :tableId
             ORDER BY FK.name, FKC.constraint_column_id
            """;

    static final String CHECKS_SQL = """
            SELECT CC.name AS CONSTRAINT_NAME,
                   CC.definition AS DEFINITION
              FROM sys.check_constraints CC
             WHERE CC.parent_object_id = :tableId
             ORDER BY CC.name
            """;

    static final String INDEXES_SQL = """
            SELECT I.index_id AS INDEX_ID,
                   I.name AS INDEX_NAME,
                   I.is_unique AS IS_UNIQUE,
                   I.type_desc AS INDEX_TYPE,
                   IC.index_column_id AS ITEM_POSITION,
                   IC.key_ordinal AS KEY_POSITION,
                   IC.is_included_column AS IS_INCLUDED_COLUMN,
                   IC.is_descending_key AS IS_DESCENDING,
                   C.name AS COLUMN_NAME,
                   I.filter_definition AS FILTER_DEFINITION,
                   DS.name AS DATA_SPACE_NAME
              FROM sys.indexes I
              JOIN sys.index_columns IC
                ON IC.object_id = I.object_id
               AND IC.index_id = I.index_id
              LEFT JOIN sys.columns C
                ON C.object_id = IC.object_id
               AND C.column_id = IC.column_id
              LEFT JOIN sys.data_spaces DS
                ON DS.data_space_id = I.data_space_id
             WHERE I.object_id = :tableId
               AND I.index_id > 0
               AND I.is_hypothetical = 0
               AND I.type IN (1, 2)
             ORDER BY I.index_id,
                      CASE WHEN IC.is_included_column = 0 THEN 0 ELSE 1 END,
                      IC.key_ordinal,
                      IC.index_column_id
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcSqlServerMetadataRepository(
            @Qualifier("sqlServerMetadataJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<String, MetadataColumnProfile> loadColumnProfiles(Set<String> columnNames) {
        Set<String> normalized = MetadataRepositorySupport.normalizeNames(columnNames, true);
        if (normalized.isEmpty()) return Map.of();

        Map<String, List<MetadataTypeFrequency>> grouped = new LinkedHashMap<>();
        List<ProfileRow> rows = jdbcTemplate.query(
                COLUMN_PROFILE_SQL,
                new MapSqlParameterSource("columnNames", normalized),
                (rs, rowNumber) -> new ProfileRow(
                        rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT),
                        profileSignature(rs),
                        rs.getLong("FREQUENCY")));
        for (ProfileRow row : rows) {
            grouped.computeIfAbsent(row.columnName(), ignored -> new ArrayList<>())
                    .add(new MetadataTypeFrequency(row.typeSignature(), row.frequency()));
        }
        return MetadataRepositorySupport.toProfiles(grouped);
    }

    @Override
    public Optional<Table> findTable(String schemaName, String tableName) {
        if (schemaName == null || schemaName.isBlank() || tableName == null || tableName.isBlank()) {
            return Optional.empty();
        }

        MapSqlParameterSource requested = new MapSqlParameterSource()
                .addValue("schemaName", schemaName.trim())
                .addValue("tableName", tableName.trim());
        List<TableInfo> tables = jdbcTemplate.query(TABLE_SQL, requested,
                (rs, rowNumber) -> new TableInfo(
                        rs.getInt("TABLE_ID"),
                        rs.getString("SCHEMA_NAME"),
                        rs.getString("TABLE_NAME"),
                        trimToNull(rs.getString("COMMENTS")),
                        trimToNull(rs.getString("DATA_SPACE_NAME"))));
        if (tables.isEmpty()) {
            LOGGER.warn("SQL Server metadata table not found. requested={}.{}, visibleTableSchemas={}",
                    schemaName, tableName, findTableSchemas(tableName));
            return Optional.empty();
        }

        TableInfo info = tables.getFirst();
        Table.Builder builder = Table.builder(info.schema(), info.name());
        if (info.comment() != null) builder.description(info.comment());
        if (info.dataSpace() != null) builder.physicalOption("tablespace", info.dataSpace());

        MapSqlParameterSource parameter = new MapSqlParameterSource("tableId", info.id());
        List<SqlServerColumnRow> columns = jdbcTemplate.query(COLUMNS_SQL, parameter,
                (rs, rowNumber) -> new SqlServerColumnRow(
                        rs.getInt("COLUMN_ID"),
                        rs.getString("COLUMN_NAME"),
                        rs.getString("USER_TYPE_NAME"),
                        rs.getString("USER_TYPE_SCHEMA"),
                        rs.getString("SYSTEM_TYPE_NAME"),
                        rs.getInt("MAX_LENGTH"),
                        rs.getInt("NUMERIC_PRECISION"),
                        rs.getInt("NUMERIC_SCALE"),
                        rs.getBoolean("IS_NULLABLE"),
                        trimToNull(rs.getString("DEFAULT_DEFINITION")),
                        trimToNull(rs.getString("COMMENTS")),
                        rs.getBoolean("IS_IDENTITY"),
                        trimToNull(rs.getString("COMPUTED_DEFINITION")),
                        rs.getBoolean("IS_PERSISTED")));
        columns.stream().map(JdbcSqlServerMetadataRepository::mapColumn).forEach(builder::addColumn);

        List<KeyConstraintRow> keys = jdbcTemplate.query(KEY_CONSTRAINTS_SQL, parameter,
                (rs, rowNumber) -> new KeyConstraintRow(
                        rs.getString("CONSTRAINT_NAME"),
                        rs.getString("CONSTRAINT_TYPE"),
                        rs.getString("COLUMN_NAME"),
                        rs.getInt("COLUMN_POSITION")));
        mapKeys(builder, keys);

        List<ForeignKeyRow> foreignKeys = jdbcTemplate.query(FOREIGN_KEYS_SQL, parameter,
                (rs, rowNumber) -> new ForeignKeyRow(
                        rs.getString("CONSTRAINT_NAME"),
                        rs.getInt("COLUMN_POSITION"),
                        rs.getString("COLUMN_NAME"),
                        rs.getString("REFERENCED_SCHEMA"),
                        rs.getString("REFERENCED_TABLE"),
                        rs.getString("REFERENCED_COLUMN"),
                        rs.getString("DELETE_ACTION"),
                        rs.getString("UPDATE_ACTION")));
        mapForeignKeys(builder, foreignKeys);

        List<CheckRow> checks = jdbcTemplate.query(CHECKS_SQL, parameter,
                (rs, rowNumber) -> new CheckRow(
                        rs.getString("CONSTRAINT_NAME"),
                        trimToNull(rs.getString("DEFINITION"))));
        for (CheckRow check : checks) {
            if (check.definition() != null) {
                builder.addCheck(new CheckConstraint(identifierOrNull(check.name()), check.definition()));
            }
        }

        List<IndexRow> indexes = jdbcTemplate.query(INDEXES_SQL, parameter,
                (rs, rowNumber) -> new IndexRow(
                        rs.getInt("INDEX_ID"),
                        rs.getString("INDEX_NAME"),
                        rs.getBoolean("IS_UNIQUE"),
                        rs.getString("INDEX_TYPE"),
                        rs.getInt("ITEM_POSITION"),
                        rs.getInt("KEY_POSITION"),
                        rs.getBoolean("IS_INCLUDED_COLUMN"),
                        rs.getBoolean("IS_DESCENDING"),
                        rs.getString("COLUMN_NAME"),
                        trimToNull(rs.getString("FILTER_DEFINITION")),
                        trimToNull(rs.getString("DATA_SPACE_NAME"))));
        mapIndexes(builder, indexes);
        return Optional.of(builder.build());
    }

    @Override
    public boolean schemaExists(String schemaName) {
        if (schemaName == null || schemaName.isBlank()) return false;
        Integer count = jdbcTemplate.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM sys.schemas WHERE LOWER(name) = LOWER(?)",
                Integer.class, schemaName);
        return count != null && count > 0;
    }

    @Override
    public List<String> findTableSchemas(String tableName) {
        if (tableName == null || tableName.isBlank()) return List.of();
        return jdbcTemplate.getJdbcTemplate().queryForList(
                "SELECT S.name FROM sys.tables T JOIN sys.schemas S ON S.schema_id = T.schema_id "
                        + "WHERE LOWER(T.name) = LOWER(?) AND S.name NOT IN ('sys','INFORMATION_SCHEMA') "
                        + "ORDER BY S.name",
                String.class, tableName);
    }

    static Column mapColumn(SqlServerColumnRow row) {
        DataType type = mapDataType(row);
        String generatedExpression = row.computedDefinition();
        String defaultExpression = generatedExpression == null ? row.defaultDefinition() : null;
        return new Column(
                Identifier.of(row.name()),
                type,
                row.nullable(),
                new DefaultValue(defaultExpression),
                new Description(row.comment()),
                row.identity(),
                row.position(),
                generatedExpression);
    }

    static DataType mapDataType(SqlServerColumnRow row) {
        String systemType = normalizeType(row.systemTypeName());
        if (isUserDefinedAlias(row, systemType)) {
            return DataType.simple(safeTypeName(row.userTypeSchema() + "_" + row.userTypeName(), "SQLSERVER_TYPE"));
        }

        return switch (systemType) {
            case "TINYINT", "SMALLINT", "INT", "BIGINT", "BIT", "MONEY", "SMALLMONEY",
                    "FLOAT", "REAL", "DATETIME", "SMALLDATETIME", "UNIQUEIDENTIFIER", "XML",
                    "SQL_VARIANT", "HIERARCHYID", "GEOGRAPHY", "GEOMETRY" -> DataType.simple(systemType);
            case "DECIMAL", "NUMERIC" -> DataType.numeric("DECIMAL", row.precision(), row.scale());
            case "DATE" -> DataType.simple("DATE_SQLSERVER");
            case "TIME", "DATETIME2", "DATETIMEOFFSET" -> row.scale() <= 0
                    ? DataType.simple(systemType + "_0")
                    : DataType.numeric(systemType, row.scale(), null);
            case "TIMESTAMP", "ROWVERSION" -> DataType.simple("SQLSERVER_TIMESTAMP");
            case "CHAR", "VARCHAR", "NCHAR", "NVARCHAR", "BINARY", "VARBINARY" ->
                    mapLengthType(systemType, row.maxLength());
            case "TEXT" -> DataType.simple("TEXT");
            case "NTEXT" -> DataType.simple("NTEXT");
            case "IMAGE" -> DataType.simple("IMAGE");
            default -> DataType.simple(safeTypeName(systemType, "SQLSERVER"));
        };
    }

    private static DataType mapLengthType(String type, int rawLength) {
        if (rawLength == -1) {
            return DataType.simple(type + "_MAX");
        }
        int length = (type.equals("NCHAR") || type.equals("NVARCHAR")) ? rawLength / 2 : rawLength;
        return new DataType(Identifier.of(type), Math.max(length, 1),
                type.contains("CHAR") ? LengthSemantics.CHAR : LengthSemantics.DEFAULT,
                null, null);
    }

    static void mapKeys(Table.Builder builder, List<KeyConstraintRow> rows) {
        Map<String, List<KeyConstraintRow>> groups = new LinkedHashMap<>();
        for (KeyConstraintRow row : rows) {
            groups.computeIfAbsent(row.name() + "|" + row.type(), ignored -> new ArrayList<>()).add(row);
        }
        for (List<KeyConstraintRow> group : groups.values()) {
            group.sort(Comparator.comparingInt(KeyConstraintRow::position));
            KeyConstraintRow first = group.getFirst();
            List<Identifier> columns = group.stream().map(KeyConstraintRow::column)
                    .filter(value -> value != null && !value.isBlank()).map(Identifier::of).toList();
            if (columns.isEmpty()) continue;
            if ("PK".equalsIgnoreCase(first.type())) {
                builder.primaryKey(new PrimaryKey(identifierOrNull(first.name()), columns, false, false));
            } else {
                builder.addUniqueKey(new UniqueKey(identifierOrNull(first.name()), columns, false, false));
            }
        }
    }

    static void mapForeignKeys(Table.Builder builder, List<ForeignKeyRow> rows) {
        Map<String, List<ForeignKeyRow>> groups = new LinkedHashMap<>();
        for (ForeignKeyRow row : rows) groups.computeIfAbsent(row.name(), ignored -> new ArrayList<>()).add(row);
        for (List<ForeignKeyRow> group : groups.values()) {
            group.sort(Comparator.comparingInt(ForeignKeyRow::position));
            ForeignKeyRow first = group.getFirst();
            List<Identifier> child = group.stream().map(ForeignKeyRow::column)
                    .filter(value -> value != null && !value.isBlank()).map(Identifier::of).toList();
            List<Identifier> parent = group.stream().map(ForeignKeyRow::referencedColumn)
                    .filter(value -> value != null && !value.isBlank()).map(Identifier::of).toList();
            if (child.isEmpty() || child.size() != parent.size()) continue;
            builder.addForeignKey(new ForeignKey(
                    identifierOrNull(first.name()),
                    child,
                    QualifiedName.of(first.referencedSchema(), first.referencedTable()),
                    parent,
                    mapReferentialAction(first.deleteAction()),
                    mapReferentialAction(first.updateAction()),
                    false,
                    false,
                    true,
                    true));
        }
    }

    static void mapIndexes(Table.Builder builder, List<IndexRow> rows) {
        Map<Integer, List<IndexRow>> groups = new LinkedHashMap<>();
        for (IndexRow row : rows) groups.computeIfAbsent(row.id(), ignored -> new ArrayList<>()).add(row);
        for (List<IndexRow> group : groups.values()) {
            group.sort(Comparator.comparingInt(IndexRow::itemPosition));
            List<IndexColumn> keyColumns = new ArrayList<>();
            List<Identifier> includeColumns = new ArrayList<>();
            for (IndexRow row : group) {
                if (row.column() == null || row.column().isBlank()) continue;
                if (row.included()) {
                    includeColumns.add(Identifier.of(row.column()));
                } else if (row.keyPosition() > 0) {
                    keyColumns.add(new IndexColumn(
                            Identifier.of(row.column()),
                            row.descending() ? SortDirection.DESC : SortDirection.ASC));
                }
            }
            if (keyColumns.isEmpty()) continue;
            IndexRow first = group.getFirst();
            builder.addIndex(new Index(
                    identifierOrNull(first.name()),
                    keyColumns,
                    first.unique() ? IndexType.UNIQUE : IndexType.NORMAL,
                    Description.empty(),
                    includeColumns,
                    first.filterDefinition()));
        }
    }

    static String profileSignature(ResultSet rs) throws SQLException {
        SqlServerColumnRow row = new SqlServerColumnRow(
                1,
                rs.getString("COLUMN_NAME"),
                rs.getString("USER_TYPE_NAME"),
                rs.getString("USER_TYPE_SCHEMA"),
                rs.getString("SYSTEM_TYPE_NAME"),
                rs.getInt("MAX_LENGTH"),
                rs.getInt("NUMERIC_PRECISION"),
                rs.getInt("NUMERIC_SCALE"),
                true,
                null,
                null,
                false,
                null,
                false);
        return renderType(mapDataType(row));
    }

    private static String renderType(DataType type) {
        String name = type.name().normalized();
        return switch (name) {
            case "DATE_SQLSERVER" -> "DATE";
            case "SQLSERVER_TIMESTAMP" -> "ROWVERSION";
            case "VARCHAR_MAX" -> "VARCHAR(MAX)";
            case "NVARCHAR_MAX" -> "NVARCHAR(MAX)";
            case "VARBINARY_MAX" -> "VARBINARY(MAX)";
            case "TIME_0" -> "TIME(0)";
            case "DATETIME2_0" -> "DATETIME2(0)";
            case "DATETIMEOFFSET_0" -> "DATETIMEOFFSET(0)";
            default -> {
                if (type.length() != null) yield type.name().value() + "(" + type.length() + ")";
                if (type.precision() != null) {
                    yield type.name().value() + "(" + type.precision()
                            + (type.scale() == null ? "" : "," + type.scale()) + ")";
                }
                yield type.name().value();
            }
        };
    }

    private static ReferentialAction mapReferentialAction(String value) {
        if (value == null || value.isBlank()) return ReferentialAction.NO_ACTION;
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "CASCADE" -> ReferentialAction.CASCADE;
            case "SET_NULL", "SET NULL" -> ReferentialAction.SET_NULL;
            case "SET_DEFAULT", "SET DEFAULT" -> ReferentialAction.SET_DEFAULT;
            case "RESTRICT" -> ReferentialAction.RESTRICT;
            default -> ReferentialAction.NO_ACTION;
        };
    }

    private static boolean isUserDefinedAlias(SqlServerColumnRow row, String systemType) {
        if (row.userTypeName() == null || row.userTypeSchema() == null) return false;
        return !"SYS".equalsIgnoreCase(row.userTypeSchema())
                && !normalizeType(row.userTypeName()).equals(systemType);
    }

    private static String normalizeType(String value) {
        return value == null ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "_");
    }

    private static String safeTypeName(String value, String fallback) {
        String source = value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
        String safe = source.replaceAll("[^A-Z0-9_$#]+", "_").replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (safe.isBlank()) return fallback;
        if (!Character.isLetter(safe.charAt(0))) return fallback + "_" + safe;
        return safe;
    }

    private static Identifier identifierOrNull(String value) {
        return value == null || value.isBlank() ? null : Identifier.of(value);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    record TableInfo(int id, String schema, String name, String comment, String dataSpace) { }

    record SqlServerColumnRow(int position, String name, String userTypeName, String userTypeSchema,
                              String systemTypeName, int maxLength, int precision, int scale,
                              boolean nullable, String defaultDefinition, String comment,
                              boolean identity, String computedDefinition, boolean persisted) { }

    record KeyConstraintRow(String name, String type, String column, int position) { }

    record ForeignKeyRow(String name, int position, String column, String referencedSchema,
                         String referencedTable, String referencedColumn,
                         String deleteAction, String updateAction) { }

    record CheckRow(String name, String definition) { }

    record IndexRow(int id, String name, boolean unique, String type, int itemPosition,
                    int keyPosition, boolean included, boolean descending, String column,
                    String filterDefinition, String dataSpace) { }

    record ProfileRow(String columnName, String typeSignature, long frequency) { }
}
