package com.behsazan.schemaforge.migration;

import com.behsazan.schemaforge.domain.model.Table;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Generates monotonic Flyway versioned migration file names. */
public final class FlywayMigrationNamer {
    private static final DateTimeFormatter VERSION = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private final Clock clock;
    private final AtomicLong lastVersion = new AtomicLong();

    public FlywayMigrationNamer() {
        this(Clock.systemDefaultZone());
    }

    public FlywayMigrationNamer(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public String fileName(Table table) {
        Objects.requireNonNull(table, "table must not be null");
        long clockVersion = Long.parseLong(VERSION.format(LocalDateTime.now(clock)));
        long numericVersion = lastVersion.updateAndGet(previous -> Math.max(clockVersion, previous + 1));
        String version = String.format(Locale.ROOT, "%017d", numericVersion);
        String schema = table.qualifiedName().schemaName().map(identifier -> identifier.normalized() + "_").orElse("");
        String description = sanitize(schema + table.qualifiedName().name().normalized() + "_ALTER");
        return "V" + version + "__" + description + ".sql";
    }

    private static String sanitize(String value) {
        String normalized = value.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        return normalized.isBlank() ? "SCHEMAFORGE_ALTER" : normalized;
    }
}
