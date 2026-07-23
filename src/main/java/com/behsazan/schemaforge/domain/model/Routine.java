package com.behsazan.schemaforge.domain.model;
import com.behsazan.schemaforge.domain.enums.*;
import com.behsazan.schemaforge.domain.valueobject.*;
import java.util.*;
public record Routine(QualifiedName qualifiedName, RoutineType routineType, List<RoutineParameter> parameters, DataType returnType, String body, Description description) implements SchemaObject {
    public Routine { Objects.requireNonNull(qualifiedName); Objects.requireNonNull(routineType); parameters = parameters == null ? List.of() : List.copyOf(parameters); body = Objects.requireNonNull(body).trim(); if (routineType == RoutineType.FUNCTION && returnType == null) throw new IllegalArgumentException("function returnType is required"); description = description == null ? Description.empty() : description; }
    @Override public ObjectType objectType() { return routineType == RoutineType.FUNCTION ? ObjectType.FUNCTION : ObjectType.PROCEDURE; }
}
