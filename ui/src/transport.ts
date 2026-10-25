/** Same-origin transport. All authorization is enforced by the Java server. */
export class ApiError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message); this.status = status; this.name = 'ApiError';
  }
}
export type HttpOptions = {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  json?: unknown; body?: FormData; adminKey?: string; metadataToken?: string; signal?: AbortSignal; accept?: string;
};
import type {ActiveMetadataConnection} from './connectionApi.ts';
let currentMetadataConnection: ActiveMetadataConnection | null = null;
export function setActiveMetadataConnection(value: ActiveMetadataConnection | null): void {
  currentMetadataConnection = value;
}
export function getActiveMetadataConnection(): ActiveMetadataConnection | null {
  return currentMetadataConnection;
}
export function requireSelectedPlatform(platforms: readonly string[]): void {
  const selected = currentMetadataConnection;
  if (selected && (platforms.length !== 1 || platforms[0] !== selected.platform)) {
    throw new Error('The active metadata connection belongs to ' + selected.platform + '. Select only that DBMS, or disconnect.');
  }
}
export function params(values: Record<string, string | number | boolean | null | undefined>): string {
  const pairs = new URLSearchParams();
  for (const [name, value] of Object.entries(values)) {
    if (value !== undefined && value !== null && value !== '') pairs.set(name, String(value));
  }
  return pairs.toString();
}
function errorMessage(status: number): string {
  switch (status) {
    case 400: return 'درخواست نامعتبر است؛ فیلدهای ورودی را کنترل کنید.';
    case 401: return 'کلید دسترسی مدیریتی معتبر نیست.';
    case 403: return 'مجوز این عملیات وجود ندارد.';
    case 404: return 'مسیر REST پیدا نشد؛ برای Glossary فعال بودن سرویس را کنترل کنید.';
    case 409: return 'تعارض داده یا وضعیت رخ داده است؛ اطلاعات را به‌روزرسانی کنید.';
    case 413: return 'حجم فایل بیش از حد مجاز است.';
    case 422: return 'Metadata permissions could not be verified. Use Test only to inspect the JDBC/schema result.';
    case 502: case 503: case 504:
      return `HTTP ${status}: Backend در دسترس نیست یا آماده نشده است. سرویس Java (پیش‌فرض 127.0.0.1:9090) و تنظیمات Proxy را بررسی کنید.`;
    default: return `درخواست REST با خطای HTTP ${status} انجام نشد.`;
  }
}
export async function fetchResponse(path: string, options: HttpOptions = {}): Promise<Response> {
  if (!path.startsWith('/') || path.startsWith('//')) throw new Error('Only same-origin relative REST paths allowed');
  const headers = new Headers({ Accept: options.accept ?? 'application/json' });
  if (options.adminKey) headers.set('X-Glossary-Admin-Key', options.adminKey);
  const active = currentMetadataConnection;
  const token = options.metadataToken ??
    (active && (/^\/api\/v1\/conformance\//.test(path) || /^\/api\/v1\/generate\//.test(path) || /^\/api\/v1\/diagram\/mermaid\/selected/.test(path))
      ? active.token : undefined);
  if (token) headers.set('X-SchemaForge-Metadata-Connection', token);
  let body: BodyInit | undefined;
  if (options.json !== undefined) {
    headers.set('Content-Type', 'application/json'); body = JSON.stringify(options.json);
  } else if (options.body) body = options.body;
  let response: Response;
  try {
    response = await fetch(path, {
      method: options.method ?? 'GET', body, headers, signal: options.signal,
      credentials: 'same-origin', cache: 'no-store', referrerPolicy: 'no-referrer',
    });
  } catch (error) {
    if (error instanceof Error && error.name === 'AbortError') throw error;
    throw new ApiError(0, 'ارتباط شبکه برقرار نشد؛ Backend جاوا و پورت آن را بررسی کنید.');
  }
  if (!response.ok) {
    if (response.status === 401 && token) {
      throw new ApiError(401, 'The selected metadata connection expired. Disconnect and connect again.');
    }
    // For this bounded, read-only cohort endpoint, preserve only the expected
    // missing-table diagnostic (its identifiers were supplied by the caller).
    if (response.status === 400 && path === '/api/v1/conformance/selected/tables') {
      const detail = await response.clone().json().catch(() => null) as
        {contract?: string; code?: string; message?: string} | null;
      if (detail?.contract === 'schemaforge-rest-error/v1' && detail.code === 'INVALID_REQUEST' &&
          detail.message?.startsWith('Requested live table(s) missing or inaccessible in ')) {
        throw new ApiError(400, detail.message.slice(0, 600));
      }
    }
    throw new ApiError(response.status, errorMessage(response.status));
  }
  return response;
}
export async function fetchJson<T>(path: string, options: HttpOptions = {}): Promise<T> {
  const response = await fetchResponse(path, options);
  return response.status === 204 ? undefined as T : await response.json() as T;
}
export type Download = { blob: Blob; filename: string };
export async function fetchDownload(path: string, fallback: string, options: HttpOptions = {}): Promise<Download> {
  const response = await fetchResponse(path, { ...options, accept: '*/*' });
  const disposition = response.headers.get('content-disposition') ?? '';
  const utf = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
  const plain = /filename="?([^";]+)"?/i.exec(disposition);
  let fromHeader: string | undefined;
  try { fromHeader = utf ? decodeURIComponent(utf[1]) : plain?.[1]; } catch { fromHeader = undefined; }
  const filename = (fromHeader ?? fallback).replace(/[\\/:*?"<>|\x00-\x1f]/g, '_').slice(0, 150) || fallback;
  return { blob: await response.blob(), filename };
}
export function saveDownload(download: Download): void {
  const url = URL.createObjectURL(download.blob);
  const anchor = document.createElement('a');
  anchor.href = url; anchor.download = download.filename;
  document.body.appendChild(anchor); anchor.click(); anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
export function uploadForm(file: File): FormData {
  const form = new FormData(); form.append('file', file, file.name); return form;
}
