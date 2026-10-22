import { useState, type FormEvent } from 'react';
import { coreApi, type AuditFields, type GenerationSource, type TimestampSemantics } from '../coreApi';
import Platforms from '../components/Platforms';
import { saveDownload } from '../transport';

const sources: { value: GenerationSource; name: string; accept: string; note: string }[] = [
  { value: 'word', name: 'Standard Word', accept: '.doc,.docx', note: 'یک سند استاندارد Word' },
  { value: 'legacy-word', name: 'Legacy Word', accept: '.doc,.docx', note: 'یک سند Legacy Word با Schema الزامی' },
  { value: 'legacy-word/zip', name: 'Legacy ZIP', accept: '.zip', note: 'بسته چند سند Legacy با Schema مشترک' },
  { value: 'zip', name: 'Word ZIP', accept: '.zip', note: 'بسته اسناد Word استاندارد' },
  { value: 'ea-xml', name: 'EA XML / XMI', accept: '.xml,.xmi', note: 'ورودی Enterprise Architect، با Schema اختیاری' },
];
export default function GenerationView() {
  const [source, setSource] = useState<GenerationSource>('word');
  const [file, setFile] = useState<File | null>(null);
  const [schema, setSchema] = useState('');
  const [platforms, setPlatforms] = useState<string[]>(['oracle']);
  const [auditFields, setAuditFields] = useState<AuditFields>('source');
  const [timestampSemantics, setTimestampSemantics] = useState<TimestampSemantics>('source');
  const [identity, setIdentity] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [result, setResult] = useState('');
  const legacy = source.startsWith('legacy-word');
  const selected = sources.find(item => item.value === source)!;
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(''); setResult('');
    if (!file || platforms.length === 0 || (legacy && !schema.trim())) {
      setError('فایل، DBMS و برای Legacy نام Schema الزامی‌اند.'); return;
    }
    setBusy(true);
    try {
      const output = await coreApi.generate({ file, source, schema, platforms, auditFields, timestampSemantics, primaryKeyAsIdentity: identity });
      saveDownload(output);
      setResult(`فایل «${output.filename}» از REST Service دریافت شد (${output.blob.size.toLocaleString('fa-IR')} بایت).`);
    } catch (err) { setError(err instanceof Error ? err.message : 'تولید ناموفق بود.'); }
    finally { setBusy(false); }
  }
  return <section>
    <div className="page-heading"><div><h2>تولید Artifact</h2><p>پنج مسیر رسمی REST؛ بسته ZIP نهایی توسط Backend تولید می‌شود.</p></div></div>
    <form className="panel content-panel form-stack" onSubmit={submit}>
      <div className="form-grid">
        <label className="field">نوع ورودی
          <select value={source} onChange={e => { setSource(e.target.value as GenerationSource); setFile(null); setResult(''); }}>
            {sources.map(item => <option key={item.value} value={item.value}>{item.name}</option>)}
          </select>
        </label>
        <label className="field grow">فایل ورودی <span className="required">*</span>
          <input key={source} type="file" accept={selected.accept} required
            onChange={e => setFile(e.target.files?.[0] ?? null)} />
        </label>
      </div>
      <p className="hint">{selected.note}</p>
      {(legacy || source === 'ea-xml') && <label className="field">Schema {legacy ? '(الزامی)' : '(اختیاری؛ عدم تغییر Schemaهای EA)'}
        <input dir="ltr" required={legacy} value={schema} maxLength={128}
          onChange={e => setSchema(e.target.value)} placeholder="SCHEMA_NAME" />
      </label>}
      <Platforms selected={platforms} onChange={setPlatforms}/>
      <div className="form-grid">
        <label className="field">Audit Fields
          <select value={auditFields} onChange={e => setAuditFields(e.target.value as AuditFields)}>
            <option value="source">source (پیشنهادی)</option>
            <option value="created-updated">created-updated</option>
            <option value="created-last-modified">created-last-modified</option>
            <option value="none">none</option>
          </select>
        </label>
        <label className="field">Timestamp Semantics
          <select value={timestampSemantics} onChange={e => setTimestampSemantics(e.target.value as TimestampSemantics)}>
            <option value="source">source (پیشنهادی)</option>
            <option value="without-time-zone">without-time-zone</option>
            <option value="with-time-zone">with-time-zone</option>
            <option value="with-local-time-zone">with-local-time-zone</option>
          </select>
        </label>
      </div>
      {source === 'ea-xml' && <label className="inline-checkbox">
        <input type="checkbox" checked={identity} onChange={e => setIdentity(e.target.checked)}/>
        تبدیل کلید اصلی به Identity (فقط در صورت نیاز صریح)
      </label>}
      <p className="hint">گزینه‌های پیش‌فرض از نوع source هستند؛ منطق تولید DDL و کنترل پلتفرم در Java باقی می‌ماند.</p>
      <div className="form-actions"><button className="btn primary" type="submit" disabled={busy || !file || !platforms.length || (legacy && !schema.trim())}>
        {busy ? 'در حال تولید…' : 'تولید و دریافت ZIP'}</button></div>
      {error && <p role="alert" className="message error">{error}</p>}
      {result && <p role="status" className="message success">{result}</p>}
    </form>
  </section>;
}
