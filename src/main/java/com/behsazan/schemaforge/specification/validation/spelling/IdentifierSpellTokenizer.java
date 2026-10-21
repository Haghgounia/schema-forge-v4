package com.behsazan.schemaforge.specification.validation.spelling;

import java.util.Locale;

/** Shared, deterministic tokenization for network and offline spelling providers. */
final class IdentifierSpellTokenizer {
    private IdentifierSpellTokenizer() { }

    static String normalizeIdentifier(String text) {
        return text.replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("[^A-Za-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
