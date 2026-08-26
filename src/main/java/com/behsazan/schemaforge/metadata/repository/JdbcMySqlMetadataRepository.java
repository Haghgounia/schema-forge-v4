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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Reads live MySQL table/column metadata for document-to-database migration planning. */
@Repository
@ConditionalOnProperty(prefix = "schemaforge.metadata.mysql", name = "enabled", havingValue = "true")
public class JdbcMySqlMetadataRepository implements MySqlMetadataRepository {
    static final String TABLE_SQL = """
            SELECT table_schema, table_name, table_comment, engine, table_collation, row_format, create_options
              FROM information_schema.tables
             WHERE table_type = 'BASE TABLE'
               AND LOWER(table_schema) = LOWER(:schemaName)
               AND LOWER(table_name) = LOWER(:tableName)
             ORDER BY CASE
                        WHEN table_schema = :schemaName AND table_name = :tableName THEN 0
                        ELSE 1
                      END,
                      table_schema, table_name
            """;

    static final String COLUMNS_SQL = """
            SELECT ordinal_position,
                   column_name,
                   data_type,
                   column_type,
                   character_maximum_length,
                   numeric_precision,
                   numeric_scale,
                   datetime_precision,
                   is_nullable,
                   column_default,
                   extra,
                   generation_expression,
                   column_comment
              FROM information_schema.columns
             WHERE table_schema = :schemaName
               AND table_name = :tableName
             ORDER BY ordinal_position
            """;

    static final String KEY_CONSTRAINTS_SQL = """
            SELECT tc.constraint_name, tc.constraint_type, kcu.column_name, kcu.ordinal_position
              FROM information_schema.table_constraints tc
              JOIN information_schema.key_column_usage kcu
                ON kcu.constraint_schema = tc.constraint_schema
               AND kcu.table_name = tc.table_name
               AND kcu.constraint_name = tc.constraint_name
             WHERE tc.table_schema = :schemaName
               AND tc.table_name = :tableName
               AND tc.constraint_type IN ('PRIMARY KEY', 'UNIQUE')
             ORDER BY tc.constraint_name, kcu.ordinal_position
            """;

    static final String FOREIGN_KEYS_SQL = """
            SELECT rc.constraint_name, kcu.column_name, kcu.ordinal_position,
                   kcu.referenced_table_schema, kcu.referenced_table_name, kcu.referenced_column_name,
                   rc.delete_rule, rc.update_rule
              FROM information_schema.referential_constraints rc
              JOIN information_schema.key_column_usage kcu
                ON kcu.constraint_schema = rc.constraint_schema
               AND kcu.constraint_name = rc.constraint_name
               AND kcu.table_name = rc.table_name
             WHERE rc.constraint_schema = :schemaName
               AND rc.table_name = :tableName
             ORDER BY rc.constraint_name, kcu.ordinal_position
            """;

    static final String CHECKS_SQL = """
            SELECT tc.constraint_name, cc.check_clause
              FROM information_schema.table_constraints tc
              JOIN information_schema.check_constraints cc
                ON cc.constraint_schema = tc.constraint_schema
               AND cc.constraint_name = tc.constraint_name
             WHERE tc.table_schema = :schemaName
               AND tc.table_name = :tableName
               AND tc.constraint_type = 'CHECK'
             ORDER BY tc.constraint_name
            """;

    static final String INDEXES_SQL = """
            SELECT index_name, non_unique, seq_in_index, column_name, expression, collation, sub_part, index_type
              FROM information_schema.statistics
             WHERE table_schema = :schemaName
               AND table_name = :tableName
             ORDER BY index_name, seq_in_index
            """;

