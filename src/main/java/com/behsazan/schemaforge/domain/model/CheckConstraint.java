package com.behsazan.schemaforge.domain.model;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import java.util.Objects;
/**
 * Represents a database-independent check constraint in the canonical schema model.
 *
 * <p>This type is database-independent and may be shared by every SQL dialect.</p>
 *
 * @since 4.1
 */
public record CheckConstraint(Identifier name, String expression) {
    public CheckConstraint { expression = Objects.requireNonNull(expression).trim(); if (expression.isEmpty()) throw new IllegalArgumentException("check expression must not be blank"); }
}
