package com.behsazan.schemaforge.domain.model;
import com.behsazan.schemaforge.domain.enums.ObjectType;
import com.behsazan.schemaforge.domain.valueobject.*;
import java.util.Objects;
public record Trigger(QualifiedName qualifiedName, QualifiedName table, String timing, String event, String body, Description description) implements SchemaObject {
    public Trigger { Objects.requireNonNull(qualifiedName); Objects.requireNonNull(table); timing = Objects.requireNonNull(timing).trim(); event = Objects.requireNonNull(event).trim(); body = Objects.requireNonNull(body).trim(); description = description == null ? Description.empty() : description; }
    @Override public ObjectType objectType() { return ObjectType.TRIGGER; }
}
