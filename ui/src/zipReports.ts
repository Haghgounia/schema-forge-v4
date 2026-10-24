/** Client-only, read-only interpretation of the ZIP already returned by a generation REST call.
 * Does not fetch another endpoint or mutate Glossary/H2. Keep parser limits conservative.
 */
import type JSZip from 'jszip';
import { MAX_EVIDENCE_SCAN_BYTES, TermEvidenceStreamReader, type EvidenceRow } from './evidenceStream.ts';

export const MAX_ARCHIVE_BYTES = 100 * 1024 * 1024;
// A Batch ZIP can be only a few megabytes compressed while its manifest is
// tens of megabytes uncompressed. These are per-entry decompressed limits, not
// the inbound upload limit and not the overall archive size limit.
export const MAX_MANIFEST_BYTES = 32 * 1024 * 1024;
const MAX_SUMMARY_BYTES = 8 * 1024 * 1024;
// Generic report preview ONLY. Term evidence uses a separate streaming scan.
export const MAX_REPORT_BYTES = 2 * 1024 * 1024;
export const MAX_SINGLE_REPORT_DOWNLOAD_BYTES = 64 * 1024 * 1024;
const MAX_INLINE_REPORT_CHARS = 150_000;
const MAX_FALLBACK_HEADER_BYTES = 64 * 1024;
const MAX_EXCEPTIONAL_ARTIFACTS = 100;
export const MAX_DIAGRAM_BYTES = 256 * 1024; // bounded client-side rendering of DOT/MMD
export const MAX_REVIEW_CSV_BYTES = 5 * 1024 * 1024; // matches GlossaryReportCsvParser

export type ArtifactOutcome = {
  type?: string; platform?: string | null; logicalName?: string;
  status?: string; outcomeReason?: string | null; path?: string | null;
};
export type GenerationManifest = {
  manifestContract?: string;
  generation?: { id?: string; generatedAt?: string };
  source?: { origin?: string; name?: string };
  requestStatus?: string;
  validation?: { available?: boolean; errorCount?: number; warningCount?: number; recoveryWarningCount?: number };
  artifactOutcomes?: { generated?: number; blocked?: number; skipped?: number; failed?: number };
  artifacts?: ArtifactOutcome[]; // bounded sample of non-GENERATED outcomes
  exceptionalCount?: number;
  artifactDetailsTruncated?: boolean;
  batchInput?: {
    regularFileCount?: number; processableDocumentCount?: number;
    successCount?: number; failedCount?: number; skippedCount?: number;
  };
  summarySource?: 'manifest' | 'generation-summary';
};
export type CandidateRegistration = {
  mode?: string; uniqueNewTerms?: number; eligibleCandidates?: number;
  possibleTypos?: number; requiresManualReview?: number;
  insertedCandidates?: number; alreadyKnown?: number; evidenceOccurrencesRecorded?: number;
  eligibleTerms?: string[]; suspectedTypos?: string[]; manualReviewTerms?: string[];
};
export type GlossarySummary = {
  contract?: string; status?: string; checkedIdentifiers?: number;
  uniqueNewTerms?: number; uniquePendingTerms?: number;
  pluralColumnViolations?: number; uniquePluralColumnTerms?: number;
  newTerms?: string[]; pendingTerms?: string[]; pluralColumnTerms?: string[];
  candidateRegistration?: CandidateRegistration;
};
export type DiagramFile = { path: string; filename: string; engine: 'mermaid' | 'graphviz' };
export type ReportFile = { path: string; filename: string; kind: 'json' | 'csv' | 'text' | 'download-only' };
export type ZipInspection = {
  manifest: GenerationManifest | null;
  glossary: GlossarySummary | null;
  reports: ReportFile[];
  diagrams: DiagramFile[];
  warnings: string[];
  archive: JSZip;
};
export type ReportPreview =
  | { kind: 'csv'; headers: string[]; rows: string[][]; totalRows: number | null; previewRows: number; truncated: boolean }
  | { kind: 'text'; text: string; isJson: boolean; truncated: boolean };

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
function requireObject(value: unknown, label: string): Record<string, unknown> {
  if (!isObject(value)) throw new Error(`فایل ${label} قالب JSON مورد انتظار را ندارد.`);
  return value;
}

/** Stream only bounded uncompressed bytes. JSZip 3.10.1 exposes internalStream at
 * runtime but omits it from its public declaration; isolate that fact here.
 */
