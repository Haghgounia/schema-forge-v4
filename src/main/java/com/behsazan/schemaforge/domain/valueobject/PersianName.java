package com.behsazan.schemaforge.domain.valueobject;

/**
 * Represents the optional Persian display name of a canonical schema object.
 *
 * <p>The value is independent from the technical database identifier and from
 * the longer object description/comment.</p>
 *
 * @since 4.1
 */
public record PersianName(String value) {
    public PersianName { value = value == null ? "" : value.trim(); }
    public static PersianName empty() { return new PersianName(""); }
    public boolean isEmpty() { return value.isEmpty(); }
}
