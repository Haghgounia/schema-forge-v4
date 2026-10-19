package com.behsazan.schemaforge.glossary;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Request-scoped, DBMS-neutral hints for columns containing terms not present
 * in the active Atomic Terms glossary. Never changes SQL execution semantics.
 *
 * <p>Only NEW terms are missing. CANDIDATE and REJECTED entries are already
 * recorded in the glossary and continue to be reported by the governance CSV,
 * but are not mislabeled as missing in generated DDL.</p>
 */
public final class GlossaryDdlHints {
    private final GlossaryLexicon snapshot;
    private final GlossaryScanner scanner = new GlossaryScanner();

    public GlossaryDdlHints(GlossaryLexicon snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    /** A SQL block comment placed before the column name on its CREATE TABLE row. */
    public String beforeColumn(String identifier) {
        Set<String> absent = new LinkedHashSet<>();
        for (UnknownGlossaryTerm finding : scanner.scanColumn(identifier, snapshot)) {
            if ("NEW".equals(finding.reviewStatus())) {
                absent.add(finding.term());
            }
        }
        if (absent.isEmpty()) return "";
        // Scanner terms consist exclusively of uppercase ASCII letters, digits, underscores.
        // No raw user-provided identifier can terminate or escape this SQL comment.
        return "/* GLOSSARY_HINT: MISSING=" + String.join(",", absent) + " */ ";
    }
}
