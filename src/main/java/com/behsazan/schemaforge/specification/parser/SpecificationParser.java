package com.behsazan.schemaforge.specification.parser;

import com.behsazan.schemaforge.domain.model.DatabaseSchema;

public interface SpecificationParser {
    boolean supports(String fileName);
    DatabaseSchema parse(SpecificationSource source);
}
