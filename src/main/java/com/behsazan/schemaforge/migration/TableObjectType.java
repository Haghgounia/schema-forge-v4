package com.behsazan.schemaforge.migration;

/** Table-owned structural objects handled by ALTER/Migration M2. */
public enum TableObjectType {
    PRIMARY_KEY,
    FOREIGN_KEY,
    UNIQUE_KEY,
    CHECK_CONSTRAINT,
    INDEX
}
