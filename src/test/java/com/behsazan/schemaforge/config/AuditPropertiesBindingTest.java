package com.behsazan.schemaforge.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Checks that YAML-style names bind without silently losing the Persian labels. */
class AuditPropertiesBindingTest {
    @Test
    void bindsPersianNameSeparatelyFromEnglishComment() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "schemaforge.standards.audit.columns[0].name", "CREATED_DATE",
                "schemaforge.standards.audit.columns[0].data-type", "TIMESTAMP",
                "schemaforge.standards.audit.columns[0].nullable", "false",
                "schemaforge.standards.audit.columns[0].comment", "Creation timestamp",
                "schemaforge.standards.audit.columns[0].persian-name", "تاریخ و زمان ایجاد")));
        AuditProperties properties = binder.bind("schemaforge.standards.audit",
                Bindable.of(AuditProperties.class)).get();
        var column = properties.getColumns().getFirst();
        assertEquals("CREATED_DATE", column.getName());
        assertEquals("TIMESTAMP", column.getDataType());
        assertEquals("Creation timestamp", column.getComment());
        assertEquals("تاریخ و زمان ایجاد", column.getPersianName());
    }
}
