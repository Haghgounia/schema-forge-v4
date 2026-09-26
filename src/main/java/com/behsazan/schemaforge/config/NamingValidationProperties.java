package com.behsazan.schemaforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configures DBMS-independent identifier naming validation.
 *
 * @since 4.1
 */
@ConfigurationProperties(prefix = "schemaforge.naming")
public class NamingValidationProperties {
    public enum TableConvention {
        SINGULAR,
        PLURAL,
        OFF
    }

    private TableConvention tableConvention = TableConvention.PLURAL;
    private boolean pluralColumnComponentsEnabled = true;

    public TableConvention getTableConvention() {
        return tableConvention;
    }

    public void setTableConvention(TableConvention tableConvention) {
        this.tableConvention = tableConvention == null ? TableConvention.PLURAL : tableConvention;
    }

    public boolean isPluralColumnComponentsEnabled() {
        return pluralColumnComponentsEnabled;
    }

    public void setPluralColumnComponentsEnabled(boolean pluralColumnComponentsEnabled) {
        this.pluralColumnComponentsEnabled = pluralColumnComponentsEnabled;
    }

    public static NamingValidationProperties defaults() {
        return new NamingValidationProperties();
    }
}
