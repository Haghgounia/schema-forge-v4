package com.behsazan.schemaforge.repository.oracle;

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
public class OracleRepository {
    private final DataSource dataSource;

    public OracleRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public boolean testConnection() {
        Integer result = new JdbcTemplate(dataSource).queryForObject("SELECT 1 FROM DUAL", Integer.class);
        return result != null && result == 1;
    }

    public SchemaDefinition readSchema(String owner) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String sql = """
                SELECT c.owner, c.table_name, c.column_name, c.data_type,
                       c.data_length, c.data_precision, c.data_scale, c.nullable, c.data_default
                  FROM all_tab_columns c
                 WHERE c.owner = ?
                 ORDER BY c.table_name, c.column_id
                """;

        Map<String, TableAccumulator> tables = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            String tableName = rs.getString("table_name");
            TableAccumulator table = tables.computeIfAbsent(tableName,
                    ignored -> new TableAccumulator(owner, tableName));
            table.columns.add(new ColumnDefinition(
                    rs.getString("column_name"),
                    rs.getString("data_type"),
                    nullableInteger(rs.getObject("data_length")),
                    nullableInteger(rs.getObject("data_precision")),
                    nullableInteger(rs.getObject("data_scale")),
                    "Y".equalsIgnoreCase(rs.getString("nullable")),
                    rs.getString("data_default")
            ));
        }, owner.toUpperCase());

        List<TableDefinition> result = tables.values().stream()
                .map(TableAccumulator::toDefinition)
                .toList();
        return new SchemaDefinition(owner, result);
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
