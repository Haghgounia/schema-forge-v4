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
@ConditionalOnProperty(prefix = "schemaforge.metadata.oracle", name = "enabled", havingValue = "true")
public class JdbcOracleMetadataRepository implements OracleMetadataRepository {
    private static final String SQL = """
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
        jdbcTemplate.query(SQL, new MapSqlParameterSource("columnNames", normalized), rs -> {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT);
                grouped.computeIfAbsent(name, ignored -> new ArrayList<>()).add(
                        new MetadataTypeFrequency(signature(rs), rs.getLong("FREQUENCY")));
            }
        });
        return MetadataRepositorySupport.toProfiles(grouped);
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

    private static String signature(ResultSet rs) throws SQLException {
        String type = MetadataTypeFrequency.normalize(rs.getString("DATA_TYPE"));
        Integer length = nullableInt(rs, "CHAR_LENGTH");
        Integer precision = nullableInt(rs, "DATA_PRECISION");
        Integer scale = nullableInt(rs, "DATA_SCALE");
        if ((type.contains("CHAR")) && length != null && length > 0) return type + "(" + length + ")";
        if (precision != null) return type + "(" + precision + (scale == null ? "" : "," + scale) + ")";
        return type;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
