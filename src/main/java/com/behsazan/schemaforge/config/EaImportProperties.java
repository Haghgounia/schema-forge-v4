package com.behsazan.schemaforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration used when importing Enterprise Architect XML/XMI models. */
@ConfigurationProperties(prefix = "schemaforge.ea")
public class EaImportProperties {
    /**
     * EA XMI exports frequently omit the physical database schema. This value is
     * applied only when the XML does not provide a schema/owner tagged value.
     */
    private String defaultSchema = "COL";

    /**
     * Compatibility switch for older REST/EA behavior that inferred identity from
     * numeric primary-key columns. The strict default is false: only explicit EA/XMI
     * identity evidence is authoritative.
     */
    private boolean primaryKeyAsIdentity = false;

    public String getDefaultSchema() {
        return defaultSchema;
    }

    public void setDefaultSchema(String defaultSchema) {
        this.defaultSchema = defaultSchema;
    }

    public boolean isPrimaryKeyAsIdentity() {
        return primaryKeyAsIdentity;
    }

    public void setPrimaryKeyAsIdentity(boolean primaryKeyAsIdentity) {
        this.primaryKeyAsIdentity = primaryKeyAsIdentity;
    }

    public static EaImportProperties defaults() {
        return new EaImportProperties();
    }
}
