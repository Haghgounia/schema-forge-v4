package com.behsazan.schemaforge.api;

import com.behsazan.schemaforge.application.DatabasePlatform;

import java.util.List;

/** REST-specific fail-closed parsing for database platform selections. */
final class RestPlatformSelection {
    static final String REQUIRED_MESSAGE = "At least one database platform must be selected.";

    private RestPlatformSelection() {
    }

    static List<DatabasePlatform> require(List<String> values) {
        if (!hasSelection(values)) {
            throw new IllegalArgumentException(REQUIRED_MESSAGE);
        }
        return DatabasePlatform.parseSelection(values).stream().toList();
    }

    static void requirePresent(List<String> values) {
        require(values);
    }

    private static boolean hasSelection(List<String> values) {
        if (values == null || values.isEmpty()) {
            return false;
        }
        for (String value : values) {
            if (value == null) {
                continue;
            }
            for (String token : value.split(",")) {
                if (!token.isBlank()) {
                    return true;
                }
            }
        }
        return false;
    }
}
