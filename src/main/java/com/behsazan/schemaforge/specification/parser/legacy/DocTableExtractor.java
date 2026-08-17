package com.behsazan.schemaforge.specification.parser.legacy;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.hwpf.usermodel.CharacterRun;
import org.apache.poi.hwpf.usermodel.HeaderStories;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.hwpf.usermodel.Table;
import org.apache.poi.hwpf.usermodel.TableCell;
import org.apache.poi.hwpf.usermodel.TableIterator;
import org.apache.poi.hwpf.usermodel.TableRow;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.ColumnDefinition;
import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.ExtractionWarning;
import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.FileResult;
import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.Metadata;
import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.Severity;
import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.Status;
import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.WordFormat;

/**
 * Extracts table specifications and audit evidence from legacy Microsoft Word documents.
 *
 * <p>This package-private parser supports both binary {@code .doc} and OOXML {@code .docx}
 * inputs. It combines document text, headers, Word tables, file-name evidence and raw
 * metadata recovery to identify table documents and map field rows into immutable
 * {@link ExtractionModels} records. Persian/English labels and known Word layout artifacts
 * are normalized conservatively; uncertain values are reported as warnings instead of being
 * silently guessed.</p>
 *
 * <p>The extractor produces an intermediate evidence model only. Mapping to the canonical
 * SchemaForge domain model and database-specific DDL is performed by later pipeline stages.</p>
 */
final class DocTableExtractor {
    private static final Pattern TABLE_NAME = Pattern.compile(
            "نام\\s*جدول\\s*[:：]?\\s*[.\\s]*([A-Za-z][A-Za-z0-9_$#]*(?:\\s*\\.\\s*[A-Za-z][A-Za-z0-9_$#]*)*)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern TABLE_NAME_RAW = Pattern.compile(
            "نام\\s*جدول\\s*[:：]?\\s*(.+?)(?=(?:نام\\s*)?موجودیت\\s*[:：]|شرح\\s*[:：]|"
                    + "طراح\\s*[:：]|تاریخ\\s*[^:：]{0,40}[:：]|نام\\s*صفت|نام\\s*ستون|$)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern CREATED_DATE = Pattern.compile(
            "تاریخ\\s*ایجاد\\s*[:：]?\\s*([0-9۰-۹٠-٩]{1,4}\\s*/\\s*[0-9۰-۹٠-٩]{1,2}\\s*/\\s*[0-9۰-۹٠-٩]{1,4})"
    );
    private static final Pattern MODIFIED_DATE = Pattern.compile(
            "تاریخ\\s*(?:ا\\s*صلاح|اصلاح|اصالح)\\s*[:：]?\\s*([0-9۰-۹٠-٩]{1,4}\\s*/\\s*[0-9۰-۹٠-٩]{1,2}\\s*/\\s*[0-9۰-۹٠-٩]{1,4})"
    );
    private static final String ENTITY_STOP =
            "(?=طراح\\s*[:：]|تاریخ\\s*[^:：]{0,40}[:：]|نام\\s*صفت|نام\\s*فیلد|"
                    + "نام\\s*فيلد|نام\\s*جدول\\s*DB2\\s*[:：]?|نام\\s*جدول\\s*[:：]|نام\\s*سامانه\\s*[:：]|"
                    + "بانک\\s*اطلاعاتی\\s*[:：]|بانك\\s*اطلاعاتي\\s*[:：]|"
                    + "فرم\\s*/?\\s*نمودار|صفحه(?:\\s|[:：])|PAGE\\b|NUMPAGES\\b|"
                    + "نحوه\\s*انتقال|محیط\\s*[:：]|محيط\\s*[:：]|آرشیو\\s*[:：]|آرشيو\\s*[:：]|"
                    + "نسخه\\s*[:：]|تاریخچه\\s*تغییرات|تاريخچه\\s*تغييرات|تهیه\\s*کننده|تهيه\\s*كننده|"
                    + "شرح\\s*تغییرات|شرح\\s*تغييرات|نگارش\\s*[:：]?|پروژه\\s*سامانه|$)";
    private static final Pattern ENTITY_NAME = Pattern.compile(
            "(?:نام\\s*)?موجودیت\\s*[:：]?\\s*(.*?)" + ENTITY_STOP
    );
    private static final Pattern ENTITY_NAME_FLEXIBLE = Pattern.compile(
            "(?:نام\\s*)?م\\s*و\\s*ج\\s*و\\s*د\\s*ی\\s*ت\\s*[:：]?\\s*(.*?)" + ENTITY_STOP
    );
    private static final Pattern RAW_METADATA_LABEL = Pattern.compile(
            "(?iu)نام\\s*جدول|(?:نام\\s*)?م\\s*و\\s*ج\\s*و\\s*د\\s*ی\\s*ت"
    );
    /**
     * The raw DOC scanner already returns a bounded canonical block containing only
     * the table label and entity label. Its entity value may legitimately start with
     * "تاریخچه تغییرات", so it must not be parsed with ENTITY_STOP, where that
     * phrase is intentionally treated as noise for unbounded HWPF text.
     */
    private static final Pattern RAW_CANONICAL_ENTITY_NAME = Pattern.compile(
            "(?iu)(?:نام\\s*)?م\\s*و\\s*ج\\s*و\\s*د\\s*[یي]\\s*ت\\s*[:：]?\\s*(.+)$"
    );
    private static final Pattern STRICT_ENTITY_NAME = Pattern.compile(
            "(?iu)(?:نام\\s*)?م\\s*و\\s*ج\\s*و\\s*د\\s*[یي]\\s*ت\\s*[:：]?\\s*(.+?)"
                    + "(?=\\s+(?:صفحه(?:\\s|[:：])|PAGE\\b|NUMPAGES\\b|نام\\s*صفت|نام\\s*فیلد|"
                    + "نام\\s*فيلد|تاریخ(?:\\s|[:：])|تاريخ(?:\\s|[:：])|"
                    + "بانک\\s*اطلاعاتی\\s*[:：]|بانك\\s*اطلاعاتي\\s*[:：]|"
                    + "فرم\\s*/?\\s*نمودار|نحوه\\s*انتقال|محیط\\s*[:：]|محيط\\s*[:：]|"
                    + "آرشیو\\s*[:：]|آرشيو\\s*[:：]|نسخه\\s*[:：]|"
                    + "تاریخچه\\s*تغییرات|تاريخچه\\s*تغييرات|تهیه\\s*کننده|تهيه\\s*كننده|"
                    + "شرح\\s*تغییرات|شرح\\s*تغييرات|نگارش\\s*[:：]?|پروژه\\s*سامانه)|$)"
    );
    private static final Pattern SYSTEM_NAME = Pattern.compile(
            "(?:سیستم|نام\\s*سامانه)\\s*[:：]?\\s*(.+?)(?=فرم\\s*/?\\s*نمودار|تاریخ\\s*ایجاد|$)"
    );
    private static final Pattern DOCUMENT_TYPE = Pattern.compile(
            "فرم\\s*/?\\s*نمودار\\s*[:：]?\\s*(.+?)(?=مدل\\s*[:：]|تاریخ\\s*ایجاد|نام\\s*جدول|$)"
    );
    private static final Pattern FILE_NAME_TABLE = Pattern.compile(
            "(?i)(?:^|[._-])tb[._-]+([A-Za-z][A-Za-z0-9_$#-]*)\\s*(?=\\.docx?$)"
    );
    private static final Pattern FILE_NAME_TABLES = Pattern.compile(
            "(?i)(?:^|[._-])tables?[._-]+([A-Za-z][A-Za-z0-9_$#-]*)\\s*(?=\\.docx?$)"
    );
    private static final Pattern FILE_NAME_TB_SUFFIX = Pattern.compile(
            "(?i)(?:^|[._-])tb[._-]+(.+?)\\s*(?=\\.docx?$)"
    );
    private static final Pattern TRAILING_TECHNICAL_TOKEN = Pattern.compile(
            "(?i)(?:^|[\\s._-]+)([A-Za-z][A-Za-z0-9_$#]*)\\s*$"
    );
    private static final Pattern FILE_NAME_CONTAINS_TABLE_WORD = Pattern.compile(
            "(?i)(?:^|[^A-Za-z])table(?:[^A-Za-z]|$)"
    );
    private static final Pattern PROBABLE_DATABASE_TABLE_TOKEN = Pattern.compile(
            "(?i)^(?:CT|JT|DT|MS|MT|ET|IP|PI|BB|EIS)[A-Za-z0-9_$#]{2,}$"
    );
    private static final Pattern TRAILING_PROBABLE_TABLE_TOKEN = Pattern.compile(
            "(?i)([A-Za-z][A-Za-z0-9$#]*)\\s*$"
    );
    private static final Pattern PERSIAN_LABEL_STOP = Pattern.compile(
            "(?iu)(?:آخرین|آخرين)\\s*(?:بروزرسانی|بروز رسانی|بروزرساني|به\\s*روزرسانی)|"
                    + "طبقه\\s*(?:بندی|بندي)\\s*[:：]|"
                    + "صفحه\\s*(?::|PAGE\\b|[0-9۰-۹٠-٩])|PAGE\\b|NUMPAGES\\b|"
                    + "نحوه\\s*انتقال|تاریخ\\s*(?:ایجاد|اصلاح|اصالح)|"
                    + "DOCPROPERTY\\b|SUBJECT\\b|MERGEFORMAT\\b|"
                    + "نسخه\\s*[:：]|تاریخچه\\s*تغییرات|تاريخچه\\s*تغييرات|"
                    + "تهیه\\s*کننده|تهيه\\s*كننده|شرح\\s*تغییرات|شرح\\s*تغييرات|نگارش\\s*[:：]?|"
                    + "بهسازان\\s*ملت|گروه\\s*طراحی\\s*سامانه|گروه\\s*طراحي\\s*سامانه|کد\\s*فرم|كد\\s*فرم|"
                    + "نام\\s*صفت|نام\\s*فیلد|نام\\s*فيلد|گزارش\\s*طراحی|گزارش\\s*طراحي"
    );
    private static final Pattern EMBEDDED_ENTITY_LABEL = Pattern.compile(
            "(?iu)(?:نام\\s*)?موجودیت\\s*[:：]?\\s*"
    );
    private static final Pattern NEGATIVE_DOCUMENT_TOKEN = Pattern.compile(
            "(?i)(?:^|[._-])(sp|fr|rp|sr)(?:[._-]|$)"
    );
    private static final Pattern DATE_SPACES = Pattern.compile("\\s*/\\s*");
    private static final Pattern CHECKED_SYMBOL = Pattern.compile("[✓✔☑√þ\uE10B]");
    private static final Pattern UNCHECKED_SYMBOL = Pattern.compile("[☐□]");
    private static final Pattern DOCX_SYMBOL_ELEMENT = Pattern.compile(
            "(?is)<(?:[A-Za-z0-9_]+:)?sym\\b[^>]*>"
    );
    private static final Pattern DOCX_SYMBOL_FONT = Pattern.compile(
            "(?i)(?:[A-Za-z0-9_]+:)?font\\s*=\\s*[\"']([^\"']+)[\"']"
    );
    private static final Pattern DOCX_SYMBOL_CHAR = Pattern.compile(
            "(?i)(?:[A-Za-z0-9_]+:)?char\\s*=\\s*[\"']([^\"']+)[\"']"
    );
    private static final Pattern LATIN_TECHNICAL_TOKEN = Pattern.compile(
            "\\b[A-Za-z][A-Za-z0-9_$#]{2,}\\b"
    );
    private static final Pattern PERSIAN_FIELD_TAIL = Pattern.compile(
            "(?iu)\\s+(?:[0-9۰-۹٠-٩]+\\s+)?(?:N|C|S|I|D|V|T|B|L|F|VC|TS|SI|TI|TIN|BI|"
                    + "DC|DE|DEC|INT|INTEGER|SMALL|SMALLINT|BIGINT|VARCHAR|CHAR|TIMESTAMP)"
                    + "(?=\\s+(?:[()\\[\\]{}]|[0-9۰-۹٠-٩]+|[A-Za-z_$#]|[\\p{L}]))"
    );
    private static final Pattern ENTITY_LABEL_PRESENT = Pattern.compile(
            "(?iu)(?:نام\\s*)?م\\s*و\\s*ج\\s*و\\s*د\\s*[یي]\\s*ت\\s*[:：]?"
    );
    private static final Pattern PERSIAN_STRUCTURAL_TAIL = Pattern.compile(
            "(?iu)\\s+(?:(?:IS|REP)\\s+)*(?:نوع\\s+طول|پیش\\s*فرض\\s+نوع\\s+طول|"
                    + "پيش\\s*فرض\\s+نوع\\s+طول|اجباری\\s+نوع\\s+طول|اجباري\\s+نوع\\s+طول)\\b.*$"
    );
    private static final Pattern PERSIAN_INVALID_METADATA_START = Pattern.compile(
            "(?iu)^(?:پیش\\s*فرض|پيش\\s*فرض|اجباری|اجباري|نوع|طول|دامنه|امنه\\s*/|"
                    + "فیلد\\s*های|فيلد\\s*هاي)(?:\\s|/|:|：|$).*"
    );
    private static final Pattern PERSIAN_METADATA_NOISE = Pattern.compile(
            "(?iu)(?:^|\\s)(?:نام\\s*صفت|نام\\s*فیلد|نام\\s*فيلد|"
                    + "جدول\\s*/\\s*محدودیت|جدول\\s*/\\s*محدوديت|"
                    + "محدودیت\\s*/\\s*دامنه|محدوديت\\s*/\\s*دامنه|"
                    + "پیش\\s*فرض|پيش\\s*فرض|اجباری|اجباري|نوع\\s+طول)(?:\\s|/|:|：|$)"
    );
    private static final Pattern PERSIAN_DOMAIN_VALUE_START = Pattern.compile(
            "^[\\s=:+-]*[0-9۰-۹٠-٩]+\\s*[:=\\-]"
    );
    private static final Pattern PERSIAN_HISTORY_GRID = Pattern.compile(
            "(?iu)(?:تاریخچه|تاريخچه)\\s+(?:تاریخ|تاريخ)\\s+نام\\s+طراح\\s+شرح\\s+پروژه"
    );
    private static final Pattern PERSIAN_HISTORY_TITLE = Pattern.compile(
            "(?iu)^(?:تاریخچه|تاريخچه)\\s+(?:تغییرات|تغييرات)\\s+.+$"
    );
    private static final Pattern PERSIAN_CHANGE_LOG_NOISE = Pattern.compile(
            "(?iu)(?:نام\\s+طراح|شرح\\s+پروژه|ایجاد\\s+(?:جدول|سند)|ايجاد\\s+(?:جدول|سند)|"
                    + "افزودن\\s+فیلد|افزودن\\s+فيلد|اضافه\\s+شدن\\s+فیلد|اضافه\\s+شدن\\s+فيلد|"
                    + "اصلاح\\s+(?:نوع\\s+)?فیلد|اصلاح\\s+(?:نوع\\s+)?فيلد)"
    );
    private static final Pattern PERSIAN_DATE_TOKEN = Pattern.compile(
            "(?<![0-9۰-۹٠-٩])[0-9۰-۹٠-٩]{1,4}\\s*/\\s*[0-9۰-۹٠-٩]{1,2}\\s*/\\s*[0-9۰-۹٠-٩]{1,4}(?![0-9۰-۹٠-٩])"
    );
    private static final Pattern PERSIAN_TRUNCATED_HISTORY = Pattern.compile(
            "(?iu)^(?:ودجه|جه)\\s+(?:[0-9۰-۹٠-٩/]+\\s+)?[^\\n]{0,80}(?:افزودن|اصلاح|ایجاد|ايجاد)"
    );
    private static final Pattern PERSIAN_EMBEDDED_METADATA_LABEL = Pattern.compile(
            "(?iu)(?:نام\\s*)?(?:موجودیت|موجوديت|جدول)\\s*[:：]"
    );
    private static final Pattern FIELD_HEADER_BOUNDARY = Pattern.compile(
            "(?iu)(?:نام\\s*صفت|نام\\s*فیلد|نام\\s*فيلد|"
                    + "نام\\s*خاصه|نام\\s*فيلد\\s+نوع|نوع\\s+طول\\s+کلید)"
    );
    private static final Pattern LATIN_WORD = Pattern.compile(
            "\\b[A-Za-z][A-Za-z0-9_$#]{3,}\\b"
    );
    private static final Pattern CONTROL_CHARACTER = Pattern.compile(
            "[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"
    );
    private static final Set<String> CHECKED_VALUES = Set.of(
            "Y", "YES", "TRUE", "1", "بله", "دارد"
    );
    private static final Set<String> UNCHECKED_VALUES = Set.of(
            "N", "NO", "FALSE", "0", "خیر", "ندارد"
    );
    private static final Set<String> INVALID_TABLE_NAMES = Set.of(
            "SCHEMA", "TABLE", "TABLENAME", "NAME", "NULL", "DB2", "DOCPROPERTY",
            "SUBJECT", "MERGEFORMAT", "PAGE", "NUMPAGES"
    );
    private static final int TABLE_DOCUMENT_SCORE_THRESHOLD = 8;

