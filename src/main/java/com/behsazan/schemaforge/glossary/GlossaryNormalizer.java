package com.behsazan.schemaforge.glossary;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared, locale-independent lexical normalization for curated glossary entries and identifiers. */
public final class GlossaryNormalizer {
    private GlossaryNormalizer() { }

    /** Normalizes English labels to stable lookup keys; enforces storage bound. */
    public static String key(String english) {
        String normalized = normalizeText(english);
        if (normalized.length() > 256) {
            throw new IllegalArgumentException("Glossary key must not exceed 256 characters");
        }
        return normalized;
    }

    private static String normalizeText(String english) {
        if (english == null || english.isBlank()) {
            throw new IllegalArgumentException("English glossary term must not be blank");
        }
        String normalized = Normalizer.normalize(english.trim(), Normalizer.Form.NFKC)
                .replaceAll("([A-Z])([A-Z][a-z])", "$1_$2")
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Glossary term must contain an English identifier");
        }
        return normalized;
    }

    /** Splits PascalCase, camelCase, snake_case, and punctuation-separated names. */
    public static List<String> identifierTokens(String identifier) {
        // Database identifiers may exceed the glossary storage limit; reporting never truncates them.
        String normalized = normalizeText(identifier);
        List<String> tokens = new ArrayList<>();
        for (String component : normalized.split("_")) {
            if (!component.isBlank() && !component.matches("[0-9]+")) {
                tokens.add(component);
            }
        }
        return List.copyOf(tokens);
    }
}