type BoundedZipStream = {
  on(event: 'data', listener: (data: Uint8Array) => void): BoundedZipStream;
  on(event: 'error', listener: (error: Error) => void): BoundedZipStream;
  on(event: 'end', listener: () => void): BoundedZipStream;
  pause(): void;
  resume(): void;
};
type StreamableEntry = JSZip.JSZipObject & { internalStream(type: 'uint8array'): BoundedZipStream };
function readEntryPrefix(entry: JSZip.JSZipObject, limit: number): Promise<{bytes: Uint8Array; truncated: boolean}> {
  return new Promise((resolve, reject) => {
    let done = false, total = 0;
    const chunks: Uint8Array[] = [];
    const stream = (entry as StreamableEntry).internalStream('uint8array');
    const finish = (truncated: boolean) => {
      if (done) return;
      done = true;
      const joined = new Uint8Array(total);
      let index = 0;
      for (const chunk of chunks) { joined.set(chunk, index); index += chunk.byteLength; }
      resolve({bytes: joined, truncated});
    };
    stream.on('data', data => {
      if (done) return;
      const remaining = limit - total;
      if (data.byteLength > remaining) {
        if (remaining > 0) chunks.push(data.subarray(0, remaining));
        total += remaining;
        stream.pause();
        finish(true);
        return;
      }
      total += data.byteLength;
      chunks.push(data);
    });
    stream.on('error', error => { if (!done) { done = true; reject(error); } });
    stream.on('end', () => finish(false));
    stream.resume();
  });
}
async function readEntryBytes(entry: JSZip.JSZipObject, limit: number): Promise<Uint8Array> {
  const result = await readEntryPrefix(entry, limit);
  if (result.truncated) throw new Error('حجم فایل برای استخراج تکی بیش از حد مجاز است؛ ZIP اصلی را دریافت کنید.');
  return result.bytes;
}
export async function readEntryText(entry: JSZip.JSZipObject, limit = MAX_REPORT_BYTES): Promise<string> {
  return new TextDecoder('utf-8', {fatal: true}).decode(await readEntryBytes(entry, limit));
}

function safeCount(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : undefined;
}
function getBatchInput(value: unknown): GenerationManifest['batchInput'] | undefined {
  if (!isObject(value)) return undefined;
  return {
    regularFileCount: safeCount(value.regularFileCount),
    processableDocumentCount: safeCount(value.processableDocumentCount),
    successCount: safeCount(value.successCount), failedCount: safeCount(value.failedCount),
    skippedCount: safeCount(value.skippedCount),
  };
}
/** Keep only the exceptional examples used by the UI, never thousands of generated entries. */
function compactManifest(value: Record<string, unknown>): GenerationManifest {
  const all = Array.isArray(value.artifacts) ? value.artifacts : [];
  let exceptionalCount = 0;
  const artifacts: ArtifactOutcome[] = [];
  for (const item of all) {
    if (!isObject(item) || !item.status || item.status === 'GENERATED') continue;
    exceptionalCount++;
    if (artifacts.length < MAX_EXCEPTIONAL_ARTIFACTS) artifacts.push(item as ArtifactOutcome);
  }
  const extensions = isObject(value.extensions) ? value.extensions : null;
  return {
    manifestContract: typeof value.manifestContract === 'string' ? value.manifestContract : undefined,
    generation: isObject(value.generation) ? value.generation : undefined,
    source: isObject(value.source) ? value.source : undefined,
    requestStatus: typeof value.requestStatus === 'string' ? value.requestStatus : undefined,
    validation: isObject(value.validation) ? value.validation : undefined,
    artifactOutcomes: isObject(value.artifactOutcomes) ? value.artifactOutcomes : undefined,
    batchInput: getBatchInput(extensions?.batchInput),
    exceptionalCount, artifacts, artifactDetailsTruncated: exceptionalCount > artifacts.length,
    summarySource: 'manifest',
  } as GenerationManifest;
}

/** If even the bounded large manifest cannot be parsed, use only factual lines
 * from Java's existing generation summary; never fabricate artifact counts.
 */
