package com.behsazan.schemaforge.domain.model;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import java.util.Objects;
public record CheckConstraint(Identifier name, String expression) {
    public CheckConstraint { expression = Objects.requireNonNull(expression).trim(); if (expression.isEmpty()) throw new IllegalArgumentException("check expression must not be blank"); }
}
