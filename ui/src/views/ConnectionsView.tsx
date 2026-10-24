import {useEffect, useState, type FormEvent} from 'react';
import {connectionApi, type ConnectionSummary, type ConnectionProbeResult,
  type ActiveMetadataConnection} from '../connectionApi.ts';
import {ApiError} from '../transport.ts';

type Props = {
  active: ActiveMetadataConnection | null;
  onSelectionChange: (selection: ActiveMetadataConnection | null) => void;
  onDisconnected?: (token: string) => void;
};

export default function ConnectionsView({active, onSelectionChange, onDisconnected}: Props) {
  const [connections, setConnections] = useState<ConnectionSummary[]>([]);
  const [selected, setSelected] = useState('oracle');
  const [url, setUrl] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [schema, setSchema] = useState('');
  const [suggestedSchemas, setSuggestedSchemas] = useState<string[]>([]);
  const [defaultSchema, setDefaultSchema] = useState('');
  const [listTruncated, setListTruncated] = useState(false);
  const [probeResult, setProbeResult] = useState<ConnectionProbeResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const current = connections.find(item => item.platform === selected);

  function choose(db: ConnectionSummary) {
    setSelected(db.platform);
    setUrl(db.jdbcUrl.startsWith('[REDACTED') ? '' : db.jdbcUrl);
    setUsername(db.username);
    setPassword('');
    setSchema('');
    setSuggestedSchemas([]);
    setDefaultSchema('');
    setListTruncated(false);
    setProbeResult(null);
    setError('');
  }
  useEffect(() => {
    let cancelled = false;
    connectionApi.defaults().then(list => {
      if (cancelled) return;
      setConnections(list);
      const first = list.find(item => item.platform === selected) ?? list[0];
      if (first) choose(first);
    }).catch(err => {
      if (!cancelled) setError(err instanceof Error ? err.message : 'Unable to load DBMS settings.');
    });
    return () => {cancelled = true;};
  }, []);

  useEffect(() => {
    if (!active) return;
    let cancelled = false;
    connectionApi.status(active.token).catch(err => {
      if (!cancelled && err instanceof ApiError && err.status === 401) onSelectionChange(null);
    });
    return () => {cancelled = true;};
  }, [active?.token]);

  function invalidateDiscovery() {
    setProbeResult(null);
    setSuggestedSchemas([]);
    setDefaultSchema('');
    setListTruncated(false);
  }
  function rememberDiscovery(details: {
    availableSchemas?: string[];
    defaultSchema?: string;
    schemaListTruncated?: boolean;
  }) {
    setSuggestedSchemas(Array.isArray(details.availableSchemas) ? details.availableSchemas : []);
    setDefaultSchema(details.defaultSchema ?? '');
    setListTruncated(Boolean(details.schemaListTruncated));
  }
  function input() {
    return {platform:selected, jdbcUrl:url.trim(), username:username.trim(), password,
      useConfiguredPassword:false as const, schema:schema.trim()};
  }
  async function execute(activate: boolean) {
    setBusy(true); setError(''); setProbeResult(null);
    try {
      if (activate) {
        const connection = await connectionApi.activate(input());
        onSelectionChange({...connection,origin:'custom'});
        rememberDiscovery(connection);
        setProbeResult({success:true, code:connection.verifiedSchema ? 'METADATA_READY' : 'METADATA_DISCOVERED',
          message:connection.verifiedSchema
            ? 'JDBC and the selected schema metadata were verified. Metadata operations now use this connection.'
            : 'The connection is active. Choose a schema/table in Conformance, CRUD or Generation; permissions for that target are checked by the DBMS.',
          product:'', version:'', elapsedMillis:0});
      } else {
        const probe = await connectionApi.test(input());
        rememberDiscovery(probe);
        setProbeResult(probe);
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Connection request failed.';
      setError(err instanceof ApiError && err.status === 403
        ? 'Connection setup is restricted to localhost until centralized authentication is available.' : message);
    } finally {
      setPassword(''); // No JDBC password in browser memory after activation or probe.
      setBusy(false);
    }
  }
  async function useProgramConnection() {
    setBusy(true);setError('');setProbeResult(null);
    try {
      const connection = await connectionApi.activateConfigured(selected);
      onSelectionChange({...connection,origin:'configured'});
      rememberDiscovery(connection);
      setProbeResult({success:true,code:'METADATA_DISCOVERED',message:'اتصال تنظیم‌شده برنامه فعال شد.',
        product:connection.databaseProduct || '',version:connection.databaseVersion || '',elapsedMillis:0});
    } catch (err) {setError(err instanceof Error ? err.message : 'اتصال برنامه فعال نشد.');}
    finally {setBusy(false);setPassword('');}
  }
  async function disconnect() {
    if (!active) return;
    setBusy(true); setError('');
    try {await connectionApi.disconnect(active.token);onDisconnected?.(active.token);
      onSelectionChange(null);setProbeResult(null);}
    catch (err) {setError(err instanceof Error ? err.message : 'Unable to disconnect.');}
    finally {setBusy(false);}
  }

  return <section className="connections-page">
    <div className="page-heading"><div><span className="eyebrow">JDBC Connection Profiles</span>
      <h1>تنظیمات اتصال به دیتابیس</h1>
      <p>Introduce your database, verify catalog access, and use the new connection for SchemaForge metadata immediately.</p>
    </div></div>
    <p className="message neutral">No administrator key or Java configuration edit is required. A selected connection is temporary,
      browser-specific, and does not replace another user's connection or modify the database.</p>
    {active && <div className="message success" role="status">
      <strong>Active metadata connection: {active.platform.toUpperCase()}
        {active.verifiedSchema ? ` / Verified schema: ${active.verifiedSchema}` : ' / Schema selection deferred'}</strong>
      {active.jdbcTarget && <p>JDBC target: <span dir="ltr">{active.jdbcTarget}</span>
        {active.databaseName && <> · Database: <strong dir="ltr">{active.databaseName}</strong></>}
        {active.username && <> · User: <span dir="ltr">{active.username}</span></>}</p>}
      <p>Expires: {new Date(active.expiresAt).toLocaleString()}. Conformance, CRUD and Generation use this connection
        when their requested DBMS matches. Other DBMS requests are rejected instead of silently mixing connections.</p>
      <button className="btn secondary" type="button" disabled={busy} onClick={() => void disconnect()}>Disconnect</button>
    </div>}
    <div className="connection-layout">
      <nav className="panel connection-list" aria-label="Select DBMS">
        {connections.map(db => <button type="button" key={db.platform}
          className={`connection-choice ${selected === db.platform ? 'selected' : ''}`}
          onClick={() => choose(db)} aria-current={selected === db.platform ? 'true' : undefined}>
          <span className="connection-choice-main"><strong>{db.displayName}</strong><small dir="ltr">{db.platform}</small></span>
          <span className="connection-choice-state">
            {db.driverAvailable ? 'Driver available' : 'Driver unavailable'}
            {active?.platform === db.platform ? ' / Selected' : ''}
          </span>
        </button>)}
      </nav>
      <section className="panel connection-editor">
        {current ? <>
          <div className="panel-header"><div><h2>{current.displayName}</h2>
            <p className="hint">Non-secret startup defaults are prefilled for convenience. You may replace them.</p>
          </div></div>
          <div className="connection-flags">
            <span className={`status ${current.driverAvailable ? 'approved' : 'rejected'}`}>
              {current.driverAvailable ? 'JDBC driver ready' : 'JDBC driver is not installed'}</span>
            <span className="status candidate">{current.enabled ? 'Startup metadata enabled' : 'Startup metadata disabled'}</span>
          </div>
          {current.enabled && current.driverAvailable && current.jdbcUrl && current.username &&
            <div className="message neutral">
              این اتصال در تنظیمات Backend تعریف شده است و بدون دریافت مجدد رمز عبور قابل استفاده است.
              <button type="button" className="btn primary" disabled={busy}
                onClick={() => void useProgramConnection()}>فعال‌سازی اتصال تنظیم‌شده برنامه</button>
            </div>}
          <form className="form-stack" onSubmit={(event: FormEvent<HTMLFormElement>) => {event.preventDefault(); void execute(true);}}>
            <label className="field">JDBC URL <span className="required">*</span>
              <input dir="ltr" value={url} maxLength={1500} required spellCheck={false}
                onChange={e => {setUrl(e.target.value);invalidateDiscovery();}}/>
            </label>
            <div className="form-grid">
              <label className="field grow">Database user <span className="required">*</span>
                <input dir="ltr" value={username} maxLength={128} required spellCheck={false}
                  onChange={e => {setUsername(e.target.value);invalidateDiscovery();}}/>
              </label>
              <label className="field grow">Schema for metadata (optional)
                <input dir="ltr" list="schemaforge-visible-schemas" value={schema} maxLength={128}
                  spellCheck={false} placeholder="Optional — choose after discovery"
                  onChange={e => {setSchema(e.target.value);setProbeResult(null);}}/>
                <datalist id="schemaforge-visible-schemas">
                  {suggestedSchemas.map(name => <option key={name} value={name}/>)}
                </datalist>
              </label>
            </div>
            <label className="field">Database password (never returned by the server)
              <input type="password" value={password} maxLength={1024} autoComplete="new-password"
                spellCheck={false} onChange={e => {setPassword(e.target.value);invalidateDiscovery();}}
                placeholder="Enter database credentials"/>
            </label>
            <p className="hint">Schema is optional. With no schema, the Backend discovers JDBC-visible schemas/databases
              and activates the connection only if catalog enumeration succeeds. You can select a specific schema
              during Conformance, CRUD or Generation; per-table permissions remain enforced by the database.</p>
            {(defaultSchema || suggestedSchemas.length > 0) && <p className="hint" role="status">
              {defaultSchema ? <>JDBC default: <strong dir="ltr">{defaultSchema}</strong>. </> : null}
              {suggestedSchemas.length} visible schema/database names suggested.
              {listTruncated ? ' Results are limited to the first 200; you may also enter a name manually.' : ''}
              {' '}Select from the optional field above or leave it blank. After a test, reenter the password to activate.
            </p>}
            <div className="connection-buttons">
              <button type="submit" className="btn primary"
                disabled={busy || !current.driverAvailable || !url.trim() || !username.trim()}>
                {busy ? 'Checking...' : 'Test metadata and use connection'}</button>
              <button type="button" className="btn secondary" disabled={busy || !current.driverAvailable}
                onClick={() => void execute(false)}>
                Test & discover schemas</button>
              <button type="button" className="btn subtle" disabled={busy} onClick={() => choose(current)}>
                Restore defaults</button>
            </div>
          </form>
          {probeResult && <div className={`message ${probeResult.success ? 'success' : 'error'}`} role="status">
            <strong>{probeResult.code}</strong> - {probeResult.message}
            {probeResult.product && <p dir="ltr">{probeResult.product} {probeResult.version} / {probeResult.elapsedMillis}ms</p>}
          </div>}
        </> : <p className="empty">Choose one of the seven DBMSs.</p>}
      </section>
    </div>
    {error && <p className="message error" role="alert">{error}</p>}
  </section>;
}
