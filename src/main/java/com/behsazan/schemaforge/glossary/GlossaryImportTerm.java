package com.behsazan.schemaforge.glossary;

/** Deduplicated import row with source-language equivalents retained. */
public record GlossaryImportTerm(
        GlossaryTermKind kind, String key, String english, String persian, String module) { }
