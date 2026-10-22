import { useState, type FormEvent } from 'react';
import { coreApi, type DiagramInput } from '../coreApi';
import { saveDownload } from '../transport';
export default function DiagramView() {
  const [file, setFile] = useState<File|null>(null);
  const [type, setType] = useState<DiagramInput['type']>('er');
  const [scope, setScope] = useState<DiagramInput['scope']>('all');
  const [schema, setSchema] = useState('');
  const [root, setRoot] = useState('');
  const [selected, setSelected] = useState('');
  const [depth, setDepth] = useState(1);
  const [flags, setFlags] = useState({
    includeColumns: true, includeDataTypes: true, includePrimaryKeys: true,
    includeForeignKeys: true, includeLogicalForeignKeys: false,
  });
  const [preview, setPreview] = useState('');
  const [outputName, setOutputName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault(); setError(''); setPreview(''); setOutputName('');
    if (!file) { setError('فایل Canonical JSON یا ZIP الزامی است.'); return; }
    setBusy(true);
    try {
      const download = await coreApi.mermaid({file, type, scope, schema, root, selected,
        depth, ...flags});
      setPreview((await download.blob.text()).slice(0, 25000));
      setOutputName(download.filename);
      saveDownload(download);
    } catch (err) { setError(err instanceof Error ? err.message : 'تولید نمودار ناموفق بود.'); }
    finally { setBusy(false); }
  }
  return <section>
    <div className="page-heading"><div><h2>Mermaid Diagram</h2>
      <p>تولید ERD یا Dependency Diagram از Canonical JSON یا ZIP.</p></div></div>
    <form className="panel content-panel form-stack" onSubmit={submit}>
      <label className="field">فایل ورودی <span className="required">*</span>
        <input type="file" accept=".json,.zip" required onChange={e => setFile(e.target.files?.[0] ?? null)} />
      </label>
      <div className="form-grid">
        <label className="field">نوع نمودار
          <select value={type} onChange={e => setType(e.target.value as DiagramInput['type'])}>
            <option value="er">ER</option><option value="dependency">Dependency</option>
            <option value="conceptual-erd">Conceptual ERD</option>
          </select></label>
        <label className="field">محدوده
          <select value={scope} onChange={e => setScope(e.target.value as DiagramInput['scope'])}>
            <option value="all">همه جدول‌ها</option><option value="schema">یک Schema</option>
            <option value="table">یک جدول</option><option value="table-with-dependencies">جدول و وابستگی‌ها</option>
            <option value="selected-tables">جدول‌های منتخب</option>
          </select></label>
        {scope === 'schema' && <label className="field grow">Schema
          <input dir="ltr" required value={schema} onChange={e => setSchema(e.target.value)} /></label>}
        {(scope === 'table' || scope === 'table-with-dependencies') && <label className="field grow">جدول ریشه (SCHEMA.TABLE)
          <input dir="ltr" required pattern="[^.]+\.[^.]+" title="SCHEMA.TABLE" value={root}
            onChange={e => setRoot(e.target.value)} /></label>}
        {scope === 'selected-tables' && <label className="field grow">لیست جدول‌ها (با کاما)
          <input dir="ltr" required value={selected} placeholder="A.T1,B.T2"
            onChange={e => setSelected(e.target.value)} /></label>}
      </div>
      {scope === 'table-with-dependencies' && <label className="field">عمق وابستگی
        <input type="number" min="0" max="20" value={depth}
          onChange={e => setDepth(Number(e.target.value))} /></label>}
      <fieldset className="platforms"><legend>جزئیات نمودار</legend>
        {([
          ['includeColumns', 'ستون‌ها'], ['includeDataTypes', 'نوع داده'], ['includePrimaryKeys', 'Primary Keys'],
          ['includeForeignKeys', 'Foreign Keys'], ['includeLogicalForeignKeys', 'Logical Foreign Keys'],
        ] as const).map(([name, label]) => <label key={name}>
          <input type="checkbox" checked={flags[name]} onChange={e => setFlags({...flags, [name]: e.target.checked})} /> {label}
        </label>)}
      </fieldset>
      <div className="form-actions"><button type="submit" className="btn primary" disabled={busy || !file}>
        {busy ? 'در حال تولید…' : 'تولید و دریافت Mermaid'}</button></div>
      {error && <p role="alert" className="message error">{error}</p>}
    </form>
    {outputName && <div className="panel content-panel result-block">
      <p className="message success">خروجی {outputName} تولید شد.</p>
      <details><summary>نمایش متن نمودار (حداکثر ۲۵٬۰۰۰ نویسه)</summary>
        <pre className="code-preview" dir="ltr">{preview}</pre></details>
    </div>}
  </section>;
}
