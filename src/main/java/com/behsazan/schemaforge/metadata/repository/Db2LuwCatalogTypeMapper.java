package com.behsazan.schemaforge.metadata.repository;

import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.LengthSemantics;

import java.util.Locale;

/** Converts Db2 LUW SYSCAT.COLUMNS type fields into canonical DataType values. */
final class Db2LuwCatalogTypeMapper {
    private Db2LuwCatalogTypeMapper() {
    }

    static DataType mapDataType(String rawType, Integer length, Integer scaleValue,
                                Integer stringUnitsLength, String typeSchema) {
        String raw = normalizeType(rawType);
        int lengthValue = positive(stringUnitsLength, positive(length, 1));
        int scale = scaleValue == null ? 0 : scaleValue;

        return switch (raw) {
            case "SMALLINT", "INTEGER", "BIGINT", "TIME", "XML", "BOOLEAN" -> DataType.simple(raw);
            case "DATE" -> DataType.simple("DB2_DATE");
            case "DECIMAL", "NUMERIC" -> DataType.numeric("DECIMAL", positive(length, 1), scale);
            case "CHAR", "CHARACTER" -> new DataType(
                    Identifier.of("CHAR"), lengthValue, LengthSemantics.CHAR, null, null);
            case "VARCHAR", "CHARACTER VARYING" -> new DataType(
                    Identifier.of("VARCHAR"), lengthValue, LengthSemantics.CHAR, null, null);
            case "GRAPHIC" -> new DataType(
                    Identifier.of("GRAPHIC"), lengthValue, LengthSemantics.CHAR, null, null);
            case "VARGRAPHIC" -> new DataType(
                    Identifier.of("VARGRAPHIC"), lengthValue, LengthSemantics.CHAR, null, null);
            case "BINARY" -> new DataType(
                    Identifier.of("BINARY"), positive(length, 1), LengthSemantics.DEFAULT, null, null);
            case "VARBINARY" -> new DataType(
                    Identifier.of("VARBINARY"), positive(length, 1), LengthSemantics.DEFAULT, null, null);
            case "BLOB", "CLOB", "DBCLOB" -> new DataType(
                    Identifier.of(raw), positive(length, 1), LengthSemantics.DEFAULT, null, null);
            case "TIMESTAMP" -> scale == 0 ? DataType.simple("DB2_LUW_TIMESTAMP0")
                    : DataType.numeric("TIMESTAMP", scale, null);
            case "REAL" -> DataType.simple("REAL");
            case "DOUBLE", "DOUBLE PRECISION" -> DataType.simple("DOUBLE");
            case "DECFLOAT" -> DataType.numeric("DECFLOAT", positive(length, 8) <= 8 ? 16 : 34, null);
            default -> isSystemType(typeSchema)
                    ? DataType.simple(safeTypeName(raw, "DB2_LUW"))
                    : DataType.simple(safeTypeName(raw, "DB2_LUW_DISTINCT"));
        };
    }

    private static boolean isSystemType(String schema) {
        return schema == null || schema.isBlank() || "SYSIBM".equalsIgnoreCase(schema.trim());
    }

    private static String normalizeType(String value) {
        return value == null ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String safeTypeName(String value, String fallback) {
        String source = value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
        String safe = source.replaceAll("[^A-Z0-9_$#]+", "_").replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (safe.isBlank()) return fallback;
        if (!Character.isLetter(safe.charAt(0))) return fallback + "_" + safe;
        return safe;
    }

    private static int positive(Integer preferred, int fallback) {
        return preferred != null && preferred > 0 ? preferred : fallback;
    }
}
