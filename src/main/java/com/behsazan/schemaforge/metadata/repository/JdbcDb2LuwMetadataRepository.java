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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Reads Db2 LUW logical metadata from the documented SYSCAT catalog views. */
@Repository
@ConditionalOnProperty(prefix = "schemaforge.metadata.db2luw", name = "enabled", havingValue = "true")
public class JdbcDb2LuwMetadataRepository implements Db2LuwMetadataRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcDb2LuwMetadataRepository.class);
    private static final String REVIEW_PREFIX = "REVIEW:";

    static final String COLUMN_PROFILE_SQL = """
            SELECT C.COLNAME AS COLUMN_NAME,
                   C.TYPESCHEMA,
                   C.TYPENAME,
                   C.LENGTH,
                   C.SCALE,
                   C.STRINGUNITSLENGTH,
                   COUNT(DISTINCT C.TABSCHEMA || '.' || C.TABNAME) AS FREQUENCY
              FROM SYSCAT.COLUMNS C
              JOIN SYSCAT.TABLES T
                ON T.TABSCHEMA = C.TABSCHEMA
               AND T.TABNAME = C.TABNAME
             WHERE T.TYPE IN ('T', 'U')
               AND C.TABSCHEMA NOT IN ('SYSIBM', 'SYSCAT', 'SYSSTAT', 'SYSFUN', 'SYSPROC', 'SYSTOOLS')
               AND COALESCE(C.HIDDEN, ' ') = ' '
               AND UPPER(C.COLNAME) IN (:columnNames)
             GROUP BY C.COLNAME, C.TYPESCHEMA, C.TYPENAME, C.LENGTH, C.SCALE, C.STRINGUNITSLENGTH
             ORDER BY C.COLNAME, FREQUENCY DESC
             WITH UR
            """;

    static final String TABLE_SQL = """
            SELECT TABSCHEMA AS SCHEMA_NAME,
                   TABNAME AS TABLE_NAME,
                   REMARKS
              FROM SYSCAT.TABLES
             WHERE TYPE IN ('T', 'U')
               AND UPPER(TABSCHEMA) = UPPER(:schemaName)
               AND UPPER(TABNAME) = UPPER(:tableName)
             ORDER BY CASE
                        WHEN TABSCHEMA = :schemaName AND TABNAME = :tableName THEN 0
                        ELSE 1
                      END,
                      TABSCHEMA,
                      TABNAME
             WITH UR
            """;

    static final String TABLE_PHYSICAL_SQL = """
            SELECT TBSPACE,
                   INDEX_TBSPACE,
                   LONG_TBSPACE,
                   PCTFREE,
                   APPEND_MODE,
                   VOLATILE,
                   COMPRESSION,
                   ROWCOMPMODE,
                   TABLEORG
              FROM SYSCAT.TABLES
             WHERE TABSCHEMA = :schemaName
               AND TABNAME = :tableName
               AND TYPE IN ('T', 'U')
             WITH UR
            """;

    static final String COLUMNS_SQL = """
            SELECT COLNO + 1 AS COLUMN_ID,
                   COLNAME AS COLUMN_NAME,
                   TYPESCHEMA,
                   TYPENAME,
                   LENGTH,
                   SCALE,
                   STRINGUNITSLENGTH,
                   NULLS,
                   DEFAULT AS DEFAULT_VALUE,
                   REMARKS,
                   IDENTITY,
                   GENERATED,
                   TEXT
              FROM SYSCAT.COLUMNS
             WHERE TABSCHEMA = :schemaName
               AND TABNAME = :tableName
               AND COALESCE(HIDDEN, ' ') = ' '
             ORDER BY COLNO
             WITH UR
            """;

    static final String KEY_CONSTRAINTS_SQL = """
            SELECT C.CONSTNAME AS CONSTRAINT_NAME,
                   C.TYPE AS CONSTRAINT_TYPE,
                   K.COLNAME AS COLUMN_NAME,
                   K.COLSEQ AS COLUMN_POSITION
              FROM SYSCAT.TABCONST C
              JOIN SYSCAT.KEYCOLUSE K
                ON K.TABSCHEMA = C.TABSCHEMA
               AND K.TABNAME = C.TABNAME
               AND K.CONSTNAME = C.CONSTNAME
             WHERE C.TABSCHEMA = :schemaName
               AND C.TABNAME = :tableName
               AND C.TYPE IN ('P', 'U')
             ORDER BY C.CONSTNAME, K.COLSEQ
             WITH UR
            """;

    static final String FOREIGN_KEYS_SQL = """
            SELECT R.CONSTNAME AS CONSTRAINT_NAME,
                   CHILD.COLSEQ AS COLUMN_POSITION,
                   CHILD.COLNAME AS COLUMN_NAME,
                   R.REFTABSCHEMA AS REFERENCED_SCHEMA,
                   R.REFTABNAME AS REFERENCED_TABLE,
                   PARENT.COLNAME AS REFERENCED_COLUMN,
                   R.DELETERULE,
                   R.UPDATERULE
              FROM SYSCAT.REFERENCES R
              JOIN SYSCAT.KEYCOLUSE CHILD
                ON CHILD.TABSCHEMA = R.TABSCHEMA
               AND CHILD.TABNAME = R.TABNAME
               AND CHILD.CONSTNAME = R.CONSTNAME
              JOIN SYSCAT.KEYCOLUSE PARENT
                ON PARENT.TABSCHEMA = R.REFTABSCHEMA
               AND PARENT.TABNAME = R.REFTABNAME
               AND PARENT.CONSTNAME = R.REFKEYNAME
               AND PARENT.COLSEQ = CHILD.COLSEQ
             WHERE R.TABSCHEMA = :schemaName
               AND R.TABNAME = :tableName
             ORDER BY R.CONSTNAME, CHILD.COLSEQ
             WITH UR
            """;

    static final String CHECKS_SQL = """
            SELECT CONSTNAME AS CONSTRAINT_NAME,
                   TEXT AS DEFINITION
              FROM SYSCAT.CHECKS
             WHERE TABSCHEMA = :schemaName
               AND TABNAME = :tableName
             ORDER BY CONSTNAME
             WITH UR
            """;

    static final String INDEXES_SQL = """
            SELECT I.INDSCHEMA AS INDEX_SCHEMA,
                   I.INDNAME AS INDEX_NAME,
                   I.UNIQUERULE,
                   U.COLSEQ AS COLUMN_POSITION,
                   U.COLORDER,
                   U.COLNAME AS COLUMN_NAME,
                   U.VIRTUAL,
                   U.TEXT AS EXPRESSION_TEXT,
                   TS.TBSPACE AS INDEX_TABLESPACE,
                   I.PCTFREE,
                   I.MINPCTUSED,
                   I.REVERSE_SCANS,
                   I.COMPRESSION,
                   I.PAGESPLIT
              FROM SYSCAT.INDEXES I
              JOIN SYSCAT.INDEXCOLUSE U
                ON U.INDSCHEMA = I.INDSCHEMA
               AND U.INDNAME = I.INDNAME
              LEFT JOIN SYSCAT.TABLESPACES TS
                ON TS.TBSPACEID = I.TBSPACEID
             WHERE I.TABSCHEMA = :schemaName
               AND I.TABNAME = :tableName
               AND I.INDEXTYPE = 'REG'
             ORDER BY I.INDSCHEMA, I.INDNAME, U.COLSEQ
             WITH UR
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcDb2LuwMetadataRepository(
            @Qualifier("db2LuwMetadataJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate) {
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
                        rs.getString("SCHEMA_NAME"),
                        rs.getString("TABLE_NAME"),
                        trimToNull(rs.getString("REMARKS"))));
        if (tables.isEmpty()) {
            LOGGER.warn("Db2 LUW metadata table not found. requested={}.{}, visibleTableSchemas={}",
                    schemaName, tableName, findTableSchemas(tableName));
            return Optional.empty();
        }

        TableInfo info = tables.getFirst();
        Table.Builder builder = Table.builder(info.schema(), info.name());
        if (info.comment() != null) builder.description(info.comment());

        MapSqlParameterSource exact = new MapSqlParameterSource()
                .addValue("schemaName", info.schema())
                .addValue("tableName", info.name());

        List<Db2LuwTablePhysicalRow> tablePhysical = jdbcTemplate.query(TABLE_PHYSICAL_SQL, exact,
                (rs, rowNumber) -> new Db2LuwTablePhysicalRow(
                        trimToNull(rs.getString("TBSPACE")),
                        trimToNull(rs.getString("INDEX_TBSPACE")),
                        trimToNull(rs.getString("LONG_TBSPACE")),
                        nullableInt(rs, "PCTFREE"),
                        trimToNull(rs.getString("APPEND_MODE")),
                        trimToNull(rs.getString("VOLATILE")),
                        trimToNull(rs.getString("COMPRESSION")),
                        trimToNull(rs.getString("ROWCOMPMODE")),
                        trimToNull(rs.getString("TABLEORG"))));
        if (!tablePhysical.isEmpty()) {
            db2LuwTablePhysicalOptions(tablePhysical.getFirst()).forEach(builder::physicalOption);
        }

        List<Db2LuwColumnRow> columns = jdbcTemplate.query(COLUMNS_SQL, exact,
                (rs, rowNumber) -> new Db2LuwColumnRow(
                        rs.getInt("COLUMN_ID"),
                        rs.getString("COLUMN_NAME"),
                        trimToNull(rs.getString("TYPESCHEMA")),
                        rs.getString("TYPENAME"),
                        nullableInt(rs, "LENGTH"),
                        nullableInt(rs, "SCALE"),
                        nullableInt(rs, "STRINGUNITSLENGTH"),
                        "Y".equalsIgnoreCase(rs.getString("NULLS")),
                        trimToNull(rs.getString("DEFAULT_VALUE")),
                        trimToNull(rs.getString("REMARKS")),
                        "Y".equalsIgnoreCase(rs.getString("IDENTITY")),
                        trimToNull(rs.getString("GENERATED")),
                        trimToNull(rs.getString("TEXT"))));
        columns.stream().map(JdbcDb2LuwMetadataRepository::mapColumn).forEach(builder::addColumn);

        List<IndexRow> indexes = jdbcTemplate.query(INDEXES_SQL, exact,
                (rs, rowNumber) -> new IndexRow(
                        rs.getString("INDEX_SCHEMA"),
                        rs.getString("INDEX_NAME"),
                        rs.getString("UNIQUERULE"),
                        rs.getInt("COLUMN_POSITION"),
                        rs.getString("COLORDER"),
                        trimToNull(rs.getString("COLUMN_NAME")),
                        trimToNull(rs.getString("VIRTUAL")),
                        trimToNull(rs.getString("EXPRESSION_TEXT")),
                        trimToNull(rs.getString("INDEX_TABLESPACE")),
                        nullableInt(rs, "PCTFREE"),
                        nullableInt(rs, "MINPCTUSED"),
                        trimToNull(rs.getString("REVERSE_SCANS")),
                        trimToNull(rs.getString("COMPRESSION")),
                        trimToNull(rs.getString("PAGESPLIT"))));

        List<KeyConstraintRow> keys = jdbcTemplate.query(KEY_CONSTRAINTS_SQL, exact,
                (rs, rowNumber) -> new KeyConstraintRow(
                        rs.getString("CONSTRAINT_NAME"),
                        rs.getString("CONSTRAINT_TYPE"),
                        rs.getString("COLUMN_NAME"),
                        rs.getInt("COLUMN_POSITION")));
        mapKeys(builder, keys, indexes);

        List<ForeignKeyRow> foreignKeys = jdbcTemplate.query(FOREIGN_KEYS_SQL, exact,
                (rs, rowNumber) -> new ForeignKeyRow(
                        rs.getString("CONSTRAINT_NAME"),
                        rs.getInt("COLUMN_POSITION"),
                        rs.getString("COLUMN_NAME"),
                        rs.getString("REFERENCED_SCHEMA"),
                        rs.getString("REFERENCED_TABLE"),
                        rs.getString("REFERENCED_COLUMN"),
                        rs.getString("DELETERULE"),
                        rs.getString("UPDATERULE")));
        mapForeignKeys(builder, foreignKeys);

        List<CheckRow> checks = jdbcTemplate.query(CHECKS_SQL, exact,
                (rs, rowNumber) -> new CheckRow(
                        rs.getString("CONSTRAINT_NAME"), trimToNull(rs.getString("DEFINITION"))));
        for (CheckRow check : checks) {
            if (check.expression() != null) {
                builder.addCheck(new CheckConstraint(identifierOrNull(check.name()), check.expression()));
            }
        }

        mapIndexes(builder, indexes, keys);

        return Optional.of(builder.build());
    }

    @Override
    public boolean schemaExists(String schemaName) {
        if (schemaName == null || schemaName.isBlank()) return false;
        Integer count = jdbcTemplate.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM SYSCAT.SCHEMATA WHERE UPPER(SCHEMANAME) = UPPER(?)",
                Integer.class, schemaName);
        return count != null && count > 0;
    }

    @Override
    public List<String> findTableNames(String schemaName) {
        if (schemaName == null || schemaName.isBlank()) return List.of();
        return jdbcTemplate.getJdbcTemplate().queryForList(
                "SELECT TABNAME FROM SYSCAT.TABLES "
                        + "WHERE TYPE IN ('T','U') "
                        + "AND TABSCHEMA NOT IN ('SYSIBM','SYSCAT','SYSSTAT','SYSFUN','SYSPROC','SYSTOOLS') "
                        + "AND UPPER(TABSCHEMA)=UPPER(?) ORDER BY TABNAME",
                String.class, schemaName);
    }

    @Override
    public List<String> findTableSchemas(String tableName) {
        if (tableName == null || tableName.isBlank()) return List.of();
        return jdbcTemplate.getJdbcTemplate().queryForList(
                "SELECT TABSCHEMA FROM SYSCAT.TABLES "
                        + "WHERE TYPE IN ('T','U') "
                        + "AND TABSCHEMA NOT IN ('SYSIBM','SYSCAT','SYSSTAT','SYSFUN','SYSPROC','SYSTOOLS') "
                        + "AND UPPER(TABNAME)=UPPER(?) ORDER BY TABSCHEMA",
                String.class, tableName);
    }

    static Column mapColumn(Db2LuwColumnRow row) {
        boolean generatedExpression = !row.identity() && row.generated() != null
                && !row.generated().isBlank() && row.generatedText() != null;
        String expression = generatedExpression ? normalizeGeneratedExpression(row.generatedText()) : null;
        DefaultValue defaultValue = row.identity() || expression != null
                ? new DefaultValue(null)
                : new DefaultValue(row.defaultValue());
        return new Column(
                Identifier.of(row.name()),
                mapDataType(row),
                row.nullable(),
                defaultValue,
                row.comment() == null ? Description.empty() : new Description(row.comment()),
                row.identity(),
                row.position(),
                expression,
                Map.of());
    }

    static DataType mapDataType(Db2LuwColumnRow row) {
        return Db2LuwCatalogTypeMapper.mapDataType(
                row.typeName(), row.length(), row.scale(), row.stringUnitsLength(), row.typeSchema());
    }

    static void mapKeys(Table.Builder builder, List<KeyConstraintRow> rows) {
        mapKeys(builder, rows, List.of());
    }

    static void mapKeys(Table.Builder builder, List<KeyConstraintRow> rows, List<IndexRow> indexes) {
        Map<List<String>, Map<String, String>> indexPhysicalByKey = constraintIndexPhysicalByKey(indexes);
        Map<String, List<KeyConstraintRow>> grouped = new LinkedHashMap<>();
        for (KeyConstraintRow row : rows) {
            grouped.computeIfAbsent(row.name(), ignored -> new ArrayList<>()).add(row);
        }
        for (List<KeyConstraintRow> group : grouped.values()) {
            group.sort(Comparator.comparingInt(KeyConstraintRow::position));
            KeyConstraintRow first = group.getFirst();
            List<Identifier> columns = group.stream().map(row -> Identifier.of(row.column())).toList();
            Map<String, String> physicalOptions = indexPhysicalByKey.getOrDefault(
                    columns.stream().map(id -> id.normalized()).toList(), Map.of());
            if ("P".equalsIgnoreCase(first.type())) {
                builder.primaryKey(new PrimaryKey(identifierOrNull(first.name()), columns, false, false, physicalOptions));
            } else if ("U".equalsIgnoreCase(first.type())) {
                builder.addUniqueKey(new UniqueKey(identifierOrNull(first.name()), columns, false, false, physicalOptions));
            }
        }
    }

    static void mapForeignKeys(Table.Builder builder, List<ForeignKeyRow> rows) {
        Map<String, List<ForeignKeyRow>> grouped = new LinkedHashMap<>();
        for (ForeignKeyRow row : rows) {
            grouped.computeIfAbsent(row.name(), ignored -> new ArrayList<>()).add(row);
        }
        for (List<ForeignKeyRow> group : grouped.values()) {
            group.sort(Comparator.comparingInt(ForeignKeyRow::position));
            ForeignKeyRow first = group.getFirst();
            List<Identifier> columns = group.stream().map(row -> Identifier.of(row.column())).toList();
            List<Identifier> referenced = group.stream().map(row -> Identifier.of(row.referencedColumn())).toList();
            builder.addForeignKey(new ForeignKey(
                    identifierOrNull(first.name()),
                    columns,
                    QualifiedName.of(first.referencedSchema(), first.referencedTable()),
                    referenced,
                    referentialAction(first.deleteRule()),
                    referentialAction(first.updateRule()),
                    false,
                    false,
                    true,
                    true));
        }
    }

    static void mapIndexes(Table.Builder builder, List<IndexRow> rows, List<KeyConstraintRow> keyConstraints) {
        Map<String, List<IndexRow>> grouped = new LinkedHashMap<>();
        for (IndexRow row : rows) {
            String key = row.schema() + "." + row.name();
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }

        Set<List<String>> enforcingKeys = new LinkedHashSet<>();
        Map<String, List<KeyConstraintRow>> keyGroups = new LinkedHashMap<>();
        for (KeyConstraintRow row : keyConstraints) {
            keyGroups.computeIfAbsent(row.name(), ignored -> new ArrayList<>()).add(row);
        }
        for (List<KeyConstraintRow> group : keyGroups.values()) {
            group.sort(Comparator.comparingInt(KeyConstraintRow::position));
            enforcingKeys.add(group.stream().map(row -> row.column().toUpperCase(Locale.ROOT)).toList());
        }

        for (List<IndexRow> group : grouped.values()) {
            group.sort(Comparator.comparingInt(IndexRow::position));
            List<String> keyNames = group.stream()
                    .filter(row -> !"I".equalsIgnoreCase(row.order()))
                    .filter(row -> row.column() != null)
                    .map(row -> row.column().toUpperCase(Locale.ROOT))
                    .toList();
            if ((isUniqueRule(group.getFirst().uniqueRule()) || "P".equalsIgnoreCase(group.getFirst().uniqueRule()))
                    && enforcingKeys.contains(keyNames)) {
                continue;
            }

            List<IndexColumn> keyColumns = new ArrayList<>();
            List<Identifier> includeColumns = new ArrayList<>();
            for (IndexRow row : group) {
                if ("I".equalsIgnoreCase(row.order())) {
                    if (row.column() != null) includeColumns.add(Identifier.of(row.column()));
                    continue;
                }
                SortDirection direction = "D".equalsIgnoreCase(row.order()) ? SortDirection.DESC : SortDirection.ASC;
                if (isVirtual(row) && row.expression() != null) {
                    keyColumns.add(IndexColumn.expression(row.expression(), direction));
                } else if (row.column() != null) {
                    keyColumns.add(new IndexColumn(Identifier.of(row.column()), direction));
                }
            }
            if (keyColumns.isEmpty()) continue;
            IndexType type = isUniqueRule(group.getFirst().uniqueRule()) ? IndexType.UNIQUE : IndexType.NORMAL;
            builder.addIndex(new Index(
                    identifierOrNull(group.getFirst().name()),
                    keyColumns,
                    type,
                    Description.empty(),
                    includeColumns,
                    null,
                    db2LuwIndexPhysicalOptions(group.getFirst())));
        }
    }

    static Map<String, String> db2LuwTablePhysicalOptions(Db2LuwTablePhysicalRow row) {
        Map<String, String> options = new LinkedHashMap<>();
        put(options, "TABLESPACE", row.tablespace());
        put(options, "DB2_LUW_INDEX_TABLESPACE",
                row.indexTablespace() == null ? row.tablespace() : row.indexTablespace());
        put(options, "DB2_LUW_LONG_TABLESPACE",
                row.longTablespace() == null ? row.tablespace() : row.longTablespace());
        if (row.pctFree() != null) {
            options.put("DB2_LUW_TABLE_PCTFREE", Integer.toString(row.pctFree() < 0 ? 0 : row.pctFree()));
        }
        mapCode(options, "DB2_LUW_APPEND", row.appendMode(), Map.of("N", "OFF", "Y", "ON"));
        String volatileCode = row.volatileMode();
        if (volatileCode == null || volatileCode.isBlank()) {
            options.put("DB2_LUW_VOLATILE", "NO");
        } else if ("C".equalsIgnoreCase(volatileCode)) {
            options.put("DB2_LUW_VOLATILE", "YES");
        } else {
            options.put("DB2_LUW_VOLATILE", REVIEW_PREFIX + volatileCode);
        }
        mapCode(options, "DB2_LUW_TABLE_ORGANIZATION", row.tableOrganization(),
                Map.of("R", "ROW", "C", "COLUMN"));
        mapCompression(options, row.compression(), row.rowCompressionMode());
        return Map.copyOf(options);
    }

    static Map<String, String> db2LuwIndexPhysicalOptions(IndexRow row) {
        Map<String, String> options = new LinkedHashMap<>();
        put(options, "INDEX_TABLESPACE", row.tablespace());
        put(options, "DB2_LUW_INDEX_PCTFREE", row.pctFree());
        put(options, "DB2_LUW_INDEX_MINPCTUSED", row.minPctUsed());
        mapCode(options, "DB2_LUW_INDEX_REVERSE_SCANS", row.reverseScans(), Map.of("N", "DISALLOW", "Y", "ALLOW"));
        mapCode(options, "DB2_LUW_INDEX_COMPRESSION", row.compression(), Map.of("N", "NO", "Y", "YES"));
        mapCode(options, "DB2_LUW_INDEX_PAGE_SPLIT", row.pageSplit(),
                Map.of("H", "HIGH", "L", "LOW", "S", "SYMMETRIC"));
        return Map.copyOf(options);
    }

    private static Map<List<String>, Map<String, String>> constraintIndexPhysicalByKey(List<IndexRow> rows) {
        Map<String, List<IndexRow>> grouped = new LinkedHashMap<>();
        for (IndexRow row : rows) {
            grouped.computeIfAbsent(row.schema() + "." + row.name(), ignored -> new ArrayList<>()).add(row);
        }
        Map<List<String>, Map<String, String>> result = new LinkedHashMap<>();
        for (List<IndexRow> group : grouped.values()) {
            group.sort(Comparator.comparingInt(IndexRow::position));
            IndexRow first = group.getFirst();
            if (!isUniqueRule(first.uniqueRule())) continue;
            List<String> key = group.stream()
                    .filter(row -> !"I".equalsIgnoreCase(row.order()))
                    .filter(row -> row.column() != null)
                    .map(row -> Identifier.of(row.column()).normalized())
                    .toList();
            if (!key.isEmpty()) result.putIfAbsent(key, db2LuwIndexPhysicalOptions(first));
        }
        return result;
    }

    private static void mapCompression(Map<String, String> options, String compression, String rowMode) {
        String c = compression == null ? "" : compression.trim().toUpperCase(Locale.ROOT);
        String mode = rowMode == null ? "" : rowMode.trim().toUpperCase(Locale.ROOT);
        switch (c) {
            case "N", "" -> {
                options.put("DB2_LUW_ROW_COMPRESSION", "NO");
                options.put("DB2_LUW_VALUE_COMPRESSION", "NO");
            }
            case "R" -> {
                options.put("DB2_LUW_ROW_COMPRESSION", compressionMode(mode));
                options.put("DB2_LUW_VALUE_COMPRESSION", "NO");
            }
            case "V" -> {
                options.put("DB2_LUW_ROW_COMPRESSION", "NO");
                options.put("DB2_LUW_VALUE_COMPRESSION", "YES");
            }
            case "B" -> {
                options.put("DB2_LUW_ROW_COMPRESSION", compressionMode(mode));
                options.put("DB2_LUW_VALUE_COMPRESSION", "YES");
            }
            default -> {
                options.put("DB2_LUW_ROW_COMPRESSION", REVIEW_PREFIX + compression);
                options.put("DB2_LUW_VALUE_COMPRESSION", REVIEW_PREFIX + compression);
            }
        }
    }

    private static String compressionMode(String mode) {
        return switch (mode) {
            case "A" -> "ADAPTIVE";
            case "S" -> "STATIC";
            case "" -> REVIEW_PREFIX + "ROW_COMPRESSION_MODE_UNAVAILABLE";
            default -> REVIEW_PREFIX + mode;
        };
    }

    private static void mapCode(Map<String, String> target, String key, String raw, Map<String, String> mapping) {
        if (raw == null) return;
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        String mapped = mapping.get(normalized);
        target.put(key, mapped == null ? REVIEW_PREFIX + raw.trim() : mapped);
    }

    private static void put(Map<String, String> target, String key, Object value) {
        if (value == null) return;
        String normalized = value.toString().trim();
        if (!normalized.isEmpty()) target.put(key, normalized);
    }

    static String profileSignature(ResultSet rs) throws SQLException {
        Db2LuwColumnRow row = new Db2LuwColumnRow(
                1,
                rs.getString("COLUMN_NAME"),
                trimToNull(rs.getString("TYPESCHEMA")),
                rs.getString("TYPENAME"),
                nullableInt(rs, "LENGTH"),
                nullableInt(rs, "SCALE"),
                nullableInt(rs, "STRINGUNITSLENGTH"),
                true,
                null,
                null,
                false,
                null,
                null);
        DataType type = mapDataType(row);
        String normalized = type.name().normalized();
        if (normalized.equals("DB2_DATE")) return "DATE";
        if (normalized.equals("DB2_LUW_TIMESTAMP0")) return "TIMESTAMP(0)";
        if (normalized.equals("BLOB") || normalized.equals("CLOB") || normalized.equals("DBCLOB")) {
            return type.name().value() + (type.length() == null ? "" : "(" + type.length() + ")");
        }
        if (type.length() != null) return type.name().value() + "(" + type.length() + ")";
        if (type.precision() != null) {
            if (normalized.equals("TIMESTAMP")) return "TIMESTAMP(" + type.precision() + ")";
            if (normalized.equals("DECFLOAT")) return "DECFLOAT(" + type.precision() + ")";
            return type.name().value() + "(" + type.precision()
                    + (type.scale() == null ? "" : "," + type.scale()) + ")";
        }
        return type.name().value().replace('_', ' ');
    }

    private static boolean isVirtual(IndexRow row) {
        return row.virtual() != null && !"N".equalsIgnoreCase(row.virtual());
    }

    private static boolean isUniqueRule(String value) {
        return value != null && ("U".equalsIgnoreCase(value) || "P".equalsIgnoreCase(value));
    }

    private static ReferentialAction referentialAction(String value) {
        if (value == null || value.isBlank()) return ReferentialAction.NO_ACTION;
        return switch (Character.toUpperCase(value.charAt(0))) {
            case 'C' -> ReferentialAction.CASCADE;
            case 'N' -> ReferentialAction.SET_NULL;
            case 'R' -> ReferentialAction.RESTRICT;
            default -> ReferentialAction.NO_ACTION;
        };
    }

    private static String normalizeGeneratedExpression(String text) {
        String value = text == null ? null : text.trim();
        if (value == null || value.isEmpty()) return null;
        if (value.regionMatches(true, 0, "AS", 0, 2)) value = value.substring(2).trim();
        if (value.startsWith("(") && value.endsWith(")") && value.length() > 2) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value.isEmpty() ? null : value;
    }

    private static Identifier identifierOrNull(String value) {
        return value == null || value.isBlank() ? null : Identifier.of(value);
    }

    private static Integer nullableInt(ResultSet rs, String name) throws SQLException {
        Object value = rs.getObject(name);
        return value == null ? null : ((Number) value).intValue();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    record ProfileRow(String columnName, String typeSignature, long frequency) { }
    record TableInfo(String schema, String name, String comment) { }
    record Db2LuwTablePhysicalRow(String tablespace, String indexTablespace, String longTablespace,
                                  Integer pctFree, String appendMode, String volatileMode,
                                  String compression, String rowCompressionMode, String tableOrganization) { }
    record Db2LuwColumnRow(Integer position, String name, String typeSchema, String typeName,
                           Integer length, Integer scale, Integer stringUnitsLength,
                           boolean nullable, String defaultValue, String comment,
                           boolean identity, String generated, String generatedText) { }
    record KeyConstraintRow(String name, String type, String column, int position) { }
    record ForeignKeyRow(String name, int position, String column,
                         String referencedSchema, String referencedTable, String referencedColumn,
                         String deleteRule, String updateRule) { }
    record CheckRow(String name, String expression) { }
    record IndexRow(String schema, String name, String uniqueRule, int position, String order,
                    String column, String virtual, String expression, String tablespace,
                    Integer pctFree, Integer minPctUsed, String reverseScans,
                    String compression, String pageSplit) {
        IndexRow(String schema, String name, String uniqueRule, int position, String order,
                 String column, String virtual, String expression) {
            this(schema, name, uniqueRule, position, order, column, virtual, expression,
                    null, null, null, null, null, null);
        }
    }
}
