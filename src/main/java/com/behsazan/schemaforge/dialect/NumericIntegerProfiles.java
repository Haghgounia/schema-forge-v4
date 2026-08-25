package com.behsazan.schemaforge.dialect;

/** Shared lossless integer-capacity profiles used by type mapping and metadata comparison. */
public final class NumericIntegerProfiles {
    public static final NumericTypeOptimizationService.NumericIntegerProfile POSTGRESQL =
            new NumericTypeOptimizationService.NumericIntegerProfile(
                    "SMALLINT", 4, "INTEGER", 9, "BIGINT", 18);

    public static final NumericTypeOptimizationService.NumericIntegerProfile DB2_ZOS =
            new NumericTypeOptimizationService.NumericIntegerProfile(
                    "SMALLINT", 4, "INTEGER", 9, "BIGINT", 18);

    public static final NumericTypeOptimizationService.NumericIntegerProfile SQL_SERVER =
            new NumericTypeOptimizationService.NumericIntegerProfile(
                    "SMALLINT", 4, "INT", 9, "BIGINT", 18);

    public static final NumericTypeOptimizationService.NumericIntegerProfile MYSQL =
            new NumericTypeOptimizationService.NumericIntegerProfile(
                    "SMALLINT", 4, "INT", 9, "BIGINT", 18);

    private NumericIntegerProfiles() {
    }
}