    FileResult extract(Path inputRoot, Path sourceFile, long maxFileBytes) {
        long startedNanos = System.nanoTime();
        long size = 0L;
        String relative = safeRelative(inputRoot, sourceFile);
        WordFormat declaredFormat = WordFileDetector.declaredFormat(sourceFile);
        WordFormat actualFormat = WordFormat.UNKNOWN;
        try {
            size = Files.size(sourceFile);
            if (size == 0L) {
                return FileResult.ignored(
                        sourceFile, relative, size, elapsedMillis(startedNanos), declaredFormat, actualFormat
                );
            }
            if (size > maxFileBytes) {
                throw new IOException("File exceeds configured size limit: " + size + " bytes");
            }

            actualFormat = WordFileDetector.detectActualFormat(sourceFile);
            if (actualFormat == WordFormat.UNKNOWN) {
                return FileResult.ignored(
                        sourceFile, relative, size, elapsedMillis(startedNanos), declaredFormat, actualFormat
                );
            }

            ParsedDocument parsed = switch (actualFormat) {
                case DOC -> parseDoc(sourceFile);
                case DOCX -> parseDocx(sourceFile);
                case UNKNOWN -> throw new IllegalStateException("Unsupported Word format: " + sourceFile);
            };

            ParsedMetadata parsedMetadata = parseMetadata(
                    parsed.rawHeaderText(),
                    parsed.rawMainText(),
                    parsed.authoritativeMetadata(),
                    sourceFile.getFileName().toString()
            );
            Metadata metadata = parsedMetadata.metadata();
            if (isTemplateDocument(relative, parsed, metadata)) {
                return FileResult.ignored(
                        sourceFile, relative, size, elapsedMillis(startedNanos), declaredFormat, actualFormat
                );
            }

            List<ExtractionWarning> warnings = new ArrayList<>();
            List<ColumnDefinition> columns = extractColumns(parsed.tables(), warnings);
            columns = sanitizeColumnQuality(columns, warnings);
            columns = LegacyRevisionDefaultOverrideResolver.apply(columns, parsed.rawMainText(), warnings);
            MetadataSanitization metadataSanitization = sanitizeMetadataAgainstColumns(
                    metadata, columns, parsed.rawMainText()
            );
            metadata = metadataSanitization.metadata();
            boolean persianCandidateRejected = parsedMetadata.persianCandidateRejected()
                    || metadataSanitization.rejected();
            if (columns.isEmpty()) {
                return FileResult.ignored(
                        sourceFile, relative, size, elapsedMillis(startedNanos), declaredFormat, actualFormat
                );
            }

            int documentScore = calculateTableDocumentScore(relative, parsed, metadata, columns);
            if (documentScore < TABLE_DOCUMENT_SCORE_THRESHOLD) {
                return FileResult.ignored(
                        sourceFile, relative, size, elapsedMillis(startedNanos), declaredFormat, actualFormat
                );
            }

            if (declaredFormat != WordFormat.UNKNOWN && declaredFormat != actualFormat) {
                warnings.add(new ExtractionWarning(
                        Severity.INFO,
                        "WORD_FORMAT_MISMATCH",
                        null,
                        null,
                        "The file extension and detected Word format are different. The detected format was used.",
                        declaredFormat + " -> " + actualFormat
                ));
            }
            if (parsedMetadata.fromEntityName()) {
                warnings.add(new ExtractionWarning(
                        Severity.INFO,
                        "TABLE_NAME_FROM_ENTITY_NAME",
                        null,
                        null,
                        "The technical table name was recovered from the entity-name value because the table-name value was descriptive text.",
                        parsedMetadata.originalEntityName()
                ));
            }
            if (parsedMetadata.fromFilename()) {
                warnings.add(new ExtractionWarning(
                        Severity.INFO,
                        "TABLE_NAME_FROM_FILENAME",
                        null,
                        null,
                        "Table name was recovered from the source file name because the header value was missing or incomplete.",
                        parsedMetadata.originalHeaderTableName()
                ));
            }
            String normalizedHeaderTableName = normalizeTableName(parsedMetadata.originalHeaderTableName());
            if (!normalizedHeaderTableName.isBlank()
                    && !parsedMetadata.fileNameTableName().isBlank()
                    && !normalizedHeaderTableName.equalsIgnoreCase(parsedMetadata.fileNameTableName())) {
                warnings.add(new ExtractionWarning(
                        Severity.WARNING,
                        "TABLE_NAME_FILENAME_MISMATCH",
                        null,
                        null,
                        "The technical table name in the document differs from the table token in the source file name. The document value was retained.",
                        normalizedHeaderTableName + " <> " + parsedMetadata.fileNameTableName()
                ));
            }

            if (metadata.persianTableName() == null || metadata.persianTableName().isBlank()) {
                warnings.add(new ExtractionWarning(
                        Severity.INFO,
                        persianCandidateRejected
                                ? "PERSIAN_TABLE_NAME_NOT_RELIABLE"
                                : "PERSIAN_TABLE_NAME_NOT_PRESENT_SOURCE",
                        null,
                        null,
                        persianCandidateRejected
                                ? "A Persian metadata candidate was present but was rejected as contaminated or invalid."
                                : "No reliable Persian table title was present in the recognized metadata positions.",
                        persianCandidateRejected
                                ? parsedMetadata.originalEntityName()
                                : null
                ));
            }

            validate(metadata, columns, warnings);
            Status status = warnings.stream()
                    .anyMatch(w -> w.severity() == Severity.WARNING || w.severity() == Severity.ERROR)
                    ? Status.PARTIAL
                    : Status.SUCCESS;

            return new FileResult(
                    sourceFile,
                    relative,
                    declaredFormat,
                    actualFormat,
                    declaredFormat != WordFormat.UNKNOWN && declaredFormat != actualFormat,
                    size,
                    elapsedMillis(startedNanos),
                    status,
                    metadata,
                    List.copyOf(columns),
                    List.copyOf(warnings),
                    parsed.rawMainText(),
                    parsed.rawHeaderText(),
                    null,
                    null,
                    null,
                    Instant.now()
            );
        } catch (Throwable error) {
            return FileResult.failed(
                    sourceFile,
                    relative,
                    declaredFormat,
                    actualFormat,
                    size,
                    elapsedMillis(startedNanos),
                    error,
                    StackTraces.toString(error)
            );
        }
    }

    static boolean supports(Path path) {
        return WordFileDetector.hasSupportedExtension(path);
    }

