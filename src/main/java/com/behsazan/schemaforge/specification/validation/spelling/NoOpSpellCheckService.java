package com.behsazan.schemaforge.specification.validation.spelling;

import java.util.List;

/**
 * Coordinates no op spell check operations.
 *
 * @since 4.1
 */
public final class NoOpSpellCheckService implements SpellCheckService {
    @Override
    public List<SpellingError> check(String text) { return List.of(); }
}
