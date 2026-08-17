package com.behsazan.schemaforge.specification.parser.legacy;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Canonicalizes confirmed legacy database-type aliases without guessing logical N/C types. */
final class LegacyDataTypeNormalizer {
    enum TypeStatus {
        TRUSTED,
        NOT_PRESENT,
        INVALID_SOURCE_TOKEN,
        UNRELIABLE
    }

    private static final Pattern DECLARATION = Pattern.compile(
            "^\\s*([A-Za-z][A-Za-z0-9_]*(?:\\s+[A-Za-z][A-Za-z0-9_]*)?)\\s*(\\([^)]*\\))?\\s*$"
    );
    private static final Pattern INDEX_OR_CONSTRAINT = Pattern.compile(
            "(?i)^\\s*(?:"
                    + "(?:(?:U?IX|IDX|INDEX|X)[A-Z0-9_-]*|I[0-9][A-Z0-9_-]*)"
                    + "(?:\\s+(?:(?:U?IX|IDX|INDEX|X)[A-Z0-9_-]*|I[0-9][A-Z0-9_-]*))*"
                    + "|(?:PK|FK|UK|UQ|PFK)\\s*[A-Z0-9_-]*"
                    + "|SEQ(?:UENCE)?(?:\\s+[A-Z0-9_$#.-]+)?"
                    + ")\\s*$"
    );
    private static final Pattern NUMERIC_OR_PUNCTUATION = Pattern.compile(
            "^[\\s0-9()\\[\\]{},،.:;/\\\\|+*=_-]+$"
    );

    private static final Map<String, String> SOURCE_ALIASES = Map.ofEntries(
            Map.entry("D", "DECIMAL"),
            Map.entry("DC", "DECIMAL"),
            Map.entry("DE", "DECIMAL"),
            Map.entry("DEC", "DECIMAL"),
            Map.entry("DECIMAL", "DECIMAL"),
            Map.entry("F", "DECIMAL"),
            Map.entry("FLT", "DECIMAL"),

            Map.entry("VC", "VARCHAR"),
            Map.entry("V", "VARCHAR"),
            Map.entry("VARCHAR", "VARCHAR"),
            Map.entry("VCHAR", "VARCHAR"),
            Map.entry("VCHR", "VARCHAR"),
            Map.entry("VRACHAR", "VARCHAR"),
            Map.entry("VARCHAT", "VARCHAR"),
            Map.entry("VARCJAR", "VARCHAR"),
            Map.entry("NCARVHAR", "VARCHAR"),
            // Recovery4: observed legacy type-column abbreviations. These tokens are only
            // accepted in the datatype column; they are not inferred from field names.
            Map.entry("VAR", "VARCHAR"),
            Map.entry("NVCHAR", "NVARCHAR"),
            Map.entry("NVC", "NVARCHAR"),

            Map.entry("I", "INTEGER"),
            Map.entry("INT", "INTEGER"),
            Map.entry("INTEGER", "INTEGER"),
            Map.entry("INTEHGER", "INTEGER"),

            Map.entry("SI", "SMALLINT"),
            Map.entry("SM", "SMALLINT"),
            Map.entry("TIN", "SMALLINT"),
            Map.entry("TI", "SMALLINT"),
            Map.entry("B", "SMALLINT"),
            Map.entry("L", "SMALLINT"),
            Map.entry("SINT", "SMALLINT"),
            Map.entry("SMALLINT", "SMALLINT"),
            Map.entry("SMALL INT", "SMALLINT"),
            Map.entry("SMALINT", "SMALLINT"),
            Map.entry("SMALIINT", "SMALLINT"),
            Map.entry("SMAILINT", "SMALLINT"),

            Map.entry("BI", "BIGINT"),
            Map.entry("BIG", "BIGINT"),
            Map.entry("BINT", "BIGINT"),
            Map.entry("BIGINT", "BIGINT"),
            Map.entry("BIG INT", "BIGINT"),

            Map.entry("T", "TIMESTAMP"),
            Map.entry("TD", "TIMESTAMP"),
            Map.entry("DT", "TIMESTAMP"),
            Map.entry("TS", "TIMESTAMP"),
            Map.entry("TIMESTAMP", "TIMESTAMP"),
            Map.entry("TIME STAMP", "TIMESTAMP"),
            Map.entry("TIMESTMP", "TIMESTAMP"),
            Map.entry("TIMSTAMP", "TIMESTAMP"),
            Map.entry("DATETIME", "TIMESTAMP"),

            Map.entry("IMAGE", "BLOB"),
            Map.entry("IMG", "BLOB"),
            Map.entry("IM", "BLOB"),
            Map.entry("BLB", "BLOB"),
            Map.entry("BLOB", "BLOB"),

            Map.entry("CH", "CHAR"),
            Map.entry("CHAR", "CHAR")
    );

    private static final Map<String, String> DB2_ALIASES;
    private static final Set<String> TRUSTED_DB2_BASE_TYPES = Set.of(
            "BIGINT", "BINARY", "BIT", "BLOB", "BOOLEAN", "CHAR", "CHARACTER",
            "CLOB", "DATE", "DBCLOB", "DEC", "DECIMAL", "DECFLOAT", "DOUBLE",
            "FLOAT", "GRAPHIC", "INT", "INTEGER", "NUMERIC", "REAL", "ROWID",
            "SMALLINT", "TIME", "TIMESTAMP", "VARBINARY", "VARCHAR", "VARGRAPHIC", "XML"
    );
    private static final Set<String> TRUSTED_SOURCE_ONLY_BASE_TYPES = Set.of(
            "NCHAR", "NVARCHAR", "NVARCHAR2", "VARCHAR2", "NCLOB", "RAW"
    );

