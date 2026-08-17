package com.behsazan.schemaforge.specification.parser.legacy;

import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.LengthSemantics;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyMetadataRecoveryPolicyTest {

    @Test
    void exactMetadataMayRecoverTypeWhenOnlyDatatypeCellsAreMerged() {
        ParsedWordColumn source = column("DETAILCODE", "N C", "I VC");
        assertTrue(LegacyWordSpecificationParser.metadataTypeRecoveryEligible(source));
    }

    @Test
    void exactMetadataMustNotRecoverTypeForMergedTechnicalFieldIdentity() {
        ParsedWordColumn source = column("DBBRANCH SUPPORTDBBRANCH", "int int", "");
        assertFalse(LegacyWordSpecificationParser.metadataTypeRecoveryEligible(source));
    }

    @Test
    void charAndVarcharShareSafeLengthEvidenceFamily() {
        DataType db2Char = new DataType(Identifier.of("CHAR"), 32, LengthSemantics.CHAR, null, null);
        assertTrue(LegacyWordSpecificationParser.metadataCharacterLengthCompatible("VARCHAR", db2Char));
    }

    @Test
    void basicAndNationalCharacterFamiliesDoNotCross() {
        DataType db2Graphic = new DataType(Identifier.of("GRAPHIC"), 32, LengthSemantics.CHAR, null, null);
        assertFalse(LegacyWordSpecificationParser.metadataCharacterLengthCompatible("VARCHAR", db2Graphic));
        assertTrue(LegacyWordSpecificationParser.metadataCharacterLengthCompatible("NVARCHAR", db2Graphic));
    }

    private ParsedWordColumn column(String technicalRaw, String logicalRaw, String physicalRaw) {
        return new ParsedWordColumn(
                1, 0, 0,
                technicalRaw.replace(" ", ""), technicalRaw,
                "", MetadataConfidence.NOT_PRESENT,
                logicalRaw, "", DataTypeConfidence.UNRELIABLE,
                "", "", null, null, null, false,
                "", List.of(), false, false,
                "", List.of(), null,
                physicalRaw, "", DataTypeConfidence.UNRELIABLE,
                "", "", "", "", "", List.of());
    }
}
