package com.behsazan.schemaforge.specification.parser;

import com.behsazan.schemaforge.domain.model.DatabaseSchema;

/**
 * Parses specification input into the canonical schema model.
 *
 * @since 4.1
 */
public interface SpecificationParser {
    boolean supports(String fileName);
    DatabaseSchema parse(SpecificationSource source);
}
