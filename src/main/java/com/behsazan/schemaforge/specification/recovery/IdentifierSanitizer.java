package com.behsazan.schemaforge.specification.recovery;


import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Performs best-effort recovery for a single SQL identifier.
 *
 * <p>This class sanitizes only standalone identifiers.
 * Qualified names must be handled by {@link QualifiedNameParser}.
 */
public final class IdentifierSanitizer {

    public RecoveryResult sanitize(
            String rawIdentifier,
            String objectType) {

        Objects.requireNonNull(objectType);

        if (rawIdentifier == null) {
            return RecoveryResult.unchanged(null);
        }

        List<String> warnings = new ArrayList<>();

        String original = rawIdentifier;

        String value = rawIdentifier
                .trim()
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT);

        if (value.contains(".")) {
            throw new IllegalArgumentException(
                    "Qualified identifier must be parsed using QualifiedNameParser: "
                            + original);
        }

        String sanitized = value
                .replace(' ', '_')
                .replace('-', '_')
                .replace('/', '_');

        sanitized = sanitized.replaceAll("[^A-Z0-9_$#]", "");

        if (!sanitized.equals(value)) {

            warnings.add(
                    "Recovered "
                            + objectType
                            + " identifier '"
                            + original
                            + "' as '"
                            + sanitized
                            + "'");
        }

        return new RecoveryResult(
                sanitized,
                warnings);
    }
}