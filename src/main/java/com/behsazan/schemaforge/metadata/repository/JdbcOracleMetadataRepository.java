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

@Repository
@ConditionalOnProperty(prefix = "schemaforge.metadata.oracle", name = "enabled", havingValue = "true")
public class JdbcOracleMetadataRepository implements OracleMetadataRepository {
    private static final String COLUMN_PROFILE_SQL = """
            SELECT c.COLUMN_NAME,
                   c.DATA_TYPE,
                   c.CHAR_LENGTH,
                   c.DATA_PRECISION,
                   c.DATA_SCALE,
                   COUNT(DISTINCT c.OWNER || '.' || c.TABLE_NAME) AS FREQUENCY
              FROM ALL_TAB_COLUMNS c
              JOIN ALL_USERS u ON u.USERNAME = c.OWNER
             WHERE u.ORACLE_MAINTAINED = 'N'
               AND c.COLUMN_NAME IN (:columnNames)
             GROUP BY c.COLUMN_NAME, c.DATA_TYPE, c.CHAR_LENGTH, c.DATA_PRECISION, c.DATA_SCALE
             ORDER BY c.COLUMN_NAME, FREQUENCY DESC
            """;

    private static final String TABLE_SQL = """
            SELECT t.TABLE_NAME, tc.COMMENTS
              FROM ALL_TABLES t
              LEFT JOIN ALL_TAB_COMMENTS tc
                ON tc.OWNER = t.OWNER
               AND tc.TABLE_NAME = t.TABLE_NAME
               AND tc.TABLE_TYPE = 'TABLE'
             WHERE t.OWNER = :owner
               AND t.TABLE_NAME = :tableName
            """;

    private static final String COLUMNS_SQL = """
            SELECT c.COLUMN_ID,
                   c.COLUMN_NAME,
                   c.DATA_TYPE,
                   c.DATA_LENGTH,
                   c.CHAR_LENGTH,
                   c.CHAR_USED,
                   c.DATA_PRECISION,
                   c.DATA_SCALE,
                   c.NULLABLE,
                   c.DATA_DEFAULT,
                   c.IDENTITY_COLUMN,
                   c.VIRTUAL_COLUMN,
                   cc.COMMENTS
              FROM ALL_TAB_COLS c
              LEFT JOIN ALL_COL_COMMENTS cc
                ON cc.OWNER = c.OWNER
               AND cc.TABLE_NAME = c.TABLE_NAME
               AND cc.COLUMN_NAME = c.COLUMN_NAME
             WHERE c.OWNER = :owner
               AND c.TABLE_NAME = :tableName
               AND NVL(c.HIDDEN_COLUMN, 'NO') = 'NO'
             ORDER BY c.COLUMN_ID
            """;

    private static final String CONSTRAINTS_SQL = """
            SELECT c.CONSTRAINT_NAME,
                   c.CONSTRAINT_TYPE,
                   cc.COLUMN_NAME,
                   cc.POSITION AS COLUMN_POSITION,
                   c.SEARCH_CONDITION_VC AS EXPRESSION,
                   rc.OWNER AS REFERENCED_OWNER,
                   rc.TABLE_NAME AS REFERENCED_TABLE,
                   rcc.COLUMN_NAME AS REFERENCED_COLUMN,
                   c.DELETE_RULE,
                   c.DEFERRABLE,
                   c.DEFERRED
              FROM ALL_CONSTRAINTS c
              LEFT JOIN ALL_CONS_COLUMNS cc
                ON cc.OWNER = c.OWNER
               AND cc.CONSTRAINT_NAME = c.CONSTRAINT_NAME
               AND cc.TABLE_NAME = c.TABLE_NAME
              LEFT JOIN ALL_CONSTRAINTS rc
                ON rc.OWNER = c.R_OWNER
               AND rc.CONSTRAINT_NAME = c.R_CONSTRAINT_NAME
              LEFT JOIN ALL_CONS_COLUMNS rcc
                ON rcc.OWNER = rc.OWNER
               AND rcc.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
               AND rcc.TABLE_NAME = rc.TABLE_NAME
               AND rcc.POSITION = cc.POSITION
             WHERE c.OWNER = :owner
               AND c.TABLE_NAME = :tableName
               AND c.CONSTRAINT_TYPE IN ('P', 'R', 'U', 'C')
               AND (c.CONSTRAINT_TYPE <> 'C' OR c.GENERATED = 'USER NAME')
             ORDER BY c.CONSTRAINT_NAME, cc.POSITION
            """;

