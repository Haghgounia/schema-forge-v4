package com.behsazan.schemaforge.domain.valueobject;

public record DefaultValue(String expression) {
    public DefaultValue { expression = expression == null ? null : expression.trim(); }
    public boolean isPresent() { return expression != null && !expression.isBlank(); }
}


