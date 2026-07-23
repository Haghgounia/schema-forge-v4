package com.behsazan.schemaforge.specification.spi;

import com.behsazan.schemaforge.domain.model.DatabaseSchema;

public interface SpecificationParser {
    boolean supports(String fileName);
    DatabaseSchema parse(SpecificationSource source);
}
