package com.behsazan.schemaforge.specification.parser.legacy;

import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.WordFormat;

/**
 * Detects whether a path contains a supported and readable Microsoft Word document.
 *
 * <p>Detection uses both the declared extension and the physical container signature. OLE2
 * documents must contain a {@code WordDocument} stream, OOXML packages must contain
 * {@code word/document.xml}, and encrypted OOXML containers or spreadsheets renamed as
 * {@code .doc} are rejected. Temporary Word files are identified separately so batch scans can
 * ignore them without treating them as parser failures.</p>
 */
final class WordFileDetector {
    private WordFileDetector() {
    }

    static boolean hasSupportedExtension(Path path) {
        return declaredFormat(path) != WordFormat.UNKNOWN;
    }

    static boolean isTemporaryWordFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.contains("~$");
    }

    static WordFormat declaredFormat(Path sourceFile) {
        String name = sourceFile.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".docx")) {
            return WordFormat.DOCX;
        }
        if (name.endsWith(".doc")) {
            return WordFormat.DOC;
        }
        return WordFormat.UNKNOWN;
    }

    static WordFormat detectActualFormat(Path sourceFile) throws IOException {
        FileMagic magic;
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(sourceFile), 64 * 1024);
             InputStream prepared = FileMagic.prepareToCheckMagic(raw)) {
            magic = FileMagic.valueOf(prepared);
        }

        if (magic == FileMagic.OLE2) {
            return detectOle2WordFormat(sourceFile);
        }
        if (magic == FileMagic.OOXML) {
            return detectOoxmlWordFormat(sourceFile);
        }
        return WordFormat.UNKNOWN;
    }

    private static WordFormat detectOle2WordFormat(Path sourceFile) throws IOException {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(sourceFile), 64 * 1024);
             POIFSFileSystem fileSystem = new POIFSFileSystem(input)) {
            DirectoryEntry root = fileSystem.getRoot();

            // Password-protected OOXML files are OLE2 containers, but they are not
            // directly readable Word documents without a password.
            if (root.hasEntry("EncryptedPackage") || root.hasEntry("EncryptionInfo")) {
                return WordFormat.UNKNOWN;
            }

            // Some spreadsheets have been saved or renamed with a .doc extension.
            if ((root.hasEntry("Workbook") || root.hasEntry("Book"))
                    && !root.hasEntry("WordDocument")) {
                return WordFormat.UNKNOWN;
            }

            return root.hasEntry("WordDocument") ? WordFormat.DOC : WordFormat.UNKNOWN;
        }
    }

    private static WordFormat detectOoxmlWordFormat(Path sourceFile) throws IOException {
        try (ZipFile zip = new ZipFile(sourceFile.toFile())) {
            ZipEntry wordDocument = zip.getEntry("word/document.xml");
            return wordDocument == null ? WordFormat.UNKNOWN : WordFormat.DOCX;
        } catch (ZipException e) {
            return WordFormat.UNKNOWN;
        }
    }
}
