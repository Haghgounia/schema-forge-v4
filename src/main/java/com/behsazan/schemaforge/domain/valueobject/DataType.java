package com.behsazan.schemaforge.domain.valueobject;

import java.util.Objects;

public record DataType(Identifier name, Integer length, Integer precision, Integer scale) {
    public DataType {
        Objects.requireNonNull(name, "data type name must not be null");
        positive(length, "length"); positive(precision, "precision");
        if (scale != null && scale < 0) throw new IllegalArgumentException("scale must not be negative");
        if (precision != null && scale != null && scale > precision) throw new IllegalArgumentException("scale must not exceed precision");
        if (length != null && (precision != null || scale != null)) throw new IllegalArgumentException("length cannot be combined with precision or scale");
    }
    private static void positive(Integer value, String name) {
        if (value != null && value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }
    public static DataType simple(String name) { return new DataType(Identifier.of(name), null, null, null); }
    public static DataType varchar(String name, int length) { return new DataType(Identifier.of(name), length, null, null); }
    public static DataType numeric(String name, int precision, Integer scale) { return new DataType(Identifier.of(name), null, precision, scale); }
}