async function generationSummaryFallback(archive: JSZip): Promise<GenerationManifest | null> {
  const entry = archive.file('reports/schemaforge-generation-summary.txt');
  if (!entry) return null;
  const prefix = await readEntryPrefix(entry, MAX_FALLBACK_HEADER_BYTES);
  const lines = new TextDecoder('utf-8').decode(prefix.bytes).split(/\r?\n/);
  const fields = new Map<string, string>();
  for (const line of lines) {
    const match = /^([A-Za-z ]{2,40})\s*:\s*(.*?)\s*$/.exec(line);
    if (match) fields.set(match[1].trim(), match[2]);
  }
  const status = fields.get('Request Status');
  if (status !== 'SUCCESS' && status !== 'PARTIAL_SUCCESS') return null;
  const numeric = (label: string) => {
    const value = fields.get(label);
    return value && /^\d+$/.test(value) ? safeCount(Number(value)) : undefined;
  };
  return {
    requestStatus: status,
    source: {name: fields.get('Source File') ?? ''},
    batchInput: {
      processableDocumentCount: numeric('Input Documents'),
      successCount: numeric('Successful Documents'),
      failedCount: numeric('Failed Documents'), skippedCount: numeric('Skipped Inputs'),
    },
    summarySource: 'generation-summary',
  };
}

function lastCompleteCsvRow(text: string): string {
  let quoted = false, end = 0;
  for (let i = 0; i < text.length; i++) {
    if (text[i] === '"') {
      if (quoted && text[i + 1] === '"') i++;
      else quoted = !quoted;
    } else if ((text[i] === '\r' || text[i] === '\n') && !quoted) {
      end = i + 1;
      if (text[i] === '\r' && text[i + 1] === '\n') end = ++i + 1;
    }
  }
  return text.slice(0, end);
}

function kindOf(path: string): ReportFile['kind'] {
  const normalized = path.toLowerCase();
  if (normalized.endsWith('.json')) return 'json';
  if (normalized.endsWith('.csv')) return 'csv';
  if (/\.(txt|md|log)$/.test(normalized)) return 'text';
  return 'download-only';
}

