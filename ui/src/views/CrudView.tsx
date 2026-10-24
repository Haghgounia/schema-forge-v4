import {useEffect, useState, type FormEvent} from 'react';
import {coreApi} from '../coreApi';
import type {ActiveMetadataConnection} from '../connectionApi';
import DatabaseTargetPicker from '../components/DatabaseTargetPicker';
import {getActiveMetadataConnection, saveDownload} from '../transport';

type Props = {activeConnection:ActiveMetadataConnection|null;
  connectionSessions:ActiveMetadataConnection[];
  onSelectConnection:(value:ActiveMetadataConnection|null)=>void;
  onGoConnections:()=>void};
export default function CrudView({activeConnection,connectionSessions,onSelectConnection,onGoConnections}:Props) {
  const [schema, setSchema] = useState('');
  const [table, setTable] = useState('');
  const [script, setScript] = useState('');
  const [outputName, setOutputName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const selectedPlatform = activeConnection?.platform;
  const supported = Boolean(activeConnection?.token && (selectedPlatform === 'oracle' || selectedPlatform === 'sqlserver'));
  useEffect(() => {
    setSchema(activeConnection?.verifiedSchema || activeConnection?.defaultSchema || '');
    setTable('');setScript('');setOutputName('');setError('');
  }, [activeConnection?.token]);
  async function run(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();setError('');setScript('');setOutputName('');
    if (!supported || !activeConnection?.token) {setError('اتصال Oracle یا SQL Server الزامی است.');return;}
    const requestToken = activeConnection.token;
    setBusy(true);
    try {
      const download = await coreApi.crud(selectedPlatform as 'oracle'|'sqlserver', schema, table);
      if (getActiveMetadataConnection()?.token !== requestToken) {
        throw new Error('اتصال مقصد هنگام تولید CRUD تغییر کرده است؛ فایل قابل پذیرش نیست.');
      }
      setScript((await download.blob.text()).slice(0, 25000));
      setOutputName(download.filename);saveDownload(download);
    } catch (err) {setError(err instanceof Error ? err.message : 'تولید CRUD ناموفق بود.');}
    finally {setBusy(false);}
  }
  return <section>
    <div className="page-heading"><div><h2>تولید CRUD</h2>
      <p>تولید SQL از متادیتای واقعی اتصال انتخاب‌شده، فقط برای Oracle و SQL Server.</p></div></div>
    <DatabaseTargetPicker activeConnection={activeConnection} sessions={connectionSessions}
      onSelect={onSelectConnection} onGoConnections={onGoConnections}
      allowedPlatforms={['oracle','sqlserver']}/>
    <form className="panel content-panel form-stack" onSubmit={run}>
      <div className="form-grid">
        <label className="field grow">Schema مستقل از کاربر JDBC <span className="required">*</span>
          <input dir="ltr" value={schema} required list="crud-schema-hints"
            onChange={e => setSchema(e.target.value)} placeholder="SCHEMA"/>
          <datalist id="crud-schema-hints">
            {activeConnection?.availableSchemas?.map(name => <option value={name} key={name}/>)}
          </datalist>
        </label>
        <label className="field grow">Table <span className="required">*</span>
          <input dir="ltr" value={table} required onChange={e => setTable(e.target.value)} placeholder="TABLE"/>
        </label>
      </div>
      <div className="form-actions"><button type="submit" className="btn primary" disabled={busy || !supported}>
        {busy ? 'در حال تولید…' : 'تولید و دریافت SQL از اتصال انتخاب‌شده'}</button></div>
      {error && <p role="alert" className="message error">{error}</p>}
    </form>
    {outputName && <div className="panel content-panel result-block"><p className="message success">فایل {outputName} دریافت شد.</p>
      <details><summary>پیش‌نمایش SQL (حداکثر ۲۵٬۰۰۰ نویسه)</summary>
        <pre className="code-preview" dir="ltr">{script}</pre></details></div>}
  </section>;
}
