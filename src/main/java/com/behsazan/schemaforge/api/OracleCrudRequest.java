package com.behsazan.schemaforge.api;

/** REST input for one Oracle metadata-based CRUD package. */
public record OracleCrudRequest(String schema, String table) { }
