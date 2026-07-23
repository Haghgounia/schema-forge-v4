package com.behsazan.schemaforge.domain.model;
import com.behsazan.schemaforge.domain.enums.ObjectType;
import com.behsazan.schemaforge.domain.valueobject.*;
import java.util.Objects;
public record Sequence(QualifiedName qualifiedName, long startWith, long incrementBy, Long minValue, Long maxValue, boolean cycle, Integer cacheSize, Description description) implements SchemaObject {
    public Sequence { Objects.requireNonNull(qualifiedName); if (incrementBy == 0) throw new IllegalArgumentException("incrementBy must not be zero"); if (cacheSize != null && cacheSize < 0) throw new IllegalArgumentException("cacheSize must not be negative"); description = description == null ? Description.empty() : description; }
    @Override public ObjectType objectType() { return ObjectType.SEQUENCE; }
}
