package com.behsazan.schemaforge.api;

/** REST input for one SQL Server metadata-based CRUD procedure script. */
public record SqlServerCrudRequest(String schema, String table) { }
