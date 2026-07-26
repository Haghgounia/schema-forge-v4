package com.behsazan.schemaforge.domain.valueobject;

/**
 * Represents the validated default value value used by the canonical schema model.
 *
 * <p>This type is database-independent and may be shared by every SQL dialect.</p>
 *
 * @since 4.1
 */
public record DefaultValue(String expression) {
    public DefaultValue { expression = expression == null ? null : expression.trim(); }
    public boolean isPresent() { return expression != null && !expression.isBlank(); }
}


