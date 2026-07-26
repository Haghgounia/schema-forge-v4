package com.behsazan.schemaforge.domain.valueobject;

import java.util.Objects;

/**
 * Represents the validated data type value used by the canonical schema model.
 *
 * <p>This type is database-independent and may be shared by every SQL dialect.</p>
 *
 * @since 4.1
 */
public record DataType(
        Identifier name,
        Integer length,
        LengthSemantics lengthSemantics,
        Integer precision,
        Integer scale) {

    public DataType {
        Objects.requireNonNull(name, "data type name must not be null");
        lengthSemantics = lengthSemantics == null ? LengthSemantics.DEFAULT : lengthSemantics;
        positive(length, "length");
        positive(precision, "precision");
        if (scale != null && scale < 0) {
            throw new IllegalArgumentException("scale must not be negative");
        }
        if (precision != null && scale != null && scale > precision) {
            throw new IllegalArgumentException("scale must not exceed precision");
        }
        if (length != null && (precision != null || scale != null)) {
            throw new IllegalArgumentException("length cannot be combined with precision or scale");
        }
        if (length == null && lengthSemantics != LengthSemantics.DEFAULT) {
            throw new IllegalArgumentException("length semantics requires a length");
        }
    }

    /** Backward-compatible constructor used by existing callers. */
    public DataType(Identifier name, Integer length, Integer precision, Integer scale) {
        this(name, length, LengthSemantics.DEFAULT, precision, scale);
    }

    private static void positive(Integer value, String name) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    public static DataType simple(String name) {
        return new DataType(Identifier.of(name), null, LengthSemantics.DEFAULT, null, null);
    }

    /** Preserves the established project policy: character lengths default to CHAR semantics. */
    public static DataType varchar(String name, int length) {
        return varchar(name, length, LengthSemantics.CHAR);
    }

    public static DataType varchar(String name, int length, LengthSemantics semantics) {
        return new DataType(Identifier.of(name), length, semantics, null, null);
    }

    public static DataType numeric(String name, int precision, Integer scale) {
        return new DataType(Identifier.of(name), null, LengthSemantics.DEFAULT, precision, scale);
    }
}
