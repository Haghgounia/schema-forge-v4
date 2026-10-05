package com.behsazan.schemaforge.domain.model;

/** Shared keys for canonical column physical/recovery metadata. */
public final class ColumnPhysicalOptionKeys {
    /** Marks a recovered character column whose source datatype is known but length is absent. */
    public static final String RECOVERY_UNRESOLVED_CHARACTER_LENGTH =
            "schemaforge.recovery.unresolvedCharacterLength";

    private ColumnPhysicalOptionKeys() {
    }
}
