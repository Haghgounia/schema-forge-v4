package com.behsazan.schemaforge.api.error;

import com.behsazan.schemaforge.application.ServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SchemaForgeRestExceptionHandlerTest {
    private static final Instant NOW = Instant.parse("2026-08-23T08:00:00Z");
    private final SchemaForgeRestExceptionHandler handler =
            new SchemaForgeRestExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void mapsIllegalArgumentToVersionedBadRequest() {
        var response = handler.invalidRequest(new IllegalArgumentException("bad schema"), request());

        assertEquals(400, response.getStatusCode().value());
        assertEquals(RestErrorCode.INVALID_REQUEST, response.getBody().code());
        assertEquals("bad schema", response.getBody().message());
        assertEquals(NOW, response.getBody().timestamp());
    }

    @Test
    void preservesCurrentIOExceptionAsBadRequest() {
        var response = handler.inputIoError(new IOException("bad archive"), request());

        assertEquals(400, response.getStatusCode().value());
        assertEquals(RestErrorCode.INPUT_IO_ERROR, response.getBody().code());
    }

    @Test
    void mapsUnavailableMetadataTo503() {
        var response = handler.serviceUnavailable(new ServiceUnavailableException("metadata disabled"), request());

        assertEquals(503, response.getStatusCode().value());
        assertEquals(RestErrorCode.SERVICE_UNAVAILABLE, response.getBody().code());
    }

    @Test
    void mapsMissingMultipartPartWithDetail() {
        var response = handler.missingPart(new MissingServletRequestPartException("file"), request());

        assertEquals(400, response.getStatusCode().value());
        assertEquals(RestErrorCode.MISSING_PART, response.getBody().code());
        assertEquals("file", response.getBody().details().get("part"));
    }

    @Test
    void mapsMissingParameterWithDetail() {
        var response = handler.missingParameter(
                new MissingServletRequestParameterException("schema", "String"), request());

        assertEquals(RestErrorCode.MISSING_PARAMETER, response.getBody().code());
        assertEquals("schema", response.getBody().details().get("parameter"));
    }

    @Test
    void unexpectedErrorDoesNotExposeInternalMessage() {
        var response = handler.internalError(new RuntimeException("password=secret"), request());

        assertEquals(500, response.getStatusCode().value());
        assertEquals(RestErrorCode.INTERNAL_ERROR, response.getBody().code());
        assertNotEquals("password=secret", response.getBody().message());
        assertEquals("Unexpected server error", response.getBody().message());
    }

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/api/v1/generate/word");
    }
}
