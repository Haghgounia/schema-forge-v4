package com.behsazan.schemaforge.specification.recovery;

import com.behsazan.schemaforge.domain.valueobject.QualifiedName;

import java.util.List;

/**
 * Represents the immutable qualified name result produced by the SchemaForge workflow.
 *
 * @since 4.1
 */
public record QualifiedNameResult(

        QualifiedName qualifiedName,

        List<String> warnings) {
}
