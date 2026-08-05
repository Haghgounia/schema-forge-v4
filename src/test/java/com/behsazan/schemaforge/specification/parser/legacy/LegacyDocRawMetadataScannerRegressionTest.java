package com.behsazan.schemaforge.specification.parser.legacy;

import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyDocRawMetadataScannerRegressionTest {
    @Test
    void preservesPersianEntityStartingWithTarikhcheh() throws Exception {
        String metadata = scan("13970705_KrmzdSubD.sd.spc.TB.CTPIncomeParamActivityLog.doc");
        assertTrue(metadata.contains("نام جدول: CTPIncomeParamActivityLog"));
        assertTrue(metadata.contains("تاریخچه تغییرات تخصیص پارامترهای کارمزد به فعالیتها"));
    }

    @Test
    void returnsMatchingPairFromMultiPageDocument() throws Exception {
        String metadata = scan("Spec_TableStructure_BudgetAccD.sd.spc.tb.CTMHIBaseFund.doc");
        assertTrue(metadata.contains("نام جدول: CTMHIBaseFund"));
        assertTrue(metadata.contains("تاریخچه تغییرات سرمایه پایه"));
    }

    private String scan(String name) throws Exception {
        return LegacyDocRawMetadataScanner.extract(resource(name));
    }

    private Path resource(String name) throws URISyntaxException {
        return Path.of(getClass().getResource("/" + name).toURI());
    }
}
