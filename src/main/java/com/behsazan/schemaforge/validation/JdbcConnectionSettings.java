package com.behsazan.schemaforge.validation;

import java.util.Objects;

/** JDBC settings used only when explicit execution validation is requested. */
public record JdbcConnectionSettings(String url, String username, String password) {
    public JdbcConnectionSettings {
        Objects.requireNonNull(url, "url must not be null");
        if (url.isBlank()) {
            throw new IllegalArgumentException("JDBC URL must not be blank");
        }
        username = username == null ? "" : username;
        password = password == null ? "" : password;
    }
}
