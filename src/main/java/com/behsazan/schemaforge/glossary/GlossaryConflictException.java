package com.behsazan.schemaforge.glossary;

/** Unique glossary identity conflict, isolated from unrelated target-DBMS errors. */
public final class GlossaryConflictException extends RuntimeException {
    public GlossaryConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
