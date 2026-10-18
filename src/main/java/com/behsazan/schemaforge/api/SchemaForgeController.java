package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.artifact.ArtifactNamingPolicy;
import com.behsazan.schemaforge.application.TimestampSemantics;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Exposes REST endpoints for schema forge operations.
 *
 * @since 4.1
 */
@RestController
@RequestMapping("/api/v1/generate")
@Tag(name = "Schema Generation", description = "Generate Oracle, PostgreSQL, Db2 for z/OS, Db2 LUW, SQL Server, and MySQL DDL from Word, legacy Word, legacy Word ZIP batches, ZIP, or Enterprise Architect XML/XMI")
public class SchemaForgeController {
    private static final String AUDIT_FIELDS_DESCRIPTION =
            "Audit Fields:\n\n"
                    + "- **source - Recommended**: Keep source audit fields; add nothing.\n"
                    + "- **created-updated**: Add `CREATED_AT, CREATED_BY, UPDATED_AT, UPDATED_BY` when missing.\n"
                    + "- **created-last-modified**: Add `CREATED_BY, CREATED_DATE, LAST_MODIFIED_BY, LAST_MODIFIED_DATE` when missing.\n"
                    + "- **none**: Add no audit fields.\n\n"
                    + "Existing source-defined audit fields are always preserved. Default: `source`.";
    private static final String TIMESTAMP_SEMANTICS_DESCRIPTION =
            "Timestamp Semantics for source columns declared as unqualified `TIMESTAMP`:\n\n"
                    + "- **source - Recommended**: Keep source semantics unchanged.\n"
                    + "- **without-time-zone**: Keep unqualified `TIMESTAMP` without timezone semantics.\n"
                    + "- **with-time-zone**: Treat unqualified `TIMESTAMP` as `TIMESTAMP WITH TIME ZONE`.\n"
                    + "- **with-local-time-zone**: Treat unqualified `TIMESTAMP` as `TIMESTAMP WITH LOCAL TIME ZONE`.\n\n"
                    + "Explicit source qualifiers are authoritative and are never overwritten. "
                    + "Target DBMS mappings remain authoritative; unsupported lossless mappings are not silently downgraded. "
                    + "Default: `source`.";
    private final SchemaForgeApiService service;
    private final ArtifactNamingPolicy archiveNamingPolicy;

    public SchemaForgeController(SchemaForgeApiService service) {
        this.service = service;
        this.archiveNamingPolicy = new ArtifactNamingPolicy();
    }

    @PostMapping(value = "/word", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "application/zip")
    @Operation(summary = "Generate from one Word specification")
    public ResponseEntity<byte[]> word(
            @RequestPart("file") MultipartFile file,
            @Parameter(
                    required = true,
                    description = "Target database platforms. Select one or more values, or choose 'all'. At least one platform is required.",
                    array = @ArraySchema(schema = @Schema(
                            type = "string",
                            allowableValues = {"all", "oracle", "postgresql", "db2zos", "db2luw", "sqlserver", "mysql", "mariadb"})))
            @RequestParam(value = "platform", required = false) List<String> platforms,
            @Parameter(
                    description = AUDIT_FIELDS_DESCRIPTION,
                    schema = @Schema(type = "string", defaultValue = "source", allowableValues = {
                            "source", "created-updated", "created-last-modified", "none"}))
            @RequestParam(value = "auditFields", required = false) String auditFields,
            @Parameter(
                    description = TIMESTAMP_SEMANTICS_DESCRIPTION,
                    schema = @Schema(type = "string", defaultValue = "source", allowableValues = {
                            "source", "without-time-zone", "with-time-zone", "with-local-time-zone"}))
            @RequestParam(value = "timestampSemantics", required = false) String timestampSemantics,
            @Parameter(hidden = true)
            @RequestParam(value = "includeAuditFields", required = false) Boolean includeAuditFields,
            @Parameter(hidden = true)
            @RequestParam(value = "auditProfile", required = false) String auditProfile)
            throws IOException {
        RestPlatformSelection.requirePresent(platforms);
        AuditRequestOptions audit = resolveAuditOptions(auditFields, includeAuditFields, auditProfile);
        return zip(service.generateFromWord(
                        file, audit.includeAuditFields(), audit.auditProfile(), platforms,
                        TimestampSemantics.parse(timestampSemantics)),
                archiveNamingPolicy.archiveFileName(ArtifactNamingPolicy.ArchiveKind.WORD));
    }

