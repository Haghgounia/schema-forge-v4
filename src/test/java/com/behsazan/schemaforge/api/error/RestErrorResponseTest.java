package com.behsazan.schemaforge.api.error;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RestErrorResponseTest {

    @Test
    void acceptsVersionedErrorPayload() {
        RestErrorResponse response = new RestErrorResponse(
                RestErrorResponse.CONTRACT,
                RestErrorCode.INVALID_REQUEST,
                400,
                "invalid input",
                "/api/v1/generate/word",
                "request-1",
                Instant.parse("2026-08-23T07:00:00Z"),
                Map.of("field", "file"));

        assertEquals("schemaforge-rest-error/v1", response.contract());
        assertEquals("file", response.details().get("field"));
    }

    @Test
    void rejectsNonErrorHttpStatus() {
        assertThrows(IllegalArgumentException.class, () -> new RestErrorResponse(
                RestErrorResponse.CONTRACT,
                RestErrorCode.INVALID_REQUEST,
                200,
                "invalid input",
                "/api/v1/generate/word",
                "request-1",
                Instant.EPOCH,
                Map.of()));
    }
}