    private ParsedDocument parseDoc(Path sourceFile) throws IOException {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(sourceFile), 64 * 1024);
             HWPFDocument document = new HWPFDocument(input)) {
            Range bodyRange = document.getRange();
            String rawMainText = TextNormalizer.cleanBlock(bodyRange.text());
            String structuredMetadata = extractDocStructuredMetadata(document);
            String rawHeaderText = TextNormalizer.cleanBlock(
                    structuredMetadata + "\n" + extractDocHeaderText(document)
            );
            // Always append bounded raw-container metadata. A multi-page legacy DOC can expose
            // only the entity header of a later page through HWPF. Conditional fallback then
            // prevents the correct table/entity pair from being discovered. The raw scanner is
            // conservative and returns only explicit "نام جدول / نام موجودیت" pairs.
            String rawContainerMetadata = LegacyDocRawMetadataScanner.extract(sourceFile);
            if (!rawContainerMetadata.isBlank()) {
                rawHeaderText = TextNormalizer.cleanBlock(rawHeaderText + "\n" + rawContainerMetadata);
            }
            return new ParsedDocument(
                    rawMainText,
                    rawHeaderText,
                    rawContainerMetadata,
                    readDocTables(bodyRange)
            );
        }
    }

    /**
     * Extracts an authoritative, bounded metadata block from table cells visible through
     * the aggregate DOC range. Some old documents store the page header as a table that is
     * absent from {@link HeaderStories} but present in {@code getOverallRange()}.
     *
     * <p>The returned text is canonicalized to only the explicit table/entity labels. This
     * prevents the following field-definition rows from being appended to the Persian title.</p>
     */
    private String extractDocStructuredMetadata(HWPFDocument document) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        collectStructuredMetadataFromRange(candidates, document.getOverallRange());
        collectStructuredMetadataFromRange(candidates, document.getHeaderStoryRange());

        try {
            HeaderStories stories = new HeaderStories(document);
            collectStructuredMetadataFromRange(candidates, stories.getFirstHeaderSubrange());
            collectStructuredMetadataFromRange(candidates, stories.getEvenHeaderSubrange());
            collectStructuredMetadataFromRange(candidates, stories.getOddHeaderSubrange());
            collectStructuredMetadataFromRange(candidates, stories.getFirstFooterSubrange());
            collectStructuredMetadataFromRange(candidates, stories.getEvenFooterSubrange());
            collectStructuredMetadataFromRange(candidates, stories.getOddFooterSubrange());
            collectStructuredMetadataFromRange(candidates, stories.getRange());
        } catch (RuntimeException ignored) {
            // getOverallRange() remains the authoritative fallback.
        }

        // Do not select one global "best" candidate. A legacy document can contain more than
        // one table header on different pages. Keeping every bounded table/entity pair allows
        // parseMetadata() to select the pair whose technical table name matches the file name.
        return TextNormalizer.cleanBlock(String.join("\n", candidates));
    }

    private void collectStructuredMetadataFromRange(Set<String> candidates, Range range) {
        if (range == null) {
            return;
        }
        try {
            TableIterator iterator = new TableIterator(range);
            while (iterator.hasNext()) {
                Table table = iterator.next();
                for (int rowIndex = 0; rowIndex < table.numRows(); rowIndex++) {
                    TableRow row = table.getRow(rowIndex);
                    StringBuilder rowText = new StringBuilder();
                    for (int cellIndex = 0; cellIndex < row.numCells(); cellIndex++) {
                        String cellText = readDocCellText(row.getCell(cellIndex));
                        appendLine(rowText, cellText);
                        String canonicalCell = canonicalMetadataCell(cellText);
                        if (!canonicalCell.isBlank()) {
                            candidates.add(canonicalCell);
                        }
                    }
                    // In many old templates "نام جدول" and "نام موجودیت" are stored in
                    // different cells of the same row. Canonicalizing the complete row preserves
                    // their relationship and prevents a later page header from being attached to
                    // the first table.
                    String canonicalRow = canonicalMetadataCell(rowText.toString());
                    if (!canonicalRow.isBlank()) {
                        candidates.add(canonicalRow);
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // A malformed table must not make the whole document fail.
        }
    }

    private String canonicalMetadataCell(String rawCellText) {
        String source = TextNormalizer.compactForMatching(rawCellText);
        if (source.isBlank()) {
            return "";
        }

        String tableName = normalizeTableName(match(TABLE_NAME, source));
        String entityName = matchStrictEntityName(source);
        if (tableName.isBlank() && entityName.isBlank()) {
            return "";
        }

        StringBuilder canonical = new StringBuilder(128);
        if (!tableName.isBlank()) {
            canonical.append("نام جدول: ").append(tableName).append('\n');
        }
        if (!entityName.isBlank()) {
            canonical.append("نام موجودیت: ").append(entityName).append('\n');
        }
        return TextNormalizer.cleanBlock(canonical.toString());
    }

    private String matchStrictEntityName(String source) {
        Matcher matcher = STRICT_ENTITY_NAME.matcher(source == null ? "" : source);
        if (!matcher.find()) {
            return "";
        }
        String candidate = normalizePersianLabel(matcher.group(1));
        if (!containsArabicScriptLetter(candidate)
                || candidate.length() > 180
                || startsWithMetadataLabel(candidate)) {
            return "";
        }
        return candidate;
    }

    private int structuredMetadataScore(String value) {
        String compact = TextNormalizer.compactForMatching(value);
        int score = 0;
        if (!match(TABLE_NAME, compact).isBlank()) {
            score += 100;
        }
        String entity = matchStrictEntityName(compact);
        if (!entity.isBlank()) {
            score += 200;
            score += Math.min(entity.length(), 80);
        }
        return score;
    }

    private String extractDocHeaderText(HWPFDocument document) {
        LinkedHashSet<String> blocks = new LinkedHashSet<>();

        // The aggregate story range is required for section-specific legacy headers.
        collectDocHeaderRange(blocks, document.getHeaderStoryRange());

        // HeaderStories exposes first/even/odd subranges separately and is more reliable
        // for DOC files whose metadata is stored inside a header table.
        try {
            HeaderStories stories = new HeaderStories(document);
            collectDocHeaderRange(blocks, stories.getFirstHeaderSubrange());
            collectDocHeaderRange(blocks, stories.getEvenHeaderSubrange());
            collectDocHeaderRange(blocks, stories.getOddHeaderSubrange());
            collectDocHeaderRange(blocks, stories.getFirstFooterSubrange());
            collectDocHeaderRange(blocks, stories.getEvenFooterSubrange());
            collectDocHeaderRange(blocks, stories.getOddFooterSubrange());
            for (int pageNumber = 1; pageNumber <= 4; pageNumber++) {
                addTextBlock(blocks, stories.getHeader(pageNumber));
                addTextBlock(blocks, stories.getFooter(pageNumber));
            }
            collectDocHeaderRange(blocks, stories.getRange());
        } catch (RuntimeException ignored) {
            // Keep the aggregate header story and overall-range fallback below.
        }

        // Some malformed DOC files expose visible header-table text only through the
        // overall range. Keep small metadata windows instead of copying the whole body.
        try {
            collectMetadataWindows(blocks, document.getOverallRange().text());
        } catch (RuntimeException ignored) {
            // The already collected header ranges remain usable.
        }

        // Some legacy DOC files have a broken text-piece to paragraph mapping. In those
        // files HeaderStories and getOverallRange() can omit a visible header table even
        // though the raw text-piece stream still contains it. Keep only bounded metadata
        // windows from the raw stream, so normal full-corpus runs do not retain body text.
        try {
            WordExtractor extractor = new WordExtractor(document);
            extractor.setCloseFilesystem(false);
            collectMetadataWindows(blocks, extractor.getTextFromPieces());
            addTextBlock(blocks, extractor.getHeaderText());
            addTextBlock(blocks, extractor.getFooterText());
        } catch (RuntimeException ignored) {
            // Normal header ranges remain authoritative when raw extraction fails.
        }

        return TextNormalizer.cleanBlock(String.join("\n", blocks));
    }

    private void collectDocHeaderRange(Set<String> blocks, Range range) {
        if (range == null) {
            return;
        }
        addTextBlock(blocks, range.text());
        try {
            TableIterator iterator = new TableIterator(range);
            while (iterator.hasNext()) {
                Table table = iterator.next();
                StringBuilder tableText = new StringBuilder();
                for (int rowIndex = 0; rowIndex < table.numRows(); rowIndex++) {
                    TableRow row = table.getRow(rowIndex);
                    for (int cellIndex = 0; cellIndex < row.numCells(); cellIndex++) {
                        appendLine(tableText, readDocCellText(row.getCell(cellIndex)));
                    }
                }
                addTextBlock(blocks, tableText.toString());
            }
        } catch (RuntimeException ignored) {
            // A damaged header table must not make an otherwise readable DOC fail.
        }
    }

    private void collectMetadataWindows(Set<String> blocks, String rawText) {
        String text = TextNormalizer.cleanBlock(rawText);
        if (text.isBlank()) {
            return;
        }
        Matcher matcher = RAW_METADATA_LABEL.matcher(text);
        while (matcher.find()) {
            int start = Math.max(0, matcher.start() - 240);
            int end = Math.min(text.length(), matcher.end() + 720);
            addTextBlock(blocks, text.substring(start, end));
        }
    }

    private void addTextBlock(Set<String> blocks, String value) {
        String cleaned = TextNormalizer.cleanBlock(value);
        if (!cleaned.isBlank()) {
            blocks.add(cleaned);
        }
    }

    private List<List<List<String>>> readDocTables(Range bodyRange) {
        List<List<List<String>>> tables = new ArrayList<>();
        TableIterator iterator = new TableIterator(bodyRange);
        while (iterator.hasNext()) {
            Table table = iterator.next();
            List<List<String>> rows = new ArrayList<>(table.numRows());
            for (int rowIndex = 0; rowIndex < table.numRows(); rowIndex++) {
                TableRow row = table.getRow(rowIndex);
                List<String> cells = new ArrayList<>(Math.max(10, row.numCells()));
                for (int cellIndex = 0; cellIndex < row.numCells(); cellIndex++) {
                    TableCell cell = row.getCell(cellIndex);
                    cells.add(readDocCellText(cell));
                }
                rows.add(List.copyOf(cells));
            }
            tables.add(List.copyOf(rows));
        }
        return List.copyOf(tables);
    }

    private ParsedDocument parseDocx(Path sourceFile) throws IOException {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(sourceFile), 64 * 1024);
             XWPFDocument document = new XWPFDocument(input)) {
            String rawMainText = TextNormalizer.firstNonBlank(
                    DocxXmlTextExtractor.extractDocumentText(sourceFile),
                    extractDocxMainText(document)
            );
            String rawHeaderText = TextNormalizer.firstNonBlank(
                    DocxXmlTextExtractor.extractHeaderText(sourceFile),
                    extractDocxHeaderTextFallback(document)
            );
            return new ParsedDocument(
                    rawMainText,
                    rawHeaderText,
                    "",
                    readDocxTables(document)
            );
        }
    }

    private String extractDocxMainText(XWPFDocument document) {
        StringBuilder text = new StringBuilder(4096);
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            appendLine(text, paragraph.getText());
        }
        for (XWPFTable table : document.getTables()) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    appendLine(text, cell.getTextRecursively());
                }
            }
        }
        return TextNormalizer.cleanBlock(text.toString());
    }

    private String extractDocxHeaderTextFallback(XWPFDocument document) {
        StringBuilder text = new StringBuilder(1024);
        for (XWPFHeader header : document.getHeaderList()) {
            appendLine(text, header.getText());
        }
        return TextNormalizer.cleanBlock(text.toString());
    }

    private List<List<List<String>>> readDocxTables(XWPFDocument document) {
        List<List<List<String>>> tables = new ArrayList<>();
        for (XWPFTable table : document.getTables()) {
            List<List<String>> rows = new ArrayList<>(table.getRows().size());
            for (XWPFTableRow row : table.getRows()) {
                List<XWPFTableCell> sourceCells = row.getTableCells();
                List<String> cells = new ArrayList<>(Math.max(10, sourceCells.size()));
                for (XWPFTableCell cell : sourceCells) {
                    cells.add(readDocxCellText(cell));
                }
                rows.add(List.copyOf(cells));
            }
            tables.add(List.copyOf(rows));
        }
        return List.copyOf(tables);
    }

    private String readDocCellText(TableCell cell) {
        // Preserve paragraph boundaries in legacy DOC cells. A large number of old table
        // specifications stack several logical field definitions vertically inside one
        // physical Word row. Collapsing CR/LF characters to spaces made those definitions
        // indistinguishable and produced synthetic names such as
        // IDNoAccNoContractRowIdConvertType.
        List<String> paragraphs = new ArrayList<>();
        for (int paragraphIndex = 0; paragraphIndex < cell.numParagraphs(); paragraphIndex++) {
            Paragraph paragraph = cell.getParagraph(paragraphIndex);
            StringBuilder paragraphText = new StringBuilder();
            for (int runIndex = 0; runIndex < paragraph.numCharacterRuns(); runIndex++) {
                CharacterRun run = paragraph.getCharacterRun(runIndex);
                if (run.isStrikeThrough() || run.isDoubleStrikeThrough()
                        || run.isMarkedDeleted() || run.isVanished() || run.isFldVanished()) {
                    continue;
                }
                appendDocRunText(paragraphText, run);
            }
            String cleaned = TextNormalizer.cleanCell(
                    paragraphText.toString().replace('\u0007', ' ')
            );
            // Keep empty paragraphs because their vertical position is used to align
            // field name, type, length, key and description cells.
            paragraphs.add(cleaned);
        }

        if (!paragraphs.isEmpty()) {
            return TextNormalizer.joinCellParagraphs(paragraphs);
        }

        // Defensive fallback for malformed DOC cells that do not expose paragraphs.
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < cell.numCharacterRuns(); index++) {
            CharacterRun run = cell.getCharacterRun(index);
            if (run.isStrikeThrough() || run.isDoubleStrikeThrough()
                    || run.isMarkedDeleted() || run.isVanished() || run.isFldVanished()) {
                continue;
            }
            appendDocRunText(text, run);
        }
        return TextNormalizer.cleanCell(text.toString());
    }

    private void appendDocRunText(StringBuilder target, CharacterRun run) {
        try {
            if (run.isSymbol()) {
                int symbol = run.getSymbolCharacter();
                int lowByte = symbol & 0xFF;
                if (lowByte == 0xFC) {
                    target.append('✓');
                    return;
                }
                if (lowByte == 0xA8) {
                    target.append('☐');
                    return;
                }
            }
        } catch (RuntimeException ignored) {
            // Continue with the font/text fallback for damaged symbol runs.
        }

        String raw = run.text();
        if (raw == null || raw.isEmpty()) {
            return;
        }
        String fontName = "";
        try {
            fontName = TextNormalizer.cleanCell(run.getFontName());
        } catch (RuntimeException ignored) {
            // Keep raw text when a damaged font table cannot be resolved.
        }
        if (isWingdings(fontName)) {
            raw = raw.replace('(', '✓')
                    .replace('ü', '✓')
                    .replace('þ', '✓');
        }
        target.append(raw);
    }

    private void appendDocxRunText(StringBuilder target, XWPFRun run) {
        String text = run.text();
        if (text != null && !text.isEmpty()) {
            target.append(text);
        }

        String xml;
        try {
            xml = run.getCTR().xmlText();
        } catch (RuntimeException ignored) {
            return;
        }
        Matcher symbols = DOCX_SYMBOL_ELEMENT.matcher(xml);
        while (symbols.find()) {
            String element = symbols.group();
            String font = matchXmlAttribute(DOCX_SYMBOL_FONT, element);
            String character = matchXmlAttribute(DOCX_SYMBOL_CHAR, element)
                    .replaceFirst("(?i)^0x", "")
                    .toUpperCase(Locale.ROOT);
            if (isWingdings(font) && (character.equals("F0FC") || character.equals("FC"))) {
                target.append('✓');
            } else if (isWingdings(font) && (character.equals("F0A8") || character.equals("A8"))) {
                target.append('☐');
            }
        }
    }

    private String matchXmlAttribute(Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source == null ? "" : source);
        return matcher.find() ? TextNormalizer.cleanCell(matcher.group(1)) : "";
    }

    private boolean isWingdings(String fontName) {
        String normalized = TextNormalizer.cleanCell(fontName).toUpperCase(Locale.ROOT);
        return normalized.startsWith("WINGDINGS");
    }

    private String readDocxCellText(XWPFTableCell cell) {
        List<String> paragraphs = new ArrayList<>();
        for (XWPFParagraph paragraph : cell.getParagraphs()) {
            StringBuilder paragraphText = new StringBuilder();
            List<XWPFRun> runs = paragraph.getRuns();
            if (runs.isEmpty()) {
                paragraphText.append(paragraph.getText());
            } else {
                for (XWPFRun run : runs) {
                    if (run.isStrikeThrough() || run.isDoubleStrikeThrough()) {
                        continue;
                    }
                    appendDocxRunText(paragraphText, run);
                }
            }
            // Keep empty paragraphs because old Word grids use vertical paragraph
            // positions to align several logical field definitions inside one row.
            paragraphs.add(TextNormalizer.cleanCell(paragraphText.toString()));
        }
        for (XWPFTable nestedTable : cell.getTables()) {
            for (XWPFTableRow nestedRow : nestedTable.getRows()) {
                for (XWPFTableCell nestedCell : nestedRow.getTableCells()) {
                    paragraphs.add(TextNormalizer.cleanCell(readDocxCellText(nestedCell)));
                }
            }
        }
        return TextNormalizer.joinCellParagraphs(paragraphs);
    }

    private void appendLine(StringBuilder target, String value) {
        if (value != null && !value.isBlank()) {
            target.append(value).append('\n');
        }
    }

    private ParsedMetadata parseMetadata(String headerText,
                                         String mainText,
                                         String authoritativeMetadata,
                                         String sourceFileName) {
        String rawSource = TextNormalizer.cleanBlock(
                (headerText == null ? "" : headerText) + "\n" + (mainText == null ? "" : mainText)
        );
        String source = TextNormalizer.compactForMatching(rawSource);
        String fileNameTable = extractTableNameFromFileName(sourceFileName);

        // The bounded raw-container scanner returns only an explicit table/entity pair.
        // Keep it separate from the noisy HWPF aggregate. Mixing both sources first can
        // cause a later page header or a field tail to win during generic candidate ranking.
        // When the raw pair matches the table token in the file name and contains a usable
        // Persian entity title, it is the authoritative table-title source.
        TableEntityPair authoritativePair = parseAuthoritativeTableEntityPair(
                authoritativeMetadata, fileNameTable
        );

        List<TableEntityPair> metadataPairs = collectTableEntityPairs(source);
        TableEntityPair selectedPair = authoritativePair != null
                ? authoritativePair
                : selectTableEntityPair(metadataPairs, fileNameTable);

        String technicalHeaderTableNameRaw = selectedPair == null
                ? match(TABLE_NAME, source)
                : selectedPair.rawTableName();
        String descriptiveHeaderTableNameRaw = selectedPair == null
                ? match(TABLE_NAME_RAW, source)
                : selectedPair.rawTableName();
        String headerTableNameRaw = TextNormalizer.firstNonBlank(
                technicalHeaderTableNameRaw,
                descriptiveHeaderTableNameRaw
        );
        String headerTableName = normalizeTableName(headerTableNameRaw);
        String rawEntityName = selectedPair == null
                ? matchBestEntityName(source)
                : selectedPair.rawEntityName();
        boolean explicitEntityLabel = selectedPair == null
                ? ENTITY_LABEL_PRESENT.matcher(source).find()
                : selectedPair.entityLabelPresent();
        String entityTableName = extractTechnicalTableNameFromEntity(rawEntityName);
        String entityName = normalizeEntityMetadataValue(rawEntityName);

        String tableName = headerTableName;
        boolean fromEntityName = false;
        boolean fromFilename = false;

        if (tableName.isBlank() && !entityTableName.isBlank()) {
            tableName = entityTableName;
            fromEntityName = true;
        }
        if (!fileNameTable.isBlank() && shouldPreferFileNameTable(tableName, fileNameTable)) {
            boolean changed = !fileNameTable.equalsIgnoreCase(tableName);
            tableName = fileNameTable;
            fromFilename = changed || (headerTableName.isBlank() && entityTableName.isBlank());
            fromEntityName = false;
        }

        String persianTableName = "";
        String persianTableNameSource = "NOT_PRESENT";

        String explicitEntityTitle = normalizePersianLabel(rawEntityName);
        if (containsArabicScriptLetter(explicitEntityTitle)
                && !isGenericPersianLabel(explicitEntityTitle)) {
            persianTableName = explicitEntityTitle;
            persianTableNameSource = "EXPLICIT_ENTITY_HEADER";
        }

        if (persianTableName.isBlank()) {
            String descriptiveTitle = resolvePersianTableName(descriptiveHeaderTableNameRaw, "");
            if (!descriptiveTitle.isBlank()) {
                persianTableName = descriptiveTitle;
                persianTableNameSource = "DESCRIPTIVE_TABLE_HEADER";
            }
        }
        if (persianTableName.isBlank()) {
            String titleAfterTableLabel = matchPersianTitleAfterTableLabel(rawSource);
            if (!titleAfterTableLabel.isBlank()) {
                persianTableName = titleAfterTableLabel;
                persianTableNameSource = "TABLE_LABEL_FALLBACK";
            }
        }
        if (persianTableName.isBlank() && !explicitEntityLabel) {
            String legacyTitle = TextNormalizer.firstNonBlank(
                    matchLegacyUnlabelledPersianTitle(mainText, tableName),
                    matchLegacyUnlabelledPersianTitle(rawSource, tableName)
            );
            if (!legacyTitle.isBlank()) {
                persianTableName = legacyTitle;
                persianTableNameSource = "LEGACY_UNLABELLED_TITLE";
                if (entityName.isBlank()) {
                    entityName = legacyTitle;
                }
            }
        }
        if (persianTableName.isBlank()) {
            String standaloneTitle = matchStandalonePersianDocumentTitle(rawSource, tableName);
            if (!standaloneTitle.isBlank()) {
                persianTableName = standaloneTitle;
                persianTableNameSource = "STANDALONE_TITLE";
            }
        }

        boolean persianCandidateRejected = persianTableName.isBlank()
                && ((!TextNormalizer.cleanCell(rawEntityName).isBlank()
                        && containsArabicScriptLetter(rawEntityName))
                || hasRejectedLegacyPersianCandidate(mainText, tableName)
                || hasRejectedLegacyPersianCandidate(rawSource, tableName));
        if (entityName.isBlank() && !persianTableName.isBlank()) {
            entityName = persianTableName;
        }

        Metadata metadata = new Metadata(
                match(DOCUMENT_TYPE, source),
                match(SYSTEM_NAME, source),
                tableName,
                persianTableName,
                persianTableNameSource,
                entityName,
                normalizeDate(match(CREATED_DATE, source)),
                normalizeDate(match(MODIFIED_DATE, source)),
                headerText
        );
        return new ParsedMetadata(
                metadata,
                fromFilename,
                fromEntityName,
                headerTableNameRaw,
                rawEntityName,
                fileNameTable,
                persianCandidateRejected
        );
    }

    private List<TableEntityPair> collectTableEntityPairs(String source) {
        List<TableOccurrence> occurrences = new ArrayList<>();
        Matcher matcher = TABLE_NAME.matcher(source == null ? "" : source);
        while (matcher.find()) {
            occurrences.add(new TableOccurrence(matcher.start(), matcher.end(), matcher.group(1)));
        }
        if (occurrences.isEmpty()) {
            return List.of();
        }

        List<TableEntityPair> pairs = new ArrayList<>(occurrences.size());
        for (int index = 0; index < occurrences.size(); index++) {
            TableOccurrence occurrence = occurrences.get(index);
            int segmentEnd = index + 1 < occurrences.size()
                    ? occurrences.get(index + 1).start()
                    : source.length();
            segmentEnd = Math.min(segmentEnd, occurrence.end() + 1800);
            String segment = source.substring(occurrence.end(), segmentEnd);
            Matcher fieldHeaderBoundary = FIELD_HEADER_BOUNDARY.matcher(segment);
            if (fieldHeaderBoundary.find()) {
                segment = segment.substring(0, fieldHeaderBoundary.start());
            }
            boolean entityLabelPresent = ENTITY_LABEL_PRESENT.matcher(segment).find();
            String rawEntity = entityLabelPresent ? matchBestEntityName(segment) : "";
            pairs.add(new TableEntityPair(
                    occurrence.rawTableName(),
                    normalizeTableName(occurrence.rawTableName()),
                    rawEntity,
                    entityLabelPresent
            ));
        }
        return List.copyOf(pairs);
    }

    private TableEntityPair parseAuthoritativeTableEntityPair(String authoritativeMetadata,
                                                                String fileNameTable) {
        String source = TextNormalizer.compactForMatching(authoritativeMetadata);
        if (source.isBlank()) {
            return null;
        }

        String rawTableName = match(TABLE_NAME, source);
        String normalizedTableName = normalizeTableName(rawTableName);
        if (normalizedTableName.isBlank()) {
            return null;
        }
        if (fileNameTable != null && !fileNameTable.isBlank()
                && !fileNameTable.equalsIgnoreCase(normalizedTableName)) {
            return null;
        }

        String rawEntityName = match(RAW_CANONICAL_ENTITY_NAME, source);
        String normalizedEntityName = normalizePersianLabel(rawEntityName);
        if (normalizedEntityName.isBlank()
                || !containsArabicScriptLetter(normalizedEntityName)
                || isGenericPersianLabel(normalizedEntityName)) {
            return null;
        }

        return new TableEntityPair(
                rawTableName,
                normalizedTableName,
                normalizedEntityName,
                true
        );
    }

    private TableEntityPair selectTableEntityPair(List<TableEntityPair> pairs, String fileNameTable) {
        if (pairs == null || pairs.isEmpty()) {
            return null;
        }

        TableEntityPair bestMatchingPair = null;
        int bestMatchingScore = Integer.MIN_VALUE;
        if (fileNameTable != null && !fileNameTable.isBlank()) {
            for (TableEntityPair pair : pairs) {
                if (!fileNameTable.equalsIgnoreCase(pair.normalizedTableName())) {
                    continue;
                }
                int score = tableEntityPairScore(pair);
                if (bestMatchingPair == null || score > bestMatchingScore) {
                    bestMatchingPair = pair;
                    bestMatchingScore = score;
                }
            }
            if (bestMatchingPair != null) {
                return bestMatchingPair;
            }
        }

        TableEntityPair bestPair = pairs.get(0);
        int bestScore = tableEntityPairScore(bestPair);
        for (int index = 1; index < pairs.size(); index++) {
            TableEntityPair candidate = pairs.get(index);
            int score = tableEntityPairScore(candidate);
            if (score > bestScore) {
                bestPair = candidate;
                bestScore = score;
            }
        }
        return bestPair;
    }

    /**
     * Ranks duplicate metadata pairs emitted by old DOC containers.
     *
     * <p>HWPF can expose an early, truncated header pair while the bounded raw-container
     * scanner later contributes the complete pair for the same technical table. Returning
     * the first pair that merely contains an entity label discards the complete Persian
     * title. Prefer a usable normalized Persian entity value, then a technical entity value,
     * while retaining deterministic source order for equal candidates.</p>
     */
    private int tableEntityPairScore(TableEntityPair pair) {
        if (pair == null) {
            return Integer.MIN_VALUE;
        }

        int score = pair.entityLabelPresent() ? 100 : 0;
        String rawEntity = TextNormalizer.cleanCell(pair.rawEntityName());
        if (rawEntity.isBlank()) {
            return score - 100;
        }

        String normalizedPersianTitle = normalizePersianLabel(rawEntity);
        if (!normalizedPersianTitle.isBlank()) {
            score += 2_000;
            score += Math.min(normalizedPersianTitle.length(), 180);
            score += Math.min(normalizedPersianTitle.split("\\s+").length, 20) * 10;
            return score;
        }

        if (TextNormalizer.isTechnicalIdentifier(rawEntity)) {
            score += 500;
        } else if (containsArabicScriptLetter(rawEntity)) {
            score -= 250;
        }
        return score;
    }

    private String normalizeEntityMetadataValue(String rawEntityName) {
        String cleaned = TextNormalizer.cleanCell(rawEntityName);
        if (cleaned.isBlank()) {
            return "";
        }
        if (TextNormalizer.isTechnicalIdentifier(cleaned)) {
            return cleaned;
        }
        return normalizePersianLabel(cleaned);
    }

    private String matchBestEntityName(String source) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        collectMatches(candidates, ENTITY_NAME, source);
        collectMatches(candidates, ENTITY_NAME_FLEXIBLE, source);

        String best = "";
        int bestScore = Integer.MIN_VALUE;
        for (String candidate : candidates) {
            String cleaned = TextNormalizer.cleanCell(candidate)
                    .replaceFirst("^[\\s._:：-]+", "")
                    .replaceFirst("[\\s._:：-]+$", "");
            int score = entityCandidateScore(cleaned);
            if (score > bestScore) {
                best = cleaned;
                bestScore = score;
            }
        }
        return bestScore > 0 ? best : "";
    }

    private void collectMatches(Set<String> target, Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source == null ? "" : source);
        while (matcher.find()) {
            String candidate = TextNormalizer.cleanCell(matcher.group(1));
            if (!candidate.isBlank()) {
                target.add(candidate);
            }
        }
    }

    private int entityCandidateScore(String value) {
        if (value == null || value.isBlank()) {
            return Integer.MIN_VALUE;
        }
        String compact = TextNormalizer.compactForMatching(value);
        if (isGenericPersianLabel(compact) || startsWithMetadataLabel(compact)) {
            return -1000;
        }

        int score = 0;
        if (TextNormalizer.isTechnicalIdentifier(compact)) {
            score += 90;
        }
        if (containsArabicScriptLetter(compact)) {
            score += 120;
        }
        if (compact.length() >= 2 && compact.length() <= 180) {
            score += 25;
        } else if (compact.length() > 320) {
            score -= 120;
        }

        String noiseProbe = compact.toUpperCase(Locale.ROOT);
        if (compact.contains("نام جدول")
                || compact.contains("نام صفت")
                || compact.contains("گزارش طراحی")
                || compact.contains("گزارش طراحي")
                || compact.contains("پروژه سامانه")
                || compact.contains("تاریخ ایجاد")
                || noiseProbe.contains("NUMPAGES")
                || noiseProbe.contains("DOCPROPERTY")) {
            score -= 250;
        }
        return score;
    }

    private String resolvePersianTableName(String rawTableName, String rawEntityName) {
        // The entity label is the authoritative Persian title in the normal template.
        // Prefer it over a descriptive TABLE_NAME_RAW candidate, because malformed Word
        // field codes can make TABLE_NAME_RAW swallow "<technical> نام موجودیت ...".
        String entityLabel = normalizePersianLabel(rawEntityName);
        if (containsArabicScriptLetter(entityLabel) && !isGenericPersianLabel(entityLabel)) {
            return entityLabel;
        }

        String rawTableLabel = TextNormalizer.compactForMatching(rawTableName);
        String tableLabel = normalizePersianLabel(rawTableName);
        if (startsWithArabicScriptLetter(rawTableLabel)
                && containsArabicScriptLetter(tableLabel)
                && !isGenericPersianLabel(tableLabel)) {
            return tableLabel;
        }
        return "";
    }

    String normalizePersianLabel(String value) {
        String withoutControls = CONTROL_CHARACTER.matcher(value == null ? "" : value).replaceAll(" ");
        String normalized = TextNormalizer.compactForMatching(withoutControls)
                .replaceFirst("^[\\s._:：-]+", "")
                .replaceFirst("[\\s._:：-]+$", "");
        if (normalized.isBlank() || startsWithMetadataLabel(normalized)) {
            return "";
        }

        // Repair reordered/concatenated legacy headers such as:
        //   CTSMSDATAINFOSHORTARنام موجودیت لاگ اطلاعات ...
        //   SpecialIban موجودیت
        Matcher entityLabel = EMBEDDED_ENTITY_LABEL.matcher(normalized);
        if (entityLabel.find()) {
            String prefix = normalized.substring(0, entityLabel.start()).trim();
            String compactPrefix = prefix.replaceAll("\\s+", "");
            if (prefix.isBlank() || TextNormalizer.isTechnicalIdentifier(compactPrefix)) {
                normalized = normalized.substring(entityLabel.end()).trim();
            }
        }
        if (normalized.isBlank()) {
            return "";
        }

        String historyTitle = normalizeLegitimateHistoryTitle(normalized);
        if (!historyTitle.isBlank()) {
            return historyTitle;
        }

        Matcher stop = PERSIAN_LABEL_STOP.matcher(normalized);
        if (stop.find()) {
            normalized = normalized.substring(0, stop.start()).trim();
        }
        Matcher structuralTail = PERSIAN_STRUCTURAL_TAIL.matcher(normalized);
        if (structuralTail.find()) {
            normalized = normalized.substring(0, structuralTail.start()).trim();
        }
        normalized = normalized
                .replaceFirst("(?iu)\\s+(?:صفحه\\s*[:：]?\\s*[0-9۰-۹٠-٩]+\\s*)?از\\s*[0-9۰-۹٠-٩]+\\s*$", "")
                .replaceFirst("^[\\s._:：-]+", "")
                .replaceFirst("[\\s._:：-]+$", "");
        normalized = stripFieldDefinitionTail(normalized);

        if (normalized.length() > 180
                || normalized.isBlank()
                || !startsWithArabicScriptLetter(normalized)
                || PERSIAN_INVALID_METADATA_START.matcher(normalized).matches()
                || TextNormalizer.isTechnicalIdentifier(normalized)
                || isGenericPersianLabel(normalized)) {
            return "";
        }
        if (TextNormalizer.compactForMatching(normalized).matches("(?iu).*\\b(?:پیش|پيش)\\s*فرض\\b.*")) {
            return "";
        }
        return normalized;
    }

    private String normalizeLegitimateHistoryTitle(String value) {
        String candidate = TextNormalizer.cleanCell(value)
                .replaceFirst("^[\\s._:：-]+", "")
                .replaceFirst("[\\s._:：-]+$", "");
        if (!PERSIAN_HISTORY_TITLE.matcher(candidate).matches()) {
            return "";
        }

        candidate = stripFieldDefinitionTail(candidate);
        if (candidate.isBlank()
                || candidate.length() > 140
                || !startsWithArabicScriptLetter(candidate)
                || PERSIAN_HISTORY_GRID.matcher(candidate).find()
                || PERSIAN_METADATA_NOISE.matcher(candidate).find()
                || PERSIAN_EMBEDDED_METADATA_LABEL.matcher(candidate).find()
                || PERSIAN_DATE_TOKEN.matcher(candidate).find()
                || PERSIAN_CHANGE_LOG_NOISE.matcher(candidate).find()) {
            return "";
        }
        return candidate;
    }

    private String stripFieldDefinitionTail(String value) {
        String normalized = TextNormalizer.cleanCell(value);
        if (!containsArabicScriptLetter(normalized)) {
            return normalized;
        }

        Matcher fieldTail = PERSIAN_FIELD_TAIL.matcher(normalized);
        if (fieldTail.find()) {
            String prefix = TextNormalizer.cleanCell(normalized.substring(0, fieldTail.start()));
            if (containsArabicScriptLetter(prefix) && prefix.length() >= 2) {
                normalized = prefix;
            }
        }

        Matcher matcher = LATIN_TECHNICAL_TOKEN.matcher(normalized);
        List<Integer> starts = new ArrayList<>();
        while (matcher.find()) {
            starts.add(matcher.start());
        }
        if (starts.size() >= 3) {
            String prefix = TextNormalizer.cleanCell(normalized.substring(0, starts.get(0)));
            if (containsArabicScriptLetter(prefix) && prefix.length() >= 2) {
                return prefix;
            }
        }
        return normalized;
    }

    private MetadataSanitization sanitizeMetadataAgainstColumns(Metadata metadata,
                                                                  List<ColumnDefinition> columns,
                                                                  String rawMainText) {
        String original = TextNormalizer.cleanCell(metadata.persianTableName());
        String cleaned = stripColumnContamination(original, columns);
        String cleanedSource = metadata.persianTableNameSource();
        boolean rejected = false;

        if (!cleaned.isBlank() && !isReliablePersianTableName(cleaned, columns)) {
            cleaned = "";
            rejected = true;
        }

        String standaloneTitle = matchStandalonePersianDocumentTitle(
                rawMainText, metadata.tableName(), columns
        );
        if (!standaloneTitle.isBlank()
                && (cleaned.isBlank() || isStrongStandaloneTitle(standaloneTitle))) {
            cleaned = standaloneTitle;
            cleanedSource = "STANDALONE_TITLE";
            rejected = false;
        }

        if (original.equals(cleaned) && !rejected) {
            return new MetadataSanitization(metadata, false);
        }

        String entityName = TextNormalizer.cleanCell(metadata.entityName());
        if (entityName.equals(original) || containsArabicScriptLetter(entityName)) {
            entityName = cleaned;
        }
        Metadata repaired = new Metadata(
                metadata.documentType(),
                metadata.systemName(),
                metadata.tableName(),
                cleaned,
                cleaned.isBlank() ? "REJECTED" : cleanedSource,
                entityName,
                metadata.createdDateRaw(),
                metadata.modifiedDateRaw(),
                metadata.headerRaw()
        );
        return new MetadataSanitization(repaired, rejected && cleaned.isBlank());
    }

    boolean isReliablePersianTableName(String value, List<ColumnDefinition> columns) {
        String candidate = normalizePersianLabel(value);
        if (candidate.isBlank() || candidate.length() < 3 || candidate.length() > 140) {
            return false;
        }
        if (PERSIAN_METADATA_NOISE.matcher(candidate).find()
                || PERSIAN_DOMAIN_VALUE_START.matcher(candidate).find()
                || candidate.matches("^[\\s=:/\\\\-].*")
                || PERSIAN_EMBEDDED_METADATA_LABEL.matcher(candidate).find()
                || PERSIAN_HISTORY_GRID.matcher(candidate).find()
                || PERSIAN_TRUNCATED_HISTORY.matcher(candidate).find()
                || (PERSIAN_DATE_TOKEN.matcher(candidate).find()
                    && PERSIAN_CHANGE_LOG_NOISE.matcher(candidate).find())) {
            return false;
        }

        String[] words = candidate.split("\\s+");
        if (words.length > 1 && words[0].codePointCount(0, words[0].length()) <= 1) {
            return false;
        }

        if (columns == null || columns.isEmpty()) {
            return true;
        }

        String comparableCandidate = comparablePersian(candidate);
        String upperCandidate = candidate.toUpperCase(Locale.ROOT);
        for (ColumnDefinition column : columns) {
            String fieldName = TextNormalizer.cleanCell(column.fieldName());
            if (!fieldName.isBlank()
                    && upperCandidate.matches(".*(?<![A-Z0-9_$#])"
                    + Pattern.quote(fieldName.toUpperCase(Locale.ROOT))
                    + "(?![A-Z0-9_$#]).*")) {
                return false;
            }

            String fieldTitle = comparablePersian(column.persianTitle());
            if (fieldTitle.length() < 4) {
                continue;
            }
            if (comparableCandidate.equals(fieldTitle)
                    || fieldTitle.contains(comparableCandidate)
                    || (comparableCandidate.contains(fieldTitle)
                    && comparableCandidate.length() - fieldTitle.length() <= 20)) {
                return false;
            }
        }
        return true;
    }

    private String comparablePersian(String value) {
        return TextNormalizer.compactForMatching(value)
                .replaceAll("[\\s._:：/\\-]+", "")
                .toLowerCase(Locale.ROOT);
    }

    private boolean isStrongStandaloneTitle(String value) {
        String compact = TextNormalizer.compactForMatching(value);
        return compact.matches("(?iu)^(?:جدول|تاریخچه|تاريخچه)(?:\\s|$).*");
    }

    private String stripColumnContamination(String value, List<ColumnDefinition> columns) {
        String normalized = normalizePersianLabel(value);
        if (normalized.isBlank() || columns == null || columns.isEmpty()) {
            return normalized;
        }

        int cutAt = normalized.length();
        Matcher tokenMatcher = LATIN_WORD.matcher(normalized);
        while (tokenMatcher.find()) {
            String token = tokenMatcher.group().toUpperCase(Locale.ROOT);
            if (matchesColumnToken(token, columns)) {
                cutAt = Math.min(cutAt, tokenMatcher.start());
                break;
            }
        }
        if (cutAt < normalized.length()) {
            normalized = TextNormalizer.cleanCell(normalized.substring(0, cutAt));
            normalized = stripTrailingPersianFieldFragment(normalized, columns);
        }
        return normalizePersianLabel(normalized);
    }

    private boolean matchesColumnToken(String token, List<ColumnDefinition> columns) {
        for (ColumnDefinition column : columns) {
            String fieldName = TextNormalizer.cleanCell(column.fieldName()).toUpperCase(Locale.ROOT);
            if (fieldName.isBlank()) {
                continue;
            }
            if (token.equals(fieldName)
                    || (token.length() >= 5 && fieldName.endsWith(token))
                    || (fieldName.length() >= 5 && token.endsWith(fieldName))) {
                return true;
            }
        }
        return false;
    }

    private String stripTrailingPersianFieldFragment(String value, List<ColumnDefinition> columns) {
        String cleaned = TextNormalizer.cleanCell(value);
        int separator = cleaned.lastIndexOf(' ');
        String lastWord = separator >= 0 ? cleaned.substring(separator + 1) : cleaned;
        if (lastWord.length() < 2) {
            return cleaned;
        }
        for (ColumnDefinition column : columns) {
            String title = TextNormalizer.cleanCell(column.persianTitle());
            for (String word : title.split("\\s+")) {
                if (word.length() > lastWord.length() && word.endsWith(lastWord)) {
                    return separator >= 0
                            ? TextNormalizer.cleanCell(cleaned.substring(0, separator))
                            : "";
                }
            }
        }
        return cleaned;
    }

    private String matchStandalonePersianDocumentTitle(String rawSource, String tableName) {
        return matchStandalonePersianDocumentTitle(rawSource, tableName, List.of());
    }

    private String matchStandalonePersianDocumentTitle(String rawSource,
                                                        String tableName,
                                                        List<ColumnDefinition> columns) {
        if (rawSource == null || rawSource.isBlank()) {
            return "";
        }
        String[] rawLines = TextNormalizer.cleanBlock(rawSource).split("\\R");
        List<String> lines = new ArrayList<>();
        for (String rawLine : rawLines) {
            String line = TextNormalizer.cleanCell(rawLine);
            if (!line.isBlank()) {
                lines.add(line);
            }
            if (lines.size() >= 180) {
                break;
            }
        }

        int firstFieldIndex = findFirstFieldDefinitionIndex(lines, columns);
        int scanLimit = firstFieldIndex >= 0
                ? firstFieldIndex
                : Math.min(lines.size(), 40);

        String normalizedTableName = normalizeComparableTechnicalName(tableName);
        int tableIndex = -1;
        for (int index = 0; index < scanLimit; index++) {
            if (normalizedTableName.equals(normalizeComparableTechnicalName(lines.get(index)))) {
                tableIndex = index;
                break;
            }
        }

        String best = "";
        int bestScore = Integer.MIN_VALUE;
        for (int index = 0; index < scanLimit; index++) {
            String candidate = normalizePersianLabel(lines.get(index));
            if (candidate.isBlank() || candidate.length() > 100) {
                continue;
            }
            if (!isReliablePersianTableName(candidate, columns)) {
                continue;
            }
            if (candidate.matches("(?iu)^سند\\s+تعریف\\s+جدول.*")
                    || candidate.matches("(?iu)^سند\\s+تعريف\\s+جدول.*")) {
                continue;
            }
            String compact = TextNormalizer.compactForMatching(candidate);
            if (compact.matches("(?iu).*(?:^|\\s)[0-9۰-۹٠-٩]+\\s*[-:=].*")) {
                continue;
            }

            int score = 0;
            if (compact.startsWith("جدول ") || compact.equals("جدول")) {
                score += 110;
            }
            if (compact.startsWith("تاریخچه ") || compact.startsWith("تاريخچه ")) {
                score += 100;
            }
            if (compact.contains("پارامتر") || compact.contains("اطلاعات")
                    || compact.contains("فایل") || compact.contains("فايل")
                    || compact.contains("گزارش") || compact.contains("سرمایه")
                    || compact.contains("سرمايه")) {
                score += 35;
            }
            if (tableIndex >= 0) {
                int distance = Math.abs(index - tableIndex);
                if (distance <= 4) {
                    score += 40;
                } else if (distance <= 12) {
                    score += 20;
                }
            }
            if (index <= 5) {
                score += 25;
            }
            if (candidate.split("\\s+").length >= 2) {
                score += 10;
            }
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return bestScore >= 90 ? best : "";
    }

    private int findFirstFieldDefinitionIndex(List<String> lines,
                                              List<ColumnDefinition> columns) {
        if (lines == null || lines.isEmpty()) {
            return -1;
        }
        for (int index = 0; index < lines.size(); index++) {
            if (looksLikeFieldDefinitionStart(lines, index)) {
                return index;
            }
            if (columns == null || columns.isEmpty()) {
                continue;
            }
            String comparableLine = comparablePersian(lines.get(index));
            String technicalLine = normalizeComparableTechnicalName(lines.get(index));
            for (ColumnDefinition column : columns) {
                String fieldName = normalizeComparableTechnicalName(column.fieldName());
                if (!fieldName.isBlank() && fieldName.equals(technicalLine)) {
                    return Math.max(0, index - 1);
                }
                String fieldTitle = comparablePersian(column.persianTitle());
                if (!fieldTitle.isBlank() && fieldTitle.equals(comparableLine)) {
                    return index;
                }
            }
        }
        return -1;
    }

    private String matchPersianTitleAfterTableLabel(String rawSource) {
        if (rawSource == null || rawSource.isBlank()) {
            return "";
        }
        String[] lines = TextNormalizer.cleanBlock(rawSource).split("\\R");
        int limit = Math.min(lines.length, 120);
        for (int index = 0; index < limit; index++) {
            String line = TextNormalizer.cleanCell(lines[index]);
            if (!line.matches("(?iu).*نام\\s*جدول\\s*[:：]?.*")) {
                continue;
            }
            for (int candidateIndex = index; candidateIndex < Math.min(limit, index + 3); candidateIndex++) {
                if (candidateIndex > index && looksLikeFieldDefinitionStart(lines, candidateIndex)) {
                    continue;
                }
                String candidateLine = TextNormalizer.cleanCell(lines[candidateIndex]);
                candidateLine = candidateLine.replaceFirst(
                        "(?iu)^SUBJECT\\b.*?MERGEFORMAT\\s*",
                        ""
                );
                if (candidateIndex == index) {
                    candidateLine = candidateLine.replaceFirst(
                            "(?iu)^.*?نام\\s*جدول\\s*[:：]?\\s*[A-Za-z][A-Za-z0-9_$#.\\s]*",
                            ""
                    );
                }
                String candidate = normalizePersianLabel(candidateLine);
                if (containsArabicScriptLetter(candidate)
                        && !isGenericPersianLabel(candidate)
                        && !startsWithMetadataLabel(candidate)
                        && candidate.length() <= 180) {
                    return candidate;
                }
            }
        }
        return "";
    }

    private String matchLegacyUnlabelledPersianTitle(String rawSource, String tableName) {
        String normalizedTableName = normalizeComparableTechnicalName(tableName);
        if (rawSource == null || rawSource.isBlank() || normalizedTableName.isBlank()) {
            return "";
        }

        String[] rawLines = TextNormalizer.cleanBlock(rawSource).split("\\R");
        List<String> lines = new ArrayList<>();
        for (String rawLine : rawLines) {
            String line = TextNormalizer.cleanCell(rawLine);
            if (!line.isBlank()) {
                lines.add(line);
            }
            if (lines.size() >= 80) {
                break;
            }
        }

        for (int first = 0; first < lines.size(); first++) {
            if (!normalizedTableName.equals(normalizeComparableTechnicalName(lines.get(first)))) {
                continue;
            }
            int candidateLimit = Math.min(lines.size(), first + 4);
            for (int candidateIndex = first + 1; candidateIndex < candidateLimit; candidateIndex++) {
                if (looksLikeFieldDefinitionStart(lines, candidateIndex)) {
                    continue;
                }
                String candidate = normalizePersianLabel(lines.get(candidateIndex));
                if (containsArabicScriptLetter(candidate)
                        && !isGenericPersianLabel(candidate)
                        && !startsWithMetadataLabel(candidate)
                        && candidate.length() <= 180) {
                    return candidate;
                }
            }
        }
        return "";
    }

    private boolean hasRejectedLegacyPersianCandidate(String rawSource, String tableName) {
        String normalizedTableName = normalizeComparableTechnicalName(tableName);
        if (rawSource == null || rawSource.isBlank() || normalizedTableName.isBlank()) {
            return false;
        }
        String[] rawLines = TextNormalizer.cleanBlock(rawSource).split("\\R");
        List<String> lines = new ArrayList<>();
        for (String rawLine : rawLines) {
            String line = TextNormalizer.cleanCell(rawLine);
            if (!line.isBlank()) {
                lines.add(line);
            }
            if (lines.size() >= 80) {
                break;
            }
        }
        for (int index = 0; index < lines.size(); index++) {
            if (!normalizedTableName.equals(normalizeComparableTechnicalName(lines.get(index)))) {
                continue;
            }
            int limit = Math.min(lines.size(), index + 4);
            for (int candidateIndex = index + 1; candidateIndex < limit; candidateIndex++) {
                if (looksLikeFieldDefinitionStart(lines, candidateIndex)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean looksLikeFieldDefinitionStart(String[] lines, int candidateIndex) {
        List<String> compact = new ArrayList<>(3);
        for (int index = candidateIndex; index < lines.length && compact.size() < 3; index++) {
            String line = TextNormalizer.cleanCell(lines[index]);
            if (!line.isBlank()) {
                compact.add(line);
            }
        }
        return looksLikeFieldDefinitionStart(compact, 0);
    }

    private boolean looksLikeFieldDefinitionStart(List<String> lines, int candidateIndex) {
        if (candidateIndex < 0 || candidateIndex + 2 >= lines.size()) {
            return false;
        }
        String candidate = TextNormalizer.cleanCell(lines.get(candidateIndex));
        String fieldName = TextNormalizer.cleanCell(lines.get(candidateIndex + 1));
        String type = TextNormalizer.cleanCell(lines.get(candidateIndex + 2));
        return containsArabicScriptLetter(candidate)
                && TextNormalizer.isTechnicalFieldName(fieldName)
                && ColumnLayoutResolver.looksLikeDataTypeValue(type);
    }

    private String normalizeComparableTechnicalName(String value) {
        String cleaned = TextNormalizer.cleanCell(value);
        if (cleaned.isBlank()) {
            return "";
        }
        String compact = cleaned.replaceAll("\\s+", "");
        return TextNormalizer.isTechnicalIdentifier(compact)
                ? compact.toUpperCase(Locale.ROOT)
                : "";
    }

    private boolean startsWithMetadataLabel(String value) {
        String compact = TextNormalizer.compactForMatching(value);
        if (compact.isBlank()) {
            return false;
        }
        String upper = compact.toUpperCase(Locale.ROOT);
        return compact.matches("(?iu)^(?:تاریخ|تاريخ|صفحه|فرم|مدل|سیستم|سيستم|نام\\s*سامانه|"
                + "نام\\s*جدول|نام\\s*صفت|نام\\s*فیلد|نام\\s*فيلد|گزارش|پروژه|"
                + "آخرین|آخرين|طبقه\\s*بندی|طبقه\\s*بندي)(?:\\s|:|：|$).*")
                || upper.startsWith("DOCPROPERTY")
                || upper.startsWith("SUBJECT")
                || upper.startsWith("MERGEFORMAT")
                || upper.startsWith("PAGE ")
                || upper.startsWith("NUMPAGES");
    }

    private boolean startsWithArabicScriptLetter(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.codePoints()
                .filter(codePoint -> Character.isLetter(codePoint))
                .findFirst()
                .stream()
                .anyMatch(codePoint -> Character.UnicodeScript.of(codePoint)
                        == Character.UnicodeScript.ARABIC);
    }

    private boolean containsArabicScriptLetter(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.codePoints().anyMatch(codePoint ->
                Character.isLetter(codePoint)
                        && Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.ARABIC
        );
    }

    private boolean isGenericPersianLabel(String value) {
        String compact = TextNormalizer.compactForMatching(value);
        return compact.equals("نام")
                || compact.equals("جدول")
                || compact.equals("نام جدول")
                || compact.equals("نام موجودیت")
                || compact.equals("موجودیت")
                || compact.equals("شرح پروژه")
                || compact.equals("نام طراح")
                || compact.equals("تاریخ اصلاح")
                || compact.equals("تاريخ اصلاح");
    }

    private String extractTableNameFromFileName(String sourceFileName) {
        String value = sourceFileName == null ? "" : sourceFileName.trim();
        Matcher matcher = FILE_NAME_TABLE.matcher(value);
        if (matcher.find()) {
            String normalized = normalizeFileNameTable(matcher.group(1));
            if (!normalized.isBlank()) {
                return normalized;
            }
        }

        matcher = FILE_NAME_TABLES.matcher(value);
        if (matcher.find()) {
            String normalized = normalizeFileNameTable(matcher.group(1));
            if (!normalized.isBlank()) {
                return normalized;
            }
        }

        matcher = FILE_NAME_TB_SUFFIX.matcher(value);
        if (matcher.find()) {
            Matcher trailing = TRAILING_TECHNICAL_TOKEN.matcher(matcher.group(1));
            if (trailing.find()) {
                return normalizeFileNameTable(trailing.group(1));
            }
        }

        String withoutExtension = value.replaceFirst("(?i)\\.docx?$", "");
        if (FILE_NAME_CONTAINS_TABLE_WORD.matcher(value).find()) {
            Matcher trailing = TRAILING_TECHNICAL_TOKEN.matcher(withoutExtension);
            if (trailing.find()) {
                return normalizeFileNameTable(trailing.group(1));
            }
        }

        // Some valid table specifications omit both the .tb. segment and the word
        // "Table", but still end with a strongly recognizable database-table token,
        // for example 14011110_CtAccessParam.doc or JAMTS-19 MSProcessType.doc.
        Matcher trailing = TRAILING_PROBABLE_TABLE_TOKEN.matcher(withoutExtension);
        if (trailing.find()) {
            String normalized = normalizeFileNameTable(trailing.group(1));
            if (PROBABLE_DATABASE_TABLE_TOKEN.matcher(normalized).matches()) {
                return normalized;
            }
        }
        return "";
    }

    private String normalizeFileNameTable(String rawValue) {
        String value = TextNormalizer.cleanCell(rawValue)
                .replaceFirst("(?i)^TABLES?[_-]+", "");
        return normalizeTableName(value);
    }

    private String normalizeTableName(String rawValue) {
        String value = TextNormalizer.cleanCell(rawValue)
                .replaceFirst("^[\\s._:：-]+", "")
                .replaceAll("\\s*\\.\\s*", ".");
        if (value.isBlank()) {
            return "";
        }
        int separator = value.lastIndexOf('.');
        String unqualified = separator >= 0 ? value.substring(separator + 1) : value;
        String normalized = TextNormalizer.cleanCell(unqualified)
                .replaceFirst("^[\\s._:：-]+", "")
                .replaceFirst("[\\s._:：-]+$", "");
        if (!TextNormalizer.isTechnicalIdentifier(normalized)) {
            return "";
        }
        return INVALID_TABLE_NAMES.contains(normalized.toUpperCase(Locale.ROOT)) ? "" : normalized;
    }

    private String extractTechnicalTableNameFromEntity(String rawEntityName) {
        String candidate = TextNormalizer.cleanCell(rawEntityName)
                .replaceFirst("^[\\s._:：-]+", "")
                .replaceFirst("[\\s._:：-]+$", "");
        return normalizeTableName(candidate);
    }

    private boolean shouldPreferFileNameTable(String headerName, String fileNameTable) {
        if (headerName == null || headerName.isBlank()) {
            return true;
        }
        String headerUpper = headerName.toUpperCase(Locale.ROOT);
        String fileUpper = fileNameTable.toUpperCase(Locale.ROOT);
        return headerName.length() < 4 && fileUpper.startsWith(headerUpper)
                || fileNameTable.length() > headerName.length() && fileUpper.startsWith(headerUpper);
    }

    private boolean isTemplateDocument(String relativePath,
                                       ParsedDocument parsed,
                                       Metadata metadata) {
        String path = relativePath == null ? "" : relativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (!path.contains("template")) {
            return false;
        }
        String source = TextNormalizer.compactForMatching(
                parsed.rawHeaderText() + "\n" + parsed.rawMainText()
        ).toUpperCase(Locale.ROOT);
        return source.contains("نام جدول")
                || source.contains("FIELD NAME")
                || source.contains("FILED NAME")
                || metadata.tableName() == null
                || metadata.tableName().isBlank();
    }

    private int calculateTableDocumentScore(String relativePath,
                                            ParsedDocument parsed,
                                            Metadata metadata,
                                            List<ColumnDefinition> columns) {
        String source = TextNormalizer.compactForMatching(
                parsed.rawHeaderText() + "\n" + parsed.rawMainText()
        );
        String path = relativePath == null ? "" : relativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        boolean tableFileName = path.contains(".tb.") || path.contains("_tb_") || path.contains("-tb-")
                || path.contains(".tables.") || path.contains("_tables.") || path.contains(".table.");
        boolean tableDocumentType = TextNormalizer.compactForMatching(metadata.documentType()).contains("تعریف جدول");
        boolean tableNameLabel = source.contains("نام جدول");
        boolean tableNamePresent = metadata.tableName() != null && !metadata.tableName().isBlank();
        if (looksLikeReportDocument(path, source, tableDocumentType)) {
            return Integer.MIN_VALUE;
        }
        if (!tableFileName && !tableDocumentType && !tableNameLabel && !tableNamePresent) {
            return Integer.MIN_VALUE;
        }
        if (!tableNamePresent && !tableDocumentType && !tableFileName) {
            return Integer.MIN_VALUE;
        }

        int score = 0;
        if (tableFileName) {
            score += 4;
        }
        if (tableDocumentType) {
            score += 3;
        }
        if (tableNameLabel) {
            score += 2;
        }
        if (tableNamePresent) {
            score += 3;
        }
        if (hasDefinitionHeader(source)) {
            score += 4;
        }
        if (columns.size() >= 3) {
            score += 3;
        } else if (!columns.isEmpty()) {
            score += 1;
        }
        if (columns.size() >= 10) {
            score += 1;
        }
        if (NEGATIVE_DOCUMENT_TOKEN.matcher(path).find()) {
            score -= 6;
        }
        if (!tableNamePresent) {
            score -= 2;
        }
        return score;
    }

    private boolean looksLikeReportDocument(String path, String source, boolean tableDocumentType) {
        if (path.contains("src_reports")) {
            return true;
        }
        boolean reportMarkers = source.contains("نام گزارش")
                && source.contains("پارامترهای ورودی")
                && source.contains("پارامترهای خروجی");
        return reportMarkers && !tableDocumentType;
    }

    private boolean hasDefinitionHeader(String source) {
        return source.contains("نام صفت")
                && (source.contains("نام فیلد") || source.contains("نام خاصه"))
                && source.contains("نوع")
                && source.contains("طول");
    }

    private List<ColumnDefinition> extractColumns(List<List<List<String>>> tables,
                                                  List<ExtractionWarning> warnings) {
        List<TableExtraction> candidates = new ArrayList<>();

        for (int tableIndex = 0; tableIndex < tables.size(); tableIndex++) {
            List<List<String>> table = tables.get(tableIndex);
            ColumnLayoutResolver.Layout layout = ColumnLayoutResolver.resolve(table);
            List<ColumnDefinition> tableColumns = new ArrayList<>();
            List<ExtractionWarning> tableWarnings = new ArrayList<>();

            for (int rowIndex = 0; rowIndex < table.size(); rowIndex++) {
                List<String> sourceCells = table.get(rowIndex);
                List<List<String>> logicalRows = splitMergedDefinitionRow(sourceCells, layout);
                if (logicalRows.size() == 1) {
                    logicalRows = LegacyFlatRowReconstructor.split(sourceCells, layout);
                }
                boolean mergedSplit = logicalRows.size() > 1;
                if (mergedSplit) {
                    boolean flatRecovery = !TextNormalizer.hasStructuredCellParagraphs(cellValue(sourceCells, 1));
                    tableWarnings.add(new ExtractionWarning(
                            Severity.INFO,
                            flatRecovery ? "FLAT_MERGED_DEFINITION_ROW_SPLIT" : "MERGED_DEFINITION_ROW_SPLIT",
                            null,
                            rowIndex,
                            flatRecovery
                                    ? "A flattened physical Word row contained several one-to-one field/type definitions and was split into "
                                            + logicalRows.size() + " logical rows without guessing structural ownership."
                                    : "A physical Word row contained several paragraph-aligned field definitions and was split into "
                                            + logicalRows.size() + " logical rows.",
                            TextNormalizer.cleanCell(cellValue(sourceCells, 1))
                    ));
                }

                for (List<String> cells : logicalRows) {
                    if (!layout.isDefinitionRow(cells)
                            && !(mergedSplit && isSparseSplitDefinitionRow(cells))) {
                        continue;
                    }

                    ColumnLayoutResolver.ResolvedColumn resolved = layout.resolve(cells);
                    String fieldNameRaw = resolved.fieldName();
                    String fieldName = TextNormalizer.normalizeTechnicalName(fieldNameRaw);
                    String mandatoryRaw = resolved.mandatory();
                    Boolean mandatory = parseMandatory(mandatoryRaw);
                    String referenceOrDefaultRaw = resolved.referenceOrDefault();
                    String typeRaw = resolved.type();
                    if (typeRaw.isBlank() && ColumnLayoutResolver.looksLikeDataTypeValue(resolved.db2Type())) {
                        typeRaw = resolved.db2Type();
                        tableWarnings.add(new ExtractionWarning(
                                Severity.INFO,
                                "FIELD_TYPE_FROM_DB2",
                                fieldName,
                                rowIndex,
                                "Field type was empty and was recovered from the DB2 type column.",
                                resolved.db2Type()
                        ));
                    }
                    if (typeRaw.isBlank()
                            && resolved.db2Type().isBlank()
                            && ColumnLayoutResolver.looksLikeDataTypeValue(referenceOrDefaultRaw)) {
                        typeRaw = referenceOrDefaultRaw;
                        tableWarnings.add(new ExtractionWarning(
                                Severity.INFO,
                                "FIELD_TYPE_FROM_REFERENCE_COLUMN",
                                fieldName,
                                rowIndex,
                                "Field type was empty and was recovered from a displaced type value in the reference/default column.",
                                referenceOrDefaultRaw
                        ));
                        referenceOrDefaultRaw = "";
                    }
                    String lengthRaw = resolved.length();
                    LengthValueParser.ParsedLength length = LengthValueParser.parse(lengthRaw);

                    if (typeRaw.isBlank()
                            && resolved.db2Type().isBlank()
                            && !containsTypeValueOutsideFieldName(cells, fieldNameRaw)) {
                        tableWarnings.add(new ExtractionWarning(
                                Severity.INFO,
                                "FIELD_TYPE_NOT_PRESENT_IN_SOURCE",
                                fieldName,
                                rowIndex,
                                mergedSplit
                                        ? "The logical field was recovered from a vertically merged row, but the source row does not contain an aligned type value."
                                        : "The source row does not contain a logical or physical data-type value.",
                                fieldNameRaw
                        ));
                    }

                    if (!fieldName.equals(fieldNameRaw)) {
                        tableWarnings.add(new ExtractionWarning(
                                Severity.INFO,
                                "TECHNICAL_NAME_WHITESPACE_REMOVED",
                                fieldName,
                                rowIndex,
                                "Whitespace inside the technical field name was removed.",
                                fieldNameRaw
                        ));
                    }
                    if (length.ambiguous()) {
                        tableWarnings.add(new ExtractionWarning(
                                Severity.WARNING,
                                "AMBIGUOUS_LENGTH",
                                fieldName,
                                rowIndex,
                                "Length contains multiple numeric groups that do not match precision/scale syntax.",
                                lengthRaw
                        ));
                    }

                    tableColumns.add(new ColumnDefinition(
                            tableColumns.size() + 1,
                            tableIndex,
                            rowIndex,
                            resolved.attributeName(),
                            fieldName,
                            fieldNameRaw,
                            typeRaw,
                            lengthRaw,
                            resolved.key(),
                            resolved.index(),
                            mandatoryRaw,
                            mandatory,
                            resolved.db2Type(),
                            resolved.db2Length(),
                            referenceOrDefaultRaw,
                            TextNormalizer.splitTokens(resolved.key()),
                            TextNormalizer.splitTokens(resolved.index()),
                            List.copyOf(flattenCells(cells))
                    ));
                }
            }

            if (!tableColumns.isEmpty()) {
                tableColumns = resolveBlankMandatoryValues(tableColumns);
                List<ColumnDefinition> deduplicated = deduplicateDefinitionRows(tableColumns, tableWarnings);
                candidates.add(new TableExtraction(tableIndex, List.copyOf(deduplicated), List.copyOf(tableWarnings)));
            }
        }

        List<TableExtraction> selected = selectDefinitionTables(candidates, warnings);
        List<ColumnDefinition> combined = new ArrayList<>();
        for (TableExtraction table : selected) {
            warnings.addAll(table.warnings());
            combined.addAll(table.columns());
        }

        // Page continuations are occasionally stored as separate Word tables and may
        // repeat one or two boundary fields. Deduplicate once more across all selected
        // tables so those repeated continuation rows do not become duplicate columns.
        List<ColumnDefinition> globallyDeduplicated = deduplicateDefinitionRows(combined, warnings);

        // Recovery7: a few DOCX rows can still reach this point as one synthetic column even
        // though their retained raw cells contain a deterministic one-to-one field/type split.
        // Recover only from the ColumnDefinition raw-cell evidence already accepted by the low-
        // level parser. LegacyFlatRowReconstructor remains the single structural validator; if
        // any key/index/mandatory/physical/default cell cannot be mapped without guessing, the
        // column is left untouched.
        List<ColumnDefinition> postRecovered = LegacyPostExtractionMergedColumnRecovery.recover(globallyDeduplicated, warnings);

        List<ColumnDefinition> columns = new ArrayList<>(postRecovered.size());
        int sequence = 0;
        for (ColumnDefinition column : postRecovered) {
            columns.add(copyWithSequence(column, ++sequence));
        }

        if (selected.size() > 1) {
            warnings.add(new ExtractionWarning(
                    Severity.INFO,
                    "MULTIPLE_DEFINITION_TABLES_FOUND",
                    null,
                    null,
                    "Rows were extracted from more than one candidate table: " + selected.size(),
                    Integer.toString(selected.size())
            ));
        }
        return List.copyOf(columns);
    }

    private List<ColumnDefinition> resolveBlankMandatoryValues(List<ColumnDefinition> columns) {
        boolean hasExplicitMandatoryValue = columns.stream().anyMatch(column -> column.mandatory() != null);
        if (!hasExplicitMandatoryValue) {
            return columns;
        }
        List<ColumnDefinition> resolved = new ArrayList<>(columns.size());
        for (ColumnDefinition column : columns) {
            if (column.mandatory() == null && TextNormalizer.cleanCell(column.mandatoryRaw()).isBlank()) {
                resolved.add(copyWithMandatory(column, Boolean.FALSE));
            } else {
                resolved.add(column);
            }
        }
        return resolved;
    }

    private ColumnDefinition copyWithMandatory(ColumnDefinition column, Boolean mandatory) {
        return new ColumnDefinition(
                column.sequence(),
                column.sourceTableIndex(),
                column.sourceRowIndex(),
                column.persianTitle(),
                column.fieldName(),
                column.fieldNameRaw(),
                column.typeRaw(),
                column.lengthRaw(),
                column.keyRaw(),
                column.indexRaw(),
                column.mandatoryRaw(),
                mandatory,
                column.db2TypeRaw(),
                column.db2LengthRaw(),
                column.referenceOrDefaultRaw(),
                column.keys(),
                column.indexes(),
                column.rawCells()
        );
    }

    /**
     * Splits old DOCX rows in which Word vertically stacked several logical fields inside
     * each cell. A split is accepted only when field name, type and length have the same
     * unambiguous cardinality. This prevents ordinary wrapped names such as
     * {@code UnderAmountExpi / reDate} from being treated as two fields.
     */
    private List<List<String>> splitMergedDefinitionRow(List<String> sourceCells,
                                                         ColumnLayoutResolver.Layout layout) {
        List<String> flattened = flattenCells(sourceCells);
        if (layout.kind() == ColumnLayoutResolver.Kind.TECHNICAL_5 || sourceCells.size() < 4) {
            return List.of(flattened);
        }

        String fieldCell = cellValue(sourceCells, 1);
        if (!TextNormalizer.hasStructuredCellParagraphs(fieldCell)) {
            return List.of(flattened);
        }

        List<PositionedValue> fields = nonEmptyParagraphs(fieldCell, false).stream()
                .map(value -> new PositionedValue(
                        value.paragraphIndex(),
                        normalizeMergedTechnicalName(value.value())
                ))
                .toList();
        int fieldCount = fields.size();
        if (fieldCount < 2 || fields.stream().anyMatch(value -> !TextNormalizer.isTechnicalIdentifier(value.value()))) {
            return List.of(flattened);
        }

        List<Integer> fieldAnchors = fields.stream().map(PositionedValue::paragraphIndex).toList();
        List<String> types = alignedSequentialValues(
                cellValue(sourceCells, 2), fieldCount, fieldAnchors, true
        );
        List<String> lengths = alignedSequentialValues(
                cellValue(sourceCells, 3), fieldCount, fieldAnchors, false
        );
        if (types.size() != fieldCount || lengths.size() != fieldCount
                || types.stream().filter(value -> !value.isBlank())
                        .anyMatch(value -> !ColumnLayoutResolver.looksLikeDataTypeValue(value))
                || lengths.stream().filter(value -> !value.isBlank())
                        .anyMatch(value -> LengthValueParser.parse(value).ambiguous())
                || types.stream().allMatch(String::isBlank)
                        && lengths.stream().allMatch(String::isBlank)) {
            return List.of(flattened);
        }
        List<List<String>> rows = new ArrayList<>(fieldCount);
        for (int fieldIndex = 0; fieldIndex < fieldCount; fieldIndex++) {
            List<String> row = new ArrayList<>(sourceCells.size());
            for (int cellIndex = 0; cellIndex < sourceCells.size(); cellIndex++) {
                row.add("");
            }
            rows.add(row);
        }

        for (int fieldIndex = 0; fieldIndex < fieldCount; fieldIndex++) {
            rows.get(fieldIndex).set(1, fields.get(fieldIndex).value());
            rows.get(fieldIndex).set(2, types.get(fieldIndex));
            rows.get(fieldIndex).set(3, lengths.get(fieldIndex));
        }

        for (int cellIndex = 0; cellIndex < sourceCells.size(); cellIndex++) {
            if (cellIndex == 1 || cellIndex == 2 || cellIndex == 3) {
                continue;
            }
            List<String> mapped = mapCellToLogicalFields(
                    cellValue(sourceCells, cellIndex), fieldCount, fieldAnchors, cellIndex
            );
            for (int fieldIndex = 0; fieldIndex < fieldCount; fieldIndex++) {
                rows.get(fieldIndex).set(cellIndex, mapped.get(fieldIndex));
            }
        }

        return rows.stream().map(List::copyOf).toList();
    }

    private List<String> sequentialValues(String raw, int expectedCount, boolean dataType) {
        List<PositionedValue> values = nonEmptyParagraphs(raw, false);
        if (values.size() != expectedCount) {
            return List.of();
        }
        List<String> result = new ArrayList<>(expectedCount);
        for (PositionedValue value : values) {
            String normalized = dataType ? normalizeLogicalDataType(value.value()) : value.value();
            result.add(normalized);
        }
        return List.copyOf(result);
    }


    /**
     * Maps vertically aligned values to logical field anchors while preserving missing
     * cells. Some legacy Word rows contain four field names but only two explicit type
     * and length values; the remaining fields are still valid definitions and must not
     * be collapsed into one synthetic technical name.
     */
    private List<String> alignedSequentialValues(String raw,
                                                 int expectedCount,
                                                 List<Integer> fieldAnchors,
                                                 boolean dataType) {
        List<String> exact = sequentialValues(raw, expectedCount, dataType);
        if (!exact.isEmpty()) {
            return exact;
        }

        List<PositionedValue> values = nonEmptyParagraphs(raw, false);
        if (values.isEmpty()) {
            return List.of();
        }
        if (values.size() > expectedCount && !fieldAnchors.isEmpty()) {
            int firstAnchor = fieldAnchors.get(0);
            int lastAnchor = fieldAnchors.get(fieldAnchors.size() - 1);
            values = values.stream()
                    .filter(value -> value.paragraphIndex() >= firstAnchor
                            && value.paragraphIndex() <= lastAnchor)
                    .toList();
        }
        if (values.size() > expectedCount) {
            return List.of();
        }
        List<String> result = new ArrayList<>(expectedCount);
        for (int index = 0; index < expectedCount; index++) {
            result.add("");
        }
        for (PositionedValue value : values) {
            String normalized = dataType ? normalizeLogicalDataType(value.value()) : value.value();
            if (dataType && !ColumnLayoutResolver.looksLikeDataTypeValue(normalized)) {
                return List.of();
            }
            if (!dataType && LengthValueParser.parse(normalized).ambiguous()) {
                return List.of();
            }
            int target = nearestAnchor(value.paragraphIndex(), fieldAnchors);
            if (!result.get(target).isBlank()) {
                return List.of();
            }
            result.set(target, normalized);
        }
        return List.copyOf(result);
    }

    private String normalizeMergedTechnicalName(String value) {
        String cleaned = TextNormalizer.cleanCell(value);
        String normalized = TextNormalizer.normalizeTechnicalName(cleaned);
        return TextNormalizer.isTechnicalIdentifier(normalized) ? normalized : cleaned;
    }

    private boolean isSparseSplitDefinitionRow(List<String> cells) {
        String fieldName = TextNormalizer.cleanCell(cellValue(cells, 1));
        if (!TextNormalizer.isTechnicalIdentifier(fieldName)) {
            return false;
        }
        return !TextNormalizer.cleanCell(cellValue(cells, 0)).isBlank()
                || !TextNormalizer.cleanCell(cellValue(cells, 4)).isBlank()
                || !TextNormalizer.cleanCell(cellValue(cells, 5)).isBlank()
                || !TextNormalizer.cleanCell(cellValue(cells, 9)).isBlank();
    }

    private String normalizeLogicalDataType(String value) {
        String cleaned = TextNormalizer.cleanCell(value);
        if (ColumnLayoutResolver.looksLikeDataTypeValue(cleaned)) {
            return cleaned;
        }
        String compact = cleaned.replaceAll("\\s+", "");
        return ColumnLayoutResolver.looksLikeDataTypeValue(compact) ? compact : cleaned;
    }

    private List<String> mapCellToLogicalFields(String raw,
                                                int fieldCount,
                                                List<Integer> fieldAnchors,
                                                int cellIndex) {
        List<PositionedValue> values = nonEmptyParagraphs(raw, cellIndex == 4 || cellIndex == 5);
        List<StringBuilder> assigned = new ArrayList<>(fieldCount);
        for (int index = 0; index < fieldCount; index++) {
            assigned.add(new StringBuilder());
        }
        if (values.isEmpty()) {
            return assigned.stream().map(StringBuilder::toString).toList();
        }

        // Attribute, mandatory and DB2 columns commonly contain one paragraph per field
        // without the blank spacer paragraphs used by the field-name cell.
        if ((cellIndex == 0 || cellIndex == 6 || cellIndex == 7 || cellIndex == 8)
                && values.size() == fieldCount) {
            for (int index = 0; index < fieldCount; index++) {
                assigned.get(index).append(values.get(index).value());
            }
        } else {
            for (PositionedValue value : values) {
                int target = nearestAnchor(value.paragraphIndex(), fieldAnchors);
                StringBuilder bucket = assigned.get(target);
                if (!bucket.isEmpty()) {
                    bucket.append(' ');
                }
                bucket.append(value.value());
            }
        }

        List<String> result = new ArrayList<>(fieldCount);
        for (StringBuilder value : assigned) {
            result.add(TextNormalizer.cleanCell(value.toString()));
        }
        return List.copyOf(result);
    }

    private List<PositionedValue> nonEmptyParagraphs(String raw, boolean joinIndexFragments) {
        List<String> paragraphs = TextNormalizer.splitCellParagraphs(raw);
        List<PositionedValue> values = new ArrayList<>();
        for (int index = 0; index < paragraphs.size(); index++) {
            String value = TextNormalizer.cleanCell(paragraphs.get(index));
            if (!value.isBlank()) {
                values.add(new PositionedValue(index, value));
            }
        }
        if (!joinIndexFragments || values.size() < 2) {
            return List.copyOf(values);
        }

        List<PositionedValue> grouped = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            PositionedValue current = values.get(index);
            if (index + 1 < values.size()
                    && current.value().matches("(?i)^(?:PK|FK|IX|I|X)$")
                    && values.get(index + 1).value().matches("^[0-9]+(?:[-_.][0-9]+)*$")) {
                grouped.add(new PositionedValue(
                        current.paragraphIndex(),
                        current.value() + values.get(index + 1).value()
                ));
                index++;
            } else {
                grouped.add(current);
            }
        }
        return List.copyOf(grouped);
    }

    private int nearestAnchor(int paragraphIndex, List<Integer> anchors) {
        int bestIndex = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int index = 0; index < anchors.size(); index++) {
            int distance = Math.abs(paragraphIndex - anchors.get(index));
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    private List<String> flattenCells(List<String> cells) {
        List<String> flattened = new ArrayList<>(cells.size());
        for (String cell : cells) {
            flattened.add(TextNormalizer.cleanCell(cell));
        }
        return List.copyOf(flattened);
    }

    private String cellValue(List<String> cells, int index) {
        return index >= 0 && index < cells.size() ? cells.get(index) : "";
    }

    private boolean containsTypeValueOutsideFieldName(List<String> cells, String fieldNameRaw) {
        String normalizedFieldName = TextNormalizer.cleanCell(fieldNameRaw);
        for (String cell : cells) {
            String value = TextNormalizer.cleanCell(cell);
            if (value.isBlank() || value.equalsIgnoreCase(normalizedFieldName)) {
                continue;
            }
            if (ColumnLayoutResolver.looksLikeDataTypeValue(value)) {
                return true;
            }
        }
        return false;
    }

    private List<ColumnDefinition> deduplicateDefinitionRows(
            List<ColumnDefinition> columns,
            List<ExtractionWarning> warnings) {
        Map<String, Integer> indexByName = new LinkedHashMap<>();
        List<ColumnDefinition> result = new ArrayList<>(columns.size());
        for (ColumnDefinition column : columns) {
            String key = column.fieldName().toUpperCase(Locale.ROOT);
            Integer existingIndex = indexByName.get(key);
            if (existingIndex == null) {
                indexByName.put(key, result.size());
                result.add(column);
                continue;
            }

            ColumnDefinition existing = result.get(existingIndex);
            if (sameDefinition(existing, column) || sameEffectiveDefinition(existing, column)) {
                // Keep the latest row even when the technical definition is equivalent;
                // appended revisions sometimes update only the Persian title or default text.
                result.set(existingIndex, column);
                warnings.add(new ExtractionWarning(
                        Severity.INFO,
                        "DUPLICATE_DEFINITION_ROW_SKIPPED",
                        column.fieldName(),
                        column.sourceRowIndex(),
                        "Equivalent duplicate rows were collapsed and the later row was retained.",
                        column.fieldNameRaw()
                ));
                continue;
            }

            // Recovery5: do not let a later row with an unresolvable datatype overwrite an
            // earlier definition that already has deterministic type evidence. This occurs in
            // legacy documents that contain a second descriptive/mapping table with the same
            // technical labels (for example a valid N(6) definition followed by a row whose
            // apparent datatype is the heading text "ParamDesc"). When both definitions are
            // resolvable we preserve the existing revision rule and keep the later row.
            if (hasResolvableTypeEvidence(existing) && !hasResolvableTypeEvidence(column)) {
                warnings.add(new ExtractionWarning(
                        Severity.INFO,
                        "DUPLICATE_UNRELIABLE_DEFINITION_SKIPPED",
                        column.fieldName(),
                        column.sourceRowIndex(),
                        "A later duplicate row was ignored because it had no resolvable datatype while the earlier definition did.",
                        summarizeDefinition(existing) + " <- kept over -> " + summarizeDefinition(column)
                ));
                continue;
            }

            // Old design documents often append a changed field definition at the end of
            // the same table instead of editing the original row. A database table cannot
            // contain the same technical column twice, so the later explicit definition is
            // treated as the current revision.
            result.set(existingIndex, column);
            warnings.add(new ExtractionWarning(
                    Severity.INFO,
                    "DUPLICATE_FIELD_DEFINITION_REPLACED",
                    column.fieldName(),
                    column.sourceRowIndex(),
                    "A later non-identical definition replaced an earlier row with the same technical field name.",
                    summarizeDefinition(existing) + " -> " + summarizeDefinition(column)
            ));
        }
        return List.copyOf(result);
    }

    private boolean hasResolvableTypeEvidence(ColumnDefinition column) {
        String logical = TextNormalizer.cleanCell(column.typeRaw());
        if (!logical.isBlank()
                && !"S".equalsIgnoreCase(logical)
                && LegacyDataTypeNormalizer.sourceTypeStatus(logical)
                        == LegacyDataTypeNormalizer.TypeStatus.TRUSTED) {
            return true;
        }
        String physical = TextNormalizer.cleanCell(column.db2TypeRaw());
        return !physical.isBlank()
                && LegacyDataTypeNormalizer.db2TypeStatus(physical)
                        == LegacyDataTypeNormalizer.TypeStatus.TRUSTED;
    }

    private boolean sameEffectiveDefinition(ColumnDefinition left, ColumnDefinition right) {
        return sameValue(effectiveType(left), effectiveType(right))
                && compatibleLength(effectiveLength(left), effectiveLength(right))
                && sameValue(left.keyRaw(), right.keyRaw())
                && sameValue(left.indexRaw(), right.indexRaw());
    }

    private String effectiveType(ColumnDefinition column) {
        String db2 = TextNormalizer.cleanCell(column.db2TypeRaw());
        if (!db2.isBlank()) {
            return canonicalTypeFamily(db2);
        }
        return canonicalTypeFamily(column.typeRaw());
    }

    private String effectiveLength(ColumnDefinition column) {
        String db2Length = LengthValueParser.parse(column.db2LengthRaw()).normalized();
        if (!db2Length.isBlank()) {
            return db2Length;
        }
        return LengthValueParser.parse(column.lengthRaw()).normalized();
    }

    private String canonicalTypeFamily(String raw) {
        String value = TextNormalizer.cleanCell(raw).toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        return switch (value) {
            case "S", "SI", "SMALLINT" -> "SMALLINT";
            case "I", "INT", "INTEGER" -> "INTEGER";
            case "D", "DC", "DEC", "DECIMAL", "NUMERIC", "NUMBER" -> "DECIMAL";
            case "C", "CHAR", "CHARACTER" -> "CHAR";
            case "V", "VC", "VARCHAR", "VARCHAR2", "CHARACTER VARYING" -> "VARCHAR";
            case "T", "TS", "TIMESTAMP" -> "TIMESTAMP";
            default -> value;
        };
    }

    private boolean compatibleLength(String left, String right) {
        if (left.isBlank() || right.isBlank()) {
            return true;
        }
        return sameValue(left, right);
    }

    private String summarizeDefinition(ColumnDefinition column) {
        return "type=" + TextNormalizer.cleanCell(column.typeRaw())
                + ", length=" + TextNormalizer.cleanCell(column.lengthRaw())
                + ", db2=" + TextNormalizer.cleanCell(column.db2TypeRaw())
                + "(" + TextNormalizer.cleanCell(column.db2LengthRaw()) + ")";
    }

    private boolean sameDefinition(ColumnDefinition left, ColumnDefinition right) {
        return sameValue(left.typeRaw(), right.typeRaw())
                && sameValue(left.lengthRaw(), right.lengthRaw())
                && sameValue(left.keyRaw(), right.keyRaw())
                && sameValue(left.indexRaw(), right.indexRaw())
                && sameValue(left.mandatoryRaw(), right.mandatoryRaw())
                && sameValue(left.db2TypeRaw(), right.db2TypeRaw())
                && sameValue(left.db2LengthRaw(), right.db2LengthRaw())
                && sameValue(left.referenceOrDefaultRaw(), right.referenceOrDefaultRaw());
    }

    private boolean sameValue(String left, String right) {
        return Objects.equals(
                TextNormalizer.compactForMatching(left).toUpperCase(Locale.ROOT),
                TextNormalizer.compactForMatching(right).toUpperCase(Locale.ROOT)
        );
    }

    private List<TableExtraction> selectDefinitionTables(List<TableExtraction> candidates,
                                                         List<ExtractionWarning> warnings) {
        if (candidates.size() <= 1) {
            return List.copyOf(candidates);
        }

        List<TableExtraction> selected = new ArrayList<>();
        Set<String> selectedNames = new LinkedHashSet<>();
        boolean selectedHasPrimaryKey = false;

        for (TableExtraction candidate : candidates) {
            Set<String> candidateNames = new LinkedHashSet<>();
            boolean candidateHasPrimaryKey = false;
            for (ColumnDefinition column : candidate.columns()) {
                candidateNames.add(column.fieldName().toUpperCase(Locale.ROOT));
                candidateHasPrimaryKey |= column.keys().stream()
                        .map(value -> value.toUpperCase(Locale.ROOT))
                        .anyMatch(value -> value.startsWith("PK"));
            }

            int overlap = 0;
            for (String fieldName : candidateNames) {
                if (selectedNames.contains(fieldName)) {
                    overlap++;
                }
            }
            double overlapRatio = candidateNames.isEmpty() ? 0.0 : (double) overlap / candidateNames.size();

            // A later table with no primary key and a majority of fields already present
            // is normally an accidentally appended second definition, not a page continuation.
            // Keep genuine continuations such as CTBlkOCustomersLog, whose field sets are disjoint.
            if (!selected.isEmpty() && selectedHasPrimaryKey && !candidateHasPrimaryKey
                    && overlap >= 3 && overlapRatio >= 0.50d) {
                warnings.add(new ExtractionWarning(
                        Severity.INFO,
                        "SECONDARY_DEFINITION_TABLE_SKIPPED",
                        null,
                        null,
                        "A duplicate-heavy secondary definition table was skipped.",
                        "table=" + candidate.tableIndex() + ", overlap=" + overlap + "/" + candidateNames.size()
                ));
                continue;
            }

            selected.add(candidate);
            selectedNames.addAll(candidateNames);
            selectedHasPrimaryKey |= candidateHasPrimaryKey;
        }
        return List.copyOf(selected);
    }

    private ColumnDefinition copyWithSequence(ColumnDefinition column, int sequence) {
        return new ColumnDefinition(
                sequence,
                column.sourceTableIndex(),
                column.sourceRowIndex(),
                column.persianTitle(),
                column.fieldName(),
                column.fieldNameRaw(),
                column.typeRaw(),
                column.lengthRaw(),
                column.keyRaw(),
                column.indexRaw(),
                column.mandatoryRaw(),
                column.mandatory(),
                column.db2TypeRaw(),
                column.db2LengthRaw(),
                column.referenceOrDefaultRaw(),
                column.keys(),
                column.indexes(),
                column.rawCells()
        );
    }

    private record PositionedValue(int paragraphIndex, String value) {
    }

    private record TableExtraction(
            int tableIndex,
            List<ColumnDefinition> columns,
            List<ExtractionWarning> warnings
    ) {
    }

    private List<ColumnDefinition> sanitizeColumnQuality(List<ColumnDefinition> columns,
                                                           List<ExtractionWarning> warnings) {
        if (columns == null || columns.isEmpty()) {
            return columns == null ? List.of() : columns;
        }
        List<ColumnDefinition> sanitized = new ArrayList<>(columns.size());
        for (ColumnDefinition column : columns) {
            String typeRaw = TextNormalizer.cleanCell(column.typeRaw());
            String db2TypeRaw = TextNormalizer.cleanCell(column.db2TypeRaw());
            String indexRaw = TextNormalizer.cleanCell(column.indexRaw());
            List<String> indexes = new ArrayList<>(column.indexes());

            if (LegacyDataTypeNormalizer.isInvalidStructuralToken(typeRaw)) {
                warnings.add(new ExtractionWarning(
                        Severity.WARNING,
                        "FIELD_TYPE_INVALID_SOURCE_TOKEN",
                        column.fieldName(),
                        column.sourceRowIndex(),
                        "An index, key, sequence, numeric-only value, or formatting artifact was found in the logical type position and was rejected.",
                        typeRaw
                ));
                if (LegacyDataTypeNormalizer.isIndexLikeToken(typeRaw)) {
                    indexRaw = mergeIndexValue(indexRaw, typeRaw);
                    addIndexTokens(indexes, typeRaw);
                }
                typeRaw = "";
            }

            if (LegacyDataTypeNormalizer.isInvalidStructuralToken(db2TypeRaw)) {
                warnings.add(new ExtractionWarning(
                        Severity.WARNING,
                        "DB2_TYPE_INVALID_SOURCE_TOKEN",
                        column.fieldName(),
                        column.sourceRowIndex(),
                        "An index, key, sequence, numeric-only value, or formatting artifact was found in the DB2 type position and was rejected.",
                        db2TypeRaw
                ));
                if (LegacyDataTypeNormalizer.isIndexLikeToken(db2TypeRaw)) {
                    indexRaw = mergeIndexValue(indexRaw, db2TypeRaw);
                    addIndexTokens(indexes, db2TypeRaw);
                }
                db2TypeRaw = "";
            }

            PersianNameQuality.Status persianStatus = PersianNameQuality.columnStatus(column);
            if (persianStatus == PersianNameQuality.Status.UNRELIABLE) {
                warnings.add(new ExtractionWarning(
                        Severity.INFO,
                        "PERSIAN_FIELD_NAME_NOT_RELIABLE",
                        column.fieldName(),
                        column.sourceRowIndex(),
                        "The Persian field title was retained as raw evidence but classified as unreliable.",
                        column.persianTitle()
                ));
            }

            sanitized.add(new ColumnDefinition(
                    column.sequence(),
                    column.sourceTableIndex(),
                    column.sourceRowIndex(),
                    column.persianTitle(),
                    column.fieldName(),
                    column.fieldNameRaw(),
                    typeRaw,
                    column.lengthRaw(),
                    column.keyRaw(),
                    indexRaw,
                    column.mandatoryRaw(),
                    column.mandatory(),
                    db2TypeRaw,
                    column.db2LengthRaw(),
                    column.referenceOrDefaultRaw(),
                    column.keys(),
                    List.copyOf(indexes),
                    column.rawCells()
            ));
        }
        return List.copyOf(sanitized);
    }

    private String mergeIndexValue(String current, String token) {
        String cleanedCurrent = TextNormalizer.cleanCell(current);
        String cleanedToken = TextNormalizer.cleanCell(token);
        if (cleanedCurrent.isBlank()) {
            return cleanedToken;
        }
        if (cleanedToken.isBlank() || TextNormalizer.splitTokens(cleanedCurrent).contains(cleanedToken.toUpperCase(Locale.ROOT))) {
            return cleanedCurrent;
        }
        return cleanedCurrent + " " + cleanedToken;
    }

    private void addIndexTokens(List<String> indexes, String raw) {
        for (String token : TextNormalizer.splitTokens(raw)) {
            if (!indexes.contains(token)) {
                indexes.add(token);
            }
        }
    }

    private void validate(Metadata metadata,
                          List<ColumnDefinition> columns,
                          List<ExtractionWarning> warnings) {
        if (metadata.tableName() == null || metadata.tableName().isBlank()) {
            warnings.add(new ExtractionWarning(
                    Severity.WARNING,
                    "TABLE_NAME_NOT_FOUND",
                    null,
                    null,
                    "Table name could not be extracted from the document header or source file name.",
                    null
            ));
        }
        if (metadata.entityName() == null || metadata.entityName().isBlank()) {
            warnings.add(new ExtractionWarning(
                    Severity.INFO,
                    "ENTITY_NAME_NOT_FOUND",
                    null,
                    null,
                    "Entity name could not be extracted from the document header.",
                    null
            ));
        }

        long unknownMandatory = columns.stream().filter(c -> c.mandatory() == null).count();
        if (unknownMandatory > 0) {
            warnings.add(new ExtractionWarning(
                    Severity.INFO,
                    "MANDATORY_VALUE_NOT_TEXTUAL",
                    null,
                    null,
                    "Mandatory values remain unresolved for " + unknownMandatory
                            + " of " + columns.size() + " rows because no explicit checkbox/text convention was detected for the table.",
                    Long.toString(unknownMandatory)
            ));
        }

        Set<String> sourceMissingTypeFields = new LinkedHashSet<>();
        for (ExtractionWarning warning : warnings) {
            if (("FIELD_TYPE_NOT_PRESENT_IN_SOURCE".equals(warning.code())
                    || "FIELD_TYPE_INVALID_SOURCE_TOKEN".equals(warning.code()))
                    && warning.fieldName() != null) {
                sourceMissingTypeFields.add(warning.fieldName().toUpperCase(Locale.ROOT));
            }
        }

        Set<String> seen = new LinkedHashSet<>();
        for (ColumnDefinition column : columns) {
            String normalized = column.fieldName().toUpperCase(Locale.ROOT);
            if (!seen.add(normalized)) {
                warnings.add(new ExtractionWarning(
                        Severity.WARNING,
                        "DUPLICATE_FIELD_NAME",
                        column.fieldName(),
                        column.sourceRowIndex(),
                        "Duplicate technical field name found.",
                        column.fieldNameRaw()
                ));
            }
            if (column.typeRaw().isBlank()
                    && !sourceMissingTypeFields.contains(normalized)) {
                warnings.add(new ExtractionWarning(
                        Severity.WARNING,
                        "FIELD_TYPE_UNRELIABLE",
                        column.fieldName(),
                        column.sourceRowIndex(),
                        "The field type is empty after layout resolution, but the source row was not confidently classified as type-absent.",
                        null
                ));
            }
        }
    }

    private Boolean parseMandatory(String raw) {
        String value = TextNormalizer.cleanCell(raw);
        if (value.isBlank()) {
            return null;
        }
        if (CHECKED_SYMBOL.matcher(value).find()) {
            return Boolean.TRUE;
        }
        if (UNCHECKED_SYMBOL.matcher(value).find()) {
            return Boolean.FALSE;
        }

        String token = value
                .replaceAll("^[\\s()\\[\\]{}<>._:：,،;؛-]+", "")
                .replaceAll("[\\s()\\[\\]{}<>._:：,،;؛-]+$", "")
                .toUpperCase(Locale.ROOT);
        if (CHECKED_VALUES.contains(token)) {
            return Boolean.TRUE;
        }
        if (UNCHECKED_VALUES.contains(token)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private String match(Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source == null ? "" : source);
        if (!matcher.find()) {
            return "";
        }
        return TextNormalizer.cleanCell(matcher.group(1));
    }

    private String normalizeDate(String value) {
        return DATE_SPACES.matcher(TextNormalizer.toLatinDigits(TextNormalizer.cleanCell(value))).replaceAll("/");
    }

    private String cell(List<String> cells, int index) {
        return index >= 0 && index < cells.size() ? cells.get(index) : "";
    }

    private String safeRelative(Path root, Path file) {
        try {
            return root.relativize(file).toString();
        } catch (IllegalArgumentException e) {
            return file.getFileName().toString();
        }
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private record ParsedDocument(
            String rawMainText,
            String rawHeaderText,
            String authoritativeMetadata,
            List<List<List<String>>> tables
    ) {
    }

    private record TableOccurrence(int start, int end, String rawTableName) {
    }

    private record TableEntityPair(
            String rawTableName,
            String normalizedTableName,
            String rawEntityName,
            boolean entityLabelPresent
    ) {
    }

    private record MetadataSanitization(Metadata metadata, boolean rejected) {
    }

    private record ParsedMetadata(
            Metadata metadata,
            boolean fromFilename,
            boolean fromEntityName,
            String originalHeaderTableName,
            String originalEntityName,
            String fileNameTableName,
            boolean persianCandidateRejected
    ) {
    }
}
