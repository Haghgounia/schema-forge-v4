package com.behsazan.schemaforge.metadata.repository;

import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.LengthSemantics;

import java.util.Locale;

/** Shared conversion of SYSIBM.SYSCOLUMNS type fields into canonical DataType values. */
final class Db2ZosCatalogTypeMapper {
    private Db2ZosCatalogTypeMapper() {
    }

    static DataType mapDataType(String rawType, Integer length, Integer longLength,
                                Integer scaleValue, String typeName) {
        String raw = normalizeType(rawType);
        int lengthValue = positive(length, 1);
        int longLengthValue = positive(longLength, lengthValue);
        int scale = scaleValue == null ? 0 : scaleValue;

        return switch (raw) {
            case "SMALLINT", "INTEGER", "BIGINT", "TIME", "XML" -> DataType.simple(raw);
            case "DATE" -> DataType.simple("DB2_DATE");
            case "ROWID" -> DataType.simple("DB2_ROWID");
            case "DECIMAL", "NUMERIC" -> DataType.numeric("DECIMAL", lengthValue, scale);
            case "CHAR" -> new DataType(Identifier.of("CHAR"), lengthValue, LengthSemantics.CHAR, null, null);
            case "VARCHAR", "LONGVAR" -> new DataType(
                    Identifier.of("VARCHAR"), raw.equals("LONGVAR") ? longLengthValue : lengthValue,
                    LengthSemantics.CHAR, null, null);
            case "GRAPHIC" -> new DataType(Identifier.of("GRAPHIC"), lengthValue, LengthSemantics.CHAR, null, null);
            case "VARG", "LONGVARG" -> new DataType(
                    Identifier.of("VARGRAPHIC"), raw.equals("LONGVARG") ? longLengthValue : lengthValue,
                    LengthSemantics.CHAR, null, null);
            case "BINARY" -> new DataType(Identifier.of("BINARY"), lengthValue, LengthSemantics.DEFAULT, null, null);
            case "VARBIN" -> new DataType(Identifier.of("VARBINARY"), lengthValue, LengthSemantics.DEFAULT, null, null);
            case "BLOB", "CLOB", "DBCLOB" -> new DataType(
                    Identifier.of(raw), longLengthValue, LengthSemantics.DEFAULT, null, null);
            case "TIMESTMP" -> scale <= 0 ? DataType.simple("TIMESTAMP")
                    : DataType.numeric("TIMESTAMP", scale, null);
            case "TIMESTZ" -> scale <= 0 ? DataType.simple("TIMESTAMP_WITH_TIME_ZONE")
                    : DataType.numeric("TIMESTAMP_WITH_TIME_ZONE", scale, null);
            case "FLOAT" -> DataType.simple(lengthValue <= 4 ? "REAL" : "DOUBLE");
            case "DECFLOAT" -> DataType.numeric("DECFLOAT", lengthValue <= 8 ? 16 : 34, null);
            case "DISTINCT" -> DataType.simple(safeTypeName(typeName, "DB2_DISTINCT"));
            default -> DataType.simple(safeTypeName(raw, "DB2"));
        };
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
