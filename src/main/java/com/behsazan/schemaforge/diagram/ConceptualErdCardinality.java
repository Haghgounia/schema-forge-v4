package com.behsazan.schemaforge.diagram;

import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.Identifier;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Evidence-based cardinality for the field-free conceptual ERD view.
 *
 * <p>The resolver deliberately uses only canonical relational evidence:</p>
 * <ul>
 *   <li>FK nullability determines whether a child requires exactly one parent or may have none.</li>
 *   <li>An exact PK/UK match on the FK columns limits a parent to at most one child.</li>
 *   <li>No schema constraint can prove that every parent has a child, so the child minimum is zero.</li>
 * </ul>
 *
 * <p>No relationship or multiplicity is inferred from column names.</p>
 */
public record ConceptualErdCardinality(End parentEnd, End childEnd) {

    public ConceptualErdCardinality {
        Objects.requireNonNull(parentEnd, "parentEnd must not be null");
        Objects.requireNonNull(childEnd, "childEnd must not be null");
    }

    public static ConceptualErdCardinality resolve(Table child, ForeignKey foreignKey) {
        Objects.requireNonNull(child, "child must not be null");
        Objects.requireNonNull(foreignKey, "foreignKey must not be null");

        boolean optionalParent = foreignKey.columns().stream()
                .map(column -> child.findColumn(column.value()).orElse(null))
                .anyMatch(column -> column == null || column.nullable());

        boolean uniqueChildReference = child.primaryKey()
                .map(primaryKey -> sameColumns(primaryKey.columns(), foreignKey.columns()))
                .orElse(false)
                || child.uniqueKeys().stream()
                .anyMatch(uniqueKey -> sameColumns(uniqueKey.columns(), foreignKey.columns()));

        return new ConceptualErdCardinality(
                optionalParent ? End.ZERO_OR_ONE : End.EXACTLY_ONE,
                uniqueChildReference ? End.ZERO_OR_ONE : End.ZERO_OR_MANY);
    }

    private static boolean sameColumns(List<Identifier> first, List<Identifier> second) {
        if (first.size() != second.size()) {
            return false;
        }
        Set<String> left = first.stream().map(Identifier::normalized).collect(Collectors.toSet());
        Set<String> right = second.stream().map(Identifier::normalized).collect(Collectors.toSet());
        return left.equals(right);
    }

    /** Cardinality forms that can be proven from the canonical relational model. */
    public enum End {
        ZERO_OR_ONE("o|", "0..1"),
        EXACTLY_ONE("||", "1"),
        ZERO_OR_MANY("o{", "0..N");

        private final String mermaid;
        private final String label;

        End(String mermaid, String label) {
            this.mermaid = mermaid;
            this.label = label;
        }

        public String mermaid() {
            return mermaid;
        }

        public String label() {
            return label;
        }
    }
}
