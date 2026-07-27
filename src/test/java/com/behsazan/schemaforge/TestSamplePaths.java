package com.behsazan.schemaforge;

import java.nio.file.Path;

/**
 * Central paths for repository-level sample files used by integration and regression tests.
 *
 * <p>The sample files are documentation artifacts under {@code docs/samples}; keeping all
 * test references here prevents widespread test failures when sample directories move.</p>
 *
 * @since 4.1
 */
public final class TestSamplePaths {

    public static final Path WORD_DIRECTORY = Path.of("docs", "samples", "word");
    public static final Path EA_DIRECTORY = Path.of("docs", "samples", "ea");

    public static final Path PROVINCES_V1_1 = WORD_DIRECTORY.resolve("MCB.BIM.TBL.PROVINCES.V1.1.docx");
    public static final Path PROVINCES_V1_2 = WORD_DIRECTORY.resolve("MCB.BIM.TBL.PROVINCES.V1.2.docx");
    public static final Path CONTINENTS_V1_0 = WORD_DIRECTORY.resolve("MCB.BIM.TBL.CONTINENTS.V1.0.docx");
    public static final Path EA_SAMPLE = EA_DIRECTORY.resolve("ea-sample.xml");

    private TestSamplePaths() {
    }
}
