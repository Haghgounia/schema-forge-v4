package com.behsazan.schemaforge.domain.model;

import com.behsazan.schemaforge.domain.enums.ObjectType;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;

/**
 * Defines the contract for schema object implementations.
 *
 * <p>This type is database-independent and may be shared by every SQL dialect.</p>
 *
 * @since 4.1
 */
public interface SchemaObject {
    QualifiedName qualifiedName();
    ObjectType objectType();
    Description description();
}
