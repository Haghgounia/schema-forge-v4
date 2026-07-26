package com.behsazan.schemaforge.metadata.repository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Repository
@ConditionalOnProperty(prefix = "schemaforge.metadata.postgresql", name = "enabled", havingValue = "true")
public class JdbcPostgreSqlMetadataRepository implements PostgreSqlMetadataRepository {
    private static final String SQL = """
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
        jdbcTemplate.query(SQL, new MapSqlParameterSource("columnNames", normalized), rs -> {
            while (rs.next()) {
                String name = rs.getString("column_name").toUpperCase(Locale.ROOT);
                grouped.computeIfAbsent(name, ignored -> new ArrayList<>()).add(
                        new MetadataTypeFrequency(signature(rs), rs.getLong("frequency")));
            }
        });
        return MetadataRepositorySupport.toProfiles(grouped);
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

    private static String signature(ResultSet rs) throws SQLException {
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
        if (type.equals("NUMERIC") && precision != null) return type + "(" + precision + (scale == null ? "" : "," + scale) + ")";
        return type;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
