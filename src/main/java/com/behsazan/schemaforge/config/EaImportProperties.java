package com.behsazan.schemaforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration used when importing Enterprise Architect XML/XMI models. */
@ConfigurationProperties(prefix = "schemaforge.ea")
public class EaImportProperties {
    /**
     * EA XMI exports frequently omit the physical database schema. This value is
     * applied only when the XML does not provide a schema/owner tagged value.
     */
    private String defaultSchema = "EA_SCHEMA";

    public String getDefaultSchema() {
        return defaultSchema;
    }

    public void setDefaultSchema(String defaultSchema) {
        this.defaultSchema = defaultSchema;
    }

    public static EaImportProperties defaults() {
        return new EaImportProperties();
    }
}
