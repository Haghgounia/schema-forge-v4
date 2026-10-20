package com.behsazan.schemaforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Defines external configuration properties for audit.
 *
 * @since 4.1
 */
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
                new AuditColumn("CREATED_BY", "VARCHAR2(50)", false,
                        "Creation user", "کاربر ایجادکننده"),
                new AuditColumn("CREATED_DATE", "TIMESTAMP", false,
                        "Creation timestamp", "تاریخ و زمان ایجاد"),
                new AuditColumn("LAST_MODIFIED_BY", "VARCHAR2(50)", false,
                        "Last modification user", "کاربر آخرین تغییر"),
                new AuditColumn("LAST_MODIFIED_DATE", "TIMESTAMP", false,
                        "Last modification timestamp", "تاریخ و زمان آخرین تغییر")
        );
    }

    public static class AuditColumn {
        private String name;
        private String dataType;
        private boolean nullable;
        private String comment;
        private String persianName;

        public AuditColumn() {
        }

        /** Compatibility constructor for existing external configuration and test clients. */
        public AuditColumn(String name, String dataType, boolean nullable, String comment) {
            this(name, dataType, nullable, comment, null);
        }

        public AuditColumn(String name, String dataType, boolean nullable,
                           String comment, String persianName) {
            this.name = name;
            this.dataType = dataType;
            this.nullable = nullable;
            this.comment = comment;
            this.persianName = persianName;
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

        /** Optional localized business name; does not replace the English SQL comment. */
        public String getPersianName() {
            return persianName;
        }

        public void setPersianName(String persianName) {
            this.persianName = persianName;
        }
    }
}
