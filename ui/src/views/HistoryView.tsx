import { useEffect, useState, type FormEvent } from 'react';
import { glossaryApi } from '../api';
import type { HistoryEvent, Page } from '../types';
import { formatDate, Paging } from '../utils';

const PAGE_SIZE = 25;
export default function HistoryView({ adminKey }: { adminKey: string }) {
  const [draftTerm, setDraftTerm] = useState('');
  const [term, setTerm] = useState('');
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<Page<HistoryEvent> | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [reload, setReload] = useState(0);
  useEffect(() => {
    if (!adminKey) { setResult(null); setError(''); return; }
    const controller = new AbortController();
    setError('');
    setLoading(true);
    setResult(null);
    glossaryApi.history(adminKey, page, PAGE_SIZE, term, controller.signal)
      .then(v => { if (!controller.signal.aborted) setResult(v); })
      .catch(e => { if (!controller.signal.aborted) {
        setResult(null);
        setError(e instanceof Error ? e.message : 'دریافت تاریخچه ناموفق بود.');
      }})
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [adminKey, page, term, reload]);
  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPage(0);
    setTerm(draftTerm.trim());
  }
  return <>
    <div className="page-heading"><div><h2>تاریخچه تصمیم‌ها</h2>
      <p>رخدادهای ثبت‌شده در Review Workflow و منشأ آن‌ها</p></div>
      <button className="btn secondary" type="button" disabled={!adminKey || loading}
        onClick={() => setReload(n => n + 1)}>به‌روزرسانی</button>
    </div>
    {!adminKey ? <div className="panel empty" role="status">برای مشاهده تاریخچه، کلید مدیر را وارد کنید.</div>
      : <>
        <form className="panel filter-form" onSubmit={search}>
          <label className="field grow">واژه دقیق (اختیاری)
            <input dir="ltr" value={draftTerm} onChange={e => setDraftTerm(e.target.value)} placeholder="مثال: CUSTOMER" />
          </label>
          <button type="submit" className="btn primary">جست‌وجو</button>
        </form>
        <section className="panel table-panel" aria-label="رویدادهای Glossary">
          {error && <p role="alert" className="message error">{error}</p>}
          {loading && <p role="status" className="loading">در حال دریافت تاریخچه…</p>}
          {!loading && result?.items.length === 0 && <p className="empty">رویدادی ثبت نشده است.</p>}
          {!loading && result && result.items.length > 0 && <div className="table-scroll"><table>
            <thead><tr><th>زمان</th><th>واژه</th><th>رویداد</th><th>تغییر وضعیت</th><th>دلیل</th></tr></thead>
            <tbody>{result.items.map(v => <tr key={v.id}>
              <td className="date-cell">{formatDate(v.createdAt)}</td>
              <td dir="ltr" className="strong-term">{v.termKey}</td>
              <td dir="ltr">{v.eventType}</td>
              <td dir="ltr">{v.statusBefore || '—'} → {v.statusAfter || '—'}</td>
              <td>{v.reason || '—'}</td>
            </tr>)}</tbody>
          </table></div>}
          {!loading && result && <Paging page={result.page} size={result.size} total={result.total} change={setPage} />}
        </section>
      </>}
  </>;
}
