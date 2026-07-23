package com.behsazan.schemaforge.domain.model;

import com.behsazan.schemaforge.domain.enums.ObjectType;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;

public interface SchemaObject {
    QualifiedName qualifiedName();
    ObjectType objectType();
    Description description();
}
