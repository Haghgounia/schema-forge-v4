package com.behsazan.schemaforge.comparison;

import com.behsazan.schemaforge.model.SchemaDefinition;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ComparisonEngine {
    public List<String> compare(SchemaDefinition expected, SchemaDefinition actual) {
        // Initial placeholder. Phase 4 comparison migration will replace this implementation.
        if (expected.equals(actual)) {
            return List.of();
        }
        return List.of("Schema definitions are different");
    }
}
