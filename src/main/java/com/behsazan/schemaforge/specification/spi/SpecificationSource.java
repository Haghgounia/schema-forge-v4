package com.behsazan.schemaforge.specification.spi;

import java.io.InputStream;
import java.util.Objects;

public record SpecificationSource(String fileName, InputStream content) {
    public SpecificationSource {
        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(content, "content must not be null");
    }
}
