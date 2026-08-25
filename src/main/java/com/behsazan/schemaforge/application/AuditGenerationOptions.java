package com.behsazan.schemaforge.application;

import com.behsazan.schemaforge.config.AuditProperties;
import com.behsazan.schemaforge.generation.enrichment.AuditProfile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Request-level audit enrichment options shared by all REST generation endpoints. */
public record AuditGenerationOptions(boolean includeAuditFields, AuditProfile auditProfile) {
    public AuditGenerationOptions {
        Objects.requireNonNull(auditProfile, "auditProfile must not be null");
    }

    public static AuditGenerationOptions defaults(AuditProperties properties) {
        Objects.requireNonNull(properties, "audit properties must not be null");
        return new AuditGenerationOptions(properties.isEnabled(), AuditProfile.AUTO);
    }

    public static AuditGenerationOptions resolve(
            AuditProperties properties,
            Boolean includeAuditFields,
            String auditProfile) {
        Objects.requireNonNull(properties, "audit properties must not be null");
        boolean include = includeAuditFields == null ? properties.isEnabled() : includeAuditFields;
        return new AuditGenerationOptions(include, AuditProfile.parse(auditProfile));
    }

    public Map<String, Object> manifestValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("includeAuditFields", includeAuditFields);
        value.put("auditProfile", auditProfile.name());
        return Map.copyOf(value);
    }
}
