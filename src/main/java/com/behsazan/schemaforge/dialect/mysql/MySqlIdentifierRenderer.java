package com.behsazan.schemaforge.dialect.mysql;

import com.behsazan.schemaforge.domain.valueobject.Identifier;

import java.util.Objects;

/** Renders MySQL identifiers with backticks, preserving canonical source case. */
public final class MySqlIdentifierRenderer {
    public String render(Identifier identifier) {
        Objects.requireNonNull(identifier, "identifier must not be null");
        return "`" + identifier.value().replace("`", "``") + "`";
    }
}
