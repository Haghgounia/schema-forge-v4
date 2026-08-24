package com.behsazan.schemaforge.api.error;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SchemaForgeRequestCorrelationFilterTest {

    @Test
    void addsServerGeneratedRequestIdHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/generate/word");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SchemaForgeRequestCorrelationFilter().doFilter(request, response, new MockFilterChain());

        String requestId = response.getHeader(SchemaForgeRequestCorrelationFilter.HEADER_NAME);
        assertNotNull(requestId);
        UUID.fromString(requestId);
        assertEquals(requestId, request.getAttribute(SchemaForgeRequestCorrelationFilter.ATTRIBUTE_NAME));
    }

    @Test
    void ignoresClientSuppliedCorrelationHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/generate/word");
        request.addHeader(SchemaForgeRequestCorrelationFilter.HEADER_NAME, "client-controlled");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SchemaForgeRequestCorrelationFilter().doFilter(request, response, new MockFilterChain());

        String requestId = response.getHeader(SchemaForgeRequestCorrelationFilter.HEADER_NAME);
        UUID.fromString(requestId);
    }
}
