package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.metadata.repository.RuntimeMetadataConnectionRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.util.List;

/**
 * Self-service local JDBC selection. No administrator key is required.
 * Until organization-wide authentication exists, provisioning is loopback-only;
 * deployments must not expose arbitrary JDBC target probing to the network.
 */
@RestController
@RequestMapping("/api/v1/connections")
@Tag(name = "Database Connections", description = "Local self-service JDBC metadata connection selection")
public final class DatabaseConnectionController {
    private final DatabaseConnectionAdministrationService service;

    public DatabaseConnectionController(DatabaseConnectionAdministrationService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Read non-secret effective JDBC settings for seven DBMSs")
    public ResponseEntity<List<DatabaseConnectionAdministrationService.ConnectionSummary>> list(
            HttpServletRequest request) {
        requireLocal(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.summaries());
    }

    @PostMapping(value = "/test", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Test JDBC and optional schema metadata access without selecting an active profile")
    public ResponseEntity<DatabaseConnectionAdministrationService.ProbeResult> test(
            HttpServletRequest servletRequest,
            @RequestBody DatabaseConnectionAdministrationService.ProbeRequest request) {
        requireLocal(servletRequest);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.probe(request));
    }

    @PostMapping(value = "/activate", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Verify JDBC and schema metadata, then activate a browser-scoped runtime connection")
    public ResponseEntity<RuntimeMetadataConnectionRegistry.Selection> activate(
            HttpServletRequest servletRequest,
            @RequestBody DatabaseConnectionAdministrationService.ProbeRequest request) {
        requireLocal(servletRequest);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.activate(request));
    }

    @PostMapping(value = "/configured/activate", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Activate an enabled server-defined JDBC connection without exposing credentials")
    public ResponseEntity<RuntimeMetadataConnectionRegistry.Selection> activateConfigured(
            HttpServletRequest servletRequest, @RequestBody ConfiguredProfileRequest body) {
        requireLocal(servletRequest);
        if (body == null || body.platform() == null || body.platform().isBlank()) {
            throw new IllegalArgumentException("Configured DBMS platform is required");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.activateConfigured(body.platform()));
    }

    public record ConfiguredProfileRequest(String platform) { }

    @GetMapping("/active")
    @Operation(summary = "Verify an existing session's active runtime metadata connection")
    public ResponseEntity<RuntimeMetadataConnectionRegistry.Selection> active(
            HttpServletRequest servletRequest,
            @RequestHeader(RuntimeMetadataConnectionRegistry.HEADER) String connectionToken) {
        requireLocal(servletRequest);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.selected(connectionToken));
    }

    @DeleteMapping("/active")
    @Operation(summary = "Forget one browser's selected runtime metadata connection")
    public ResponseEntity<Void> disconnect(
            HttpServletRequest servletRequest,
            @RequestHeader(RuntimeMetadataConnectionRegistry.HEADER) String connectionToken) {
        requireLocal(servletRequest);
        service.disconnect(connectionToken);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static boolean loopbackHost(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) return true;
        String host = hostHeader.trim().toLowerCase(java.util.Locale.ROOT);
        return host.matches("(?:localhost|127\\.0\\.0\\.1|\\[::1\\])(?::[0-9]{1,5})?");
    }

    private static boolean loopbackOrigin(String origin) {
        if (origin == null || origin.isBlank()) return true;
        try {
            java.net.URI url = java.net.URI.create(origin);
            return ("http".equalsIgnoreCase(url.getScheme())
                    || "https".equalsIgnoreCase(url.getScheme()))
                    && ("localhost".equalsIgnoreCase(url.getHost())
                    || "127.0.0.1".equals(url.getHost())
                    || "::1".equals(url.getHost()));
        } catch (Exception ignored) { return false; }
    }

    private static void requireLocal(HttpServletRequest request) {
        try {
            if (InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()
                    && loopbackHost(request.getHeader("Host"))
                    && loopbackOrigin(request.getHeader("Origin"))) return;
        } catch (Exception ignored) { }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "JDBC connection provisioning is local-only until an authenticated gateway is configured");
    }
}
