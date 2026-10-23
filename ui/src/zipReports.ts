/** Client-only, read-only interpretation of the ZIP already returned by a generation REST call.
 * Does not fetch another endpoint or mutate Glossary/H2. Keep parser limits conservative.
 */
import type JSZip from 'jszip';

export const MAX_ARCHIVE_BYTES = 100 * 1024 * 1024;
const MAX_SUMMARY_BYTES = 2 * 1024 * 1024;
export const MAX_REPORT_BYTES = 2 * 1024 * 1024;
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
  artifacts?: ArtifactOutcome[];
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
  | { kind: 'csv'; headers: string[]; rows: string[][]; totalRows: number; previewRows: number }
  | { kind: 'text'; text: string; isJson: boolean };

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
function readEntryBytes(entry: JSZip.JSZipObject, limit: number): Promise<Uint8Array> {
  return new Promise((resolve, reject) => {
    let done = false, total = 0;
    const chunks: Uint8Array[] = [];
    const stream = (entry as StreamableEntry).internalStream('uint8array');
    stream.on('data', data => {
      if (done) return;
      total += data.byteLength;
      if (total > limit) {
        done = true; stream.pause();
        reject(new Error('حجم فایل برای نمایش یا استخراج تکی بیش از حد مجاز است؛ ZIP اصلی را دریافت کنید.'));
        return;
      }
      chunks.push(data);
    });
    stream.on('error', error => { if (!done) { done = true; reject(error); } });
    stream.on('end', () => {
      if (done) return;
      done = true;
      const joined = new Uint8Array(total);
      let index = 0;
      for (const chunk of chunks) { joined.set(chunk, index); index += chunk.byteLength; }
      resolve(joined);
    });
    stream.resume();
  });
}
export async function readEntryText(entry: JSZip.JSZipObject, limit = MAX_REPORT_BYTES): Promise<string> {
  return new TextDecoder('utf-8').decode(await readEntryBytes(entry, limit));
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
  const readJson = async (path: string): Promise<Record<string, unknown> | null> => {
    const entry = archive.file(path);
    if (!entry) { warnings.push(`فایل ${path} داخل بسته موجود نیست.`); return null; }
    try { return requireObject(JSON.parse(await readEntryText(entry, MAX_SUMMARY_BYTES)) as unknown, path); }
    catch { warnings.push(`فایل ${path} قابل تفسیر نیست. برای بررسی، نسخه اصلی آن را از ZIP دریافت کنید.`); return null; }
  };
  const manifest = await readJson('manifest.json') as GenerationManifest | null;
  const glossary = archive.file('reports/glossary/summary.json')
    ? await readJson('reports/glossary/summary.json') as GlossarySummary | null
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
  const text = await readEntryText(entry);
  if (report.kind === 'csv') {
    const parsed = parseCsv(text);
    const [headers = [], ...rows] = parsed;
    return { kind: 'csv', headers, rows: rows.slice(0, 75), totalRows: rows.length, previewRows: Math.min(rows.length, 75) };
  }
  if (report.kind === 'json') {
    try { return { kind: 'text', text: JSON.stringify(JSON.parse(text), null, 2), isJson: true }; }
    catch { return { kind: 'text', text, isJson: false }; }
  }
  return { kind: 'text', text, isJson: false };
}

export async function extractReportDownload(inspection: ZipInspection, report: ReportFile): Promise<Blob> {
  if (!inspection.reports.some(item => item.path === report.path)) throw new Error('گزارش انتخابی در بسته وجود ندارد.');
  const entry = inspection.archive.file(report.path);
  if (!entry) throw new Error('فایل گزارش موجود نیست.');
  // Individual report download is limited too; the full ZIP remains available separately.
  const bytes = await readEntryBytes(entry, 10 * 1024 * 1024);
  return new Blob([new Uint8Array(bytes)]);
}

export type TermEvidence = { source: string; objectType: string; table: string; column: string; identifier: string; status: string };
export async function readTermEvidence(inspection: ZipInspection, term: string): Promise<{total: number; rows: TermEvidence[]} | null> {
  const entry = inspection.archive.file('reports/glossary/unknown-terms.csv');
  if (!entry) return null;
  const all = parseCsv(await readEntryText(entry));
  const header = (all.shift() ?? []).map(value => value.toLowerCase().replace(/^\ufeff/, ''));
  const ix = (name: string) => header.indexOf(name);
  const wordIndex = ix('unknown_term');
  if (wordIndex < 0) return null;
  const matches = all.filter(row => row[wordIndex]?.toUpperCase() === term.toUpperCase());
  const get = (row: string[], name: string) => row[ix(name)] ?? '';
  return { total: matches.length, rows: matches.slice(0, 25).map(row => ({
    source: get(row, 'source_name'), objectType: get(row, 'object_type'),
    table: get(row, 'table_name'), column: get(row, 'column_name'),
    identifier: get(row, 'identifier'), status: get(row, 'review_status'),
  })) };
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
