package com.behsazan.schemaforge.performance;

import java.util.Locale;

/**
 * Opt-in, read-only timing diagnostics for the Enterprise Architect import path.
 * No timings are added to canonical JSON, artifact manifests, or database state.
 */
public final class EaPerformanceDiagnostics {
    private static final System.Logger LOGGER = System.getLogger(EaPerformanceDiagnostics.class.getName());

    private EaPerformanceDiagnostics() { }

    public static boolean enabled() {
        return Boolean.getBoolean("schemaforge.ea.perf.enabled")
                || "true".equalsIgnoreCase(System.getenv("SCHEMAFORGE_EA_PERF_ENABLED"));
    }

    public static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    /** Format one flat log entry, suitable for automated collection and comparisons. */
    public static void log(String section, String details) {
        if (!enabled()) return;
        LOGGER.log(System.Logger.Level.INFO, "EA_PERF section=" + section + " " + details);
    }

    /** Make untrusted upload filenames single-line and bounded before logging. */
    public static String safeLabel(String raw) {
        if (raw == null || raw.isBlank()) return "unknown";
        String clean = raw.replaceAll("[\\r\\n\\t\\s]+", "_")
                .replaceAll("[^A-Za-z0-9._-]", "_");
        return clean.length() > 120 ? clean.substring(0, 120) : clean;
    }
}
