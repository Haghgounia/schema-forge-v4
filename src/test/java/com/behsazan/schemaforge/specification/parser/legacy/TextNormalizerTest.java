package com.behsazan.schemaforge.specification.parser.legacy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Regression tests for Unicode, Persian-letter and Word-control normalization.
 *
 * <p>The cases protect technical-name recovery, Tatweel and cell-marker removal, and consistent
 * canonicalization of Arabic Yeh/Kaf variants in both cell and block text.</p>
 */
class TextNormalizerTest {
    @Test
    void removesWhitespaceInsideTechnicalName() {
        assertEquals("ActivityLicNum", TextNormalizer.normalizeTechnicalName("Activity Lic Num"));
    }

    @Test
    void recognizesTechnicalName() {
        assertTrue(TextNormalizer.isTechnicalFieldName("RegcityCode"));
    }

    @Test
    void removesPersianTatweelFromLabels() {
        assertEquals("نام جدول : DtOrder", TextNormalizer.cleanCell("نام جـدول : DtOrder"));
    }

    @Test
    void cleansWordCellMarkers() {
        assertEquals("FLOrgName", TextNormalizer.cleanCell("FLOrgName\r\u0007"));
    }

    @Test
    void normalizesArabicYehAndKafInCellsAndBlocks() {
        assertEquals("نام موجودیت: مشتریان", TextNormalizer.cleanCell("نام موجوديت: مشتريان"));
        assertEquals("کد مشتری", TextNormalizer.cleanBlock("كد مشتري"));
    }
}