    private static final String COLUMN_PROFILE_SQL = """
            SELECT column_name,
                   data_type,
                   character_maximum_length,
                   numeric_precision,
                   numeric_scale,
                   COUNT(DISTINCT CONCAT(table_schema, '.', table_name)) AS frequency
              FROM information_schema.columns
             WHERE table_schema NOT IN ('mysql', 'information_schema', 'performance_schema', 'sys')
               AND UPPER(column_name) IN (:columnNames)
             GROUP BY column_name, data_type, character_maximum_length, numeric_precision, numeric_scale
             ORDER BY column_name, frequency DESC
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcMySqlMetadataRepository(
            @Qualifier("mySqlMetadataJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate) {
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
                        rs.getString("column_name").toUpperCase(Locale.ROOT),
                        profileSignature(
                                rs.getString("data_type"),
                                nullableInt(rs.getObject("character_maximum_length")),
                                nullableInt(rs.getObject("numeric_precision")),
                                nullableInt(rs.getObject("numeric_scale"))),
                        rs.getLong("frequency")));
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
        List<TableInfo> tables = jdbcTemplate.query(
                TABLE_SQL,
                requested,
                (rs, rowNumber) -> new TableInfo(
                        rs.getString("table_schema"),
                        rs.getString("table_name"),
                        trimToNull(rs.getString("table_comment")),
                        trimToNull(rs.getString("engine")),
                        trimToNull(rs.getString("table_collation")),
                        trimToNull(rs.getString("row_format")),
                        trimToNull(rs.getString("create_options"))));
        if (tables.isEmpty()) return Optional.empty();

        TableInfo info = tables.getFirst();
        Table.Builder builder = Table.builder(info.schema(), info.name());
        if (info.comment() != null) builder.description(info.comment());
        if (info.engine() != null) builder.physicalOption("MYSQL_ENGINE", info.engine());
        if (info.collation() != null) builder.physicalOption("MYSQL_COLLATION", info.collation());
        if (info.rowFormat() != null) builder.physicalOption("MYSQL_ROW_FORMAT", info.rowFormat().toUpperCase(Locale.ROOT));
        if (info.createOptions() != null) builder.physicalOption("MYSQL_CREATE_OPTIONS", info.createOptions());

        MapSqlParameterSource exact = new MapSqlParameterSource()
                .addValue("schemaName", info.schema())
                .addValue("tableName", info.name());
        List<MySqlColumnRow> columns = jdbcTemplate.query(
                COLUMNS_SQL,
                exact,
                (rs, rowNumber) -> new MySqlColumnRow(
                        rs.getInt("ordinal_position"),
                        rs.getString("column_name"),
                        rs.getString("data_type"),
                        rs.getString("column_type"),
                        nullableInt(rs.getObject("character_maximum_length")),
                        nullableInt(rs.getObject("numeric_precision")),
                        nullableInt(rs.getObject("numeric_scale")),
                        nullableInt(rs.getObject("datetime_precision")),
                        "YES".equalsIgnoreCase(rs.getString("is_nullable")),
                        rs.getString("column_default"),
                        trimToNull(rs.getString("extra")),
                        trimToNull(rs.getString("generation_expression")),
                        trimToNull(rs.getString("column_comment"))));
        for (MySqlColumnRow row : columns) builder.addColumn(mapColumn(row));

        List<KeyConstraintRow> keyConstraints = jdbcTemplate.query(
                KEY_CONSTRAINTS_SQL, exact,
                (rs, rowNumber) -> new KeyConstraintRow(
                        rs.getString("constraint_name"), rs.getString("constraint_type"),
                        rs.getString("column_name"), rs.getInt("ordinal_position")));
        mapKeyConstraints(builder, keyConstraints);

        List<ForeignKeyRow> foreignKeys = jdbcTemplate.query(
                FOREIGN_KEYS_SQL, exact,
                (rs, rowNumber) -> new ForeignKeyRow(
                        rs.getString("constraint_name"), rs.getString("column_name"), rs.getInt("ordinal_position"),
                        rs.getString("referenced_table_schema"), rs.getString("referenced_table_name"),
                        rs.getString("referenced_column_name"), rs.getString("delete_rule"), rs.getString("update_rule")));
        mapForeignKeys(builder, foreignKeys);

        List<CheckRow> checks = jdbcTemplate.query(
                CHECKS_SQL, exact,
                (rs, rowNumber) -> new CheckRow(rs.getString("constraint_name"), rs.getString("check_clause")));
        for (CheckRow check : checks) {
            if (check.expression() != null && !check.expression().isBlank()) {
                builder.addCheck(new CheckConstraint(Identifier.of(check.name()), check.expression()));
            }
        }

        List<IndexRow> indexes = jdbcTemplate.query(
                INDEXES_SQL, exact,
                (rs, rowNumber) -> new IndexRow(
                        rs.getString("index_name"), rs.getInt("non_unique") == 0, rs.getInt("seq_in_index"),
                        rs.getString("column_name"), trimToNull(rs.getString("expression")),
                        rs.getString("collation"), nullableInt(rs.getObject("sub_part")), rs.getString("index_type")));
        mapIndexes(builder, indexes, keyConstraints);
        return Optional.of(builder.build());
    }

    @Override
    public boolean schemaExists(String schemaName) {
        if (schemaName == null || schemaName.isBlank()) return false;
        Integer count = jdbcTemplate.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE LOWER(schema_name) = LOWER(?)",
                Integer.class, schemaName);
        return count != null && count > 0;
    }

    @Override
    public List<String> findTableSchemas(String tableName) {
        if (tableName == null || tableName.isBlank()) return List.of();
        return jdbcTemplate.getJdbcTemplate().queryForList(
                "SELECT table_schema FROM information_schema.tables "
                        + "WHERE table_type='BASE TABLE' "
                        + "AND table_schema NOT IN ('mysql','information_schema','performance_schema','sys') "
                        + "AND LOWER(table_name)=LOWER(?) ORDER BY table_schema",
                String.class, tableName);
    }

    static void mapKeyConstraints(Table.Builder builder, List<KeyConstraintRow> rows) {
        Map<String, List<KeyConstraintRow>> grouped = new LinkedHashMap<>();
        for (KeyConstraintRow row : rows) {
            grouped.computeIfAbsent(row.name(), ignored -> new ArrayList<>()).add(row);
        }
        for (List<KeyConstraintRow> group : grouped.values()) {
            group.sort(java.util.Comparator.comparingInt(KeyConstraintRow::position));
            KeyConstraintRow first = group.getFirst();
            List<Identifier> columns = group.stream().map(row -> Identifier.of(row.column())).toList();
            if ("PRIMARY KEY".equalsIgnoreCase(first.type())) {
                builder.primaryKey(new PrimaryKey(Identifier.of(first.name()), columns));
            } else if ("UNIQUE".equalsIgnoreCase(first.type())) {
                builder.addUniqueKey(new UniqueKey(Identifier.of(first.name()), columns));
            }
        }
    }

    static void mapForeignKeys(Table.Builder builder, List<ForeignKeyRow> rows) {
        Map<String, List<ForeignKeyRow>> grouped = new LinkedHashMap<>();
        for (ForeignKeyRow row : rows) {
            grouped.computeIfAbsent(row.name(), ignored -> new ArrayList<>()).add(row);
        }
        for (List<ForeignKeyRow> group : grouped.values()) {
            group.sort(java.util.Comparator.comparingInt(ForeignKeyRow::position));
            ForeignKeyRow first = group.getFirst();
            List<Identifier> columns = group.stream().map(row -> Identifier.of(row.column())).toList();
            List<Identifier> referenced = group.stream().map(row -> Identifier.of(row.referencedColumn())).toList();
            builder.addForeignKey(new ForeignKey(
                    Identifier.of(first.name()), columns,
                    com.behsazan.schemaforge.domain.valueobject.QualifiedName.of(
                            first.referencedSchema(), first.referencedTable()),
                    referenced, referentialAction(first.deleteRule()), referentialAction(first.updateRule()),
                    false, false, true, true));
        }
    }

    static void mapIndexes(Table.Builder builder, List<IndexRow> rows, List<KeyConstraintRow> keyConstraints) {
        Set<String> enforcingNames = new java.util.LinkedHashSet<>();
        for (KeyConstraintRow row : keyConstraints) enforcingNames.add(row.name().toUpperCase(Locale.ROOT));
        enforcingNames.add("PRIMARY");

        Map<String, List<IndexRow>> grouped = new LinkedHashMap<>();
        for (IndexRow row : rows) {
            if (row.name() == null || enforcingNames.contains(row.name().toUpperCase(Locale.ROOT))) continue;
            grouped.computeIfAbsent(row.name(), ignored -> new ArrayList<>()).add(row);
        }
        for (List<IndexRow> group : grouped.values()) {
            group.sort(java.util.Comparator.comparingInt(IndexRow::position));
            IndexRow first = group.getFirst();
            List<IndexColumn> columns = new ArrayList<>();
            for (IndexRow row : group) {
                SortDirection direction = "D".equalsIgnoreCase(row.collation()) ? SortDirection.DESC : SortDirection.ASC;
                if (row.expression() != null) columns.add(IndexColumn.expression(row.expression(), direction));
                else if (row.column() != null) columns.add(new IndexColumn(Identifier.of(row.column()), direction));
            }
            if (columns.isEmpty()) continue;
            Map<String, String> physical = new LinkedHashMap<>();
            if (first.indexType() != null && !first.indexType().isBlank()) {
                physical.put("MYSQL_INDEX_TYPE", first.indexType().trim().toUpperCase(Locale.ROOT));
            }
            builder.addIndex(new Index(
                    Identifier.of(first.name()), columns,
                    first.unique() ? IndexType.UNIQUE : IndexType.NORMAL, Description.empty(),
                    List.of(), null, physical, Map.of()));
        }
    }

    private static ReferentialAction referentialAction(String value) {
        if (value == null || value.isBlank()) return ReferentialAction.NO_ACTION;
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        try {
            return ReferentialAction.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return ReferentialAction.NO_ACTION;
        }
    }

    static Column mapColumn(MySqlColumnRow row) {
        boolean identity = containsToken(row.extra(), "auto_increment");
        boolean generated = row.generatedExpression() != null && !row.generatedExpression().isBlank();
        Map<String, String> physical = new LinkedHashMap<>();
        if (row.columnType() != null && !row.columnType().isBlank()) {
            physical.put("MYSQL_NATIVE_COLUMN_TYPE", row.columnType().trim());
        }
        if (row.extra() != null) physical.put("MYSQL_EXTRA", row.extra());
        return new Column(
                Identifier.of(row.name()),
                mapDataType(row),
                row.nullable(),
                new DefaultValue(generated ? null : defaultExpression(row)),
                new Description(row.comment()),
                identity,
                row.position(),
                generated ? row.generatedExpression() : null,
                physical);
    }

    static DataType mapDataType(MySqlColumnRow row) {
        String type = row.dataType() == null ? "UNKNOWN" : row.dataType().trim().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "VARCHAR", "CHAR", "VARBINARY", "BINARY" -> new DataType(
                    Identifier.of(type), positive(row.characterLength(), 1),
                    type.contains("CHAR") ? LengthSemantics.CHAR : LengthSemantics.DEFAULT,
                    null, null);
            case "DECIMAL", "NUMERIC" -> DataType.numeric(
                    "DECIMAL", positive(row.numericPrecision(), 1), row.numericScale() == null ? 0 : row.numericScale());
            case "DATETIME", "TIMESTAMP", "TIME" -> row.datetimePrecision() == null || row.datetimePrecision() <= 0
                    ? DataType.simple(type)
                    : DataType.numeric(type, row.datetimePrecision(), null);
            case "TINYINT", "SMALLINT", "MEDIUMINT", "INT", "INTEGER", "BIGINT",
                    "FLOAT", "DOUBLE", "REAL", "DATE", "YEAR", "JSON",
                    "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT",
                    "TINYBLOB", "BLOB", "MEDIUMBLOB", "LONGBLOB", "BIT" -> DataType.simple(safeTypeName(type));
            default -> DataType.simple(safeTypeName(type));
        };
    }

    private static String defaultExpression(MySqlColumnRow row) {
        String value = row.defaultValue();
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return "''";
        if (looksExpression(trimmed, row.extra())) return trimmed;
        String type = row.dataType() == null ? "" : row.dataType().trim().toUpperCase(Locale.ROOT);
        if (Set.of("CHAR", "VARCHAR", "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT",
                "DATE", "DATETIME", "TIMESTAMP", "TIME", "YEAR", "JSON", "ENUM", "SET").contains(type)) {
            return "'" + trimmed.replace("'", "''") + "'";
        }
        return trimmed;
    }

    private static boolean looksExpression(String value, String extra) {
        String upper = value.toUpperCase(Locale.ROOT);
        if (upper.startsWith("CURRENT_TIMESTAMP") || upper.equals("CURRENT_DATE") || upper.equals("CURRENT_TIME")) {
            return true;
        }
        return containsToken(extra, "default_generated") && (value.contains("(") || upper.endsWith("()"));
    }

    private static boolean containsToken(String value, String token) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT));
    }

    private static String profileSignature(String type, Integer length, Integer precision, Integer scale) {
        String normalized = type == null ? "UNKNOWN" : type.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("CHAR") && length != null) return normalized + "(" + length + ")";
        if ((normalized.equals("DECIMAL") || normalized.equals("NUMERIC")) && precision != null) {
            return "DECIMAL(" + precision + "," + (scale == null ? 0 : scale) + ")";
        }
        return normalized;
    }

    private static String safeTypeName(String type) {
        String safe = type.replaceAll("[^A-Z0-9_$#]+", "_").replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (safe.isBlank() || !Character.isLetter(safe.charAt(0))) return "MYSQL_" + safe;
        return safe;
    }

    private static int positive(Integer value, int fallback) {
        return value != null && value > 0 ? value : fallback;
    }

    private static Integer nullableInt(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.intValue();
        String text = value.toString().trim();
        return text.isEmpty() ? null : Integer.valueOf(text);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    record TableInfo(String schema, String name, String comment, String engine, String collation,
                     String rowFormat, String createOptions) {}
    record ProfileRow(String columnName, String typeSignature, long frequency) {}
    record MySqlColumnRow(
            int position,
            String name,
            String dataType,
            String columnType,
            Integer characterLength,
            Integer numericPrecision,
            Integer numericScale,
            Integer datetimePrecision,
            boolean nullable,
            String defaultValue,
            String extra,
            String generatedExpression,
            String comment) {}
    record KeyConstraintRow(String name, String type, String column, int position) {}
    record ForeignKeyRow(String name, String column, int position, String referencedSchema,
                         String referencedTable, String referencedColumn, String deleteRule, String updateRule) {}
    record CheckRow(String name, String expression) {}
    record IndexRow(String name, boolean unique, int position, String column, String expression,
                    String collation, Integer prefixLength, String indexType) {}
}
