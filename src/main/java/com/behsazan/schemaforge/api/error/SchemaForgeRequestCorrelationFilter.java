package com.behsazan.schemaforge.api.error;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** Adds one server-generated correlation identifier to every HTTP request/response. */
@Component
public class SchemaForgeRequestCorrelationFilter extends OncePerRequestFilter {
    public static final String HEADER_NAME = "X-SchemaForge-Request-Id";
    static final String ATTRIBUTE_NAME = SchemaForgeRequestCorrelationFilter.class.getName() + ".requestId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String requestId = currentOrCreate(request);
        response.setHeader(HEADER_NAME, requestId);
        filterChain.doFilter(request, response);
    }

    static String applicationPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    static String currentOrCreate(HttpServletRequest request) {
        Object current = request.getAttribute(ATTRIBUTE_NAME);
        if (current instanceof String value && !value.isBlank()) {
            return value;
        }
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE_NAME, requestId);
        return requestId;
    }
}
