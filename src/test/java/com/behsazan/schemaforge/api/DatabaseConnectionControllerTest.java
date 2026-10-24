package com.behsazan.schemaforge.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseConnectionControllerTest {
    @Test void localBrowserMayReadDefaultsWithoutAdminKey() {
        var service = mock(DatabaseConnectionAdministrationService.class);
        var controller = new DatabaseConnectionController(service);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        assertEquals(200, controller.list(request).getStatusCode().value());
        verify(service).summaries();
    }

    @Test void remoteUnauthenticatedRequestsCannotOpenArbitraryJdbcConnections() {
        var service = mock(DatabaseConnectionAdministrationService.class);
        var controller = new DatabaseConnectionController(service);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.15");
        assertThrows(ResponseStatusException.class, () -> controller.list(request));
        verifyNoInteractions(service);
    }
}
