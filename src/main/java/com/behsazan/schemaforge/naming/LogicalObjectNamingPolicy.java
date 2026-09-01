package com.behsazan.schemaforge.naming;

import com.behsazan.schemaforge.domain.enums.IndexType;
import com.behsazan.schemaforge.domain.model.CheckConstraint;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.IndexColumn;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.validation.constraint.CheckConstraintReferenceAnalyzer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Single cross-service logical naming contract for generated database objects.
 *
 * <p>Source-provided PK/UK/FK/CHECK/INDEX names are intentionally ignored. Names are derived
 * only from the owning table and structural object definition. DBMS-specific length adaptation
 * is a separate concern handled by {@code PhysicalObjectNamePolicy}.</p>
 */
public final class LogicalObjectNamingPolicy {
    private static final int COLLISION_HASH_LENGTH = 8;

    private LogicalObjectNamingPolicy() {
    }

    /** Primary-key constraint: {@code PK_<TABLE>}. */
    public static Identifier primaryKey(Table table, PrimaryKey primaryKey) {
        Objects.requireNonNull(primaryKey, "primaryKey must not be null");
        return Identifier.of("PK_" + tableName(table));
    }

    /** Enforcing unique index for a primary key: {@code PK_<TABLE>_<PK_COLUMNS>}. */
    public static Identifier primaryKeyIndex(Table table, PrimaryKey primaryKey) {
        Objects.requireNonNull(primaryKey, "primaryKey must not be null");
        return Identifier.of("PK_" + tableName(table) + "_" + identifiers(primaryKey.columns()));
    }

    /** Unique-key constraint and its enforcing unique index: {@code UK_<TABLE>_<COLUMNS>}. */
    public static Identifier uniqueKey(Table table, UniqueKey uniqueKey) {
        Objects.requireNonNull(uniqueKey, "uniqueKey must not be null");
        String base = "UK_" + tableName(table) + "_" + identifiers(uniqueKey.columns());
        if (countUniqueKeyBase(table, base) <= 1) return Identifier.of(base);
        return Identifier.of(base + "_" + digest(uniqueSignature(uniqueKey)));
    }

    public static Identifier uniqueKeyIndex(Table table, UniqueKey uniqueKey) {
        return uniqueKey(table, uniqueKey);
    }

    /** Foreign key: {@code FK_<CHILD_TABLE>_<CHILD_COLUMNS>}. */
    public static Identifier foreignKey(Table table, ForeignKey foreignKey) {
        Objects.requireNonNull(foreignKey, "foreignKey must not be null");
        String base = "FK_" + tableName(table) + "_" + identifiers(foreignKey.columns());
        if (countForeignKeyBase(table, base) <= 1) return Identifier.of(base);
        return Identifier.of(base + "_" + digest(foreignKeySignature(foreignKey)));
    }

    /**
     * Check constraint: {@code CHK_<TABLE>_<REFERENCED_COLUMNS>}.
     *
     * <p>When the expression has no column reference, a stable RULE hash is used. When more than
     * one check resolves to the same base name, a stable expression hash disambiguates it.</p>
     */
    public static Identifier checkConstraint(Table table, CheckConstraint check) {
        Objects.requireNonNull(check, "check must not be null");
        List<String> columns = CheckConstraintReferenceAnalyzer.referencedColumns(table, check.expression());
        String suffix = columns.isEmpty()
                ? "RULE_" + digest(normalizeExpression(check.expression()))
                : String.join("_", columns);
        String base = "CHK_" + tableName(table) + "_" + suffix;
        if (countCheckBase(table, base) <= 1) return Identifier.of(base);
        return Identifier.of(base + "_" + digest(normalizeExpression(check.expression())));
    }

    /**
     * Standalone index, unique or non-unique: {@code IX_<TABLE>_<KEY_TERMS>}.
     * Uniqueness is SQL semantics and does not change the logical naming prefix.
     */
    public static Identifier index(Table table, Index index) {
        Objects.requireNonNull(index, "index must not be null");
        String base = "IX_" + tableName(table) + "_" + indexTerms(index.columns());
        if (countIndexBase(table, base) <= 1) return Identifier.of(base);
        return Identifier.of(base + "_" + digest(indexSignature(index)));
    }

    public static boolean isUniqueIndex(Index index) {
        return index != null && index.type() == IndexType.UNIQUE;
    }

    private static long countUniqueKeyBase(Table table, String base) {
        return table.uniqueKeys().stream()
                .map(key -> "UK_" + tableName(table) + "_" + identifiers(key.columns()))
                .filter(base::equals)
                .count();
    }

    private static long countForeignKeyBase(Table table, String base) {
        return table.foreignKeys().stream()
                .map(key -> "FK_" + tableName(table) + "_" + identifiers(key.columns()))
                .filter(base::equals)
                .count();
    }

    private static long countCheckBase(Table table, String base) {
        return table.checkConstraints().stream()
                .map(check -> {
                    List<String> columns = CheckConstraintReferenceAnalyzer.referencedColumns(table, check.expression());
                    String suffix = columns.isEmpty()
                            ? "RULE_" + digest(normalizeExpression(check.expression()))
                            : String.join("_", columns);
                    return "CHK_" + tableName(table) + "_" + suffix;
                })
                .filter(base::equals)
                .count();
    }

    private static long countIndexBase(Table table, String base) {
        return table.indexes().stream()
                .map(index -> "IX_" + tableName(table) + "_" + indexTerms(index.columns()))
                .filter(base::equals)
                .count();
    }

    private static String tableName(Table table) {
        return Objects.requireNonNull(table, "table must not be null")
                .qualifiedName().name().normalized();
    }

    private static String identifiers(List<Identifier> values) {
        return values.stream().map(Identifier::normalized).collect(Collectors.joining("_"));
    }

    private static String indexTerms(List<IndexColumn> columns) {
        return columns.stream()
                .map(column -> column.expressionBased()
                        ? "EXPR_" + digest(normalizeExpression(column.expression()))
                        : column.column().normalized())
                .collect(Collectors.joining("_"));
    }

    private static String uniqueSignature(UniqueKey key) {
        return identifiers(key.columns())
                + "|DEF=" + key.deferrable()
                + "|INIT=" + key.initiallyDeferred();
    }

    private static String foreignKeySignature(ForeignKey key) {
        return identifiers(key.columns())
                + "->" + key.referencedTable().toString().toUpperCase(Locale.ROOT)
                + "(" + identifiers(key.referencedColumns()) + ")"
                + "|DEL=" + key.onDelete()
                + "|UPD=" + key.onUpdate()
                + "|DEF=" + key.deferrable()
                + "|INIT=" + key.initiallyDeferred()
                + "|PHYS=" + key.physicalReference();
    }

    private static String indexSignature(Index index) {
        String keys = index.columns().stream()
                .map(column -> column.expressionBased()
                        ? "EXPR:" + normalizeExpression(column.expression()) + ":" + column.direction()
                        : "COL:" + column.column().normalized() + ":" + column.direction())
                .collect(Collectors.joining("|"));
        return index.type() + "|" + keys
                + "|INCLUDE=" + identifiers(index.includeColumns())
                + "|WHERE=" + normalizeExpression(index.predicate());
    }

    private static String normalizeExpression(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private static String digest(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) hex.append(String.format(Locale.ROOT, "%02X", b));
            return hex.substring(0, COLLISION_HASH_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the SchemaForge naming contract", e);
        }
    }
}
