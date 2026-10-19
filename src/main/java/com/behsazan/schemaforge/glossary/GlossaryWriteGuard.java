package com.behsazan.schemaforge.glossary;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Explicit credential for write/import operations; never enables unauthenticated vocabulary mutation. */
public final class GlossaryWriteGuard {
    private final String secret;

    public GlossaryWriteGuard(String secret) {
        this.secret = secret == null ? "" : secret;
    }

    public void requireAuthorized(String supplied) {
        if (secret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Glossary write access requires SCHEMAFORGE_GLOSSARY_ADMIN_KEY");
        }
        if (supplied == null || !MessageDigest.isEqual(
                secret.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid glossary administrator key");
        }
    }
}
