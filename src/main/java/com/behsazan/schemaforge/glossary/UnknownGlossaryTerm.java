package com.behsazan.schemaforge.glossary;

/** Identifier occurrence that needs review: NEW, PENDING_APPROVAL, or REJECTED. */
public record UnknownGlossaryTerm(
        String sourceName, String objectType, String tableName, String columnName,
        String identifier, String term, int tokenPosition, String reviewStatus) { }
