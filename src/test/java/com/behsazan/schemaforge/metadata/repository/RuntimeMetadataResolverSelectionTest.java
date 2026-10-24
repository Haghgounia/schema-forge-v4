package com.behsazan.schemaforge.metadata.repository;

import com.behsazan.schemaforge.application.DatabasePlatform;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeMetadataResolverSelectionTest {
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        return mock(ObjectProvider.class);
    }

    @Test void theCurrentRequestUsesOnlyItsOwnVerifiedConnection() {
        var registry = new RuntimeMetadataConnectionRegistry();
        var selection = registry.register(DatabasePlatform.ORACLE,
                "jdbc:oracle:thin:@//example.invalid:1521/TEST", "reader", "sensitive", "APP");
        var resolver = new MetadataRepositoryResolver(emptyProvider(), emptyProvider(), emptyProvider(),
                emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(), registry);
        var request = new MockHttpServletRequest();
        request.addHeader(RuntimeMetadataConnectionRegistry.HEADER, selection.token());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            assertTrue(resolver.resolve(DatabasePlatform.ORACLE) instanceof JdbcOracleMetadataRepository);
            assertThrows(ResponseStatusException.class, () -> resolver.resolve(DatabasePlatform.POSTGRESQL));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
        // No user selection on the next request: legacy server wiring stays unchanged.
        assertFalse(resolver.resolve(DatabasePlatform.ORACLE).available());
    }
}
