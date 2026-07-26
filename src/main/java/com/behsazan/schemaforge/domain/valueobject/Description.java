package com.behsazan.schemaforge.domain.valueobject;

/**
 * Represents the validated description value used by the canonical schema model.
 *
 * <p>This type is database-independent and may be shared by every SQL dialect.</p>
 *
 * @since 4.1
 */
public record Description(String value) {
    public Description { value = value == null ? "" : value.trim(); }
    public static Description empty() { return new Description(""); }
    public boolean isEmpty() { return value.isEmpty(); }
}
