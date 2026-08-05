package com.behsazan.schemaforge.specification.parser.legacy;

import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinalMetadataAcceptanceRegressionTest {
    @Test
    void recoversStandaloneLegacyPersianTitleAfterRejectingFieldTail() throws Exception {
        ExtractionModels.FileResult result = extract(
                "13970705_KrmzdSubD.sd.spc.TB.CTPIncomeParamActivityLog.doc"
        );

        assertEquals("CTPIncomeParamActivityLog", result.metadata().tableName());
        assertEquals("تاریخچه تغییرات تخصیص پارامترهای کارمزد به فعالیتها", result.metadata().persianTableName());
        assertEquals("EXPLICIT_ENTITY_HEADER", result.metadata().persianTableNameSource());
        assertEquals("تاریخچه تغییرات تخصیص پارامترهای کارمزد به فعالیتها", result.metadata().entityName());
        assertFalse(result.metadata().persianTableName().contains("ChangeTime"));
    }

    @Test
    void keepsPersianTitleEmptyWhenExplicitEntityLabelHasNoValue() throws Exception {
        ExtractionModels.FileResult result = extract("Spec_BBANKINGI.sd.spc.tb.BBAction.doc");

        assertEquals("BBAction", result.metadata().tableName());
        assertEquals("", result.metadata().persianTableName());
        assertTrue(result.warnings().stream().anyMatch(warning ->
                "PERSIAN_TABLE_NAME_NOT_PRESENT_SOURCE".equals(warning.code())
        ));
        assertFalse(result.warnings().stream().anyMatch(warning ->
                "PERSIAN_TABLE_NAME_NOT_RELIABLE".equals(warning.code())
        ));
    }

    @Test
    void selectsMetadataPairThatMatchesTheFileTableToken() throws Exception {
        ExtractionModels.FileResult result = extract(
                "Spec_TableStructure_BudgetAccD.sd.spc.tb.CTMHIBaseFund.doc"
        );

        assertEquals("CTMHIBaseFund", result.metadata().tableName());
        assertEquals("تاریخچه تغییرات سرمایه پایه", result.metadata().persianTableName());
        assertEquals("EXPLICIT_ENTITY_HEADER", result.metadata().persianTableNameSource());
        assertFalse(result.metadata().persianTableName().contains("تلورانس"));
    }

    @Test
    void removesFieldNameTailFromPichakEntityAndClassifiesMissingTypeAsSourceAbsent()
            throws Exception {
        ExtractionModels.FileResult result = extract(
                "FileGroupProccess_Pichak_PichaksubsD.sd.spc.tb.CTMSFGPCHQ .doc"
        );

        assertEquals("CTMSFGPCHQ", result.metadata().tableName());
        assertEquals("فایلهای بارگذاری شده", result.metadata().persianTableName());
        assertFalse(result.metadata().persianTableName().contains("StatusTime"));
        assertTrue(result.warnings().stream().anyMatch(warning ->
                "FIELD_TYPE_NOT_PRESENT_IN_SOURCE".equals(warning.code())
                        && "RowNo".equalsIgnoreCase(warning.fieldName())
        ));
        assertFalse(result.warnings().stream().anyMatch(warning ->
                "FIELD_TYPE_UNRELIABLE".equals(warning.code())
                        && "RowNo".equalsIgnoreCase(warning.fieldName())
        ));
    }

    private ExtractionModels.FileResult extract(String resourceName) throws URISyntaxException {
        Path source = Path.of(getClass().getResource("/" + resourceName).toURI());
        ExtractionModels.FileResult result = new DocTableExtractor().extract(
                source.getParent(), source, 20L * 1024L * 1024L
        );
        assertNotEquals(ExtractionModels.Status.IGNORED, result.status());
        assertNotEquals(ExtractionModels.Status.FAILED, result.status());
        return result;
    }
}
