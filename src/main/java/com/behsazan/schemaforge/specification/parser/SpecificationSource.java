package com.behsazan.schemaforge.specification.parser;

import java.io.InputStream;
import java.util.Objects;

/**
 * Provides specification source functionality within the SchemaForge processing pipeline.
 *
 * @since 4.1
 */
public record SpecificationSource(String fileName, InputStream content) {
    public SpecificationSource {
        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(content, "content must not be null");
    }
}