export async function inspectGenerationZip(blob: Blob): Promise<ZipInspection> {
  if (blob.size > MAX_ARCHIVE_BYTES) {
    throw new Error('ZIP دریافت شد، اما برای پیش‌نمایش مرورگر بیش از ۱۰۰ مگابایت است. خود فایل ZIP قابل دانلود است.');
  }
  const JSZipModule = await import('jszip'); // Load only after a generation completes.
  const archive = await JSZipModule.default.loadAsync(await blob.arrayBuffer(), { checkCRC32: false });
  const warnings: string[] = [];
  const readJson = async (path: string, limit: number): Promise<Record<string, unknown> | null> => {
    const entry = archive.file(path);
    if (!entry) { warnings.push(`فایل ${path} داخل بسته موجود نیست.`); return null; }
    try { return requireObject(JSON.parse(await readEntryText(entry, limit)) as unknown, path); }
    catch (error) {
      const exceeded = error instanceof Error && /حد مجاز/.test(error.message);
      warnings.push(exceeded
        ? `فایل ${path} از سقف امن خواندن در مرورگر (${Math.floor(limit / 1048576)} مگابایتِ بازشده) بزرگ‌تر است؛ ZIP و گزارش اصلی همچنان معتبر و قابل دریافت‌اند.`
        : `فایل ${path} قابل تفسیر نیست؛ نسخه اصلی را از ZIP بررسی کنید.`);
      return null;
    }
  };
  const rawManifest = await readJson('manifest.json', MAX_MANIFEST_BYTES);
  let manifest = rawManifest ? compactManifest(rawManifest) : null;
  if (!manifest) {
    try {
      manifest = await generationSummaryFallback(archive);
      if (manifest) warnings.push('وضعیت درخواست از گزارش متنی تولید استخراج شده، نه Manifest کامل؛ شمارنده‌های Artifact در دسترس نیستند.');
    } catch { /* Missing or unreadable fallback remains an explicitly unknown status. */ }
  }
  const glossary = archive.file('reports/glossary/summary.json')
    ? await readJson('reports/glossary/summary.json', MAX_SUMMARY_BYTES) as GlossarySummary | null
    : null;
  const reports = Object.values(archive.files)
    .filter(entry => !entry.dir && (entry.name === 'manifest.json' || entry.name.startsWith('reports/')))
    .map(entry => ({
      path: entry.name,
      filename: (entry.name.split('/').pop() || 'report.txt').replace(/[\\/:*?"<>|\x00-\x1f]/g, '_').slice(0, 140),
      kind: kindOf(entry.name),
    }))
    .sort((a, b) => a.path.localeCompare(b.path, 'en'));
  const diagrams: DiagramFile[] = Object.values(archive.files)
    .filter(entry => !entry.dir && /^diagram\/(mermaid|graphviz)\/.+/i.test(entry.name))
    .flatMap(entry => {
      const path = entry.name;
      const engine: DiagramFile['engine'] | null = /^diagram\/mermaid\//i.test(path) && /\.mmd$/i.test(path)
        ? 'mermaid' : /^diagram\/graphviz\//i.test(path) && /\.(dot|gv)$/i.test(path)
          ? 'graphviz' : null;
      return engine ? [{ path,
        filename: (path.split('/').pop() || 'diagram').replace(/[\\/:*?"<>|\x00-\x1f]/g, '_').slice(0, 140), engine }] : [];
    })
    .sort((a, b) => a.path.localeCompare(b.path, 'en'));
  if (!glossary) warnings.push('گزارش خلاصه Glossary در این بسته موجود نیست یا قابل خواندن نیست.');
  return { manifest, glossary, reports, diagrams, warnings, archive };
}

/** Parses standard RFC 4180-style CSV fields (including escaped quotes and embedded newlines). */
export function parseCsv(text: string): string[][] {
  const result: string[][] = [];
  let row: string[] = [], field = '', quoted = false;
  for (let i = 0; i < text.length; i++) {
    const char = text[i];
    if (char === '"') {
      if (quoted && text[i + 1] === '"') { field += '"'; i++; }
      else quoted = !quoted;
    } else if (char === ',' && !quoted) {
      row.push(field); field = '';
    } else if ((char === '\n' || char === '\r') && !quoted) {
      if (char === '\r' && text[i + 1] === '\n') i++;
      row.push(field); field = '';
      if (row.some(cell => cell !== '')) result.push(row);
      row = [];
    } else field += char;
  }
  row.push(field);
  if (row.some(cell => cell !== '')) result.push(row);
  return result;
}

export async function readReportPreview(inspection: ZipInspection, report: ReportFile): Promise<ReportPreview> {
  if (!inspection.reports.some(item => item.path === report.path)) throw new Error('گزارش انتخابی در بسته وجود ندارد.');
  if (report.kind === 'download-only') throw new Error('این گزارش باینری است و فقط امکان دریافت فایل اصلی آن وجود دارد.');
  const entry = inspection.archive.file(report.path);
  if (!entry) throw new Error('فایل گزارش در ZIP پیدا نشد.');
  // Read a bounded uncompressed prefix; a huge report must not blank the whole panel.
  const prefix = await readEntryPrefix(entry, MAX_REPORT_BYTES);
  const original = new TextDecoder('utf-8').decode(prefix.bytes);
  if (report.kind === 'csv') {
    const text = prefix.truncated ? lastCompleteCsvRow(original) : original;
    const parsed = parseCsv(text);
    const [headers = [], ...rows] = parsed;
    return {kind: 'csv', headers, rows: rows.slice(0, 75), totalRows: prefix.truncated ? null : rows.length,
      previewRows: Math.min(rows.length, 75), truncated: prefix.truncated};
  }
  if (prefix.truncated && report.path === 'manifest.json' && inspection.manifest) {
    const compact = JSON.stringify(inspection.manifest, null, 2);
    return {kind: 'text', text: compact.slice(0, MAX_INLINE_REPORT_CHARS), isJson: true, truncated: true};
  }
  let text = original, isJson = false;
  if (report.kind === 'json' && !prefix.truncated) {
    try { text = JSON.stringify(JSON.parse(original), null, 2); isJson = true; }
    catch { /* Present source safely without claiming it is valid JSON. */ }
  }
  const oversizedInline = text.length > MAX_INLINE_REPORT_CHARS;
  if (oversizedInline) text = text.slice(0, MAX_INLINE_REPORT_CHARS);
  return {kind: 'text', text, isJson, truncated: prefix.truncated || oversizedInline};
}

export async function extractReportDownload(inspection: ZipInspection, report: ReportFile): Promise<Blob> {
  if (!inspection.reports.some(item => item.path === report.path)) throw new Error('گزارش انتخابی در بسته وجود ندارد.');
  const entry = inspection.archive.file(report.path);
  if (!entry) throw new Error('فایل گزارش موجود نیست.');
  // Individual report download is limited too; the full ZIP remains available separately.
  const bytes = await readEntryBytes(entry, MAX_SINGLE_REPORT_DOWNLOAD_BYTES);
  return new Blob([new Uint8Array(bytes)]);
}

export type TermEvidence = EvidenceRow;
/**
 * Scan all rows of the ZIP's evidence CSV, keeping only the requested term.
 * The 2 MiB limit belongs to inline report PREVIEW, not to a full evidence
 * search. JSZip inflates chunks without assembling the entire CSV in memory.
 * Abort an obsolete search when the user picks another word.
 */
export function readTermEvidence(
  inspection: ZipInspection, term: string, signal?: AbortSignal,
): Promise<{total: number; rows: TermEvidence[]} | null> {
  const entry = inspection.archive.file('reports/glossary/unknown-terms.csv');
  if (!entry) return Promise.resolve(null);
  return new Promise((resolve, reject) => {
    const reader = new TermEvidenceStreamReader(term);
    const stream = (entry as StreamableEntry).internalStream('uint8array');
    let bytesRead = 0;
    let settled = false;
    const abort = () => fail(new Error('خواندن شواهد لغو شد.'));
    const settle = (action: () => void) => {
      if (settled) return;
      settled = true;
      signal?.removeEventListener('abort', abort);
      action();
    };
    const fail = (error: Error) => settle(() => { stream.pause(); reject(error); });
    if (signal?.aborted) { fail(new Error('خواندن شواهد لغو شد.')); return; }
    signal?.addEventListener('abort', abort, { once: true });
    stream.on('data', chunk => {
      if (settled) return;
      try {
        bytesRead += chunk.byteLength;
        if (bytesRead > MAX_EVIDENCE_SCAN_BYTES) {
          throw new Error('گزارش شواهد از سقف امن پردازش تدریجی ۱۲۸ مگابایت بیشتر است. ZIP اصلی را بررسی کنید.');
        }
        reader.writeBytes(chunk);
      } catch (error) { fail(error instanceof Error ? error : new Error('پردازش شواهد ممکن نبود.')); }
    });
    stream.on('error', () => fail(new Error('استخراج CSV شواهد از ZIP ممکن نبود.')));
    stream.on('end', () => {
      if (settled) return;
      try {
        const result = reader.finish();
        settle(() => resolve(result));
      } catch (error) { fail(error instanceof Error ? error : new Error('گزارش شواهد قابل تفسیر نیست.')); }
    });
    stream.resume();
  });
}

/** The original diagram source is read from the existing ZIP, never from a URL. */
export async function readDiagramSource(inspection: ZipInspection, diagram: DiagramFile): Promise<string> {
  if (!inspection.diagrams.some(item => item.path === diagram.path && item.engine === diagram.engine)) {
    throw new Error('نمودار انتخابی در فهرست ZIP معتبر نیست.');
  }
  const entry = inspection.archive.file(diagram.path);
  if (!entry) throw new Error('فایل نمودار در ZIP موجود نیست.');
  return readEntryText(entry, MAX_DIAGRAM_BYTES);
}

/** Preserve the exact CSV bytes and report digest used by the Java import endpoint. */
export async function reviewCsvFromZip(inspection: ZipInspection): Promise<File | null> {
  const entry = inspection.archive.file('reports/glossary/unknown-terms.csv');
  if (!entry) return null;
  const bytes = await readEntryBytes(entry, MAX_REVIEW_CSV_BYTES);
  if (bytes.byteLength === 0) throw new Error('گزارش Glossary خالی است.');
  return new File([new Uint8Array(bytes)], 'unknown-terms.csv', { type: 'text/csv' });
}

/** Original diagram download is independent of the stricter preview/render limit. */
export async function extractDiagramDownload(inspection: ZipInspection, diagram: DiagramFile): Promise<Blob> {
  if (!inspection.diagrams.some(item => item.path === diagram.path && item.engine === diagram.engine)) {
    throw new Error('نمودار انتخابی در فهرست ZIP معتبر نیست.');
  }
  const entry = inspection.archive.file(diagram.path);
  if (!entry) throw new Error('فایل نمودار در ZIP موجود نیست.');
  const bytes = await readEntryBytes(entry, 10 * 1024 * 1024);
  return new Blob([new Uint8Array(bytes)], {type: 'text/plain;charset=utf-8'});
}
