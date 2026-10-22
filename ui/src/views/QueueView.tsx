import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError, glossaryApi } from '../api';
import type { DecisionRequest, GlossaryTerm, ImportReportResult, QueueItem, Page } from '../types';
import { Paging, Status } from '../utils';

const PAGE_SIZE = 25;

function ReviewEditor({ item, adminKey, onSaved }: {
  item: QueueItem;
  adminKey: string;
  onSaved: (message: string) => void;
}) {
  const [term, setTerm] = useState<GlossaryTerm | null>(null);
  const [reason, setReason] = useState('');
  const [persian, setPersian] = useState('');
  const [module, setModule] = useState('');
  const [decision, setDecision] = useState<'' | 'APPROVED' | 'REJECTED'>('');
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    setTerm(null);
    setPersian('');
    setModule('');
    setReason('');
    setDecision('');
    setError('');
    setLoading(true);
    glossaryApi.term(item.id, controller.signal).then(value => {
      if (controller.signal.aborted) return;
      setTerm(value);
      setPersian(value.persian || '');
      setModule(value.module || '');
    }).catch(e => {
      if (!controller.signal.aborted) setError(e instanceof Error ? e.message : 'دریافت واژه ناموفق بود.');
    }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [item.id]);

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!term || term.status !== 'CANDIDATE' || !decision || !reason.trim() || busy) return;
    const prompt = decision === 'APPROVED' ? 'تأیید' : 'رد';
    if (!window.confirm(`آیا از ${prompt} واژه ${term.key} مطمئن هستید؟`)) return;
    const payload: DecisionRequest = {
      term: term.key, kind: term.kind, to: decision,
      persian: persian.trim(), module: module.trim(), reason: reason.trim(),
    };
    setBusy(true);
    setError('');
    try {
      await glossaryApi.decision(adminKey, payload);
      onSaved(`تصمیم ${prompt} برای ${term.key} ثبت شد.`);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'ثبت تصمیم ناموفق بود.');
    } finally {
      setBusy(false);
    }
  }

  return <section className="review-editor" aria-label="فرم تصمیم‌گیری">
    <div className="panel-header"><div>
      <span className="eyebrow">بررسی واژه</span>
      <h3 dir="ltr" className="term-heading">{item.key}</h3>
      <p className="muted small">{item.kind} · {item.sampleSource || 'منبع نامشخص'}</p>
    </div><Status value={item.status}/></div>
    {loading && <p role="status" className="muted">در حال دریافت اطلاعات واژه…</p>}
    {error && <p role="alert" className="message error">{error}</p>}
    {term && term.status === 'CANDIDATE' && <form onSubmit={save} className="form-stack">
      <label className="field">معادل فارسی (اختیاری)
        <input value={persian} onChange={e => setPersian(e.target.value)} maxLength={2000}
          placeholder="فقط مقدار معتبر؛ ترجمه حدسی ممنوع" />
      </label>
      <label className="field">ماژول (اختیاری)
        <input value={module} onChange={e => setModule(e.target.value)} maxLength={1000} dir="ltr" />
      </label>
      <label className="field">دلیل تصمیم <span className="required">*</span>
        <textarea value={reason} onChange={e => setReason(e.target.value)} required
          maxLength={500} rows={3} placeholder="دلیل بررسی را ثبت کنید" />
      </label>
      <fieldset className="decision-set">
        <legend>نوع تصمیم <span className="required">*</span></legend>
        <label><input type="radio" name="decision" value="APPROVED"
          checked={decision === 'APPROVED'} onChange={() => setDecision('APPROVED')} /> تأیید</label>
        <label><input type="radio" name="decision" value="REJECTED"
          checked={decision === 'REJECTED'} onChange={() => setDecision('REJECTED')} /> رد</label>
      </fieldset>
      <p className="hint">تنها واژه‌های CANDIDATE از این مسیر قابل تصمیم‌گیری هستند. تصمیم و دلیل آن در تاریخچه سرور ثبت می‌شود.</p>
      <button type="submit" className="btn primary" disabled={busy || !reason.trim() || !decision}>
        {busy ? 'در حال ثبت…' : 'ثبت تصمیم'}
      </button>
    </form>}
    {term && term.status !== 'CANDIDATE' && <p className="message neutral">این واژه دیگر در وضعیت انتظار بررسی نیست. فهرست را به‌روزرسانی کنید.</p>}
  </section>;
}

