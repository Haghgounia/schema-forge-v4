package com.behsazan.schemaforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "schemaforge.standards.audit")
public class AuditProperties {
    private boolean enabled = true;
    private List<AuditColumn> columns = new ArrayList<>(defaultColumns());

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<AuditColumn> getColumns() {
        return columns;
    }

    public void setColumns(List<AuditColumn> columns) {
        this.columns = columns == null ? new ArrayList<>() : new ArrayList<>(columns);
    }

    public static AuditProperties defaults() {
        return new AuditProperties();
    }

    private static List<AuditColumn> defaultColumns() {
        return List.of(
                new AuditColumn("CREATED_BY", "VARCHAR2(50)", false, "Creation user"),
                new AuditColumn("CREATED_DATE", "TIMESTAMP", false, "Creation timestamp"),
                new AuditColumn("LAST_MODIFIED_BY", "VARCHAR2(50)", false, "Last modification user"),
                new AuditColumn("LAST_MODIFIED_DATE", "TIMESTAMP", false, "Last modification timestamp")
        );
    }

    public static class AuditColumn {
        private String name;
        private String dataType;
        private boolean nullable;
        private String comment;

        public AuditColumn() {
        }

        public AuditColumn(String name, String dataType, boolean nullable, String comment) {
            this.name = name;
            this.dataType = dataType;
            this.nullable = nullable;
            this.comment = comment;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDataType() {
            return dataType;
        }

        public void setDataType(String dataType) {
            this.dataType = dataType;
        }

        public boolean isNullable() {
            return nullable;
        }

        public void setNullable(boolean nullable) {
            this.nullable = nullable;
        }

        public String getComment() {
            return comment;
        }

        public void setComment(String comment) {
            this.comment = comment;
        }
    }
}