    @PostMapping(value = "/legacy-word", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "application/zip")
    @Operation(summary = "Generate from one legacy Word table specification")
    public ResponseEntity<byte[]> legacyWord(
            @RequestPart("file") MultipartFile file,
            @Parameter(description = "Schema name used for this legacy Word import.")
            @RequestParam("schema") String schema,
            @Parameter(
                    required = true,
                    description = "Target database platforms. Select one or more values, or choose 'all'. At least one platform is required.",
                    array = @ArraySchema(schema = @Schema(
                            type = "string",
                            allowableValues = {"all", "oracle", "postgresql", "db2zos", "db2luw", "sqlserver", "mysql", "mariadb"})))
            @RequestParam(value = "platform", required = false) List<String> platforms,
            @Parameter(
                    description = AUDIT_FIELDS_DESCRIPTION,
                    schema = @Schema(type = "string", defaultValue = "source", allowableValues = {
                            "source", "created-updated", "created-last-modified", "none"}))
            @RequestParam(value = "auditFields", required = false) String auditFields,
            @Parameter(
                    description = TIMESTAMP_SEMANTICS_DESCRIPTION,
                    schema = @Schema(type = "string", defaultValue = "source", allowableValues = {
                            "source", "without-time-zone", "with-time-zone", "with-local-time-zone"}))
            @RequestParam(value = "timestampSemantics", required = false) String timestampSemantics,
            @Parameter(hidden = true)
            @RequestParam(value = "includeAuditFields", required = false) Boolean includeAuditFields,
            @Parameter(hidden = true)
            @RequestParam(value = "auditProfile", required = false) String auditProfile)
            throws IOException {
        RestPlatformSelection.requirePresent(platforms);
        AuditRequestOptions audit = resolveAuditOptions(auditFields, includeAuditFields, auditProfile);
        return zip(service.generateFromLegacyWord(
                        file, schema, audit.includeAuditFields(), audit.auditProfile(), platforms,
                        TimestampSemantics.parse(timestampSemantics)),
                archiveNamingPolicy.archiveFileName(ArtifactNamingPolicy.ArchiveKind.LEGACY_WORD));
    }

    @PostMapping(value = "/legacy-word/zip", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "application/zip")
    @Operation(
            summary = "Generate from a ZIP containing legacy Word table specifications",
            description = "Processes each .doc or .docx file independently with the Legacy Word parser. "
                    + "One failed document does not stop the remaining documents. The schema parameter is required "
                    + "and is applied to every legacy document in the ZIP.")
    public ResponseEntity<byte[]> legacyWordZip(
            @RequestPart("file") MultipartFile file,
            @Parameter(description = "Schema name applied to every legacy Word document in this ZIP batch.")
            @RequestParam("schema") String schema,
            @Parameter(
                    required = true,
                    description = "Target database platforms. Select one or more values, or choose 'all'. At least one platform is required.",
                    array = @ArraySchema(schema = @Schema(
                            type = "string",
                            allowableValues = {"all", "oracle", "postgresql", "db2zos", "db2luw", "sqlserver", "mysql", "mariadb"})))
            @RequestParam(value = "platform", required = false) List<String> platforms,
            @Parameter(
                    description = AUDIT_FIELDS_DESCRIPTION,
                    schema = @Schema(type = "string", defaultValue = "source", allowableValues = {
                            "source", "created-updated", "created-last-modified", "none"}))
            @RequestParam(value = "auditFields", required = false) String auditFields,
            @Parameter(
                    description = TIMESTAMP_SEMANTICS_DESCRIPTION,
                    schema = @Schema(type = "string", defaultValue = "source", allowableValues = {
                            "source", "without-time-zone", "with-time-zone", "with-local-time-zone"}))
            @RequestParam(value = "timestampSemantics", required = false) String timestampSemantics,
            @Parameter(hidden = true)
            @RequestParam(value = "includeAuditFields", required = false) Boolean includeAuditFields,
            @Parameter(hidden = true)
            @RequestParam(value = "auditProfile", required = false) String auditProfile)
            throws IOException {
        RestPlatformSelection.requirePresent(platforms);
        AuditRequestOptions audit = resolveAuditOptions(auditFields, includeAuditFields, auditProfile);
        return zip(service.generateFromLegacyWordZip(
                        file, schema, audit.includeAuditFields(), audit.auditProfile(), platforms,
                        TimestampSemantics.parse(timestampSemantics)),
                archiveNamingPolicy.archiveFileName(ArtifactNamingPolicy.ArchiveKind.LEGACY_WORD_BATCH));
    }

    @PostMapping(value = "/zip", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "application/zip")
    @Operation(summary = "Generate from a ZIP containing Word specifications")
    public ResponseEntity<byte[]> zip(
            @RequestPart("file") MultipartFile file,
            @Parameter(
                    required = true,
                    description = "Target database platforms. Select one or more values, or choose 'all'. At least one platform is required.",
                    array = @ArraySchema(schema = @Schema(
                            type = "string",
                            allowableValues = {"all", "oracle", "postgresql", "db2zos", "db2luw", "sqlserver", "mysql", "mariadb"})))
            @RequestParam(value = "platform", required = false) List<String> platforms,
            @Parameter(
                    description = AUDIT_FIELDS_DESCRIPTION,
                    schema = @Schema(type = "string", defaultValue = "source", allowableValues = {
                            "source", "created-updated", "created-last-modified", "none"}))
            @RequestParam(value = "auditFields", required = false) String auditFields,
            @Parameter(
                    description = TIMESTAMP_SEMANTICS_DESCRIPTION,
                    schema = @Schema(type = "string", defaultValue = "source", allowableValues = {
                            "source", "without-time-zone", "with-time-zone", "with-local-time-zone"}))
            @RequestParam(value = "timestampSemantics", required = false) String timestampSemantics,
            @Parameter(hidden = true)
            @RequestParam(value = "includeAuditFields", required = false) Boolean includeAuditFields,
            @Parameter(hidden = true)
            @RequestParam(value = "auditProfile", required = false) String auditProfile)
            throws IOException {
        RestPlatformSelection.requirePresent(platforms);
        AuditRequestOptions audit = resolveAuditOptions(auditFields, includeAuditFields, auditProfile);
        return zip(service.generateFromZip(
                        file, audit.includeAuditFields(), audit.auditProfile(), platforms,
                        TimestampSemantics.parse(timestampSemantics)),
                archiveNamingPolicy.archiveFileName(ArtifactNamingPolicy.ArchiveKind.BATCH));
    }

