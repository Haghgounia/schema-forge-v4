package com.behsazan.schemaforge.domain.model;

import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import java.util.List;
import java.util.Objects;

public record Grant(QualifiedName target, String objectType, Identifier grantee,
                    List<String> privileges, boolean grantOption) {
    public Grant {
        Objects.requireNonNull(target, "target must not be null");
        objectType = objectType == null || objectType.isBlank() ? "OBJECT" : objectType.trim().toUpperCase();
        Objects.requireNonNull(grantee, "grantee must not be null");
        privileges = privileges == null ? List.of() : privileges.stream()
                .map(value -> Objects.requireNonNull(value, "privilege must not be null").trim().toUpperCase())
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        if (privileges.isEmpty()) throw new IllegalArgumentException("privileges must not be empty");
    }
}
