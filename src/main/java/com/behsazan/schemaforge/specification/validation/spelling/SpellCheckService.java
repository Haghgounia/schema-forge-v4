package com.behsazan.schemaforge.specification.validation.spelling;

import java.util.List;

/**
 * Coordinates spell check operations.
 *
 * @since 4.1
 */
@FunctionalInterface
public interface SpellCheckService {
    List<SpellingError> check(String text);
}
