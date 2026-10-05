package com.behsazan.schemaforge.specification.parser.legacy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyRtl9ColumnLayoutResolverTest {

    @Test
    void detectsAndMapsLegacyPersianRtlNineColumnGrid() {
        List<List<String>> table = List.of(
                List.of("ردیف", "نام صفت خاصه", "نام فیلد", "نوع", "طول", "کلید", "اجباری", "پیش فرض", "توضیحات"),
                List.of("", "شماره قرارداد", "CNTRCTID", "D", "18", "X", "X", "", ""),
                List.of("", "کد نوع تسهیلات", "PRPSLTYPCD", "S", "", "", "X", "", ""),
                List.of("", "کاربر", "USRCD", "C", "8", "", "", "", ""),
                List.of("", "شرح", "DESCRIPTION", "VCH", "150", "", "", "0", ""),
                List.of("", "وضعیت دریافت بدهی سررسید", "MTRTYDBTAMNTRCVSTS", "", "", "", "X", "0", "")
        );

        ColumnLayoutResolver.Layout layout = ColumnLayoutResolver.resolve(table);
        assertEquals(ColumnLayoutResolver.Kind.LEGACY_RTL_9, layout.kind());

        ColumnLayoutResolver.ResolvedColumn id = layout.resolve(table.get(1));
        assertEquals("شماره قرارداد", id.attributeName());
        assertEquals("CNTRCTID", id.fieldName());
        assertEquals("D", id.type());
        assertEquals("18", id.length());
        assertEquals("YES", id.mandatory());

        ColumnLayoutResolver.ResolvedColumn small = layout.resolve(table.get(2));
        assertEquals("SMALLINT", small.type());

        ColumnLayoutResolver.ResolvedColumn fixed = layout.resolve(table.get(3));
        assertEquals("CHAR", fixed.type());
        assertEquals("8", fixed.length());

        ColumnLayoutResolver.ResolvedColumn varchar = layout.resolve(table.get(4));
        assertEquals("VARCHAR", varchar.type());
        assertEquals("150", varchar.length());
        assertTrue(varchar.referenceOrDefault().contains("default: 0"));

        assertTrue(layout.isDefinitionRow(table.get(5)));
        ColumnLayoutResolver.ResolvedColumn missingType = layout.resolve(table.get(5));
        assertEquals("MTRTYDBTAMNTRCVSTS", missingType.fieldName());
        assertEquals("", missingType.type());
        assertEquals("YES", missingType.mandatory());
    }
}
