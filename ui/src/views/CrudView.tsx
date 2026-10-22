import { useState, type FormEvent } from 'react';
import { coreApi } from '../coreApi';
import { saveDownload } from '../transport';
export default function CrudView() {
  const [platform, setPlatform] = useState<'oracle'|'sqlserver'>('oracle');
  const [schema, setSchema] = useState('');
  const [table, setTable] = useState('');
  const [script, setScript] = useState('');
  const [outputName, setOutputName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  async function run(e: FormEvent<HTMLFormElement>) {
    e.preventDefault(); setError(''); setScript(''); setOutputName(''); setBusy(true);
    try {
      const download = await coreApi.crud(platform, schema, table);
      setScript((await download.blob.text()).slice(0, 25000));
      setOutputName(download.filename);
      saveDownload(download);
    } catch (err) { setError(err instanceof Error ? err.message : 'تولید CRUD ناموفق بود.'); }
    finally { setBusy(false); }
  }
  return <section>
    <div className="page-heading"><div><h2>تولید CRUD</h2>
      <p>بسته SQL از متادیتای واقعی Oracle یا SQL Server توسط Backend تولید می‌شود.</p></div></div>
    <form className="panel content-panel form-stack" onSubmit={run}>
      <div className="form-grid">
        <label className="field">DBMS
          <select value={platform} onChange={e => setPlatform(e.target.value as 'oracle'|'sqlserver')}>
            <option value="oracle">Oracle</option><option value="sqlserver">SQL Server</option>
          </select></label>
        <label className="field grow">Schema <span className="required">*</span>
          <input dir="ltr" value={schema} required onChange={e => setSchema(e.target.value)} placeholder="SCHEMA" /></label>
        <label className="field grow">Table <span className="required">*</span>
          <input dir="ltr" value={table} required onChange={e => setTable(e.target.value)} placeholder="TABLE" /></label>
      </div>
      <div className="form-actions"><button type="submit" className="btn primary" disabled={busy}>
        {busy ? 'در حال تولید…' : 'تولید و دریافت SQL'}</button></div>
      {error && <p role="alert" className="message error">{error}</p>}
    </form>
    {outputName && <div className="panel content-panel result-block"><p className="message success">فایل {outputName} دریافت شد.</p>
      <details><summary>پیش‌نمایش SQL (حداکثر ۲۵٬۰۰۰ نویسه)</summary>
        <pre className="code-preview" dir="ltr">{script}</pre></details></div>}
  </section>;
}
