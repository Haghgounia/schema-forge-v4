package com.behsazan.schemaforge.application;

/** Signals that an explicitly required external/infrastructure-backed service is not available. */
public class ServiceUnavailableException extends IllegalStateException {
    public ServiceUnavailableException(String message) {
        super(message);
    }
}
