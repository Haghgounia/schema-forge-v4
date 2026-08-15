package com.behsazan.schemaforge.diagram;

/** Controls which canonical tables participate in a diagram export. */
public enum DiagramScope {
    ALL,
    SCHEMA,
    TABLE,
    TABLE_WITH_DEPENDENCIES,
    SELECTED_TABLES
}
