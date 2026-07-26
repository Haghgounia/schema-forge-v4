package com.behsazan.schemaforge.specification.validation.spelling;

import java.util.List;

/**
 * Provides spelling error functionality within the SchemaForge processing pipeline.
 *
 * @since 4.1
 */
public record SpellingError(
        String word,
        String message,
        List<SpellingSuggestion> suggestions,
        boolean serviceFailure) {

    public SpellingError(String word, String message, List<SpellingSuggestion> suggestions) {
        this(word, message, suggestions, false);
    }

    public SpellingError {
        word = word == null ? "" : word;
        message = message == null ? "" : message;
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }

    public static SpellingError serviceFailure(String message) {
        return new SpellingError("", message, List.of(), true);
    }
}
