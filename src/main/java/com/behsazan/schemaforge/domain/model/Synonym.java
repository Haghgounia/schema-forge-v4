package com.behsazan.schemaforge.domain.model;
import com.behsazan.schemaforge.domain.enums.ObjectType;
import com.behsazan.schemaforge.domain.valueobject.*;
import java.util.Objects;
public record Synonym(QualifiedName qualifiedName, QualifiedName target, boolean publicSynonym, Description description) implements SchemaObject {
    public Synonym { Objects.requireNonNull(qualifiedName); Objects.requireNonNull(target); description = description == null ? Description.empty() : description; }
    @Override public ObjectType objectType() { return ObjectType.SYNONYM; }
}
