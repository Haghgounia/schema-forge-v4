import { useState, type FormEvent } from 'react';
import { coreApi, type ConformanceReport, type ConformanceResponse } from '../coreApi';
import Platforms from '../components/Platforms';
import { saveDownload } from '../transport';

export default function ConformanceView() {
  const [scope, setScope] = useState<'table' | 'schema'>('table');
  const [schema, setSchema] = useState('');
  const [table, setTable] = useState('');
  const [platforms, setPlatforms] = useState<string[]>(['oracle']);
  const [data, setData] = useState<ConformanceResponse | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  async function audit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(''); setData(null);
    if (!platforms.length || !schema.trim() || (scope === 'table' && !table.trim())) {
      setError('حداقل یک DBMS، Schema و در حالت Table نام جدول الزامی است.'); return;
    }
    setBusy(true);
    try { setData(await coreApi.conformance({scope, schema, table, platforms})); }
    catch (err) { setError(err instanceof Error ? err.message : 'ممیزی ناموفق بود.'); }
    finally { setBusy(false); }
  }
  const reports: ConformanceReport[] = data ? ('reports' in data ? data.reports : [data]) : [];
  function downloadJson() {
    if (!data) return;
    const blob = new Blob([JSON.stringify(data, null, 2)], {type: 'application/json'});
    saveDownload({blob, filename: `schemaforge-conformance-${scope}.json`});
  }
  return <section>
    <div className="page-heading"><div><h2>Schema Conformance</h2>
      <p>ممیزی فقط‌خواندنی Objectهای موجود در دیتابیس‌های متصل؛ بدون اصلاح ساختار.</p></div></div>
    <form className="panel content-panel form-stack" onSubmit={audit}>
      <div className="form-grid">
        <label className="field">محدوده
          <select value={scope} onChange={e => setScope(e.target.value as 'table'|'schema')}>
            <option value="table">یک جدول</option><option value="schema">کل Schema</option>
          </select>
        </label>
        <label className="field grow">Schema <span className="required">*</span>
          <input dir="ltr" required value={schema} onChange={e => setSchema(e.target.value)} placeholder="BANK" />
        </label>
        {scope === 'table' && <label className="field grow">Table <span className="required">*</span>
          <input dir="ltr" required value={table} onChange={e => setTable(e.target.value)} placeholder="ACCOUNTS" />
        </label>}
      </div>
      <Platforms selected={platforms} onChange={setPlatforms}/>
      <div className="form-actions"><button className="btn primary" disabled={busy || !platforms.length}>
        {busy ? 'در حال ممیزی…' : 'اجرای Validation'}</button></div>
      {error && <p className="message error" role="alert">{error}</p>}
    </form>
    {data && <section className="panel content-panel result-block" aria-label="نتایج ممیزی">
      <div className="page-heading"><h3>نتیجه ممیزی</h3>
        <button type="button" className="btn secondary" onClick={downloadJson}>دریافت JSON</button></div>
      {reports.map((report, index) => <div key={`${report.platform}-${index}`} className="audit-report">
        <div className="toolbar"><strong dir="ltr">{report.platform} · {report.schema}{report.table ? `.${report.table}` : ''}</strong>
          <span className={`status ${report.summary?.compliant ? 'approved' : 'rejected'}`}>
            {report.summary?.compliant ? 'منطبق' : 'یافته دارد'}</span></div>
        {report.summary && <div className="stat-strip">
          <span>جدول: <strong>{report.summary.tablesScanned}</strong></span>
          <span>ستون: <strong>{report.summary.columnsScanned}</strong></span>
          <span>خطا: <strong>{report.summary.errorCount}</strong></span>
          <span>هشدار: <strong>{report.summary.warningCount}</strong></span>
          <span>اطلاعات: <strong>{report.summary.infoCount}</strong></span></div>}
        {report.findings?.length > 0 ? <div className="table-scroll"><table>
          <thead><tr><th>شدت</th><th>کد</th><th>مسیر</th><th>پیام</th></tr></thead>
          <tbody>{report.findings.map((finding, item) => <tr key={item}>
            <td>{finding.severity}</td><td dir="ltr">{finding.code}</td>
            <td dir="ltr">{finding.path}</td><td>{finding.message}</td></tr>)}</tbody>
        </table></div> : <p className="small muted">یافته‌ای ثبت نشده است.</p>}
      </div>)}
    </section>}
  </section>;
}
