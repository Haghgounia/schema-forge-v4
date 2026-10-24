/** Default ingress limit for the five Schema Generation multipart endpoints.
 * Keep in sync with spring.servlet.multipart.max-file-size's default. This
 * client guard is advisory; Spring Boot remains the authoritative limit.
 */
export const MAX_GENERATION_UPLOAD_BYTES = 500 * 1024 * 1024;

export function generationUploadError(bytes: number): string | null {
  if (!Number.isFinite(bytes) || bytes < 0) return 'اندازه فایل انتخاب‌شده معتبر نیست.';
  if (bytes > MAX_GENERATION_UPLOAD_BYTES) {
    return 'فایل ورودی بیش از ۵۰۰ مگابایت است. یک فایل کوچک‌تر انتخاب کنید.';
  }
  return null;
}
