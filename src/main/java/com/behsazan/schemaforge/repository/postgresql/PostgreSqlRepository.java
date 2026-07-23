package com.behsazan.schemaforge.repository.postgresql;

import com.behsazan.schemaforge.model.ColumnDefinition;
import com.behsazan.schemaforge.model.SchemaDefinition;
import com.behsazan.schemaforge.model.TableDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class PostgreSqlRepository {
    private final DataSource dataSource;

    public PostgreSqlRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public boolean testConnection() {
        Integer result = new JdbcTemplate(dataSource).queryForObject("SELECT 1", Integer.class);
        return result != null && result == 1;
    }

    public SchemaDefinition readSchema(String schemaName) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String sql = """
                SELECT table_schema, table_name, column_name, data_type,
                       character_maximum_length, numeric_precision, numeric_scale,
                       is_nullable, column_default, ordinal_position
                  FROM information_schema.columns
                 WHERE table_schema = ?
                 ORDER BY table_name, ordinal_position
                """;

        Map<String, TableAccumulator> tables = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            String tableName = rs.getString("table_name");
            TableAccumulator table = tables.computeIfAbsent(tableName,
                    ignored -> new TableAccumulator(schemaName, tableName));
            table.columns.add(new ColumnDefinition(
                    rs.getString("column_name"),
                    rs.getString("data_type"),
                    nullableInteger(rs.getObject("character_maximum_length")),
                    nullableInteger(rs.getObject("numeric_precision")),
                    nullableInteger(rs.getObject("numeric_scale")),
                    "YES".equalsIgnoreCase(rs.getString("is_nullable")),
                    rs.getString("column_default")
            ));
        }, schemaName);

        List<TableDefinition> result = tables.values().stream()
                .map(TableAccumulator::toDefinition)
                .toList();
        return new SchemaDefinition(schemaName, result);
    }

    private Integer nullableInteger(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static final class TableAccumulator {
        private final String schema;
        private final String name;
        private final java.util.ArrayList<ColumnDefinition> columns = new java.util.ArrayList<>();

        private TableAccumulator(String schema, String name) {
            this.schema = schema;
            this.name = name;
        }

        private TableDefinition toDefinition() {
            return new TableDefinition(schema, name, columns);
        }
    }
}
