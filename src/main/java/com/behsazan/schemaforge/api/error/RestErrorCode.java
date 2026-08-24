package com.behsazan.schemaforge.api.error;

/** Stable machine-readable error codes for the SchemaForge REST contract. */
public enum RestErrorCode {
    INVALID_REQUEST,
    INPUT_IO_ERROR,
    MISSING_PART,
    MISSING_PARAMETER,
    MALFORMED_REQUEST,
    INVALID_PARAMETER,
    UNSUPPORTED_MEDIA_TYPE,
    NOT_ACCEPTABLE,
    METHOD_NOT_ALLOWED,
    NOT_FOUND,
    PAYLOAD_TOO_LARGE,
    SERVICE_UNAVAILABLE,
    INTERNAL_ERROR
}
