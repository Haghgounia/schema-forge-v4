package com.behsazan.schemaforge.api.error;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Versioned HTTP error payload returned by all SchemaForge REST endpoints. */
public record RestErrorResponse(
        String contract,
        RestErrorCode code,
        int status,
        String message,
        String path,
        String requestId,
        Instant timestamp,
        Map<String, Object> details) {

    public static final String CONTRACT = "schemaforge-rest-error/v1";

    public RestErrorResponse {
        if (!CONTRACT.equals(contract)) {
            throw new IllegalArgumentException("Unsupported REST error contract: " + contract);
        }
        Objects.requireNonNull(code, "code");
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("status must be an HTTP error status: " + status);
        }
        message = requireText(message, "message");
        path = requirePath(path);
        requestId = requireText(requestId, "requestId");
        Objects.requireNonNull(timestamp, "timestamp");
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    private static String requirePath(String value) {
        String path = requireText(value, "path");
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("path must start with '/': " + path);
        }
        return path;
    }
}
