package com.behsazan.schemaforge.specification.parser.legacy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.ColumnDefinition;
import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.ExtractionWarning;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyRtl9DependencySectionResolverTest {

    @Test
    void attachesQualifiedDependencyEvidenceAndPreservesDefaultSupplement() {
        List<ColumnDefinition> columns = List.of(column(
                1, "REQCD", "default 0", List.of()));
        List<List<List<String>>> tables = List.of(List.of(
                List.of("ردیف", "نام فیلد", "فیلد خارجی"),
                List.of("1", "REQCD", "TASSHMA.CTFAVRGREQ.REQID")
        ));
        List<ExtractionWarning> warnings = new ArrayList<>();

        List<ColumnDefinition> resolved = LegacyRtl9DependencySectionResolver.apply(columns, tables, warnings);

        assertEquals(1, resolved.size());
        assertTrue(resolved.getFirst().keys().contains("FKD"));
        assertTrue(resolved.getFirst().referenceOrDefaultRaw().contains("reference: TASSHMA.CTFAVRGREQ.REQID"));
        assertTrue(resolved.getFirst().referenceOrDefaultRaw().contains("default 0"));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void normalizesWhitespaceAroundReferenceDots() {
        List<ColumnDefinition> columns = List.of(column(1, "TRNSCTNCD", "", List.of()));
        List<List<List<String>>> tables = List.of(List.of(
                List.of("ردیف", "نام فیلد", "فیلد خارجی"),
                List.of("1", "TRNSCTNCD", "TASSHMA.CTFTRNSCTN. TRNSCTNID")
        ));
        List<ExtractionWarning> warnings = new ArrayList<>();

        List<ColumnDefinition> resolved = LegacyRtl9DependencySectionResolver.apply(columns, tables, warnings);

        assertTrue(resolved.getFirst().referenceOrDefaultRaw()
                .contains("TASSHMA.CTFTRNSCTN.TRNSCTNID"));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void keepsBareOrDescriptiveForeignValuesFailClosed() {
        List<ColumnDefinition> columns = List.of(
                column(1, "CHQASSIGNID", "", List.of()),
                column(2, "INSURNCTYP", "", List.of())
        );
        List<List<List<String>>> tables = List.of(List.of(
                List.of("ردیف", "نام فیلد", "فیلد خارجی"),
                List.of("1", "CHQASSIGNID", "CHQASSIGNID"),
                List.of("2", "INSURNCTYP", "وثیقه/اموال = 0 سرمایه = 1")
        ));
        List<ExtractionWarning> warnings = new ArrayList<>();

        List<ColumnDefinition> resolved = LegacyRtl9DependencySectionResolver.apply(columns, tables, warnings);

        assertTrue(resolved.stream().noneMatch(c -> c.keys().contains("FKD")));
        assertTrue(warnings.stream().anyMatch(w -> "LEGACY_DEPENDENCY_REFERENCE_UNQUALIFIED".equals(w.code())));
        assertTrue(warnings.stream().anyMatch(w -> "LEGACY_DEPENDENCY_REFERENCE_INVALID".equals(w.code())));
    }

    @Test
    void requiresExactLocalFieldAndForeignValue() {
        List<ColumnDefinition> columns = List.of(column(1, "ACTNSTS", "", List.of()));
        List<List<List<String>>> tables = List.of(List.of(
                List.of("ردیف", "نام فیلد", "فیلد خارجی"),
                List.of("1", "ACTNSTS 1=فعال 0 = غیر فعال", ""),
                List.of("2", "UNKNOWNFIELD", "TASSHMA.CTFX.ID")
        ));
        List<ExtractionWarning> warnings = new ArrayList<>();

        List<ColumnDefinition> resolved = LegacyRtl9DependencySectionResolver.apply(columns, tables, warnings);

        assertTrue(resolved.getFirst().keys().isEmpty());
        assertEquals(2, warnings.size());
        assertTrue(warnings.stream().allMatch(w -> "LEGACY_DEPENDENCY_LOCAL_FIELD_UNRESOLVED".equals(w.code())));
    }

    private static ColumnDefinition column(
            int sequence,
            String name,
            String referenceOrDefault,
            List<String> keys) {
        return new ColumnDefinition(
                sequence, 0, sequence, "", name, name,
                "I", "", String.join(" ", keys), "",
                "X", Boolean.TRUE, "", "",
                referenceOrDefault, keys, List.of(), List.of()
        );
    }
}
