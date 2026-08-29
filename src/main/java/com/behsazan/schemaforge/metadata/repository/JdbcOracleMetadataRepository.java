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

/**
 * Provides access to jdbc oracle metadata data.
 *
 * @since 4.1
 */
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

    static final String TABLE_SQL = """
            SELECT t.TABLE_NAME,
                   tc.COMMENTS,
                   t.TABLESPACE_NAME,
                   t.PCT_FREE,
                   t.PCT_USED,
                   t.INI_TRANS,
                   t.LOGGING,
                   t.COMPRESSION,
                   t.COMPRESS_FOR,
                   t.DEGREE,
                   t.PARTITIONED
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
                   c.DEFERRED,
                   ci.TABLESPACE_NAME AS INDEX_TABLESPACE_NAME,
                   ci.PCT_FREE AS INDEX_PCT_FREE,
                   ci.INI_TRANS AS INDEX_INI_TRANS,
                   ci.LOGGING AS INDEX_LOGGING,
                   ci.COMPRESSION AS INDEX_COMPRESSION,
                   ci.PREFIX_LENGTH AS INDEX_PREFIX_LENGTH,
                   ci.DEGREE AS INDEX_DEGREE
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
              LEFT JOIN ALL_INDEXES ci
                ON ci.OWNER = c.INDEX_OWNER
               AND ci.INDEX_NAME = c.INDEX_NAME
             WHERE c.OWNER = :owner
               AND c.TABLE_NAME = :tableName
               AND c.CONSTRAINT_TYPE IN ('P', 'R', 'U', 'C')
               AND (c.CONSTRAINT_TYPE <> 'C' OR c.GENERATED = 'USER NAME')
             ORDER BY c.CONSTRAINT_NAME, cc.POSITION
            """;

    static final String INDEXES_SQL = """
            SELECT i.INDEX_NAME,
                   i.UNIQUENESS,
                   i.INDEX_TYPE,
                   ic.COLUMN_NAME,
                   ic.COLUMN_POSITION,
                   ic.DESCEND,
                   ie.COLUMN_EXPRESSION,
                   i.TABLESPACE_NAME,
                   i.PCT_FREE,
                   i.INI_TRANS,
                   i.LOGGING,
                   i.COMPRESSION,
                   i.PREFIX_LENGTH,
                   i.DEGREE
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
               AND NOT EXISTS (
                   SELECT 1
                     FROM ALL_CONSTRAINTS c
                    WHERE c.OWNER = i.TABLE_OWNER
                      AND c.TABLE_NAME = i.TABLE_NAME
                      AND c.CONSTRAINT_TYPE IN ('P', 'U')
                      AND c.INDEX_OWNER = i.OWNER
                      AND c.INDEX_NAME = i.INDEX_NAME
               )
             ORDER BY i.INDEX_NAME, ic.COLUMN_POSITION
            """;

    private static final String TABLES_BULK_SQL = TABLE_SQL
            .replace("t.TABLE_NAME = :tableName", "t.TABLE_NAME IN (:tableNames)")
            .replace("WHERE t.OWNER = :owner", "WHERE t.OWNER = :owner");

    private static final String COLUMNS_BULK_SQL = COLUMNS_SQL
            .replace("SELECT c.COLUMN_ID,", "SELECT c.TABLE_NAME AS OWNER_TABLE_NAME,\n                   c.COLUMN_ID,")
            .replace("c.TABLE_NAME = :tableName", "c.TABLE_NAME IN (:tableNames)")
            .replace("ORDER BY c.COLUMN_ID", "ORDER BY c.TABLE_NAME, c.COLUMN_ID");

    private static final String CONSTRAINTS_BULK_SQL = CONSTRAINTS_SQL
            .replace("SELECT c.CONSTRAINT_NAME,", "SELECT c.TABLE_NAME AS OWNER_TABLE_NAME,\n                   c.CONSTRAINT_NAME,")
            .replace("c.TABLE_NAME = :tableName", "c.TABLE_NAME IN (:tableNames)")
            .replace("ORDER BY c.CONSTRAINT_NAME, cc.POSITION",
                    "ORDER BY c.TABLE_NAME, c.CONSTRAINT_NAME, cc.POSITION");

    private static final String INDEXES_BULK_SQL = INDEXES_SQL
            .replace("SELECT i.INDEX_NAME,", "SELECT i.TABLE_NAME AS OWNER_TABLE_NAME,\n                   i.INDEX_NAME,")
            .replace("i.TABLE_NAME = :tableName", "i.TABLE_NAME IN (:tableNames)")
            .replace("ORDER BY i.INDEX_NAME, ic.COLUMN_POSITION",
                    "ORDER BY i.TABLE_NAME, i.INDEX_NAME, ic.COLUMN_POSITION");

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
                (rs, rowNumber) -> new TableInfo(
                        rs.getString("TABLE_NAME"),
                        rs.getString("COMMENTS"),
                        trimToNull(rs.getString("TABLESPACE_NAME")),
                        nullableInt(rs, "PCT_FREE"),
                        nullableInt(rs, "PCT_USED"),
                        nullableInt(rs, "INI_TRANS"),
                        trimToNull(rs.getString("LOGGING")),
                        trimToNull(rs.getString("COMPRESSION")),
                        trimToNull(rs.getString("COMPRESS_FOR")),
                        trimToNull(rs.getString("DEGREE")),
                        "YES".equalsIgnoreCase(rs.getString("PARTITIONED"))));
        if (tables.isEmpty()) return Optional.empty();

        Table.Builder builder = Table.builder(owner, name);
        TableInfo tableInfo = tables.getFirst();
        String comment = trimToNull(tableInfo.comment());
        if (comment != null) builder.description(comment);
        oracleTablePhysicalOptions(tableInfo).forEach(builder::physicalOption);

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
                        "DEFERRED".equalsIgnoreCase(rs.getString("DEFERRED")),
                        trimToNull(rs.getString("INDEX_TABLESPACE_NAME")),
                        nullableInt(rs, "INDEX_PCT_FREE"),
                        nullableInt(rs, "INDEX_INI_TRANS"),
                        trimToNull(rs.getString("INDEX_LOGGING")),
                        trimToNull(rs.getString("INDEX_COMPRESSION")),
                        nullableInt(rs, "INDEX_PREFIX_LENGTH"),
                        trimToNull(rs.getString("INDEX_DEGREE"))));
        mapConstraints(builder, constraints);

        List<IndexRow> indexes = jdbcTemplate.query(INDEXES_SQL, parameters,
                (rs, rowNumber) -> new IndexRow(
                        rs.getString("INDEX_NAME"),
                        "UNIQUE".equalsIgnoreCase(rs.getString("UNIQUENESS")),
                        rs.getString("INDEX_TYPE"),
                        rs.getString("COLUMN_NAME"),
                        nullableInt(rs, "COLUMN_POSITION"),
                        rs.getString("DESCEND"),
                        trimToNull(rs.getString("COLUMN_EXPRESSION")),
                        trimToNull(rs.getString("TABLESPACE_NAME")),
                        nullableInt(rs, "PCT_FREE"),
                        nullableInt(rs, "INI_TRANS"),
                        trimToNull(rs.getString("LOGGING")),
                        trimToNull(rs.getString("COMPRESSION")),
                        nullableInt(rs, "PREFIX_LENGTH"),
                        trimToNull(rs.getString("DEGREE"))));
        mapIndexes(builder, indexes);
        return Optional.of(builder.build());
    }

    @Override
    public Map<String, Table> findTables(String schemaName, Set<String> tableNames) {
        if (schemaName == null || schemaName.isBlank() || tableNames == null || tableNames.isEmpty()) {
            return Map.of();
        }
        String owner = schemaName.trim().toUpperCase(Locale.ROOT);
        Set<String> names = MetadataRepositorySupport.normalizeNames(tableNames, true);
        if (names.isEmpty()) return Map.of();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("owner", owner)
                .addValue("tableNames", names);

        Map<String, Table.Builder> builders = new LinkedHashMap<>();
        List<TableInfo> tableInfos = jdbcTemplate.query(TABLES_BULK_SQL, parameters,
                (rs, rowNumber) -> new TableInfo(
                        rs.getString("TABLE_NAME"),
                        rs.getString("COMMENTS"),
                        trimToNull(rs.getString("TABLESPACE_NAME")),
                        nullableInt(rs, "PCT_FREE"),
                        nullableInt(rs, "PCT_USED"),
                        nullableInt(rs, "INI_TRANS"),
                        trimToNull(rs.getString("LOGGING")),
                        trimToNull(rs.getString("COMPRESSION")),
                        trimToNull(rs.getString("COMPRESS_FOR")),
                        trimToNull(rs.getString("DEGREE")),
                        "YES".equalsIgnoreCase(rs.getString("PARTITIONED"))));
        for (TableInfo info : tableInfos) {
            String tableName = info.name().toUpperCase(Locale.ROOT);
            Table.Builder builder = Table.builder(owner, tableName);
            String comment = trimToNull(info.comment());
            if (comment != null) builder.description(comment);
            oracleTablePhysicalOptions(info).forEach(builder::physicalOption);
            builders.put(tableName, builder);
        }
        if (builders.isEmpty()) return Map.of();

        List<BulkOracleColumnRow> columns = jdbcTemplate.query(COLUMNS_BULK_SQL, parameters,
                (rs, rowNumber) -> new BulkOracleColumnRow(
                        rs.getString("OWNER_TABLE_NAME"),
                        new OracleColumnRow(
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
                                trimToNull(rs.getString("COMMENTS")))));
        for (BulkOracleColumnRow item : columns) {
            Table.Builder builder = builders.get(item.tableName().toUpperCase(Locale.ROOT));
            if (builder != null) builder.addColumn(mapColumn(item.row()));
        }

        Map<String, List<ConstraintRow>> constraintsByTable = new LinkedHashMap<>();
        List<BulkConstraintRow> constraints = jdbcTemplate.query(CONSTRAINTS_BULK_SQL, parameters,
                (rs, rowNumber) -> new BulkConstraintRow(
                        rs.getString("OWNER_TABLE_NAME"),
                        new ConstraintRow(
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
                                "DEFERRED".equalsIgnoreCase(rs.getString("DEFERRED")),
                                trimToNull(rs.getString("INDEX_TABLESPACE_NAME")),
                                nullableInt(rs, "INDEX_PCT_FREE"),
                                nullableInt(rs, "INDEX_INI_TRANS"),
                                trimToNull(rs.getString("INDEX_LOGGING")),
                                trimToNull(rs.getString("INDEX_COMPRESSION")),
                                nullableInt(rs, "INDEX_PREFIX_LENGTH"),
                                trimToNull(rs.getString("INDEX_DEGREE")))));
        for (BulkConstraintRow item : constraints) {
            constraintsByTable.computeIfAbsent(item.tableName().toUpperCase(Locale.ROOT), ignored -> new ArrayList<>())
                    .add(item.row());
        }
        constraintsByTable.forEach((tableName, rows) -> {
            Table.Builder builder = builders.get(tableName);
            if (builder != null) mapConstraints(builder, rows);
        });

        Map<String, List<IndexRow>> indexesByTable = new LinkedHashMap<>();
        List<BulkIndexRow> indexes = jdbcTemplate.query(INDEXES_BULK_SQL, parameters,
                (rs, rowNumber) -> new BulkIndexRow(
                        rs.getString("OWNER_TABLE_NAME"),
                        new IndexRow(
                                rs.getString("INDEX_NAME"),
                                "UNIQUE".equalsIgnoreCase(rs.getString("UNIQUENESS")),
                                rs.getString("INDEX_TYPE"),
                                rs.getString("COLUMN_NAME"),
                                nullableInt(rs, "COLUMN_POSITION"),
                                rs.getString("DESCEND"),
                                trimToNull(rs.getString("COLUMN_EXPRESSION")),
                                trimToNull(rs.getString("TABLESPACE_NAME")),
                                nullableInt(rs, "PCT_FREE"),
                                nullableInt(rs, "INI_TRANS"),
                                trimToNull(rs.getString("LOGGING")),
                                trimToNull(rs.getString("COMPRESSION")),
                                nullableInt(rs, "PREFIX_LENGTH"),
                                trimToNull(rs.getString("DEGREE")))));
        for (BulkIndexRow item : indexes) {
            indexesByTable.computeIfAbsent(item.tableName().toUpperCase(Locale.ROOT), ignored -> new ArrayList<>())
                    .add(item.row());
        }
        indexesByTable.forEach((tableName, rows) -> {
            Table.Builder builder = builders.get(tableName);
            if (builder != null) mapIndexes(builder, rows);
        });

        Map<String, Table> result = new LinkedHashMap<>();
        builders.forEach((tableName, builder) -> result.put(tableName, builder.build()));
        return Map.copyOf(result);
    }

    @Override
    public boolean bulkTableReadOptimized() {
        return true;
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

    @Override
    public Map<String, List<String>> findTableSchemas(Set<String> tableNames) {
        Set<String> names = MetadataRepositorySupport.normalizeNames(tableNames, true);
        if (names.isEmpty()) return Map.of();
        String sql = "SELECT TABLE_NAME, OWNER FROM ALL_TABLES "
                + "WHERE TABLE_NAME IN (:tableNames) AND OWNER NOT IN "
                + "(SELECT USERNAME FROM ALL_USERS WHERE ORACLE_MAINTAINED = 'Y') "
                + "ORDER BY TABLE_NAME, OWNER";
        Map<String, List<String>> result = new LinkedHashMap<>();
        names.forEach(name -> result.put(name, new ArrayList<>()));
        jdbcTemplate.query(sql, new MapSqlParameterSource("tableNames", names), rs -> {
            String tableName = rs.getString("TABLE_NAME").toUpperCase(Locale.ROOT);
            result.computeIfAbsent(tableName, ignored -> new ArrayList<>()).add(rs.getString("OWNER"));
        });
        Map<String, List<String>> immutable = new LinkedHashMap<>();
        result.forEach((name, schemas) -> immutable.put(name, List.copyOf(schemas)));
        return Map.copyOf(immutable);
    }

    @Override
    public boolean bulkTableSchemaReadOptimized() {
        return true;
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
                            name, columns, first.deferrable(), first.initiallyDeferred(),
                            oracleIndexPhysicalOptions(first.indexTablespace(), first.indexPctFree(),
                                    first.indexIniTrans(), first.indexLogging(), first.indexCompression(),
                                    first.indexPrefixLength(), first.indexDegree())));
                }
                case "U" -> {
                    if (!columns.isEmpty()) builder.addUniqueKey(new UniqueKey(
                            name, columns, first.deferrable(), first.initiallyDeferred(),
                            oracleIndexPhysicalOptions(first.indexTablespace(), first.indexPctFree(),
                                    first.indexIniTrans(), first.indexLogging(), first.indexCompression(),
                                    first.indexPrefixLength(), first.indexDegree())));
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
            IndexRow first = group.getFirst();
            builder.addIndex(new Index(Identifier.of(entry.getKey()), columns, type, Description.empty(),
                    List.of(), null, oracleIndexPhysicalOptions(first.tablespace(), first.pctFree(),
                    first.iniTrans(), first.logging(), first.compression(), first.prefixLength(), first.degree())));
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

    static Map<String, String> oracleTablePhysicalOptions(TableInfo info) {
        Map<String, String> options = new LinkedHashMap<>();
        put(options, "TABLESPACE", info.tablespace());
        put(options, "ORACLE_PCTFREE", info.pctFree());
        put(options, "ORACLE_PCTUSED", info.pctUsed());
        put(options, "ORACLE_INITRANS", info.iniTrans());

        String logging = trimToNull(info.logging());
        if (logging != null) {
            if ("YES".equalsIgnoreCase(logging)) options.put("ORACLE_TABLE_LOGGING", "LOGGING");
            else if ("NO".equalsIgnoreCase(logging)) options.put("ORACLE_TABLE_LOGGING", "NOLOGGING");
            else options.put("ORACLE_TABLE_LOGGING", "REVIEW:" + logging);
        }

        String compression = trimToNull(info.compression());
        String compressFor = trimToNull(info.compressFor());
        if (compression != null) {
            if ("DISABLED".equalsIgnoreCase(compression)) {
                options.put("ORACLE_TABLE_COMPRESSION", "NOCOMPRESS");
            } else if ("ENABLED".equalsIgnoreCase(compression)) {
                options.put("ORACLE_TABLE_COMPRESSION", oracleCompression(compressFor));
            } else {
                options.put("ORACLE_TABLE_COMPRESSION", "REVIEW:" + compression
                        + (compressFor == null ? "" : " / " + compressFor));
            }
        }

        String degree = trimToNull(info.degree());
        if (degree != null) {
            if ("DEFAULT".equalsIgnoreCase(degree)) {
                options.put("ORACLE_TABLE_PARALLEL", "PARALLEL");
            } else {
                try {
                    int value = Integer.parseInt(degree);
                    options.put("ORACLE_TABLE_PARALLEL", value <= 1 ? "NOPARALLEL" : "PARALLEL " + value);
                } catch (NumberFormatException exception) {
                    options.put("ORACLE_TABLE_PARALLEL", "REVIEW:" + degree);
                }
            }
        }
        return Map.copyOf(options);
    }

    static Map<String, String> oracleIndexPhysicalOptions(
            String tablespace, Integer pctFree, Integer iniTrans, String logging,
            String compression, Integer prefixLength, String degree) {
        Map<String, String> options = new LinkedHashMap<>();
        put(options, "INDEX_TABLESPACE", tablespace);
        put(options, "ORACLE_INDEX_PCTFREE", pctFree);
        put(options, "ORACLE_INDEX_INITRANS", iniTrans);

        String normalizedLogging = trimToNull(logging);
        if (normalizedLogging != null) {
            if ("YES".equalsIgnoreCase(normalizedLogging)) options.put("ORACLE_INDEX_LOGGING", "LOGGING");
            else if ("NO".equalsIgnoreCase(normalizedLogging)) options.put("ORACLE_INDEX_LOGGING", "NOLOGGING");
            else options.put("ORACLE_INDEX_LOGGING", "REVIEW:" + normalizedLogging);
        }

        String normalizedCompression = trimToNull(compression);
        if (normalizedCompression != null) {
            String upper = normalizedCompression.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
            switch (upper) {
                case "DISABLED" -> options.put("ORACLE_INDEX_COMPRESSION", "NOCOMPRESS");
                case "ENABLED" -> options.put("ORACLE_INDEX_COMPRESSION",
                        prefixLength != null && prefixLength > 0 ? "COMPRESS " + prefixLength : "COMPRESS");
                case "ADVANCED LOW" -> options.put("ORACLE_INDEX_COMPRESSION", "COMPRESS ADVANCED LOW");
                case "ADVANCED HIGH" -> options.put("ORACLE_INDEX_COMPRESSION", "COMPRESS ADVANCED HIGH");
                default -> options.put("ORACLE_INDEX_COMPRESSION", "REVIEW:" + normalizedCompression);
            }
        }

        String normalizedDegree = trimToNull(degree);
        if (normalizedDegree != null) {
            if ("DEFAULT".equalsIgnoreCase(normalizedDegree)) {
                options.put("ORACLE_INDEX_PARALLEL", "PARALLEL");
            } else {
                try {
                    int value = Integer.parseInt(normalizedDegree);
                    options.put("ORACLE_INDEX_PARALLEL", value <= 1 ? "NOPARALLEL" : "PARALLEL " + value);
                } catch (NumberFormatException exception) {
                    options.put("ORACLE_INDEX_PARALLEL", "REVIEW:" + normalizedDegree);
                }
            }
        }
        return Map.copyOf(options);
    }

    private static String oracleCompression(String compressFor) {
        if (compressFor == null) return "COMPRESS";
        String normalized = compressFor.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        return switch (normalized) {
            case "BASIC" -> "COMPRESS BASIC";
            case "ADVANCED" -> "ROW STORE COMPRESS ADVANCED";
            case "QUERY LOW", "QUERY HIGH", "ARCHIVE LOW", "ARCHIVE HIGH" ->
                    "COLUMN STORE COMPRESS FOR " + normalized;
            default -> "REVIEW:" + normalized;
        };
    }

    private static void put(Map<String, String> options, String key, Object value) {
        if (value == null) return;
        String normalized = trimToNull(String.valueOf(value));
        if (normalized != null) options.put(key, normalized);
    }

    record TableInfo(String name, String comment, String tablespace,
                     Integer pctFree, Integer pctUsed, Integer iniTrans,
                     String logging, String compression, String compressFor,
                     String degree, boolean partitioned) { }

    private record OracleColumnRow(Integer position, String name, String rawType, Integer dataLength,
                                   Integer charLength, String charUsed, Integer precision, Integer scale,
                                   boolean nullable, String defaultValue, boolean identity,
                                   boolean virtualColumn, String comment) { }

    private record ConstraintRow(String name, String type, String column, Integer position, String expression,
                                 String referencedOwner, String referencedTable, String referencedColumn,
                                 String deleteRule, boolean deferrable, boolean initiallyDeferred,
                                 String indexTablespace, Integer indexPctFree, Integer indexIniTrans,
                                 String indexLogging, String indexCompression, Integer indexPrefixLength,
                                 String indexDegree) { }

    private record IndexRow(String name, boolean unique, String indexType, String column, Integer position,
                            String direction, String expression, String tablespace, Integer pctFree,
                            Integer iniTrans, String logging, String compression, Integer prefixLength,
                            String degree) { }

    private record BulkOracleColumnRow(String tableName, OracleColumnRow row) { }

    private record BulkConstraintRow(String tableName, ConstraintRow row) { }

    private record BulkIndexRow(String tableName, IndexRow row) { }

    private record ProfileRow(String columnName, String typeSignature, long frequency) { }
}
