# Patch-01 - Word Parser Migration

## هدف
انتقال رفتاری Word Parser فاز 3 به فاز 4 بدون بازنویسی منطق استخراج.

## منتقل‌شده از فاز 3
- `DocxSpecificationParser`
- قراردادهای `SpecificationParser` و `SpecificationSource`
- `CheckConstraintNormalizer`
- `NumericRangeParser` و `NumericRange`
- `OracleIdentifierValidator`
- مدل Canonical کامل فاز 3 در پکیج `domain`
- تست اصلی Parser، تست Corpus و تست Range
- تمام نمونه‌های DOCX موجود در `src/test/resources/samples/docx`

## API

`POST /api/schema/import/word`

ورودی: `multipart/form-data` با part به نام `file`

خروجی: `DatabaseSchema` استخراج‌شده از فایل Word.

## اصل مهاجرت
در این Patch نام پکیج‌ها و مدل‌های فاز 3 عمداً حفظ شده‌اند تا ابتدا Feature Parity تثبیت شود. ادغام نهایی `domain` با `model` و جابه‌جایی Parser به ساختار نهایی، فقط پس از سبز شدن همه تست‌های Corpus انجام خواهد شد.

## وضعیت تست در محیط تولید بسته
اجرای Maven در محیط فعلی ممکن نشد؛ Maven نصب نبود و Maven Wrapper نیز به دلیل عدم دسترسی شبکه نتوانست Distribution را دریافت کند. تست‌ها و Wrapper داخل پروژه قرار گرفته‌اند تا روی محیط توسعه اجرا شوند.
