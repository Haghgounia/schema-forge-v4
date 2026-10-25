package com.behsazan.schemaforge.glossary;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Request-scoped, DBMS-neutral hints for columns with absent terms or recognized
 * plural components. Never changes SQL execution semantics.
 *
 * <p>Only NEW terms are missing. CANDIDATE and REJECTED entries are already
 * recorded in the glossary and continue to be reported by the governance CSV,
 * but are not mislabeled as missing in generated DDL. Known singular aliases
 * produce a naming hint for plural COLUMN components, not a missing-term hint.</p>
 */
public final class GlossaryDdlHints {
    private final GlossaryLexicon snapshot;
    private final GlossaryScanner scanner = new GlossaryScanner();

    public GlossaryDdlHints(GlossaryLexicon snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    /**
     * A compact, non-executable SQL comment appended after a column definition
     * (and after its comma, when present), ahead of any inline issue warning.
     * GH means terms missing from the approved glossary; PLURAL is a separate
     * identifier naming warning. No SQL keywords or datatypes are changed.
     */
    public String afterColumn(String identifier) {
        Set<String> absent = new LinkedHashSet<>();
        Set<String> plurals = new LinkedHashSet<>();
        for (UnknownGlossaryTerm finding : scanner.scanColumn(identifier, snapshot)) {
            if ("NEW".equals(finding.reviewStatus())) {
                absent.add(finding.term());
            } else if ("PLURAL_COLUMN".equals(finding.reviewStatus())) {
                plurals.add(finding.term());
            }
        }
        if (absent.isEmpty() && plurals.isEmpty()) return "";
        // Scanner terms consist exclusively of uppercase ASCII letters, digits, underscores.
        // No raw user-provided identifier can terminate or escape this SQL comment.
        StringBuilder hint = new StringBuilder(" /* ");
        if (!absent.isEmpty()) hint.append("GH: ").append(String.join(",", absent));
        if (!plurals.isEmpty()) {
            if (!absent.isEmpty()) hint.append(" | ");
            hint.append("PLURAL: ").append(String.join(",", plurals));
        }
        return hint.append(" */").toString();
    }
}
