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
 * Reads Db2 for z/OS metadata from the public SYSIBM catalog interface.
 *
 * <p>The repository is read-only and uses uncommitted-read isolation for catalog queries.</p>
 *
 * @since 4.2
 */
@Repository
@ConditionalOnProperty(prefix = "schemaforge.metadata.db2zos", name = "enabled", havingValue = "true")
public class JdbcDb2ZosMetadataRepository implements Db2ZosMetadataRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcDb2ZosMetadataRepository.class);

    static final String COLUMN_PROFILE_SQL = """
            SELECT C.NAME AS COLUMN_NAME,
                   C.COLTYPE,
                   C.LENGTH,
                   C.LENGTH2,
                   C.SCALE,
                   C.TYPESCHEMA,
                   C.TYPENAME,
                   COUNT(*) AS FREQUENCY
              FROM SYSIBM.SYSCOLUMNS C
              JOIN SYSIBM.SYSTABLES T
                ON T.CREATOR = C.TBCREATOR
               AND T.NAME = C.TBNAME
             WHERE T.TYPE IN ('T', 'M', 'H')
               AND C.TBCREATOR NOT IN ('SYSIBM', 'SYSFUN', 'SYSPROC', 'SYSSTAT', 'SYSTOOLS')
               AND C.HIDDEN = 'N'
               AND UPPER(C.NAME) IN (:columnNames)
             GROUP BY C.NAME, C.COLTYPE, C.LENGTH, C.LENGTH2, C.SCALE,
                      C.TYPESCHEMA, C.TYPENAME
             ORDER BY C.NAME, FREQUENCY DESC
             WITH UR
            """;

    static final String TABLE_SQL = """
            SELECT CREATOR AS SCHEMA_NAME,
                   NAME AS TABLE_NAME,
                   REMARKS,
                   DBNAME,
                   TSNAME
              FROM SYSIBM.SYSTABLES
             WHERE TYPE IN ('T', 'M', 'H')
               AND UPPER(CREATOR) = UPPER(:schemaName)
               AND UPPER(NAME) = UPPER(:tableName)
             ORDER BY CASE
                        WHEN CREATOR = :schemaName AND NAME = :tableName THEN 0
                        ELSE 1
                      END,
                      CREATOR,
                      NAME
             WITH UR
            """;

    static final String COLUMNS_SQL = """
            SELECT COLNO + 1 AS COLUMN_ID,
                   NAME AS COLUMN_NAME,
                   COLTYPE,
                   LENGTH,
                   LENGTH2,
                   SCALE,
                   NULLS,
                   REMARKS,
                   DEFAULT AS DEFAULT_INDICATOR,
                   DEFAULTVALUE,
                   GENERATED_ATTR,
                   TYPESCHEMA,
                   TYPENAME
              FROM SYSIBM.SYSCOLUMNS
             WHERE TBCREATOR = :schemaName
               AND TBNAME = :tableName
               AND HIDDEN = 'N'
             ORDER BY COLNO
             WITH UR
            """;

    static final String KEY_CONSTRAINTS_SQL = """
            SELECT C.CONSTNAME AS CONSTRAINT_NAME,
                   C.TYPE AS CONSTRAINT_TYPE,
                   K.COLNAME AS COLUMN_NAME,
                   K.COLSEQ AS COLUMN_POSITION
              FROM SYSIBM.SYSTABCONST C
              JOIN SYSIBM.SYSKEYS K
                ON K.IXCREATOR = C.IXOWNER
               AND K.IXNAME = C.IXNAME
             WHERE C.TBCREATOR = :schemaName
               AND C.TBNAME = :tableName
               AND C.TYPE IN ('P', 'U')
               AND K.ORDERING IN ('A', 'D', 'R')
             ORDER BY C.CONSTNAME, K.COLSEQ
             WITH UR
            """;

    static final String FOREIGN_KEYS_SQL = """
            SELECT R.RELNAME AS CONSTRAINT_NAME,
                   F.COLSEQ AS COLUMN_POSITION,
                   F.COLNAME AS COLUMN_NAME,
                   R.REFTBCREATOR AS REFERENCED_SCHEMA,
                   R.REFTBNAME AS REFERENCED_TABLE,
                   PARENT_KEY.COLNAME AS REFERENCED_COLUMN,
                   R.DELETERULE
              FROM SYSIBM.SYSRELS R
              JOIN SYSIBM.SYSFOREIGNKEYS F
                ON F.CREATOR = R.CREATOR
               AND F.TBNAME = R.TBNAME
               AND F.RELNAME = R.RELNAME
              LEFT JOIN SYSIBM.SYSINDEXES PARENT_INDEX
                ON PARENT_INDEX.TBCREATOR = R.REFTBCREATOR
               AND PARENT_INDEX.TBNAME = R.REFTBNAME
               AND (
                    (RTRIM(R.IXNAME) <> ''
                     AND PARENT_INDEX.CREATOR = R.IXOWNER
                     AND PARENT_INDEX.NAME = R.IXNAME)
                    OR
                    (RTRIM(R.IXNAME) = '' AND PARENT_INDEX.UNIQUERULE = 'P')
                   )
              LEFT JOIN SYSIBM.SYSKEYS PARENT_KEY
                ON PARENT_KEY.IXCREATOR = PARENT_INDEX.CREATOR
               AND PARENT_KEY.IXNAME = PARENT_INDEX.NAME
               AND PARENT_KEY.COLSEQ = F.COLSEQ
               AND PARENT_KEY.ORDERING IN ('A', 'D', 'R')
             WHERE R.CREATOR = :schemaName
               AND R.TBNAME = :tableName
             ORDER BY R.RELNAME, F.COLSEQ
             WITH UR
            """;

    static final String CHECKS_SQL = """
            SELECT CHECKNAME AS CONSTRAINT_NAME,
                   CHECKCONDITION AS DEFINITION
              FROM SYSIBM.SYSCHECKS
             WHERE TBOWNER = :schemaName
               AND TBNAME = :tableName
             ORDER BY CHECKNAME
             WITH UR
            """;

    static final String INDEXES_SQL = """
            SELECT I.NAME AS INDEX_NAME,
                   I.CREATOR AS INDEX_SCHEMA,
                   I.UNIQUERULE,
                   K.COLNAME AS COLUMN_NAME,
                   K.COLSEQ AS COLUMN_POSITION,
                   K.ORDERING
              FROM SYSIBM.SYSINDEXES I
              LEFT JOIN SYSIBM.SYSKEYS K
                ON K.IXCREATOR = I.CREATOR
               AND K.IXNAME = I.NAME
             WHERE I.TBCREATOR = :schemaName
               AND I.TBNAME = :tableName
             ORDER BY I.NAME, K.COLSEQ
             WITH UR
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcDb2ZosMetadataRepository(
            @Qualifier("db2ZosMetadataJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate) {
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
                        trimToNull(rs.getString("REMARKS")),
                        trimToNull(rs.getString("DBNAME")),
                        trimToNull(rs.getString("TSNAME"))));
        if (tables.isEmpty()) {
            LOGGER.warn("Db2 z/OS metadata table not found. requested={}.{}, visibleTableSchemas={}",
                    schemaName, tableName, findTableSchemas(tableName));
            return Optional.empty();
        }

        TableInfo info = tables.getFirst();
        Table.Builder builder = Table.builder(info.schema(), info.name());
        if (info.comment() != null) builder.description(info.comment());
        if (info.tablespace() != null) {
            String location = info.database() == null
                    ? info.tablespace()
                    : info.database() + "." + info.tablespace();
            builder.physicalOption("tablespace", location);
        }

        MapSqlParameterSource tableParameter = new MapSqlParameterSource()
                .addValue("schemaName", info.schema())
                .addValue("tableName", info.name());

        List<Db2ColumnRow> columns = jdbcTemplate.query(COLUMNS_SQL, tableParameter,
                (rs, rowNumber) -> new Db2ColumnRow(
                        rs.getInt("COLUMN_ID"),
                        rs.getString("COLUMN_NAME"),
                        rs.getString("COLTYPE"),
                        nullableInt(rs, "LENGTH"),
                        nullableInt(rs, "LENGTH2"),
                        nullableInt(rs, "SCALE"),
                        "Y".equalsIgnoreCase(rs.getString("NULLS")),
                        trimToNull(rs.getString("REMARKS")),
                        trimToNull(rs.getString("DEFAULT_INDICATOR")),
                        trimToNull(rs.getString("DEFAULTVALUE")),
                        trimToNull(rs.getString("GENERATED_ATTR")),
                        trimToNull(rs.getString("TYPESCHEMA")),
                        trimToNull(rs.getString("TYPENAME"))));
        columns.stream().map(JdbcDb2ZosMetadataRepository::mapColumn).forEach(builder::addColumn);

        List<KeyConstraintRow> keys = jdbcTemplate.query(KEY_CONSTRAINTS_SQL, tableParameter,
                (rs, rowNumber) -> new KeyConstraintRow(
                        rs.getString("CONSTRAINT_NAME"),
                        rs.getString("CONSTRAINT_TYPE"),
                        rs.getString("COLUMN_NAME"),
                        rs.getInt("COLUMN_POSITION")));
        mapKeys(builder, keys);

        List<ForeignKeyRow> foreignKeys = jdbcTemplate.query(FOREIGN_KEYS_SQL, tableParameter,
                (rs, rowNumber) -> new ForeignKeyRow(
                        rs.getString("CONSTRAINT_NAME"),
                        rs.getInt("COLUMN_POSITION"),
                        rs.getString("COLUMN_NAME"),
                        rs.getString("REFERENCED_SCHEMA"),
                        rs.getString("REFERENCED_TABLE"),
                        rs.getString("REFERENCED_COLUMN"),
                        rs.getString("DELETERULE")));
        mapForeignKeys(builder, foreignKeys);

        List<CheckRow> checks = jdbcTemplate.query(CHECKS_SQL, tableParameter,
                (rs, rowNumber) -> new CheckRow(
                        rs.getString("CONSTRAINT_NAME"),
                        trimToNull(rs.getString("DEFINITION"))));
        for (CheckRow check : checks) {
            if (check.definition() != null) {
                builder.addCheck(new CheckConstraint(identifierOrNull(check.name()), check.definition()));
            }
        }

        List<IndexRow> indexes = jdbcTemplate.query(INDEXES_SQL, tableParameter,
                (rs, rowNumber) -> new IndexRow(
                        rs.getString("INDEX_NAME"),
                        rs.getString("INDEX_SCHEMA"),
                        rs.getString("UNIQUERULE"),
                        rs.getString("COLUMN_NAME"),
                        nullableInt(rs, "COLUMN_POSITION"),
                        trimToNull(rs.getString("ORDERING"))));
        mapIndexes(builder, indexes);
        return Optional.of(builder.build());
    }

    @Override
    public boolean schemaExists(String schemaName) {
        if (schemaName == null || schemaName.isBlank()) return false;
        Integer count = jdbcTemplate.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM SYSIBM.SYSTABLES WHERE UPPER(CREATOR) = UPPER(?) WITH UR",
                Integer.class, schemaName);
        return count != null && count > 0;
    }

    @Override
    public List<String> findTableSchemas(String tableName) {
        if (tableName == null || tableName.isBlank()) return List.of();
        return jdbcTemplate.getJdbcTemplate().queryForList(
                "SELECT DISTINCT CREATOR FROM SYSIBM.SYSTABLES "
                        + "WHERE TYPE IN ('T','M','H') "
                        + "AND CREATOR NOT IN ('SYSIBM','SYSFUN','SYSPROC','SYSSTAT','SYSTOOLS') "
                        + "AND UPPER(NAME) = UPPER(?) ORDER BY CREATOR WITH UR",
                String.class, tableName);
    }

    static Column mapColumn(Db2ColumnRow row) {
        boolean identity = isIdentity(row.defaultIndicator());
        String defaultExpression = identity ? null : mapDefault(row);
        return new Column(
                Identifier.of(row.name()),
                mapDataType(row),
                row.nullable(),
                new DefaultValue(defaultExpression),
                new Description(row.comment()),
                identity,
                row.position(),
                null);
    }

    static DataType mapDataType(Db2ColumnRow row) {
        String raw = normalizeType(row.rawType());
        int length = positive(row.length(), 1);
        int longLength = positive(row.longLength(), length);
        int scale = row.scale() == null ? 0 : row.scale();

        return switch (raw) {
            case "SMALLINT", "INTEGER", "BIGINT", "TIME", "XML" -> DataType.simple(raw);
            case "DATE" -> DataType.simple("DB2_DATE");
            case "ROWID" -> DataType.simple("DB2_ROWID");
            case "DECIMAL", "NUMERIC" -> DataType.numeric("DECIMAL", length, scale);
            case "CHAR" -> new DataType(Identifier.of("CHAR"), length, LengthSemantics.CHAR, null, null);
            case "VARCHAR", "LONGVAR" -> new DataType(
                    Identifier.of("VARCHAR"), raw.equals("LONGVAR") ? longLength : length,
                    LengthSemantics.CHAR, null, null);
            case "GRAPHIC" -> new DataType(Identifier.of("GRAPHIC"), length, LengthSemantics.CHAR, null, null);
            case "VARG", "LONGVARG" -> new DataType(
                    Identifier.of("VARGRAPHIC"), raw.equals("LONGVARG") ? longLength : length,
                    LengthSemantics.CHAR, null, null);
            case "BINARY" -> new DataType(Identifier.of("BINARY"), length, LengthSemantics.DEFAULT, null, null);
            case "VARBIN" -> new DataType(Identifier.of("VARBINARY"), length, LengthSemantics.DEFAULT, null, null);
            case "BLOB", "CLOB", "DBCLOB" -> new DataType(
                    Identifier.of(raw), longLength, LengthSemantics.DEFAULT, null, null);
            case "TIMESTMP" -> scale <= 0 ? DataType.simple("TIMESTAMP")
                    : DataType.numeric("TIMESTAMP", scale, null);
            case "TIMESTZ" -> scale <= 0 ? DataType.simple("TIMESTAMP_WITH_TIME_ZONE")
                    : DataType.numeric("TIMESTAMP_WITH_TIME_ZONE", scale, null);
            case "FLOAT" -> DataType.simple(length <= 4 ? "REAL" : "DOUBLE");
            case "DECFLOAT" -> DataType.numeric("DECFLOAT", length <= 8 ? 16 : 34, null);
            case "DISTINCT" -> DataType.simple(safeTypeName(row.typeName(), "DB2_DISTINCT"));
            default -> DataType.simple(safeTypeName(raw, "DB2"));
        };
    }

    static String mapDefault(Db2ColumnRow row) {
        String indicator = row.defaultIndicator();
        if (indicator == null || indicator.isBlank() || "N".equalsIgnoreCase(indicator)) return null;
        String value = row.defaultValue();
        char code = indicator.charAt(0);
        return switch (code) {
            case '1', '7', '8' -> quoteLiteral(value == null ? "" : value);
            case '2', '3', '4', '9' -> value;
            case '5' -> value == null ? null : "X'" + value.replace("'", "''") + "'";
            case '6' -> value == null ? null : "UX'" + value.replace("'", "''") + "'";
            case 'S' -> "CURRENT SQLID";
            case 'U' -> "SESSION_USER";
            case 'a', 'b' -> value;
            case 'd' -> "DATA CHANGE OPERATION";
            case 'Y', 'B' -> implicitDefault(row);
            default -> null;
        };
    }

    private static String implicitDefault(Db2ColumnRow row) {
        if (row.nullable()) return "NULL";
        String type = normalizeType(row.rawType());
        if (type.equals("DATE")) return "CURRENT DATE";
        if (type.equals("TIME")) return "CURRENT TIME";
        if (type.equals("TIMESTMP")) return "CURRENT TIMESTAMP";
        if (type.equals("TIMESTZ")) return "CURRENT TIMESTAMP WITH TIME ZONE";
        if (isNumericType(type)) return "0";
        if (type.contains("CHAR") || type.equals("VARCHAR") || type.equals("LONGVAR")
                || type.equals("GRAPHIC") || type.equals("VARG") || type.equals("LONGVARG")) {
            return "''";
        }
        return null;
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
            if ("P".equalsIgnoreCase(first.type())) {
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
            if (child.isEmpty() || child.size() != parent.size()
                    || first.referencedSchema() == null || first.referencedTable() == null) {
                LOGGER.warn("Db2 z/OS foreign key metadata skipped because parent-key columns could not be resolved: {}",
                        first.name());
                continue;
            }
            builder.addForeignKey(new ForeignKey(
                    identifierOrNull(first.name()),
                    child,
                    QualifiedName.of(first.referencedSchema(), first.referencedTable()),
                    parent,
                    mapDeleteRule(first.deleteRule()),
                    ReferentialAction.NO_ACTION,
                    false,
                    false,
                    true,
                    true));
        }
    }

    static void mapIndexes(Table.Builder builder, List<IndexRow> rows) {
        Map<String, List<IndexRow>> groups = new LinkedHashMap<>();
        for (IndexRow row : rows) {
            String key = (row.schema() == null ? "" : row.schema()) + "." + row.name();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        for (List<IndexRow> group : groups.values()) {
            group.sort(Comparator.comparing(IndexRow::position, Comparator.nullsLast(Integer::compareTo)));
            List<IndexColumn> keyColumns = new ArrayList<>();
            List<Identifier> includeColumns = new ArrayList<>();
            for (IndexRow row : group) {
                if (row.column() == null || row.column().isBlank()) continue;
                if (row.ordering() == null || row.ordering().isBlank()) {
                    includeColumns.add(Identifier.of(row.column()));
                    continue;
                }
                SortDirection direction = "D".equalsIgnoreCase(row.ordering())
                        ? SortDirection.DESC : SortDirection.ASC;
                keyColumns.add(new IndexColumn(Identifier.of(row.column()), direction));
            }
            if (keyColumns.isEmpty()) continue;
            IndexType type = isUniqueRule(group.getFirst().uniqueRule())
                    ? IndexType.UNIQUE : IndexType.NORMAL;
            builder.addIndex(new Index(
                    identifierOrNull(group.getFirst().name()),
                    keyColumns,
                    type,
                    Description.empty(),
                    includeColumns,
                    null));
        }
    }

    static String profileSignature(ResultSet rs) throws SQLException {
        Db2ColumnRow row = new Db2ColumnRow(
                1,
                rs.getString("COLUMN_NAME"),
                rs.getString("COLTYPE"),
                nullableInt(rs, "LENGTH"),
                nullableInt(rs, "LENGTH2"),
                nullableInt(rs, "SCALE"),
                true,
                null,
                null,
                null,
                null,
                trimToNull(rs.getString("TYPESCHEMA")),
                trimToNull(rs.getString("TYPENAME")));
        DataType type = mapDataType(row);
        String normalizedName = type.name().normalized();
        if (normalizedName.equals("DB2_DATE")) return "DATE";
        if (normalizedName.equals("DB2_ROWID")) return "ROWID";
        if (normalizedName.equals("BLOB") || normalizedName.equals("CLOB")
                || normalizedName.equals("DBCLOB")) {
            return type.name().value();
        }
        if (type.length() != null) return type.name().value() + "(" + type.length() + ")";
        if (type.precision() != null) {
            if (type.name().normalized().startsWith("TIMESTAMP")) {
                return type.name().value().replace('_', ' ') + "(" + type.precision() + ")";
            }
            return type.name().value() + "(" + type.precision()
                    + (type.scale() == null ? "" : "," + type.scale()) + ")";
        }
        return type.name().value().replace('_', ' ');
    }

    private static ReferentialAction mapDeleteRule(String value) {
        if (value == null || value.isBlank()) return ReferentialAction.NO_ACTION;
        return switch (Character.toUpperCase(value.charAt(0))) {
            case 'C' -> ReferentialAction.CASCADE;
            case 'N' -> ReferentialAction.SET_NULL;
            case 'R' -> ReferentialAction.RESTRICT;
            default -> ReferentialAction.NO_ACTION;
        };
    }

    private static boolean isIdentity(String indicator) {
        return indicator != null && ("I".equalsIgnoreCase(indicator) || "J".equalsIgnoreCase(indicator));
    }

    private static boolean isUniqueRule(String rule) {
        return rule != null && !rule.isBlank() && !"D".equalsIgnoreCase(rule);
    }

    private static boolean isNumericType(String type) {
        return type.equals("SMALLINT") || type.equals("INTEGER") || type.equals("BIGINT")
                || type.equals("DECIMAL") || type.equals("NUMERIC") || type.equals("FLOAT")
                || type.equals("DECFLOAT");
    }

    private static String normalizeType(String value) {
        return value == null ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String safeTypeName(String value, String fallback) {
        String source = value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
        String safe = source.replaceAll("[^A-Z0-9_$#]+", "_").replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (safe.isBlank()) return fallback;
        if (!Character.isLetter(safe.charAt(0))) return fallback + "_" + safe;
        return safe;
    }

    private static String quoteLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static int positive(Integer preferred, int fallback) {
        return preferred != null && preferred > 0 ? preferred : fallback;
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

    record TableInfo(String schema, String name, String comment, String database, String tablespace) { }

    record Db2ColumnRow(int position, String name, String rawType, Integer length, Integer longLength,
                        Integer scale, boolean nullable, String comment, String defaultIndicator,
                        String defaultValue, String generatedAttribute, String typeSchema, String typeName) { }

    record KeyConstraintRow(String name, String type, String column, int position) { }

    record ForeignKeyRow(String name, int position, String column, String referencedSchema,
                         String referencedTable, String referencedColumn, String deleteRule) { }

    record CheckRow(String name, String definition) { }

    record IndexRow(String name, String schema, String uniqueRule, String column,
                    Integer position, String ordering) { }

    record ProfileRow(String columnName, String typeSignature, long frequency) { }
}
