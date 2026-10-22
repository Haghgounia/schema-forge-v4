import { useEffect, useState, type FormEvent } from 'react';
import { glossaryApi } from '../api';
import type { GlossaryKind, GlossaryStatus, GlossaryTerm, Page, SpellingAdvice } from '../types';
import { Paging, Status } from '../utils';
import { saveDownload } from '../transport';

const PAGE_SIZE = 25;
type Filters = { q: string; kind: GlossaryKind | ''; status: GlossaryStatus | '' };
const emptyFilters: Filters = { q: '', kind: '', status: '' };

function SpellSuggestions() {
  const [term, setTerm] = useState('');
  const [objectType, setObjectType] = useState<'TABLE'|'COLUMN'>('COLUMN');
  const [advice, setAdvice] = useState<SpellingAdvice | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  async function check(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setAdvice(null);
    setError('');
    setLoading(true);
    try {
      setAdvice(await glossaryApi.spelling(term.trim(), objectType));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'بررسی ناموفق بود.');
    } finally { setLoading(false); }
  }
  return <details className="import-details">
    <summary>پیشنهادهای املایی (فقط راهنما)</summary>
    <p className="small muted">این بررسی واژه را اصلاح یا تأیید نمی‌کند و تغییری در پایگاه داده نمی‌دهد.</p>
    <form className="filter-form" onSubmit={check}>
      <label className="field grow">یک جزء واژه (بدون _)
        <input dir="ltr" required maxLength={64} value={term} onChange={e => setTerm(e.target.value)} placeholder="مثال: CUTOMER" />
      </label>
      <label className="field">نوع شناسه
        <select value={objectType} onChange={e => setObjectType(e.target.value as 'TABLE' | 'COLUMN')}>
          <option value="COLUMN">ستون</option><option value="TABLE">جدول</option>
        </select>
      </label>
      <button type="submit" className="btn secondary" disabled={!term.trim() || loading}>{loading ? 'بررسی…' : 'بررسی املا'}</button>
    </form>
    {error && <p role="alert" className="message error">{error}</p>}
    {advice && <div className="advice-result" role="status">
      <p><strong dir="ltr">{advice.term}</strong> · {advice.classification}
        {advice.canonicalSingular ? <> · مفرد: <code dir="ltr">{advice.canonicalSingular}</code></> : null}</p>
      {advice.suggestions.length > 0
        ? <p>واژه‌های مشابه: {advice.suggestions.map(v => v.key).join('، ')}</p>
        : <p className="muted small">پیشنهادی پیدا نشد؛ این نتیجه به معنی معتبر بودن واژه نیست.</p>}
    </div>}
  </details>;
}

export default function TermsView({ onEditTerm }: { onEditTerm: (id: number) => void }) {
  const [draft, setDraft] = useState<Filters>(emptyFilters);
  const [filters, setFilters] = useState<Filters>(emptyFilters);
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<Page<GlossaryTerm> | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [exportBusy, setExportBusy] = useState<'csv'|'xlsx'|null>(null);
  const [exportError, setExportError] = useState('');
  async function exportGlossary(format: 'csv'|'xlsx') {
    setExportBusy(format); setExportError('');
    try { saveDownload(format === 'csv' ? await glossaryApi.exportCsv() : await glossaryApi.exportXlsx()); }
    catch (err) { setExportError(err instanceof Error ? err.message : 'دریافت خروجی Glossary ناموفق بود.'); }
    finally { setExportBusy(null); }
  }
  useEffect(() => {
    const controller = new AbortController();
    setError('');
    setLoading(true);
    setResult(null);
    glossaryApi.terms({
      q: filters.q || undefined,
      kind: filters.kind || undefined,
      status: filters.status || undefined,
      page, size: PAGE_SIZE,
    }, controller.signal)
      .then(v => { if (!controller.signal.aborted) setResult(v); })
      .catch(e => { if (!controller.signal.aborted) {
        setResult(null);
        setError(e instanceof Error ? e.message : 'جست‌وجو ناموفق بود.');
      }})
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [filters, page]);
  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPage(0);
    setFilters({ ...draft, q: draft.q.trim() });
  }
  return <>
    <div className="page-heading"><div><h2>واژه‌نامه</h2><p>جست‌وجو در اصطلاحات، وضعیت تصویب و منشأ واژگان</p></div>
      <div className="export-actions">
        <button type="button" className="btn secondary" disabled={exportBusy !== null} onClick={() => void exportGlossary('csv')}>CSV</button>
        <button type="button" className="btn secondary" disabled={exportBusy !== null} onClick={() => void exportGlossary('xlsx')}>Excel</button>
      </div>
    </div>
    {exportError && <p role="alert" className="message error">{exportError}</p>}
    <form className="panel filter-form" onSubmit={search}>
      <label className="field grow">نام واژه
        <input dir="ltr" value={draft.q} onChange={e => setDraft({...draft, q: e.target.value})}
          placeholder="نام انگلیسی یا بخشی از آن" />
      </label>
      <label className="field">نوع
        <select value={draft.kind} onChange={e => setDraft({...draft, kind: e.target.value as Filters['kind']})}>
          <option value="">همه</option><option value="ATOMIC">ATOMIC</option><option value="PHRASE">PHRASE</option>
        </select>
      </label>
      <label className="field">وضعیت
        <select value={draft.status} onChange={e => setDraft({...draft, status: e.target.value as Filters['status']})}>
          <option value="">همه</option><option value="CANDIDATE">در انتظار</option><option value="APPROVED">تأیید شده</option><option value="REJECTED">رد شده</option>
        </select>
      </label>
      <button className="btn primary" type="submit">جست‌وجو</button>
    </form>
    <section className="panel table-panel" aria-label="نتایج جست‌وجوی واژه‌نامه">
      {error && <p className="message error" role="alert">{error}</p>}
      {loading && <p className="loading" role="status">در حال دریافت واژگان…</p>}
      {!loading && result?.items.length === 0 && <p className="empty">نتیجه‌ای پیدا نشد.</p>}
      {!loading && result && result.items.length > 0 && <div className="table-scroll"><table>
        <thead><tr><th>واژه</th><th>نوع</th><th>وضعیت</th><th>معادل فارسی</th><th>ماژول</th><th>منبع</th><th>جزئیات</th></tr></thead>
        <tbody>{result.items.map(term => <tr key={term.id}>
          <td dir="ltr" className="strong-term">{term.key}</td><td>{term.kind}</td>
          <td><Status value={term.status}/></td><td>{term.persian || '—'}</td>
          <td dir="ltr">{term.module || '—'}</td><td className="source-name" title={term.source}>{term.source}</td>
          <td><button className="btn secondary mini-btn" type="button" onClick={() => onEditTerm(term.id)}>مشاهده / ویرایش</button></td>
        </tr>)}</tbody>
      </table></div>}
      {!loading && result && <Paging page={result.page} size={result.size} total={result.total} change={setPage} />}
    </section>
    <SpellSuggestions />
  </>;
}
