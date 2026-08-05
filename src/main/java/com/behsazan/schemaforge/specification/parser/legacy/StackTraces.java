package com.behsazan.schemaforge.specification.parser.legacy;

import java.io.PrintWriter;
import java.io.StringWriter;

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
