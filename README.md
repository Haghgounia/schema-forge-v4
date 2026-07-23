# SchemaForge v4 - Initial Project

این نسخه، اسکلت اولیه فاز چهار است و بر قواعد زیر بنا شده است:

- ساختار ساده و feature-oriented؛ بدون لایه‌های Enterprise/DDD/Hexagonal.
- `dialect` در ریشه پروژه حفظ شده است.
- هر دیتابیس فقط یک Repository دارد.
- Metadata در هر API call مستقیماً از دیتابیس خوانده می‌شود؛ Cache کاربردی وجود ندارد.
- Normalization عمومی فقط در `parser/SchemaNormalizer` است.
- Normalization وابسته به دیتابیس در همان Dialect انجام می‌شود.
- Controller مستقیماً Engineهای اجرایی را فراخوانی می‌کند؛ Serviceهای thin ایجاد نشده‌اند.

## اجرای تست

```bash
mvn clean test
```

## وضعیت این تحویل

این تحویل یک Bootstrap قابل Compile برای شروع Patchهای فاز چهار است. پیاده‌سازی‌های کامل فاز سه هنوز منتقل نشده‌اند؛ فایل `MIGRATION-MANIFEST.md` فهرست انتقال پیشنهادی و مقصد هر کلاس را مشخص می‌کند.
