package com.behsazan.schemaforge.api.error;

import com.behsazan.schemaforge.application.ServiceUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/** Central exception-to-HTTP mapping for the versioned SchemaForge REST error contract. */
@RestControllerAdvice
public class SchemaForgeRestExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(SchemaForgeRestExceptionHandler.class);
    private final Clock clock;

    public SchemaForgeRestExceptionHandler() {
        this(Clock.systemUTC());
    }

    SchemaForgeRestExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<RestErrorResponse> invalidRequest(
            IllegalArgumentException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, RestErrorCode.INVALID_REQUEST,
                message(exception, "Invalid request"), request, Map.of());
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<RestErrorResponse> inputIoError(IOException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, RestErrorCode.INPUT_IO_ERROR,
                message(exception, "Unable to read request input"), request, Map.of());
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<RestErrorResponse> serviceUnavailable(
            ServiceUnavailableException exception, HttpServletRequest request) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, RestErrorCode.SERVICE_UNAVAILABLE,
                message(exception, "Required service is unavailable"), request, Map.of());
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<RestErrorResponse> missingPart(
            MissingServletRequestPartException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, RestErrorCode.MISSING_PART,
                "Required multipart part is missing: " + exception.getRequestPartName(), request,
                Map.of("part", exception.getRequestPartName()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<RestErrorResponse> missingParameter(
            MissingServletRequestParameterException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, RestErrorCode.MISSING_PARAMETER,
                "Required request parameter is missing: " + exception.getParameterName(), request,
                Map.of("parameter", exception.getParameterName()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<RestErrorResponse> malformedRequest(
            HttpMessageNotReadableException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, RestErrorCode.MALFORMED_REQUEST,
                "Request body is malformed or unreadable", request, Map.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<RestErrorResponse> invalidParameter(
            MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, RestErrorCode.INVALID_PARAMETER,
                "Invalid value for request parameter: " + exception.getName(), request,
                Map.of("parameter", exception.getName()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<RestErrorResponse> invalidBody(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, RestErrorCode.INVALID_REQUEST,
                "Request validation failed", request,
                Map.of("errorCount", exception.getBindingResult().getErrorCount()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<RestErrorResponse> unsupportedMediaType(
            HttpMediaTypeNotSupportedException exception, HttpServletRequest request) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, RestErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "Unsupported request media type", request, Map.of());
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<RestErrorResponse> notAcceptable(
            HttpMediaTypeNotAcceptableException exception, HttpServletRequest request) {
        return build(HttpStatus.NOT_ACCEPTABLE, RestErrorCode.NOT_ACCEPTABLE,
                "Requested response media type is not available", request, Map.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<RestErrorResponse> methodNotAllowed(
            HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, RestErrorCode.METHOD_NOT_ALLOWED,
                "HTTP method is not supported for this endpoint", request,
                Map.of("method", exception.getMethod()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<RestErrorResponse> notFound(
            NoResourceFoundException exception, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, RestErrorCode.NOT_FOUND,
                "Requested resource was not found", request, Map.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<RestErrorResponse> payloadTooLarge(
            MaxUploadSizeExceededException exception, HttpServletRequest request) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, RestErrorCode.PAYLOAD_TOO_LARGE,
                "Uploaded payload exceeds the configured size limit", request, Map.of());
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<RestErrorResponse> malformedMultipart(
            MultipartException exception, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, RestErrorCode.MALFORMED_REQUEST,
                "Multipart request is malformed or unreadable", request, Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<RestErrorResponse> internalError(Exception exception, HttpServletRequest request) {
        String requestId = SchemaForgeRequestCorrelationFilter.currentOrCreate(request);
        log.error("Unhandled REST error requestId={} path={}", requestId, SchemaForgeRequestCorrelationFilter.applicationPath(request), exception);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, RestErrorCode.INTERNAL_ERROR,
                "Unexpected server error", request, Map.of());
    }

    private ResponseEntity<RestErrorResponse> build(
            HttpStatus status,
            RestErrorCode code,
            String message,
            HttpServletRequest request,
            Map<String, Object> details) {
        String requestId = SchemaForgeRequestCorrelationFilter.currentOrCreate(request);
        request.removeAttribute(HandlerMapping.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE);
        RestErrorResponse body = new RestErrorResponse(
                RestErrorResponse.CONTRACT,
                code,
                status.value(),
                message,
                SchemaForgeRequestCorrelationFilter.applicationPath(request),
                requestId,
                Instant.now(clock),
                details);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(SchemaForgeRequestCorrelationFilter.HEADER_NAME, requestId);
        return ResponseEntity.status(status).headers(headers).body(body);
    }

    private static String message(Exception exception, String fallback) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? fallback
                : exception.getMessage();
    }
}
