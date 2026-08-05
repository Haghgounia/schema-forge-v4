package com.behsazan.schemaforge.specification.parser.legacy;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.ColumnDefinition;
import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.ExtractionWarning;
import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.FileResult;
import static com.behsazan.schemaforge.specification.parser.legacy.ExtractionModels.Metadata;

/** Conservative confidence classification for Persian table and column labels. */
final class PersianNameQuality {
    enum Status {
        TRUSTED,
        NOT_PRESENT,
        UNRELIABLE
    }

    private static final Pattern ARABIC_SCRIPT = Pattern.compile("[\\p{IsArabic}]");
    private static final Pattern LATIN_IDENTIFIER = Pattern.compile(
            "(?i)(?<![A-Z0-9_$#])[A-Z_$#][A-Z0-9_$#]{6,}(?![A-Z0-9_$#])"
    );
    private static final Pattern STRUCTURAL_NOISE = Pattern.compile(
            "(?i)(?:نام\\s*(?:فیلد|فيلد|جدول|موجودیت|موجوديت)|نوع\\s*(?:داده|طول)|"
                    + "پیش\\s*فرض|پيش\\s*فرض|کلید\\s*(?:اصلی|خارجی)|كليد\\s*(?:اصلي|خارجي)|"
                    + "محدودیت|محدوديت|دامنه|شرح\\s*تغییرات|"
                    + "CREATE\\s+TABLE|ALTER\\s+TABLE|COMMENT\\s+ON)"
    );
    private static final Pattern DOMAIN_VALUE = Pattern.compile("^\\s*[0-9۰-۹٠-٩]+\\s*[-:=.)]\\s*.+");
    private static final Pattern HISTORY_GRID = Pattern.compile(
            "(?iu)(?:تاریخچه|تاريخچه)\\s+(?:تاریخ|تاريخ)\\s+نام\\s+طراح\\s+شرح\\s+پروژه"
    );
    private static final Pattern CHANGE_LOG_NOISE = Pattern.compile(
            "(?iu)(?:نام\\s+طراح|شرح\\s+پروژه|ایجاد\\s+(?:جدول|سند)|ايجاد\\s+(?:جدول|سند)|"
                    + "افزودن\\s+فیلد|افزودن\\s+فيلد|اضافه\\s+شدن\\s+فیلد|اضافه\\s+شدن\\s+فيلد|"
                    + "اصلاح\\s+(?:نوع\\s+)?فیلد|اصلاح\\s+(?:نوع\\s+)?فيلد)"
    );
    private static final Pattern DATE_TOKEN = Pattern.compile(
            "(?<![0-9۰-۹٠-٩])[0-9۰-۹٠-٩]{1,4}\\s*/\\s*[0-9۰-۹٠-٩]{1,2}\\s*/\\s*[0-9۰-۹٠-٩]{1,4}(?![0-9۰-۹٠-٩])"
    );
    private static final Pattern TRUNCATED_HISTORY = Pattern.compile(
            "(?iu)^(?:ودجه|جه)\\s+(?:[0-9۰-۹٠-٩/]+\\s+)?[^\\n]{0,80}(?:افزودن|اصلاح|ایجاد|ايجاد)"
    );

    private PersianNameQuality() {
    }

    static Status tableStatus(FileResult result) {
        if (result == null || result.metadata() == null) {
            return Status.NOT_PRESENT;
        }
        Metadata metadata = result.metadata();
        String value = TextNormalizer.cleanCell(metadata.persianTableName());
        if (value.isBlank()) {
            List<ExtractionWarning> warnings = result.warnings();
            boolean rejected = warnings.stream().anyMatch(warning ->
                    "PERSIAN_TABLE_NAME_NOT_RELIABLE".equals(warning.code())
                            || "PERSIAN_TABLE_NAME_PARSE_FAILED".equals(warning.code()));
            return rejected ? Status.UNRELIABLE : Status.NOT_PRESENT;
        }

        String source = TextNormalizer.cleanCell(metadata.persianTableNameSource());
        if (!"EXPLICIT_ENTITY_HEADER".equalsIgnoreCase(source)
                && !"DESCRIPTIVE_TABLE_HEADER".equalsIgnoreCase(source)) {
            return Status.UNRELIABLE;
        }
        if (value.codePointCount(0, value.length()) < 3
                || value.codePointCount(0, value.length()) > 180
                || !ARABIC_SCRIPT.matcher(value).find()
                || HISTORY_GRID.matcher(value).find()
                || TRUNCATED_HISTORY.matcher(value).find()
                || (DATE_TOKEN.matcher(value).find() && CHANGE_LOG_NOISE.matcher(value).find())) {
            return Status.UNRELIABLE;
        }

        String technicalName = TextNormalizer.cleanCell(metadata.tableName());
        if (!technicalName.isBlank()) {
            String upperValue = value.toUpperCase(Locale.ROOT);
            String upperTechnical = technicalName.toUpperCase(Locale.ROOT);
            if (upperValue.matches(".*(?<![A-Z0-9_$#])"
                    + Pattern.quote(upperTechnical)
                    + "(?![A-Z0-9_$#]).*")) {
                return Status.UNRELIABLE;
            }
        }
        return Status.TRUSTED;
    }

    static Status columnStatus(ColumnDefinition column) {
        if (column == null) {
            return Status.NOT_PRESENT;
        }
        String value = TextNormalizer.cleanCell(column.persianTitle());
        if (value.isBlank()) {
            return Status.NOT_PRESENT;
        }
        if (value.codePointCount(0, value.length()) < 2
                || value.codePointCount(0, value.length()) > 180
                || !ARABIC_SCRIPT.matcher(value).find()
                || STRUCTURAL_NOISE.matcher(value).find()
                || DOMAIN_VALUE.matcher(value).matches()) {
            return Status.UNRELIABLE;
        }

        String technicalName = TextNormalizer.cleanCell(column.fieldName());
        if (!technicalName.isBlank()) {
            String upperValue = value.toUpperCase(Locale.ROOT);
            String upperTechnical = technicalName.toUpperCase(Locale.ROOT);
            if (upperValue.matches(".*(?<![A-Z0-9_$#])"
                    + Pattern.quote(upperTechnical)
                    + "(?![A-Z0-9_$#]).*")) {
                return Status.UNRELIABLE;
            }
        }

        // Short domain acronyms such as IBAN are allowed. A long Latin identifier embedded in a
        // Persian caption is normally a shifted-cell artifact.
        if (LATIN_IDENTIFIER.matcher(value).find()) {
            return Status.UNRELIABLE;
        }
        return Status.TRUSTED;
    }
}
