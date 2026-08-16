package com.behsazan.schemaforge;

import com.behsazan.schemaforge.dialect.db2zos.Db2ZosDialect;
import com.behsazan.schemaforge.dialect.oracle.OracleDialect;
import com.behsazan.schemaforge.dialect.postgresql.PostgreSqlDialect;
import com.behsazan.schemaforge.dialect.sqlserver.SqlServerDialect;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.generation.DdlGenerator;
import com.behsazan.schemaforge.specification.normalization.SpecificationNormalizer;
import com.behsazan.schemaforge.specification.parser.SpecificationSource;
import com.behsazan.schemaforge.specification.parser.WordSpecificationParser;
import com.behsazan.schemaforge.specification.parser.legacy.LegacyWordSpecificationParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end Physical Phase-1 regression over real table-design documents supplied for the project.
 *
 * <p>These tests deliberately verify only source facts and physical behavior that are already agreed.
 * They do not infer missing FK target columns and do not use SPACE_FREE_NAME as a test contract.</p>
 */
class RealSourcePhysicalPhase1RegressionTest {

    @Test
    void countriesShouldPreserveOraclePlacementAndDb2CharacterRules() throws Exception {
        DatabaseSchema schema = parseDocx("/physical-real-corpus/MCB.BIM.TBL.COUNTRIES.V1.1.docx");
        Outputs out = render(schema);

        assertTrue(out.oracle().contains("CREATE TABLE BIM.COUNTRIES"));
        assertTrue(out.oracle().contains("TABLESPACE TS_BIM;"));
        assertTrue(out.oracle().contains("TABLESPACE ITS_BIM"));
        assertTrue(out.oracle().contains("-- ORACLE TABLE PHYSICAL OPTIONS"));
        assertTrue(out.oracle().contains("-- ORACLE INDEX PHYSICAL OPTIONS"));

        String db2 = upper(out.db2());
        assertTrue(db2.contains("COUNTRY_ISO_CODE CHAR(3) FOR MIXED DATA"));
        assertTrue(db2.contains("-- DB2/ZOS TABLE PHYSICAL OPTIONS"));
        assertTrue(db2.contains("-- DB2/ZOS INDEX PHYSICAL OPTIONS"));
        assertTrue(db2.contains("<PADDED_OR_NOT_PADDED>"));

        assertTrue(out.postgresql().contains("-- POSTGRESQL TABLE PHYSICAL OPTIONS"));
        assertTrue(out.sqlServer().contains("-- SQL SERVER TABLE PHYSICAL OPTIONS"));
    }

    @Test
    void voucherHeaderRowsShouldKeepAccOraclePlacementAndPhysicalComments() throws Exception {
        DatabaseSchema schema = parseDocx(
                "/physical-real-corpus/MCB.ACC.TBL.VOUCHER_TEMPLATE_HEADER_ROWS.V1.0.docx");
        Outputs out = render(schema);

        assertTrue(out.oracle().contains("CREATE TABLE ACC.VOUCHER_TEMPLATE_HEADER_ROWS"));
        assertTrue(out.oracle().contains("TABLESPACE TS_ACC;"));
        assertTrue(out.oracle().contains("TABLESPACE ITS_ACC"));
        assertTrue(out.oracle().contains("PCTFREE 10"));
        assertTrue(out.oracle().contains("INITRANS 1"));
        assertTrue(out.oracle().contains("INITRANS 2"));

        String db2 = upper(out.db2());
        assertTrue(db2.contains("VOUCHER_TEMPLATE_HEADER_ROW_NAME VARCHAR(255) FOR MIXED DATA"));
        assertTrue(db2.contains("<PADDED_OR_NOT_PADDED>"));
        assertTrue(db2.contains("FREEPAGE 0"));
        assertTrue(db2.contains("PCTFREE 10"));
    }

