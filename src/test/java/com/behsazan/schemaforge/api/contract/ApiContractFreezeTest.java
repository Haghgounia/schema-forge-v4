package com.behsazan.schemaforge.api.contract;

import com.behsazan.schemaforge.api.MermaidDiagramController;
import com.behsazan.schemaforge.api.OracleCrudController;
import com.behsazan.schemaforge.api.OracleCrudRequest;
import com.behsazan.schemaforge.api.SchemaConformanceController;
import com.behsazan.schemaforge.api.SchemaForgeController;
import com.behsazan.schemaforge.api.SqlServerCrudController;
import com.behsazan.schemaforge.api.SqlServerCrudRequest;
import com.behsazan.schemaforge.api.error.RestErrorCode;
import com.behsazan.schemaforge.api.error.RestErrorResponse;
import com.behsazan.schemaforge.api.error.SchemaForgeRequestCorrelationFilter;
import com.behsazan.schemaforge.conformance.SchemaConformanceReport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 16 API contract freeze.
 *
 * <p>This test intentionally duplicates the externally visible HTTP surface. Any future
 * route, media-type, parameter/default, versioned payload contract, correlation header,
 * or machine-readable error-code change must be treated as an explicit API contract change.</p>
 */
class ApiContractFreezeTest {

    @Test
    void generationApiSurfaceIsFrozen() throws Exception {
        assertBasePath(SchemaForgeController.class, "/api/v1/generate");

        Method word = SchemaForgeController.class.getMethod(
                "word", MultipartFile.class, Boolean.class, String.class);
        assertPost(word, "/word", MediaType.MULTIPART_FORM_DATA_VALUE, "application/zip");
        assertPart(word, 0, "file");
        assertParam(word, 1, "includeAuditFields", false, null);
        assertParam(word, 2, "auditProfile", false, "AUTO");

        Method legacy = SchemaForgeController.class.getMethod(
                "legacyWord", MultipartFile.class, String.class, Boolean.class, String.class);
        assertPost(legacy, "/legacy-word", MediaType.MULTIPART_FORM_DATA_VALUE, "application/zip");
        assertPart(legacy, 0, "file");
        assertParam(legacy, 1, "schema", true, null);
        assertParam(legacy, 2, "includeAuditFields", false, null);
        assertParam(legacy, 3, "auditProfile", false, "AUTO");

        Method zip = SchemaForgeController.class.getMethod(
                "zip", MultipartFile.class, Boolean.class, String.class);
        assertPost(zip, "/zip", MediaType.MULTIPART_FORM_DATA_VALUE, "application/zip");
        assertPart(zip, 0, "file");
        assertParam(zip, 1, "includeAuditFields", false, null);
        assertParam(zip, 2, "auditProfile", false, "AUTO");

        Method ea = SchemaForgeController.class.getMethod(
                "eaXml", MultipartFile.class, String.class, List.class, Boolean.class, String.class);
        assertPost(ea, "/ea-xml", MediaType.MULTIPART_FORM_DATA_VALUE, "application/zip");
        assertPart(ea, 0, "file");
        assertParam(ea, 1, "schema", false, null);
        assertParam(ea, 2, "platform", false, null);
        assertParam(ea, 3, "includeAuditFields", false, null);
        assertParam(ea, 4, "auditProfile", false, "AUTO");
    }

    @Test
    void conformanceApiSurfaceAndReportVersionAreFrozen() throws Exception {
        assertBasePath(SchemaConformanceController.class, "/api/v1/conformance");

        Method table = SchemaConformanceController.class.getMethod(
                "table", String.class, String.class, String.class);
        assertGet(table, "/table", MediaType.APPLICATION_JSON_VALUE);
        assertParam(table, 0, "platform", true, null);
        assertParam(table, 1, "schema", true, null);
        assertParam(table, 2, "table", true, null);

        Method schema = SchemaConformanceController.class.getMethod(
                "schema", String.class, String.class);
        assertGet(schema, "/schema", MediaType.APPLICATION_JSON_VALUE);
        assertParam(schema, 0, "platform", true, null);
        assertParam(schema, 1, "schema", true, null);

        assertEquals("schemaforge-schema-conformance/v3", SchemaConformanceReport.CONTRACT);
    }

    @Test
    void crudApiSurfaceIsFrozen() throws Exception {
        assertBasePath(OracleCrudController.class, "/api/v1/generate/oracle");
        Method oracle = OracleCrudController.class.getMethod("generate", OracleCrudRequest.class);
        assertPost(oracle, "/crud", MediaType.APPLICATION_JSON_VALUE, "application/sql");
        assertRequestBody(oracle, 0);

        assertBasePath(SqlServerCrudController.class, "/api/v1/generate/sqlserver");
        Method sqlServer = SqlServerCrudController.class.getMethod("generate", SqlServerCrudRequest.class);
        assertPost(sqlServer, "/crud", MediaType.APPLICATION_JSON_VALUE, "application/sql");
        assertRequestBody(sqlServer, 0);
    }

