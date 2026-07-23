package com.behsazan.schemaforge.validation.oracle;

import com.behsazan.schemaforge.domain.valueobject.Identifier;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Validates and normalizes unquoted Oracle identifiers. */
public final class OracleIdentifierValidator {
    private static final int MAX_IDENTIFIER_LENGTH = 128;
    private static final Pattern VALID_IDENTIFIER = Pattern.compile("[A-Z][A-Z0-9_$#]*");

    public String requireValid(String value, String objectType, String sourceName) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(missingMessage(objectType, sourceName));
        }
        validateNormalized(normalized, objectType, sourceName);
        return normalized;
    }

    public String requireValid(String value, String objectType) {
        return requireValid(value, objectType, null);
    }

    public Identifier toIdentifier(String value, String objectType) {
        return Identifier.of(requireValid(value, objectType));
    }

    public String normalize(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value
                .replace('\u00A0', ' ')
                .replace('\u2007', ' ')
                .replace('\u202F', ' ')
                .replace("\uFEFF", "")
                .replace("\u200B", "")
                .replace("\u200C", "")
                .replace("\u200D", "")
                .replace('\t', ' ')
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim()
                .replaceAll("\\s+", "")
                .toUpperCase(Locale.ROOT);

        return normalized.isEmpty() ? null : normalized;
    }

    private void validateNormalized(String value, String objectType, String sourceName) {
        Objects.requireNonNull(objectType, "objectType must not be null");

        if (value.length() > MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException(message(
                    objectType,
                    value,
                    "exceeds Oracle's " + MAX_IDENTIFIER_LENGTH + " character limit",
                    sourceName));
        }
        if (!VALID_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(message(
                    objectType,
                    value,
                    "must start with a letter and contain only letters, digits, _, $, or #",
                    sourceName));
        }
    }

    private String missingMessage(String objectType, String sourceName) {
        String message = "Missing " + objectType + " name";
        return sourceName == null || sourceName.isBlank() ? message : message + " in " + sourceName;
    }

    private String message(String objectType, String value, String reason, String sourceName) {
        String message = "Invalid Oracle " + objectType + " identifier '" + value + "': " + reason;
        return sourceName == null || sourceName.isBlank() ? message : message + " in " + sourceName;
    }
}
