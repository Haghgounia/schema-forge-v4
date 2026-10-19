package com.behsazan.schemaforge.glossary;

/** Persisted canonical entry. Only APPROVED and non-deleted entries are valid naming vocabulary. */
public record GlossaryTerm(
        long id, GlossaryTermKind kind, String key, String english, String persian,
        String module, GlossaryTermStatus status, String source,
        String createdAt, String updatedAt) { }
