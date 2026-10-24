import { lazy, Suspense, useEffect, useState, type FormEvent } from 'react';
import { coreApi, type DiagramInput } from '../coreApi';
import {getActiveMetadataConnection, saveDownload} from '../transport';
import type {ActiveMetadataConnection} from '../connectionApi';
import DatabaseTargetPicker from '../components/DatabaseTargetPicker';
import { MAX_DIAGRAM_BYTES } from '../zipReports';
import type { DiagramEngine } from '../diagramRender';

const RenderedDiagram = lazy(() => import('../components/RenderedDiagram'));

type Props = {activeConnection:ActiveMetadataConnection|null; connectionSessions:ActiveMetadataConnection[];
  onSelectConnection:(value:ActiveMetadataConnection|null)=>void; onGoConnections:()=>void};
export default function DiagramView({activeConnection,connectionSessions,onSelectConnection,onGoConnections}:Props) {
  const [inputMode,setInputMode] = useState<'database'|'file'>('database');
  const [liveSchema,setLiveSchema] = useState('');
  const [liveTable,setLiveTable] = useState('');
  const [liveScope,setLiveScope] = useState<'schema'|'table'>('schema');
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
  const [local, setLocal] = useState<{filename:string;source:string;engine:DiagramEngine} | null>(null);
  const [localError, setLocalError] = useState('');
  useEffect(() => {
    setLiveSchema(activeConnection?.verifiedSchema || activeConnection?.defaultSchema || '');
    setLiveTable('');setPreview('');setOutputName('');
  }, [activeConnection?.token]);

  async function openLocalDiagram(chosen: File | null) {
    setLocal(null); setLocalError('');
    if (!chosen) return;
    const engine: DiagramEngine | null = /\.mmd$/i.test(chosen.name) ? 'mermaid'
      : /\.(dot|gv)$/i.test(chosen.name) ? 'graphviz' : null;
    if (!engine) { setLocalError('فقط فایل MMD، DOT یا GV قابل نمایش است.'); return; }
    if (chosen.size > MAX_DIAGRAM_BYTES) {
      setLocalError('حجم نمودار برای رندر مرورگر بیش از ۲۵۶ کیلوبایت است.'); return;
    }
    try { setLocal({filename:chosen.name,source:await chosen.text(),engine}); }
    catch { setLocalError('خواندن فایل نمودار ممکن نبود.'); }
  }
  async function submitLive(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();setError('');setPreview('');setOutputName('');
    if (!activeConnection?.token) {setError('اتصال دیتابیس مقصد را انتخاب کنید.');return;}
    const token = activeConnection.token;
    setBusy(true);
    try {
      const download = await coreApi.liveDiagram({schema:liveSchema,
        table:liveScope==='table'?liveTable:undefined,type,
        includeColumns:flags.includeColumns,includeDataTypes:flags.includeDataTypes,
        includePrimaryKeys:flags.includePrimaryKeys,includeForeignKeys:flags.includeForeignKeys});
      if (getActiveMetadataConnection()?.token !== token)
        throw new Error('اتصال مقصد هنگام تولید نمودار تغییر کرد؛ درخواست باید دوباره اجرا شود.');
      setPreview(download.blob.size<=MAX_DIAGRAM_BYTES?await download.blob.text():'');
      setOutputName(download.filename);saveDownload(download);
    } catch(err) {setError(err instanceof Error?err.message:'نمودار زنده تولید نشد.');}
    finally {setBusy(false);}
  }
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault(); setError(''); setPreview(''); setOutputName('');
    if (!file) { setError('فایل Canonical JSON یا ZIP الزامی است.'); return; }
    setBusy(true);
    try {
      const download = await coreApi.mermaid({file, type, scope, schema, root, selected,
        depth, ...flags});
      setPreview(download.blob.size <= MAX_DIAGRAM_BYTES ? await download.blob.text() : '');
      setOutputName(download.filename);
      saveDownload(download);
    } catch (err) { setError(err instanceof Error ? err.message : 'تولید نمودار ناموفق بود.'); }
    finally { setBusy(false); }
  }
  return <section>
    <div className="page-heading"><div><h2>Mermaid Diagram</h2>
      <p>تولید نمودار از متادیتای دیتابیس موجود یا فایل Canonical JSON / ZIP.</p></div></div>
    <section className="panel content-panel">
      <label className="field">منبع نمودار
        <select value={inputMode} onChange={e => {setInputMode(e.target.value as 'database'|'file');setError('');setPreview('');setOutputName('');}}>
          <option value="database">دیتابیس زنده — متادیتای اتصال انتخاب‌شده</option>
          <option value="file">فایل Canonical JSON / ZIP — بدون نیاز به اتصال</option>
        </select>
      </label>
    </section>
    {inputMode==='database' && <>
      <DatabaseTargetPicker activeConnection={activeConnection} sessions={connectionSessions}
        onSelect={value => {onSelectConnection(value);setLiveSchema(value?.verifiedSchema || value?.defaultSchema || '');setLiveTable('');}}
        onGoConnections={onGoConnections}/>
      <form className="panel content-panel form-stack" onSubmit={submitLive}>
        <div className="form-grid">
          <label className="field">نوع نمودار
            <select value={type} onChange={e=>setType(e.target.value as DiagramInput['type'])}>
              <option value="er">ER</option><option value="dependency">Dependency</option>
              <option value="conceptual-erd">Conceptual ERD</option>
            </select></label>
          <label className="field">محدوده
            <select value={liveScope} onChange={e=>setLiveScope(e.target.value as 'schema'|'table')}>
              <option value="schema">تمام جدول‌های Schema (حداکثر ۱۵۰)</option>
              <option value="table">یک جدول</option>
            </select></label>
          <label className="field grow">Schema <span className="required">*</span>
            <input required dir="ltr" list="diagram-live-schema-hints" value={liveSchema}
              onChange={e=>setLiveSchema(e.target.value)}/>
            <datalist id="diagram-live-schema-hints">
              {activeConnection?.availableSchemas?.map(name=><option value={name} key={name}/>)}
            </datalist>
          </label>
          {liveScope==='table' && <label className="field grow">Table <span className="required">*</span>
            <input dir="ltr" required value={liveTable} onChange={e=>setLiveTable(e.target.value)}/>
          </label>}
        </div>
        <p className="hint">نمودار از Metadata واقعی خوانده می‌شود؛ هیچ DDL یا DML اجرا نمی‌شود. حساب JDBC لزوماً مالک Schema نیست.</p>
        <div className="form-actions"><button type="submit" className="btn primary"
          disabled={busy || !activeConnection?.token || !liveSchema.trim() || (liveScope==='table'&&!liveTable.trim())}>
          {busy?'در حال خواندن Metadata…':'ساخت نمودار از دیتابیس انتخاب‌شده'}</button></div>
        {error && <p role="alert" className="message error">{error}</p>}
      </form>
    </>}
    {inputMode==='file' && <form className="panel content-panel form-stack" onSubmit={submit}>
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
    </form>}
    <section className="panel content-panel result-block" aria-label="نمایش فایل نمودار موجود">
      <div className="page-heading"><div><h3>نمایش فایل Mermaid یا Graphviz</h3>
        <p>فایل <code dir="ltr">.mmd</code>، <code dir="ltr">.dot</code> یا <code dir="ltr">.gv</code> را بدون ارسال به Backend در همین مرورگر باز کنید.</p></div></div>
      <label className="field">فایل نمودار
        <input type="file" accept=".mmd,.dot,.gv" onChange={event => void openLocalDiagram(event.target.files?.[0] ?? null)}/>
      </label>
      {localError && <p role="alert" className="message error">{localError}</p>}
      {local && <Suspense fallback={<p className="message neutral" role="status">در حال بارگذاری نمایشگر نمودار…</p>}><RenderedDiagram key={local.filename} source={local.source} engine={local.engine} filename={local.filename}/></Suspense>}
    </section>
    {outputName && <div className="panel content-panel result-block">
      <p className="message success">خروجی {outputName} تولید شد.</p>
      {preview ? <Suspense fallback={<p className="message neutral" role="status">در حال بارگذاری نمایشگر نمودار…</p>}><RenderedDiagram source={preview} engine="mermaid" filename={outputName}/></Suspense> :
        <p className="message neutral">حجم فایل نمودار از سقف پیش‌نمایش بیشتر است؛ فایل دانلودشده معتبر است.</p>}
    </div>}
  </section>;
}
