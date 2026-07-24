package com.behsazan.schemaforge.specification.validation;

import com.behsazan.schemaforge.domain.valueobject.Identifier;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Database-independent identifier validation used in phase 1. */
public final class IdentifierValidator {
    private static final int MAX_LENGTH = 128;
    private static final Pattern VALID = Pattern.compile("[A-Z][A-Z0-9_$#]*");

    public String requireValid(String value, String objectType, String sourceName) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException("Missing " + objectType + " name" + suffix(sourceName));
        }
        Objects.requireNonNull(objectType, "objectType must not be null");
        if (normalized.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Invalid " + objectType + " identifier '" + normalized
                    + "': exceeds " + MAX_LENGTH + " characters" + suffix(sourceName));
        }
        if (!VALID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid " + objectType + " identifier '" + normalized
                    + "': must start with a letter and contain only letters, digits, _, $, or #" + suffix(sourceName));
        }
        return normalized;
    }

    public String requireValid(String value, String objectType) {
        return requireValid(value, objectType, null);
    }

    public Identifier toIdentifier(String value, String objectType) {
        return Identifier.of(requireValid(value, objectType));
    }

    public String normalize(String value) {
        if (value == null) return null;
        String normalized = value.replace('\u00A0', ' ')
                .replace("\uFEFF", "")
                .replace("\u200B", "")
                .replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')
                .trim().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static String suffix(String sourceName) {
        return sourceName == null || sourceName.isBlank() ? "" : " in " + sourceName;
    }
}