    @Test
    void mermaidApiSurfaceAndDefaultsAreFrozen() throws Exception {
        assertBasePath(MermaidDiagramController.class, "/api/v1/diagram/mermaid");
        Method method = MermaidDiagramController.class.getMethod(
                "canonicalJson",
                MultipartFile.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                int.class,
                boolean.class,
                boolean.class,
                boolean.class,
                boolean.class,
                boolean.class);

        assertPost(method, "/canonical-json", MediaType.MULTIPART_FORM_DATA_VALUE, MediaType.TEXT_PLAIN_VALUE);
        assertPart(method, 0, "file");
        assertParam(method, 1, "type", true, "er");
        assertParam(method, 2, "scope", true, "all");
        assertParam(method, 3, "schema", false, null);
        assertParam(method, 4, "root", false, null);
        assertParam(method, 5, "selected", false, null);
        assertParam(method, 6, "depth", true, "1");
        assertParam(method, 7, "includeColumns", true, "true");
        assertParam(method, 8, "includeDataTypes", true, "true");
        assertParam(method, 9, "includePrimaryKeys", true, "true");
        assertParam(method, 10, "includeForeignKeys", true, "true");
        assertParam(method, 11, "includeLogicalForeignKeys", true, "false");
    }

    @Test
    void restErrorContractAndCorrelationHeaderAreFrozen() {
        assertEquals("schemaforge-rest-error/v1", RestErrorResponse.CONTRACT);
        assertEquals("X-SchemaForge-Request-Id", SchemaForgeRequestCorrelationFilter.HEADER_NAME);

        Set<String> expected = Set.of(
                "INVALID_REQUEST",
                "INPUT_IO_ERROR",
                "MISSING_PART",
                "MISSING_PARAMETER",
                "MALFORMED_REQUEST",
                "INVALID_PARAMETER",
                "UNSUPPORTED_MEDIA_TYPE",
                "NOT_ACCEPTABLE",
                "METHOD_NOT_ALLOWED",
                "NOT_FOUND",
                "PAYLOAD_TOO_LARGE",
                "SERVICE_UNAVAILABLE",
                "INTERNAL_ERROR");
        Set<String> actual = Arrays.stream(RestErrorCode.values())
                .map(Enum::name)
                .collect(Collectors.toSet());
        assertEquals(expected, actual);
    }

    private static void assertBasePath(Class<?> controller, String expected) {
        RequestMapping mapping = controller.getAnnotation(RequestMapping.class);
        assertNotNull(mapping, controller.getSimpleName());
        assertEquals(List.of(expected), List.of(mapping.value()), controller.getSimpleName());
    }

    private static void assertPost(Method method, String path, String consumes, String produces) {
        PostMapping mapping = method.getAnnotation(PostMapping.class);
        assertNotNull(mapping, method.toString());
        assertEquals(List.of(path), List.of(mapping.value()), method.toString());
        assertEquals(List.of(consumes), List.of(mapping.consumes()), method.toString());
        assertEquals(List.of(produces), List.of(mapping.produces()), method.toString());
    }

    private static void assertGet(Method method, String path, String produces) {
        GetMapping mapping = method.getAnnotation(GetMapping.class);
        assertNotNull(mapping, method.toString());
        assertEquals(List.of(path), List.of(mapping.value()), method.toString());
        assertEquals(List.of(produces), List.of(mapping.produces()), method.toString());
        assertEquals(0, mapping.consumes().length, method.toString());
    }

    private static void assertPart(Method method, int parameterIndex, String name) {
        RequestPart part = annotation(method.getParameters()[parameterIndex], RequestPart.class);
        assertEquals(name, part.value());
        assertTrue(part.required());
    }

    private static void assertParam(Method method, int parameterIndex, String name, boolean required, String defaultValue) {
        RequestParam parameter = annotation(method.getParameters()[parameterIndex], RequestParam.class);
        assertEquals(name, parameter.value(), method.toString());
        assertEquals(required, parameter.required(), method.toString());
        if (defaultValue != null) {
            assertEquals(defaultValue, parameter.defaultValue(), method.toString());
        } else {
            assertEquals(org.springframework.web.bind.annotation.ValueConstants.DEFAULT_NONE,
                    parameter.defaultValue(), method.toString());
        }
    }

    private static void assertRequestBody(Method method, int parameterIndex) {
        RequestBody body = annotation(method.getParameters()[parameterIndex], RequestBody.class);
        assertTrue(body.required());
    }

    private static <A extends Annotation> A annotation(Parameter parameter, Class<A> type) {
        A annotation = parameter.getAnnotation(type);
        assertNotNull(annotation, () -> "Missing @" + type.getSimpleName() + " on " + parameter);
        return annotation;
    }
}
