package com.behsazan.schemaforge.metadata.repository;

import com.behsazan.schemaforge.domain.valueobject.DataType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Db2SysColumnsFileCatalogTest {
    @TempDir
    Path tempDir;

    @Test
    void readsExactCaseInsensitiveTypesFromCsv() throws Exception {
        Path csv = tempDir.resolve("syscolumns.csv");
        Files.writeString(csv, "TBCREATOR,TBNAME,NAME,COLTYPE,LENGTH,LENGTH2,SCALE,TYPENAME\n"
                + "TSTSHMA,CTTEST,USERID,VARCHAR,32,,0,\n"
                + "TSTSHMA,CTTEST,AMOUNT,DECIMAL,15,,2,\n"
                + "TSTSHMA,CTTEST,STATUS,SMALLINT,2,,0,\n", StandardCharsets.UTF_8);

        Db2SysColumnsFileCatalog catalog = new Db2SysColumnsFileCatalog(csv);
        DataType userId = catalog.findType("tstshma", "cttest", "userid").orElseThrow();
        DataType amount = catalog.findType("TSTSHMA", "CTTEST", "AMOUNT").orElseThrow();
        DataType status = catalog.findType("TSTSHMA", "CTTEST", "STATUS").orElseThrow();

        assertEquals("VARCHAR", userId.name().value());
        assertEquals(32, userId.length());
        assertEquals("DECIMAL", amount.name().value());
        assertEquals(15, amount.precision());
        assertEquals(2, amount.scale());
        assertEquals("SMALLINT", status.name().value());
        assertTrue(catalog.findType("TSTSHMA", "OTHER", "USERID").isEmpty());
    }

    @Test
    void acceptsZipAndRejectsConflictingDuplicate() throws Exception {
        Path zip = tempDir.resolve("SYSCOLUMNS.zip");
        String csv = "TBCREATOR;TBNAME;NAME;COLTYPE;LENGTH;LENGTH2;SCALE;TYPENAME\n"
                + "TSTSHMA;CTTEST;TOKEN;VARCHAR;20;;0;\n"
                + "TSTSHMA;CTTEST;TOKEN;VARCHAR;30;;0;\n"
                + "TSTSHMA;CTTEST;CREATEDATE;DATE;4;;0;\n";
        try (OutputStream output = Files.newOutputStream(zip);
             ZipOutputStream archive = new ZipOutputStream(output)) {
            archive.putNextEntry(new ZipEntry("SYSCOLUMNS.csv"));
            archive.write(csv.getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
        }

        Db2SysColumnsFileCatalog catalog = new Db2SysColumnsFileCatalog(zip);
        Optional<DataType> ambiguous = catalog.findType("TSTSHMA", "CTTEST", "TOKEN");
        DataType date = catalog.findType("TSTSHMA", "CTTEST", "CREATEDATE").orElseThrow();

        assertTrue(ambiguous.isEmpty());
        assertEquals("DB2_DATE", date.name().value());
        assertEquals(1, catalog.ambiguousColumns());
    }

    @Test
    void doesNotInventMissingCharacterLength() throws Exception {
        Path csv = tempDir.resolve("syscolumns.tsv");
        Files.writeString(csv, "TBCREATOR\tTBNAME\tNAME\tCOLTYPE\tLENGTH\tLENGTH2\tSCALE\n"
                + "TSTSHMA\tCTTEST\tUSERID\tVARCHAR\t\t\t0\n"
                + "TSTSHMA\tCTTEST\tSTATUS\tINTEGER\t4\t\t0\n", StandardCharsets.UTF_8);

        Db2SysColumnsFileCatalog catalog = new Db2SysColumnsFileCatalog(csv);
        assertTrue(catalog.findType("TSTSHMA", "CTTEST", "USERID").isEmpty());
        assertEquals("INTEGER", catalog.findType("TSTSHMA", "CTTEST", "STATUS").orElseThrow().name().value());
    }
    @Test
    void diagnosesExactMetadataMissReasonWithoutFuzzyMatching() throws Exception {
        Path csv = tempDir.resolve("syscolumns-diagnostic.csv");
        Files.writeString(csv, "TBCREATOR,TBNAME,NAME,COLTYPE,LENGTH,LENGTH2,SCALE,TYPENAME\n"
                + "TSTSHMA,CTTEST,GOOD,VARCHAR,20,,0,\n"
                + "TSTSHMA,CTTEST,INCOMPLETE,VARCHAR,,,0,\n"
                + "TSTSHMA,CTAMB,DUP,VARCHAR,10,,0,\n"
                + "TSTSHMA,CTAMB,DUP,VARCHAR,30,,0,\n", StandardCharsets.UTF_8);

        Db2SysColumnsFileCatalog catalog = new Db2SysColumnsFileCatalog(csv);

        assertEquals(Db2SysColumnsFileCatalog.LookupStatus.USABLE,
                catalog.lookupStatus("tstshma", "cttest", "good"));
        assertEquals(Db2SysColumnsFileCatalog.LookupStatus.INCOMPLETE,
                catalog.lookupStatus("TSTSHMA", "CTTEST", "INCOMPLETE"));
        assertEquals(Db2SysColumnsFileCatalog.LookupStatus.COLUMN_NOT_FOUND,
                catalog.lookupStatus("TSTSHMA", "CTTEST", "MISSING"));
        assertEquals(Db2SysColumnsFileCatalog.LookupStatus.TABLE_NOT_FOUND,
                catalog.lookupStatus("TSTSHMA", "NO_TABLE", "GOOD"));
        assertEquals(Db2SysColumnsFileCatalog.LookupStatus.AMBIGUOUS,
                catalog.lookupStatus("TSTSHMA", "CTAMB", "DUP"));
    }

}