    private static final String INDEXES_SQL = """
            SELECT i.INDEX_NAME,
                   i.UNIQUENESS,
                   i.INDEX_TYPE,
                   ic.COLUMN_NAME,
                   ic.COLUMN_POSITION,
                   ic.DESCEND,
                   ie.COLUMN_EXPRESSION
              FROM ALL_INDEXES i
              JOIN ALL_IND_COLUMNS ic
                ON ic.INDEX_OWNER = i.OWNER
               AND ic.INDEX_NAME = i.INDEX_NAME
               AND ic.TABLE_OWNER = i.TABLE_OWNER
               AND ic.TABLE_NAME = i.TABLE_NAME
              LEFT JOIN ALL_IND_EXPRESSIONS ie
                ON ie.INDEX_OWNER = ic.INDEX_OWNER
               AND ie.INDEX_NAME = ic.INDEX_NAME
               AND ie.TABLE_OWNER = ic.TABLE_OWNER
               AND ie.TABLE_NAME = ic.TABLE_NAME
               AND ie.COLUMN_POSITION = ic.COLUMN_POSITION
             WHERE i.TABLE_OWNER = :owner
               AND i.TABLE_NAME = :tableName
             ORDER BY i.INDEX_NAME, ic.COLUMN_POSITION
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcOracleMetadataRepository(
            @Qualifier("oracleMetadataJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate) {
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
        String owner = schemaName.trim().toUpperCase(Locale.ROOT);
        String name = tableName.trim().toUpperCase(Locale.ROOT);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("owner", owner)
                .addValue("tableName", name);

        List<TableInfo> tables = jdbcTemplate.query(TABLE_SQL, parameters,
                (rs, rowNumber) -> new TableInfo(rs.getString("TABLE_NAME"), rs.getString("COMMENTS")));
        if (tables.isEmpty()) return Optional.empty();

        Table.Builder builder = Table.builder(owner, name);
        String comment = trimToNull(tables.getFirst().comment());
        if (comment != null) builder.description(comment);

        List<OracleColumnRow> columns = jdbcTemplate.query(COLUMNS_SQL, parameters,
                (rs, rowNumber) -> new OracleColumnRow(
                        nullableInt(rs, "COLUMN_ID"),
                        rs.getString("COLUMN_NAME"),
                        rs.getString("DATA_TYPE"),
                        nullableInt(rs, "DATA_LENGTH"),
                        nullableInt(rs, "CHAR_LENGTH"),
                        rs.getString("CHAR_USED"),
                        nullableInt(rs, "DATA_PRECISION"),
                        nullableInt(rs, "DATA_SCALE"),
                        "Y".equalsIgnoreCase(rs.getString("NULLABLE")),
                        trimToNull(rs.getString("DATA_DEFAULT")),
                        "YES".equalsIgnoreCase(rs.getString("IDENTITY_COLUMN")),
                        "YES".equalsIgnoreCase(rs.getString("VIRTUAL_COLUMN")),
                        trimToNull(rs.getString("COMMENTS"))));
        columns.stream().map(this::mapColumn).forEach(builder::addColumn);

        List<ConstraintRow> constraints = jdbcTemplate.query(CONSTRAINTS_SQL, parameters,
                (rs, rowNumber) -> new ConstraintRow(
                        rs.getString("CONSTRAINT_NAME"),
                        rs.getString("CONSTRAINT_TYPE"),
                        rs.getString("COLUMN_NAME"),
                        nullableInt(rs, "COLUMN_POSITION"),
                        trimToNull(rs.getString("EXPRESSION")),
                        rs.getString("REFERENCED_OWNER"),
                        rs.getString("REFERENCED_TABLE"),
                        rs.getString("REFERENCED_COLUMN"),
                        rs.getString("DELETE_RULE"),
                        "DEFERRABLE".equalsIgnoreCase(rs.getString("DEFERRABLE")),
                        "DEFERRED".equalsIgnoreCase(rs.getString("DEFERRED"))));
        mapConstraints(builder, constraints);

        List<IndexRow> indexes = jdbcTemplate.query(INDEXES_SQL, parameters,
                (rs, rowNumber) -> new IndexRow(
                        rs.getString("INDEX_NAME"),
                        "UNIQUE".equalsIgnoreCase(rs.getString("UNIQUENESS")),
                        rs.getString("INDEX_TYPE"),
                        rs.getString("COLUMN_NAME"),
                        nullableInt(rs, "COLUMN_POSITION"),
                        rs.getString("DESCEND"),
                        trimToNull(rs.getString("COLUMN_EXPRESSION"))));
        mapIndexes(builder, indexes);
        return Optional.of(builder.build());
    }

    @Override
    public boolean schemaExists(String schemaName) {
        if (schemaName == null || schemaName.isBlank()) return false;
        Integer count = jdbcTemplate.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM ALL_USERS WHERE USERNAME = ?", Integer.class,
                schemaName.toUpperCase(Locale.ROOT));
        return count != null && count > 0;
    }

    @Override
    public List<String> findTableSchemas(String tableName) {
        if (tableName == null || tableName.isBlank()) return List.of();
        return jdbcTemplate.getJdbcTemplate().queryForList(
                "SELECT OWNER FROM ALL_TABLES WHERE TABLE_NAME = ? AND OWNER NOT IN " +
                        "(SELECT USERNAME FROM ALL_USERS WHERE ORACLE_MAINTAINED = 'Y') ORDER BY OWNER",
                String.class, tableName.toUpperCase(Locale.ROOT));
    }

    private Column mapColumn(OracleColumnRow row) {
        String generatedExpression = row.virtualColumn() ? row.defaultValue() : null;
        DefaultValue defaultValue = new DefaultValue(row.virtualColumn() ? null : row.defaultValue());
        return new Column(
                Identifier.of(row.name()),
                mapDataType(row),
                row.nullable(),
                defaultValue,
                new Description(row.comment()),
                row.identity(),
                row.position(),
                generatedExpression);
    }

    private DataType mapDataType(OracleColumnRow row) {
        String raw = normalizeType(row.rawType());
        if (raw.equals("CHAR") || raw.equals("VARCHAR") || raw.equals("VARCHAR2")
                || raw.equals("NCHAR") || raw.equals("NVARCHAR2")) {
            int length = positiveLength(row.charLength(), row.dataLength());
            LengthSemantics semantics = switch (row.charUsed() == null ? "" : row.charUsed().toUpperCase(Locale.ROOT)) {
                case "C" -> LengthSemantics.CHAR;
                case "B" -> LengthSemantics.BYTE;
                default -> LengthSemantics.DEFAULT;
            };
            return new DataType(Identifier.of(raw), length, semantics, null, null);
        }
        if (raw.equals("RAW")) {
            return new DataType(Identifier.of(raw), positiveLength(row.dataLength(), row.charLength()),
                    LengthSemantics.DEFAULT, null, null);
        }
        if (raw.equals("NUMBER") || raw.equals("NUMERIC") || raw.equals("DECIMAL") || raw.equals("FLOAT")) {
            return row.precision() == null
                    ? DataType.simple(raw)
                    : DataType.numeric(raw, row.precision(), row.scale());
        }
        if (raw.startsWith("TIMESTAMP")) {
            String canonical = raw.contains("LOCAL TIME ZONE")
                    ? "TIMESTAMP_WITH_LOCAL_TIME_ZONE"
                    : raw.contains("TIME ZONE") ? "TIMESTAMP_WITH_TIME_ZONE" : "TIMESTAMP";
            return row.scale() == null || row.scale() <= 0 ? DataType.simple(canonical)
                    : DataType.numeric(canonical, row.scale(), null);
        }
        return DataType.simple(safeTypeName(raw));
    }

    private void mapConstraints(Table.Builder builder, List<ConstraintRow> rows) {
        Map<String, List<ConstraintRow>> groups = new LinkedHashMap<>();
        for (ConstraintRow row : rows) {
            groups.computeIfAbsent(row.name() + "|" + row.type(), ignored -> new ArrayList<>()).add(row);
        }
        for (List<ConstraintRow> group : groups.values()) {
            group.sort(Comparator.comparing(ConstraintRow::position, Comparator.nullsLast(Integer::compareTo)));
            ConstraintRow first = group.getFirst();
            Identifier name = identifierOrNull(first.name());
            List<Identifier> columns = group.stream().map(ConstraintRow::column)
                    .filter(value -> value != null && !value.isBlank()).map(Identifier::of).toList();
            switch (first.type()) {
                case "P" -> {
                    if (!columns.isEmpty()) builder.primaryKey(new PrimaryKey(
                            name, columns, first.deferrable(), first.initiallyDeferred()));
                }
                case "U" -> {
                    if (!columns.isEmpty()) builder.addUniqueKey(new UniqueKey(
                            name, columns, first.deferrable(), first.initiallyDeferred()));
                }
                case "C" -> {
                    if (first.expression() != null) builder.addCheck(new CheckConstraint(name, first.expression()));
                }
                case "R" -> {
                    List<Identifier> referencedColumns = group.stream().map(ConstraintRow::referencedColumn)
                            .filter(value -> value != null && !value.isBlank()).map(Identifier::of).toList();
                    if (!columns.isEmpty() && columns.size() == referencedColumns.size()
                            && first.referencedTable() != null && !first.referencedTable().isBlank()) {
                        builder.addForeignKey(new ForeignKey(
                                name,
                                columns,
                                QualifiedName.of(first.referencedOwner(), first.referencedTable()),
                                referencedColumns,
                                mapDeleteRule(first.deleteRule()),
                                ReferentialAction.NO_ACTION,
                                first.deferrable(),
                                first.initiallyDeferred(),
                                true,
                                true));
                    }
                }
                default -> { }
            }
        }
    }

    private void mapIndexes(Table.Builder builder, List<IndexRow> rows) {
        Map<String, List<IndexRow>> groups = new LinkedHashMap<>();
        for (IndexRow row : rows) groups.computeIfAbsent(row.name(), ignored -> new ArrayList<>()).add(row);
        for (Map.Entry<String, List<IndexRow>> entry : groups.entrySet()) {
            List<IndexRow> group = entry.getValue();
            group.sort(Comparator.comparing(IndexRow::position, Comparator.nullsLast(Integer::compareTo)));
            List<IndexColumn> columns = new ArrayList<>();
            for (IndexRow row : group) {
                SortDirection direction = "DESC".equalsIgnoreCase(row.direction())
                        ? SortDirection.DESC : SortDirection.ASC;
                if (row.expression() != null) columns.add(IndexColumn.expression(row.expression(), direction));
                else if (row.column() != null && !row.column().isBlank()) {
                    columns.add(new IndexColumn(Identifier.of(row.column()), direction));
                }
            }
            if (columns.isEmpty()) continue;
            IndexType type;
            if (group.getFirst().unique()) type = IndexType.UNIQUE;
            else if (group.getFirst().indexType() != null
                    && group.getFirst().indexType().toUpperCase(Locale.ROOT).contains("BITMAP")) type = IndexType.BITMAP;
            else if (group.stream().anyMatch(row -> row.expression() != null)) type = IndexType.FUNCTION_BASED;
            else type = IndexType.NORMAL;
            builder.addIndex(new Index(Identifier.of(entry.getKey()), columns, type, Description.empty()));
        }
    }

    private static ReferentialAction mapDeleteRule(String value) {
        if (value == null) return ReferentialAction.NO_ACTION;
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "CASCADE" -> ReferentialAction.CASCADE;
            case "SET NULL" -> ReferentialAction.SET_NULL;
            default -> ReferentialAction.NO_ACTION;
        };
    }

    private static String profileSignature(ResultSet rs) throws SQLException {
        String type = MetadataTypeFrequency.normalize(rs.getString("DATA_TYPE"));
        Integer length = nullableInt(rs, "CHAR_LENGTH");
        Integer precision = nullableInt(rs, "DATA_PRECISION");
        Integer scale = nullableInt(rs, "DATA_SCALE");
        if (type.contains("CHAR") && length != null && length > 0) return type + "(" + length + ")";
        if (precision != null) return type + "(" + precision + (scale == null ? "" : "," + scale) + ")";
        return type;
    }

    private static String normalizeType(String value) {
        return value == null ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String safeTypeName(String value) {
        String safe = value.replaceAll("[^A-Z0-9_$#]+", "_").replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (safe.isBlank() || !Character.isLetter(safe.charAt(0))) return "ORACLE_" + safe;
        return safe;
    }

    private static int positiveLength(Integer preferred, Integer fallback) {
        Integer value = preferred != null && preferred > 0 ? preferred : fallback;
        return value == null || value <= 0 ? 1 : value;
    }

    private static Identifier identifierOrNull(String value) {
        return value == null || value.isBlank() ? null : Identifier.of(value);
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

    private record TableInfo(String name, String comment) { }

    private record OracleColumnRow(Integer position, String name, String rawType, Integer dataLength,
                                   Integer charLength, String charUsed, Integer precision, Integer scale,
                                   boolean nullable, String defaultValue, boolean identity,
                                   boolean virtualColumn, String comment) { }

    private record ConstraintRow(String name, String type, String column, Integer position, String expression,
                                 String referencedOwner, String referencedTable, String referencedColumn,
                                 String deleteRule, boolean deferrable, boolean initiallyDeferred) { }

    private record IndexRow(String name, boolean unique, String indexType, String column, Integer position,
                            String direction, String expression) { }

    private record ProfileRow(String columnName, String typeSignature, long frequency) { }
}