    @PostMapping(value = "/ea-xml", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "application/zip")
    @Operation(summary = "Generate from Enterprise Architect XML/XMI")
    public ResponseEntity<byte[]> eaXml(
            @RequestPart("file") MultipartFile file,
            @Parameter(description = "Optional schema override. Leave empty to keep schema names from the EA/XMI file. If provided, all imported tables are placed in this schema.")
            @RequestParam(value = "schema", required = false) String schema,
            @Parameter(
                    required = true,
                    description = "Target database platforms. Select one or more values, or choose 'all'. At least one platform is required.",
                    array = @ArraySchema(schema = @Schema(
                            type = "string",
                            allowableValues = {"all", "oracle", "postgresql", "db2zos", "db2luw", "sqlserver", "mysql", "mariadb"})))
            @RequestParam(value = "platform", required = false) List<String> platforms,
            @Parameter(
                    description = AUDIT_FIELDS_DESCRIPTION,
                    schema = @Schema(type = "string", defaultValue = "source", allowableValues = {
                            "source", "created-updated", "created-last-modified", "none"}))
            @RequestParam(value = "auditFields", required = false) String auditFields,
            @Parameter(
                    description = TIMESTAMP_SEMANTICS_DESCRIPTION,
                    schema = @Schema(type = "string", defaultValue = "source", allowableValues = {
                            "source", "without-time-zone", "with-time-zone", "with-local-time-zone"}))
            @RequestParam(value = "timestampSemantics", required = false) String timestampSemantics,
            @Parameter(hidden = true)
            @RequestParam(value = "includeAuditFields", required = false) Boolean includeAuditFields,
            @Parameter(hidden = true)
            @RequestParam(value = "auditProfile", required = false) String auditProfile,
            @Parameter(description = "Automatically generate values for primary-key columns. Enable only when PK values should be generated by the database. Leave disabled when the application or source model supplies the PK value. Default: disabled.")
            @RequestParam(value = "primaryKeyAsIdentity", required = false) Boolean primaryKeyAsIdentity)
            throws IOException {
        RestPlatformSelection.requirePresent(platforms);
        AuditRequestOptions audit = resolveAuditOptions(auditFields, includeAuditFields, auditProfile);
        return zip(service.generateFromEaXml(
                        file, schema, audit.includeAuditFields(), audit.auditProfile(), platforms,
                        primaryKeyAsIdentity, TimestampSemantics.parse(timestampSemantics)),
                archiveNamingPolicy.archiveFileName(ArtifactNamingPolicy.ArchiveKind.EA));
    }

    private static AuditRequestOptions resolveAuditOptions(
            String auditFields,
            Boolean legacyIncludeAuditFields,
            String legacyAuditProfile) {
        boolean hasNewSelection = auditFields != null && !auditFields.isBlank();
        boolean hasLegacySelection = legacyIncludeAuditFields != null
                || (legacyAuditProfile != null && !legacyAuditProfile.isBlank());
        if (hasNewSelection && hasLegacySelection) {
            throw new IllegalArgumentException(
                    "AUDIT_OPTIONS_CONFLICT: use auditFields or the legacy includeAuditFields/auditProfile parameters, not both");
        }
        if (!hasNewSelection) {
            if (hasLegacySelection) {
                return new AuditRequestOptions(legacyIncludeAuditFields, legacyAuditProfile);
            }
            return new AuditRequestOptions(false, null);
        }

        String normalized = auditFields.trim().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        return switch (normalized) {
            case "source", "none" -> new AuditRequestOptions(false, null);
            case "created-updated" -> new AuditRequestOptions(true, "CREATED_UPDATED");
            case "created-last-modified" -> new AuditRequestOptions(true, "CREATED_LAST_MODIFIED");
            default -> throw new IllegalArgumentException(
                    "Unsupported auditFields: " + auditFields
                            + ". Expected source, created-updated, created-last-modified, or none");
        };
    }

    private record AuditRequestOptions(Boolean includeAuditFields, String auditProfile) {
    }

    private static ResponseEntity<byte[]> zip(byte[] content, String fileName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.setContentDisposition(ContentDisposition.attachment().filename(fileName).build());
        headers.setContentLength(content.length);
        return ResponseEntity.ok().headers(headers).body(content);
    }

}
