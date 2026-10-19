package com.behsazan.schemaforge.glossary;

/** Validated by the repository to keep REST and batch-import rules identical. */
public record GlossaryTermRequest(
        String english, String persian, String module,
        GlossaryTermKind kind, GlossaryTermStatus status) { }
