package com.behsazan.schemaforge.specification.recovery;

import com.behsazan.schemaforge.domain.valueobject.QualifiedName;

import java.util.List;

public record QualifiedNameResult(

        QualifiedName qualifiedName,

        List<String> warnings) {
}