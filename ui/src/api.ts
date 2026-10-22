import type {
  BulkStatusResult, DecisionRequest, DecisionResult, GlossaryKind,
  GlossaryStatus, GlossaryTerm, GlossaryTermRequest,
  HistoryEvent, ImportReportResult, ImportWorkbookResult, Page, QueueItem, SpellingAdvice,
} from './types';
import { ApiError, fetchJson, fetchDownload, params, uploadForm } from './transport.ts';
export { ApiError };
export const API_ROOT = '/api/v1/glossary';

export const glossaryApi = {
  terms: (values: { q?: string; kind?: GlossaryKind; status?: GlossaryStatus; page: number; size: number }, signal?: AbortSignal) =>
    fetchJson<Page<GlossaryTerm>>(`${API_ROOT}/terms?${params(values)}`, { signal }),
  term: (id: number, signal?: AbortSignal) =>
    fetchJson<GlossaryTerm>(`${API_ROOT}/terms/${encodeURIComponent(String(id))}`, { signal }),
  create: (adminKey: string, value: GlossaryTermRequest) =>
    fetchJson<GlossaryTerm>(`${API_ROOT}/terms`, { method: 'POST', adminKey, json: value }),
  update: (adminKey: string, id: number, value: GlossaryTermRequest) =>
    fetchJson<GlossaryTerm>(`${API_ROOT}/terms/${encodeURIComponent(String(id))}`, { method: 'PUT', adminKey, json: value }),
  remove: (adminKey: string, id: number) =>
    fetchJson<void>(`${API_ROOT}/terms/${encodeURIComponent(String(id))}`, { method: 'DELETE', adminKey }),
  bulkStatus: (adminKey: string, values: { kind?: GlossaryKind; from: GlossaryStatus; to: GlossaryStatus }) =>
    fetchJson<BulkStatusResult>(`${API_ROOT}/bulk-status?${params(values)}`, { method: 'POST', adminKey }),
  importWorkbook: (adminKey: string, file: File, status: GlossaryStatus) =>
    fetchJson<ImportWorkbookResult>(`${API_ROOT}/import?${params({ status })}`,
      { method: 'POST', adminKey, body: uploadForm(file) }),
  exportCsv: () => fetchDownload(`${API_ROOT}/export.csv`, 'schemaforge-glossary.csv'),
  exportXlsx: () => fetchDownload(`${API_ROOT}/export.xlsx`, 'schemaforge-glossary.xlsx'),
  queue: (key: string, page: number, size: number, includeResolved: boolean, signal?: AbortSignal) =>
    fetchJson<Page<QueueItem>>(`${API_ROOT}/review/queue?${params({page, size, includeResolved})}`,
      { adminKey: key, signal }),
  history: (key: string, page: number, size: number, term?: string, signal?: AbortSignal) =>
    fetchJson<Page<HistoryEvent>>(`${API_ROOT}/review/history?${params({page, size, term})}`,
      { adminKey: key, signal }),
  decision: (key: string, value: DecisionRequest) =>
    fetchJson<DecisionResult>(`${API_ROOT}/review/decision`, { method: 'POST', adminKey: key, json: value }),
  importReport: (key: string, file: File) =>
    fetchJson<ImportReportResult>(`${API_ROOT}/review/import-report`, { method: 'POST', adminKey: key, body: uploadForm(file) }),
  spelling: (term: string, objectType: 'TABLE' | 'COLUMN', signal?: AbortSignal) =>
    fetchJson<SpellingAdvice>(`${API_ROOT}/spelling-suggestions?${params({term, objectType, limit: 5})}`, { signal }),
};
