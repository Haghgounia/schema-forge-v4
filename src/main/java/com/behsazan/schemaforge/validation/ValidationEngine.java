package com.behsazan.schemaforge.validation;

import com.behsazan.schemaforge.dialect.DatabaseDialect;
import com.behsazan.schemaforge.model.SchemaDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ValidationEngine {
    public List<ValidationMessage> validate(SchemaDefinition schema, DatabaseDialect dialect) {
        List<ValidationMessage> messages = new ArrayList<>();
        if (schema.name() == null) {
            messages.add(new ValidationMessage("schema", "SCHEMA_NAME_REQUIRED", "Schema name is required"));
        }
        schema.tables().forEach(table -> {
            if (table.name() == null) {
                messages.add(new ValidationMessage("table", "TABLE_NAME_REQUIRED", "Table name is required"));
            }
            table.columns().forEach(column -> {
                try {
                    dialect.sqlType(column);
                } catch (IllegalArgumentException ex) {
                    messages.add(new ValidationMessage(
                            table.name() + "." + column.name(),
                            "UNSUPPORTED_DATA_TYPE",
                            ex.getMessage()
                    ));
                }
            });
        });
        return List.copyOf(messages);
    }
}
