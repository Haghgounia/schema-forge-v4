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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides access to jdbc postgre sql metadata data.
 *
 * @since 4.1
 */
@Repository
@ConditionalOnProperty(prefix = "schemaforge.metadata.postgresql", name = "enabled", havingValue = "true")
public class JdbcPostgreSqlMetadataRepository implements PostgreSqlMetadataRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcPostgreSqlMetadataRepository.class);
    private static final Pattern TYPE_WITH_TWO_ARGUMENTS = Pattern.compile("^(.+?)\\((\\d+)\\s*,\\s*(\\d+)\\)(.*)$");
    private static final Pattern TYPE_WITH_ONE_ARGUMENT = Pattern.compile("^(.+?)\\((\\d+)\\)(.*)$");

    private static final String COLUMN_PROFILE_SQL = """
            SELECT c.column_name,
                   c.data_type,
                   c.character_maximum_length,
                   c.numeric_precision,
                   c.numeric_scale,
                   COUNT(DISTINCT c.table_schema || '.' || c.table_name) AS frequency
              FROM information_schema.columns c
             WHERE c.table_schema NOT IN ('pg_catalog', 'information_schema')
               AND UPPER(c.column_name) IN (:columnNames)
             GROUP BY c.column_name, c.data_type, c.character_maximum_length,
                      c.numeric_precision, c.numeric_scale
             ORDER BY c.column_name, frequency DESC
            """;

    private static final String TABLE_SQL = """
            SELECT c.oid AS table_oid,
                   n.nspname AS schema_name,
                   c.relname AS table_name,
                   obj_description(c.oid, 'pg_class') AS comments
              FROM pg_class c
              JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE c.relkind IN ('r', 'p')
               AND LOWER(n.nspname) = LOWER(:schemaName)
               AND LOWER(c.relname) = LOWER(:tableName)
             ORDER BY CASE
                        WHEN n.nspname = :schemaName AND c.relname = :tableName THEN 0
                        WHEN n.nspname = LOWER(:schemaName) AND c.relname = LOWER(:tableName) THEN 1
                        ELSE 2
                      END,
                      n.nspname,
                      c.relname
            """;

    private static final String COLUMNS_SQL = """
            SELECT a.attnum AS column_id,
                   a.attname AS column_name,
                   format_type(a.atttypid, a.atttypmod) AS formatted_type,
                   a.attnotnull,
                   pg_get_expr(ad.adbin, ad.adrelid) AS default_value,
                   col_description(a.attrelid, a.attnum) AS comments,
                   a.attidentity,
                   a.attgenerated
              FROM pg_attribute a
              LEFT JOIN pg_attrdef ad
                ON ad.adrelid = a.attrelid
               AND ad.adnum = a.attnum
             WHERE a.attrelid = :tableOid
               AND a.attnum > 0
               AND NOT a.attisdropped
             ORDER BY a.attnum
            """;

    private static final String KEY_CONSTRAINTS_SQL = """
            SELECT con.conname AS constraint_name,
                   con.contype AS constraint_type,
                   con.condeferrable,
                   con.condeferred,
                   a.attname AS column_name,
                   key_column.ordinality AS column_position
              FROM pg_constraint con
              CROSS JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS key_column(attnum, ordinality)
              JOIN pg_attribute a
                ON a.attrelid = con.conrelid
               AND a.attnum = key_column.attnum
             WHERE con.conrelid = :tableOid
               AND con.contype IN ('p', 'u')
             ORDER BY con.conname, key_column.ordinality
            """;

    private static final String FOREIGN_KEYS_SQL = """
            SELECT con.conname AS constraint_name,
                   con.condeferrable,
                   con.condeferred,
                   con.confdeltype,
                   con.confupdtype,
                   child_column.ordinality AS column_position,
                   child_attribute.attname AS column_name,
                   parent_namespace.nspname AS referenced_schema,
                   parent_table.relname AS referenced_table,
                   parent_attribute.attname AS referenced_column
              FROM pg_constraint con
              CROSS JOIN LATERAL unnest(con.conkey) WITH ORDINALITY
                   AS child_column(attnum, ordinality)
              CROSS JOIN LATERAL unnest(con.confkey) WITH ORDINALITY
                   AS parent_column(attnum, ordinality)
              JOIN pg_attribute child_attribute
                ON child_attribute.attrelid = con.conrelid
               AND child_attribute.attnum = child_column.attnum
              JOIN pg_class parent_table ON parent_table.oid = con.confrelid
              JOIN pg_namespace parent_namespace ON parent_namespace.oid = parent_table.relnamespace
              JOIN pg_attribute parent_attribute
                ON parent_attribute.attrelid = con.confrelid
               AND parent_attribute.attnum = parent_column.attnum
             WHERE con.conrelid = :tableOid
               AND con.contype = 'f'
               AND parent_column.ordinality = child_column.ordinality
             ORDER BY con.conname, child_column.ordinality
            """;

    private static final String CHECKS_SQL = """
            SELECT con.conname AS constraint_name,
                   pg_get_constraintdef(con.oid, true) AS definition
              FROM pg_constraint con
             WHERE con.conrelid = :tableOid
               AND con.contype = 'c'
             ORDER BY con.conname
            """;

    private static final String INDEXES_SQL = """
            SELECT index_table.relname AS index_name,
                   index_definition.indisunique,
                   access_method.amname AS access_method,
                   item.ordinality AS item_position,
                   item.ordinality <= index_definition.indnkeyatts AS key_column,
                   attribute.attname AS column_name,
                   pg_get_indexdef(index_definition.indexrelid, item.ordinality::integer, true) AS item_definition,
                   CASE WHEN item.ordinality <= index_definition.indnkeyatts
                        THEN COALESCE((index_definition.indoption[(item.ordinality - 1)::integer] & 1) = 1, false)
                        ELSE false END AS descending,
                   pg_get_expr(index_definition.indpred, index_definition.indrelid) AS predicate
              FROM pg_index index_definition
              JOIN pg_class table_definition ON table_definition.oid = index_definition.indrelid
              JOIN pg_namespace table_namespace ON table_namespace.oid = table_definition.relnamespace
              JOIN pg_class index_table ON index_table.oid = index_definition.indexrelid
              JOIN pg_am access_method ON access_method.oid = index_table.relam
              CROSS JOIN LATERAL unnest(index_definition.indkey) WITH ORDINALITY
                   AS item(attnum, ordinality)
              LEFT JOIN pg_attribute attribute
                ON attribute.attrelid = table_definition.oid
               AND attribute.attnum = item.attnum
             WHERE table_definition.oid = :tableOid
             ORDER BY index_table.relname, item.ordinality
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcPostgreSqlMetadataRepository(
            @Qualifier("postgresqlMetadataJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate) {
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
                        profileSignature(rs),
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
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("schemaName", schemaName.trim())
                .addValue("tableName", tableName.trim());
        List<TableInfo> tables = jdbcTemplate.query(TABLE_SQL, parameters,
                (rs, rowNumber) -> new TableInfo(
                        rs.getLong("table_oid"), rs.getString("schema_name"),
                        rs.getString("table_name"), rs.getString("comments")));
        if (tables.isEmpty()) {
            Map<String, Object> connectionInfo = jdbcTemplate.getJdbcTemplate().queryForMap(
                    "SELECT current_database() AS database_name, current_user AS user_name");
            List<String> visibleSchemas = findTableSchemas(tableName);
            LOGGER.warn("PostgreSQL metadata table not found. database={}, user={}, requested={}.{}, visibleTableSchemas={}",
                    connectionInfo.get("database_name"), connectionInfo.get("user_name"),
                    schemaName, tableName, visibleSchemas);
            return Optional.empty();
        }

        TableInfo info = tables.getFirst();
        Table.Builder builder = Table.builder(info.schema(), info.name());
        String tableComment = trimToNull(info.comment());
        if (tableComment != null) builder.description(tableComment);

        MapSqlParameterSource tableParameter = new MapSqlParameterSource("tableOid", info.oid());
        List<PostgreSqlColumnRow> columns = jdbcTemplate.query(COLUMNS_SQL, tableParameter,
                (rs, rowNumber) -> new PostgreSqlColumnRow(
                        rs.getInt("column_id"),
                        rs.getString("column_name"),
                        rs.getString("formatted_type"),
                        !rs.getBoolean("attnotnull"),
                        trimToNull(rs.getString("default_value")),
                        trimToNull(rs.getString("comments")),
                        trimToNull(rs.getString("attidentity")),
                        trimToNull(rs.getString("attgenerated"))));
        columns.stream().map(this::mapColumn).forEach(builder::addColumn);

        List<KeyConstraintRow> keys = jdbcTemplate.query(KEY_CONSTRAINTS_SQL, tableParameter,
                (rs, rowNumber) -> new KeyConstraintRow(
                        rs.getString("constraint_name"),
                        rs.getString("constraint_type"),
                        rs.getBoolean("condeferrable"),
                        rs.getBoolean("condeferred"),
                        rs.getString("column_name"),
                        rs.getInt("column_position")));
        mapKeys(builder, keys);

        List<ForeignKeyRow> foreignKeys = jdbcTemplate.query(FOREIGN_KEYS_SQL, tableParameter,
                (rs, rowNumber) -> new ForeignKeyRow(
                        rs.getString("constraint_name"),
                        rs.getBoolean("condeferrable"),
                        rs.getBoolean("condeferred"),
                        rs.getString("confdeltype"),
                        rs.getString("confupdtype"),
                        rs.getInt("column_position"),
                        rs.getString("column_name"),
                        rs.getString("referenced_schema"),
                        rs.getString("referenced_table"),
                        rs.getString("referenced_column")));
        mapForeignKeys(builder, foreignKeys);

        List<CheckRow> checks = jdbcTemplate.query(CHECKS_SQL, tableParameter,
                (rs, rowNumber) -> new CheckRow(
                        rs.getString("constraint_name"), trimToNull(rs.getString("definition"))));
        for (CheckRow check : checks) {
            String expression = unwrapCheck(check.definition());
            if (expression != null) builder.addCheck(new CheckConstraint(Identifier.of(check.name()), expression));
        }

        List<IndexRow> indexes = jdbcTemplate.query(INDEXES_SQL, tableParameter,
                (rs, rowNumber) -> new IndexRow(
                        rs.getString("index_name"),
                        rs.getBoolean("indisunique"),
                        rs.getString("access_method"),
                        rs.getInt("item_position"),
                        rs.getBoolean("key_column"),
                        rs.getString("column_name"),
                        trimToNull(rs.getString("item_definition")),
                        rs.getBoolean("descending"),
                        trimToNull(rs.getString("predicate"))));
        mapIndexes(builder, indexes);
        return Optional.of(builder.build());
    }

    @Override
    public boolean schemaExists(String schemaName) {
        if (schemaName == null || schemaName.isBlank()) return false;
        Integer count = jdbcTemplate.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE UPPER(schema_name) = UPPER(?)",
                Integer.class, schemaName);
        return count != null && count > 0;
    }

    @Override
    public List<String> findTableSchemas(String tableName) {
        if (tableName == null || tableName.isBlank()) return List.of();
        return jdbcTemplate.getJdbcTemplate().queryForList(
                "SELECT table_schema FROM information_schema.tables " +
                        "WHERE table_type = 'BASE TABLE' " +
                        "AND table_schema NOT IN ('pg_catalog','information_schema') " +
                        "AND UPPER(table_name) = UPPER(?) ORDER BY table_schema",
                String.class, tableName);
    }

    private Column mapColumn(PostgreSqlColumnRow row) {
        boolean identity = row.identityFlag() != null && !row.identityFlag().isBlank();
        boolean generated = row.generatedFlag() != null && !row.generatedFlag().isBlank();
        return new Column(
                Identifier.of(row.name()),
                parseType(row.formattedType()),
                row.nullable(),
                new DefaultValue(generated ? null : row.defaultValue()),
                new Description(row.comment()),
                identity,
                row.position(),
                generated ? row.defaultValue() : null);
    }

    private DataType parseType(String formattedType) {
        String value = formattedType == null ? "UNKNOWN" : formattedType.trim().toUpperCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        Matcher two = TYPE_WITH_TWO_ARGUMENTS.matcher(value);
        if (two.matches()) {
            String base = canonicalPostgreSqlType(two.group(1) + two.group(4));
            return DataType.numeric(base, Integer.parseInt(two.group(2)), Integer.parseInt(two.group(3)));
        }
        Matcher one = TYPE_WITH_ONE_ARGUMENT.matcher(value);
        if (one.matches()) {
            String base = canonicalPostgreSqlType(one.group(1) + one.group(3));
            int argument = Integer.parseInt(one.group(2));
            if (base.equals("VARCHAR") || base.equals("CHAR") || base.equals("BIT") || base.equals("VARBIT")) {
                return new DataType(Identifier.of(base), argument, LengthSemantics.CHAR, null, null);
            }
            if (argument == 0 && (base.startsWith("TIMESTAMP") || base.startsWith("TIME"))) {
                return DataType.simple(base);
            }
            return DataType.numeric(base, argument, null);
        }
        return DataType.simple(canonicalPostgreSqlType(value));
    }

    private static String canonicalPostgreSqlType(String raw) {
        String value = raw.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        return switch (value) {
            case "CHARACTER VARYING", "VARCHAR" -> "VARCHAR";
            case "CHARACTER", "CHAR" -> "CHAR";
            case "NUMERIC", "DECIMAL" -> "NUMERIC";
            case "TIMESTAMP WITHOUT TIME ZONE", "TIMESTAMP" -> "TIMESTAMP";
            case "TIMESTAMP WITH TIME ZONE" -> "TIMESTAMP_WITH_TIME_ZONE";
            case "TIME WITHOUT TIME ZONE", "TIME" -> "TIME";
            case "TIME WITH TIME ZONE" -> "TIME_WITH_TIME_ZONE";
            case "BIT VARYING", "VARBIT" -> "VARBIT";
            case "DOUBLE PRECISION" -> "DOUBLE";
            default -> safeTypeName(value, "POSTGRESQL");
        };
    }

    private void mapKeys(Table.Builder builder, List<KeyConstraintRow> rows) {
        Map<String, List<KeyConstraintRow>> groups = new LinkedHashMap<>();
        for (KeyConstraintRow row : rows) {
            groups.computeIfAbsent(row.name() + "|" + row.type(), ignored -> new ArrayList<>()).add(row);
        }
        for (List<KeyConstraintRow> group : groups.values()) {
            group.sort(Comparator.comparingInt(KeyConstraintRow::position));
            KeyConstraintRow first = group.getFirst();
            List<Identifier> columns = group.stream().map(KeyConstraintRow::column).map(Identifier::of).toList();
            if ("p".equals(first.type())) {
                builder.primaryKey(new PrimaryKey(Identifier.of(first.name()), columns,
                        first.deferrable(), first.initiallyDeferred()));
            } else {
                builder.addUniqueKey(new UniqueKey(Identifier.of(first.name()), columns,
                        first.deferrable(), first.initiallyDeferred()));
            }
        }
    }

    private void mapForeignKeys(Table.Builder builder, List<ForeignKeyRow> rows) {
        Map<String, List<ForeignKeyRow>> groups = new LinkedHashMap<>();
        for (ForeignKeyRow row : rows) groups.computeIfAbsent(row.name(), ignored -> new ArrayList<>()).add(row);
        for (List<ForeignKeyRow> group : groups.values()) {
            group.sort(Comparator.comparingInt(ForeignKeyRow::position));
            ForeignKeyRow first = group.getFirst();
            builder.addForeignKey(new ForeignKey(
                    Identifier.of(first.name()),
                    group.stream().map(ForeignKeyRow::column).map(Identifier::of).toList(),
                    QualifiedName.of(first.referencedSchema(), first.referencedTable()),
                    group.stream().map(ForeignKeyRow::referencedColumn).map(Identifier::of).toList(),
                    mapReferentialAction(first.deleteAction()),
                    mapReferentialAction(first.updateAction()),
                    first.deferrable(),
                    first.initiallyDeferred(),
                    true,
                    true));
        }
    }

    private void mapIndexes(Table.Builder builder, List<IndexRow> rows) {
        Map<String, List<IndexRow>> groups = new LinkedHashMap<>();
        for (IndexRow row : rows) groups.computeIfAbsent(row.name(), ignored -> new ArrayList<>()).add(row);
        for (Map.Entry<String, List<IndexRow>> entry : groups.entrySet()) {
            List<IndexRow> group = entry.getValue();
            group.sort(Comparator.comparingInt(IndexRow::position));
            List<IndexColumn> keyColumns = new ArrayList<>();
            List<Identifier> includeColumns = new ArrayList<>();
            for (IndexRow row : group) {
                if (!row.keyColumn()) {
                    if (row.column() != null && !row.column().isBlank()) includeColumns.add(Identifier.of(row.column()));
                    continue;
                }
                SortDirection direction = row.descending() ? SortDirection.DESC : SortDirection.ASC;
                if (row.column() != null && !row.column().isBlank()) {
                    keyColumns.add(new IndexColumn(Identifier.of(row.column()), direction));
                } else if (row.definition() != null) {
                    keyColumns.add(IndexColumn.expression(row.definition(), direction));
                }
            }
            if (keyColumns.isEmpty()) continue;
            IndexType type = group.getFirst().unique() ? IndexType.UNIQUE : IndexType.NORMAL;
            builder.addIndex(new Index(
                    Identifier.of(entry.getKey()), keyColumns, type, Description.empty(),
                    includeColumns, group.getFirst().predicate()));
        }
    }

    private static ReferentialAction mapReferentialAction(String code) {
        if (code == null || code.isBlank()) return ReferentialAction.NO_ACTION;
        return switch (code.charAt(0)) {
            case 'a' -> ReferentialAction.NO_ACTION;
            case 'r' -> ReferentialAction.RESTRICT;
            case 'c' -> ReferentialAction.CASCADE;
            case 'n' -> ReferentialAction.SET_NULL;
            case 'd' -> ReferentialAction.SET_DEFAULT;
            default -> ReferentialAction.NO_ACTION;
        };
    }

    private static String unwrapCheck(String definition) {
        if (definition == null) return null;
        String value = definition.trim();
        if (value.regionMatches(true, 0, "CHECK", 0, 5)) {
            int open = value.indexOf('(');
            int close = value.lastIndexOf(')');
            if (open >= 0 && close > open) return value.substring(open + 1, close).trim();
        }
        return value;
    }

    private static String profileSignature(ResultSet rs) throws SQLException {
        String raw = rs.getString("data_type");
        String type = switch (raw.toLowerCase(Locale.ROOT)) {
            case "character varying" -> "VARCHAR";
            case "character" -> "CHAR";
            case "numeric", "decimal" -> "NUMERIC";
            case "timestamp without time zone" -> "TIMESTAMP";
            case "timestamp with time zone" -> "TIMESTAMP WITH TIME ZONE";
            default -> raw.toUpperCase(Locale.ROOT);
        };
        Integer length = nullableInt(rs, "character_maximum_length");
        Integer precision = nullableInt(rs, "numeric_precision");
        Integer scale = nullableInt(rs, "numeric_scale");
        if ((type.equals("VARCHAR") || type.equals("CHAR")) && length != null) return type + "(" + length + ")";
        if (type.equals("NUMERIC") && precision != null) {
            return type + "(" + precision + (scale == null ? "" : "," + scale) + ")";
        }
        return type;
    }

    private static String safeTypeName(String value, String prefix) {
        String safe = value.replaceAll("[^A-Z0-9_$#]+", "_").replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (safe.isBlank() || !Character.isLetter(safe.charAt(0))) return prefix + "_" + safe;
        return safe;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private record TableInfo(long oid, String schema, String name, String comment) { }

    private record PostgreSqlColumnRow(int position, String name, String formattedType, boolean nullable,
                                       String defaultValue, String comment, String identityFlag,
                                       String generatedFlag) { }

    private record KeyConstraintRow(String name, String type, boolean deferrable,
                                    boolean initiallyDeferred, String column, int position) { }

    private record ForeignKeyRow(String name, boolean deferrable, boolean initiallyDeferred,
                                 String deleteAction, String updateAction, int position, String column,
                                 String referencedSchema, String referencedTable, String referencedColumn) { }

    private record CheckRow(String name, String definition) { }

    private record IndexRow(String name, boolean unique, String accessMethod, int position,
                            boolean keyColumn, String column, String definition,
                            boolean descending, String predicate) { }

    private record ProfileRow(String columnName, String typeSignature, long frequency) { }
}