    static {
        java.util.LinkedHashMap<String, String> aliases = new java.util.LinkedHashMap<>(SOURCE_ALIASES);
        // S is ambiguous in the logical/source column (it is also used for string-like data),
        // but it is a confirmed SMALLINT abbreviation in the physical DB2-type column.
        aliases.put("S", "SMALLINT");
        // C in the physical DB2-type column is a legacy abbreviation for CHAR.
        // It remains ambiguous in the logical/source column and is not added to SOURCE_ALIASES.
        aliases.put("C", "CHAR");
        aliases.put("SMALL", "SMALLINT");
        DB2_ALIASES = Map.copyOf(aliases);
    }

    private LegacyDataTypeNormalizer() {
    }

    /** Normalizes the logical/source type column without guessing the ambiguous S alias. */
    static String normalize(String raw) {
        return normalizeWithAliases(raw, SOURCE_ALIASES);
    }

    /**
     * Normalizes the physical DB2 type column only when the value is a confirmed DB2 type or alias.
     * Structural tokens such as IX1, UIX, PK and FK are deliberately rejected.
     */
    static String normalizeDb2(String raw) {
        TypeStatus status = db2TypeStatus(raw);
        if (status != TypeStatus.TRUSTED) {
            return "";
        }
        return normalizeWithAliases(raw, DB2_ALIASES);
    }

    static TypeStatus sourceTypeStatus(String raw) {
        String cleaned = TextNormalizer.cleanCell(raw);
        if (cleaned.isBlank()) {
            return TypeStatus.NOT_PRESENT;
        }
        if (isInvalidStructuralToken(cleaned)) {
            return TypeStatus.INVALID_SOURCE_TOKEN;
        }
        String normalized = normalizeWithAliases(cleaned, SOURCE_ALIASES);
        Matcher matcher = DECLARATION.matcher(normalized);
        if (!matcher.matches()) {
            return TypeStatus.UNRELIABLE;
        }
        String base = matcher.group(1).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        if (Set.of("N", "C", "S").contains(base)
                || TRUSTED_DB2_BASE_TYPES.contains(base)
                || TRUSTED_SOURCE_ONLY_BASE_TYPES.contains(base)) {
            return TypeStatus.TRUSTED;
        }
        return TypeStatus.UNRELIABLE;
    }

    static TypeStatus db2TypeStatus(String raw) {
        String cleaned = TextNormalizer.cleanCell(raw);
        if (cleaned.isBlank()) {
            return TypeStatus.NOT_PRESENT;
        }
        if (isInvalidStructuralToken(cleaned)) {
            return TypeStatus.INVALID_SOURCE_TOKEN;
        }

        String normalized = normalizeWithAliases(cleaned, DB2_ALIASES);
        Matcher matcher = DECLARATION.matcher(normalized);
        if (!matcher.matches()) {
            return TypeStatus.UNRELIABLE;
        }
        String base = matcher.group(1).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        return TRUSTED_DB2_BASE_TYPES.contains(base)
                ? TypeStatus.TRUSTED
                : TypeStatus.UNRELIABLE;
    }

    static boolean isInvalidStructuralToken(String raw) {
        String cleaned = TextNormalizer.cleanCell(raw).toUpperCase(Locale.ROOT);
        if (cleaned.isBlank()) {
            return false;
        }
        // A real SQL datatype must win over the legacy X... index shorthand. In particular
        // XML was previously rejected because the broad index regex also matches X + letters.
        String normalizedSource = normalizeWithAliases(cleaned, SOURCE_ALIASES);
        Matcher sourceMatcher = DECLARATION.matcher(normalizedSource);
        if (sourceMatcher.matches()) {
            String base = sourceMatcher.group(1).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
            if (TRUSTED_DB2_BASE_TYPES.contains(base) || TRUSTED_SOURCE_ONLY_BASE_TYPES.contains(base)) {
                return false;
            }
        }
        if (INDEX_OR_CONSTRAINT.matcher(cleaned).matches()) {
            return true;
        }
        return NUMERIC_OR_PUNCTUATION.matcher(cleaned).matches();
    }

    static boolean isIndexLikeToken(String raw) {
        String cleaned = TextNormalizer.cleanCell(raw).toUpperCase(Locale.ROOT);
        if (cleaned.isBlank() || !INDEX_OR_CONSTRAINT.matcher(cleaned).matches()) {
            return false;
        }
        // Do not classify a real SQL datatype such as XML as an X-prefixed index token.
        String normalized = normalizeWithAliases(cleaned, SOURCE_ALIASES);
        Matcher matcher = DECLARATION.matcher(normalized);
        if (matcher.matches()) {
            String base = matcher.group(1).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
            if (TRUSTED_DB2_BASE_TYPES.contains(base) || TRUSTED_SOURCE_ONLY_BASE_TYPES.contains(base)) {
                return false;
            }
        }
        return !cleaned.startsWith("PK")
                && !cleaned.startsWith("FK")
                && !cleaned.startsWith("UK")
                && !cleaned.startsWith("UQ")
                && !cleaned.startsWith("PFK");
    }

    private static String normalizeWithAliases(String raw, Map<String, String> aliases) {
        String cleaned = TextNormalizer.cleanCell(raw);
        if (cleaned.isBlank()) {
            return "";
        }

        Matcher matcher = DECLARATION.matcher(cleaned);
        if (!matcher.matches()) {
            return cleaned;
        }

        String base = matcher.group(1).replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        String normalizedBase = aliases.getOrDefault(base, base);
        String parameters = matcher.group(2);
        if (parameters == null || parameters.isBlank()) {
            return normalizedBase;
        }
        return normalizedBase + parameters.replaceAll("\\s+", "");
    }
}