    @Test
    void smsServiceDetailsShouldPreserveCompositeKeyPlacementAndMixedData() throws Exception {
        DatabaseSchema schema = parseLegacy(
                "/physical-real-corpus/14000218_SmsServcD.sd.spc.tb.CTSMSServiceDetails.docx",
                "SMSSHMA");
        Outputs out = render(schema);

        String oracle = upper(out.oracle());
        assertTrue(oracle.contains("CREATE TABLE SMSSHMA.CTSMSSERVICEDETAILS"));
        assertTrue(oracle.contains("TABLESPACE TS_SMSSHMA;"));
        assertTrue(oracle.contains("TABLESPACE ITS_SMSSHMA"));
        assertTrue(oracle.contains("PRIMARY KEY"));

        String db2 = upper(out.db2());
        assertTrue(db2.contains("VARCHAR(400) FOR MIXED DATA"));
        assertTrue(db2.contains("-- DB2/ZOS TABLE PHYSICAL OPTIONS"));
        assertTrue(db2.contains("-- DB2/ZOS INDEX PHYSICAL OPTIONS"));
    }

    @Test
    void sourcePermissionDetailShouldNotReintroduceRemovedDefaults() throws Exception {
        // Legacy Word schema is an explicit REST/API input. ARZSHMA is only the test-supplied schema,
        // not a schema value inferred from the document itself.
        DatabaseSchema schema = parseLegacy(
                "/physical-real-corpus/trunk_Spec_Arz_CmmnDpstD.sd.spc.tb.CTMSourcePermissionDetail.doc",
                "ARZSHMA");
        Outputs out = render(schema);

        String db2 = upper(out.db2());
        assertTrue(db2.contains("SOURCEAMNT DECIMAL(20,5) WITH DEFAULT 0"));
        assertFalse(db2.contains("REQUESTAMNT DECIMAL(20,5) WITH DEFAULT"));
        assertFalse(db2.contains("PERMITAMNT DECIMAL(20,5) WITH DEFAULT"));
        assertFalse(db2.contains("USEDAMNT DECIMAL(20,5) WITH DEFAULT"));
        assertFalse(db2.contains("WITH DEFAULT NULL"));
        assertTrue(db2.contains("FOR MIXED DATA"));

        String oracle = upper(out.oracle());
        assertTrue(oracle.contains("CREATE TABLE ARZSHMA.CTMSOURCEPERMISSIONDETAIL"));
        assertTrue(oracle.contains("TABLESPACE TS_ARZSHMA;"));
    }

    private static DatabaseSchema parseDocx(String resource) throws Exception {
        Path path = resourcePath(resource);
        try (InputStream input = Files.newInputStream(path)) {
            DatabaseSchema parsed = new WordSpecificationParser().parse(
                    new SpecificationSource(path.getFileName().toString(), input));
            return new SpecificationNormalizer().normalize(parsed);
        }
    }

    private static DatabaseSchema parseLegacy(String resource, String schemaName) throws Exception {
        Path path = resourcePath(resource);
        DatabaseSchema parsed = new LegacyWordSpecificationParser().parse(path.getParent(), path, schemaName);
        return new SpecificationNormalizer().normalize(parsed);
    }

    private static Path resourcePath(String resource) throws URISyntaxException {
        return Path.of(RealSourcePhysicalPhase1RegressionTest.class.getResource(resource).toURI());
    }

    private static Outputs render(DatabaseSchema schema) {
        return new Outputs(
                new DdlGenerator(new OracleDialect()).generate(schema),
                new DdlGenerator(new PostgreSqlDialect()).generate(schema),
                new DdlGenerator(new SqlServerDialect()).generate(schema),
                new DdlGenerator(new Db2ZosDialect()).generate(schema));
    }

    private static String upper(String value) {
        return value.toUpperCase(Locale.ROOT);
    }

    private record Outputs(String oracle, String postgresql, String sqlServer, String db2) {
    }
}
