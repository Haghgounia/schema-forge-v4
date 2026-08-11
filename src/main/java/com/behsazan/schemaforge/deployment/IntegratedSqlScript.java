package com.behsazan.schemaforge.deployment;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * SQL chunks rendered from one integrated deployment plan.
 *
 * <p>The lists preserve deployment boundaries so callers may write separate phase files or use
 * {@link #combinedSql()} for one ordered script.</p>
 */
public record IntegratedSqlScript(
        List<String> preTableStatements,
        List<String> phase1TableStatements,
        List<String> phase2TableLocalStatements,
        List<String> phase3ForeignKeyStatements,
        List<String> phase4MetadataStatements) {

    private static final String NL = System.lineSeparator();

    public IntegratedSqlScript {
        preTableStatements = immutable(preTableStatements);
        phase1TableStatements = immutable(phase1TableStatements);
        phase2TableLocalStatements = immutable(phase2TableLocalStatements);
        phase3ForeignKeyStatements = immutable(phase3ForeignKeyStatements);
        phase4MetadataStatements = immutable(phase4MetadataStatements);
    }

    /** Returns one ordered integrated deployment script with visible phase boundaries. */
    public String combinedSql() {
        StringBuilder sql = new StringBuilder("-- SchemaForge Integrated Deployment");
        appendPhase(sql, "PRE-TABLE", preTableStatements);
        appendPhase(sql, "PHASE 1 - TABLES", phase1TableStatements);
        appendPhase(sql, "PHASE 2 - TABLE LOCAL OBJECTS", phase2TableLocalStatements);
        appendPhase(sql, "PHASE 3 - FOREIGN KEYS", phase3ForeignKeyStatements);
        appendPhase(sql, "PHASE 4 - METADATA AND GRANTS", phase4MetadataStatements);
        return sql.toString();
    }

    /** Total number of rendered chunks. A chunk may contain a DBMS-specific post-create statement. */
    public int renderedChunkCount() {
        return preTableStatements.size()
                + phase1TableStatements.size()
                + phase2TableLocalStatements.size()
                + phase3ForeignKeyStatements.size()
                + phase4MetadataStatements.size();
    }

    private static List<String> immutable(List<String> statements) {
        Objects.requireNonNull(statements, "statements must not be null");
        return statements.stream()
                .filter(Objects::nonNull)
                .filter(statement -> !statement.isBlank())
                .toList();
    }

    private static void appendPhase(StringBuilder target, String title, List<String> statements) {
        target.append(NL).append(NL).append("-- ============================================================")
                .append(NL).append("-- ").append(title)
                .append(NL).append("-- ============================================================");
        if (!statements.isEmpty()) {
            target.append(NL).append(NL)
                    .append(statements.stream().collect(Collectors.joining(NL + NL)));
        }
    }
}
