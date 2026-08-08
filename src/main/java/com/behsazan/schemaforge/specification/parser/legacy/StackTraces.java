package com.behsazan.schemaforge.specification.parser.legacy;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Converts a {@link Throwable} stack trace to text for deterministic extraction reports.
 *
 * <p>This utility is used to persist diagnostic evidence in batch reports. Callers remain
 * responsible for ensuring that exception messages do not contain credentials or other
 * sensitive runtime configuration.</p>
 */
final class StackTraces {
    private StackTraces() {
    }

    static String toString(Throwable throwable) {
        StringWriter buffer = new StringWriter(2048);
        try (PrintWriter writer = new PrintWriter(buffer)) {
            throwable.printStackTrace(writer);
        }
        return buffer.toString();
    }
}