function ImportReport({ adminKey, onImported }: {
  adminKey: string;
  onImported: (result: ImportReportResult) => void;
}) {
  const [file, setFile] = useState<File | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  async function upload(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!file || busy) return;
    if (file.size > 5 * 1024 * 1024 || !file.name.toLowerCase().endsWith('.csv')) {
      setError('فقط فایل CSV تولیدی Glossary با حداکثر ۵ مگابایت مجاز است.');
      return;
    }
    setBusy(true);
    setError('');
    try {
      const result = await glossaryApi.importReport(adminKey, file);
      onImported(result);
      setFile(null);
      if (fileRef.current) fileRef.current.value = '';
    } catch (e) {
      setError(e instanceof Error ? e.message : 'درون‌ریزی ناموفق بود.');
    } finally { setBusy(false); }
  }
  return <details className="import-details">
    <summary>درون‌ریزی گزارش واژگان ناشناخته</summary>
    <p className="muted small">فقط فایل خروجی <code dir="ltr">reports/glossary/unknown-terms.csv</code>؛ ثبت کاندیدا، نه تأیید واژه.</p>
    <form className="import-form" onSubmit={upload}>
      <label className="field">فایل CSV
        <input id="glossary-report-file" ref={fileRef} type="file" accept=".csv,text/csv"
          onChange={e => setFile(e.target.files?.[0] ?? null)} required />
      </label>
      <button className="btn secondary" type="submit" disabled={!file || busy}>{busy ? 'در حال درون‌ریزی…' : 'درون‌ریزی'}</button>
    </form>
    {error && <p className="message error" role="alert">{error}</p>}
  </details>;
}

export default function QueueView({ adminKey }: {adminKey: string}) {
  const [page, setPage] = useState(0);
  const [includeResolved, setIncludeResolved] = useState(false);
  const [reload, setReload] = useState(0);
  const [result, setResult] = useState<Page<QueueItem> | null>(null);
  const [selected, setSelected] = useState<QueueItem | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  useEffect(() => {
    if (!adminKey) { setResult(null); setSelected(null); setError(''); return; }
    const controller = new AbortController();
    setLoading(true);
    setResult(null);
    setError('');
    glossaryApi.queue(adminKey, page, PAGE_SIZE, includeResolved, controller.signal)
      .then(value => { if (!controller.signal.aborted) setResult(value); })
      .catch(e => { if (!controller.signal.aborted) {
        setResult(null);
        setError(e instanceof ApiError ? e.message : 'دریافت صف بازبینی ناموفق بود.');
      } })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [adminKey, page, includeResolved, reload]);

  function refreshed(message: string) {
    setNotice(message);
    setSelected(null);
    setReload(value => value + 1);
  }

  return <>
    <div className="page-heading">
      <div><h2>صف بازبینی</h2><p>تصمیم‌گیری آگاهانه روی واژه‌های ثبت‌شده در Glossary</p></div>
      <button className="btn secondary" type="button" disabled={!adminKey || loading}
        onClick={() => setReload(value => value + 1)}>به‌روزرسانی</button>
    </div>
    {!adminKey ? <div className="panel empty" role="status">برای مشاهده صف و ثبت تصمیم، کلید مدیر Glossary را در کادر بالای صفحه وارد کنید.</div>
      : <>
        <div className="toolbar">
          <label className="inline-checkbox"><input type="checkbox" checked={includeResolved}
            onChange={e => { setIncludeResolved(e.target.checked); setPage(0); setSelected(null); }} />
            نمایش موارد تعیین‌تکلیف‌شده</label>
          <span className="small muted">هر تصمیم به دلیل ثبت‌شده نیاز دارد؛ تأیید گروهی وجود ندارد.</span>
        </div>
        {notice && <p className="message success" role="status">{notice}</p>}
        {error && <p className="message error" role="alert">{error}</p>}
        <div className="review-layout">
          <section className="panel table-panel" aria-label="فهرست واژه‌های در صف">
            {loading && <p className="loading" role="status">در حال دریافت صف بازبینی…</p>}
            {!loading && result?.items.length === 0 && <p className="empty">موردی در این صفحه وجود ندارد.</p>}
            {!loading && result && result.items.length > 0 && <div className="table-scroll"><table>
              <thead><tr><th>واژه</th><th>وضعیت</th><th>رخداد</th><th>منبع نمونه</th></tr></thead>
              <tbody>{result.items.map(item => <tr key={`${item.kind}:${item.key}`} className={selected?.id === item.id ? 'selected-row' : ''}>
                <td><button type="button" className="term-link" onClick={() => setSelected(item)} dir="ltr">{item.key}</button>
                  <div className="muted tiny">{item.kind}</div></td>
                <td><Status value={item.status} /></td>
                <td>{item.occurrences.toLocaleString('fa-IR')}</td>
                <td className="source-name" title={item.sampleSource}>{item.sampleSource || '—'}</td>
              </tr>)}</tbody>
            </table></div>}
            {!loading && result && <Paging page={result.page} size={result.size} total={result.total}
              change={next => { setPage(next); setSelected(null); setNotice(''); }} />}
          </section>
          <aside className="panel inspector" aria-label="جزئیات واژه انتخابی">
            {selected ? <ReviewEditor key={`${selected.kind}:${selected.key}`} item={selected}
              adminKey={adminKey} onSaved={refreshed} />
              : <div className="empty inspector-empty"><span className="empty-symbol" aria-hidden="true">↗</span>
                  <h3>انتخاب واژه</h3><p>برای مشاهده جزئیات و ثبت تصمیم، روی یک واژه در جدول کلیک کنید.</p>
                </div>}
          </aside>
        </div>
        <ImportReport adminKey={adminKey} onImported={v => refreshed(
          `درون‌ریزی تکمیل شد: ${v.createdCandidates.toLocaleString('fa-IR')} Candidate و ${v.recordedOccurrences.toLocaleString('fa-IR')} شاهد جدید.`)} />
      </>}
  </>;
}
