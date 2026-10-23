import {useState, type FormEvent} from 'react';
import {connectionApi, metadataYaml, type ConnectionSummary, type ConnectionProbeResult} from '../connectionApi';
import {ApiError} from '../transport';

function connectionError(error: unknown): string {
  if (error instanceof ApiError && error.status === 503) {
    return 'کلید مدیریت اتصال در Backend تعریف نشده است. SCHEMAFORGE_CONNECTIONS_ADMIN_KEY را در فرآیند Java تنظیم و آن را مجدداً راه‌اندازی کنید.';
  }
  return error instanceof Error ? error.message : 'درخواست اتصال ناموفق بود.';
}

export default function ConnectionsView() {
  const [keyEditor, setKeyEditor] = useState('');
  const [adminKey, setAdminKey] = useState('');
  const [connections, setConnections] = useState<ConnectionSummary[]>([]);
  const [selected, setSelected] = useState('oracle');
  const [url, setUrl] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [useConfiguredPassword, setUseConfiguredPassword] = useState(false);
  const [enableNextRestart, setEnableNextRestart] = useState(true);
  const [probeResult, setProbeResult] = useState<ConnectionProbeResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [showSnippet, setShowSnippet] = useState(false);
  const [copied, setCopied] = useState(false);
  const current = connections.find(item => item.platform === selected);
  const matchesConfigured = Boolean(current && url === current.jdbcUrl && username === current.username);
  const effectiveConfiguredPassword = useConfiguredPassword && matchesConfigured;

  function choose(db: ConnectionSummary) {
    setSelected(db.platform);
    setUrl(db.jdbcUrl);
    setUsername(db.username);
    setPassword('');
    setUseConfiguredPassword(db.passwordConfigured);
    setEnableNextRestart(db.enabled);
    setProbeResult(null);
    setError('');
    setShowSnippet(false);
    setCopied(false);
  }
  async function authenticate() {
    const key = keyEditor.trim();
    if (!key) return;
    setBusy(true); setError(''); setProbeResult(null);
    try {
      const configured = await connectionApi.defaults(key);
      setAdminKey(key);
      setKeyEditor('');
      setConnections(configured);
      const initial = configured.find(item => item.platform === selected) ?? configured[0];
      if (initial) choose(initial);
    } catch (err) {
      setAdminKey(''); setConnections([]);
      setError(connectionError(err));
    } finally { setBusy(false); }
  }
  async function refresh() {
    setBusy(true); setError(''); setProbeResult(null);
    try {
      const configured = await connectionApi.defaults(adminKey);
      setConnections(configured);
      const active = configured.find(item => item.platform === selected);
      if (active) choose(active);
    } catch (err) {
      setError(connectionError(err));
    } finally { setBusy(false); }
  }
  async function testConnection(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!current || !adminKey) return;
    setBusy(true); setProbeResult(null); setError('');
    try {
      const result = await connectionApi.test(adminKey, {
        platform: selected, jdbcUrl: url.trim(), username: username.trim(),
        password: effectiveConfiguredPassword ? '' : password,
        useConfiguredPassword: effectiveConfiguredPassword,
      });
      setProbeResult(result);
    } catch (err) {
      setError(connectionError(err));
    } finally {
      setPassword(''); // Do not retain a typed JDBC password after a network request.
      setBusy(false);
    }
  }
  let snippet = '';
  let snippetError = '';
  if (current) {
    try { snippet = metadataYaml(current.platform, url.trim(), username.trim(), enableNextRestart); }
    catch (err) { snippetError = err instanceof Error ? err.message : 'تنظیمات قابل آماده‌سازی نیست.'; }
  }
  return <section className="connections-page">
    <div className="page-heading"><div><span className="eyebrow">JDBC Connection Profiles</span>
      <h1>اتصالات دیتابیس</h1>
      <p>مشاهده تنظیمات مؤثر هفت DBMS در Spring Boot و آزمون اتصال بدون اجرای SQL یا تغییر دیتابیس.</p></div></div>
    <p className="message neutral">این فرم تنظیمات فعال Backend را می‌خواند، نه مقادیر ثابت داخل React. آزمون اتصال هیچ تغییری در Metadata Repository یا فایل پیکربندی نمی‌دهد؛ برای اعمال تنظیمات جدید روی سرویس‌های پروژه، راه‌اندازی مجدد Java لازم است.</p>
    {!adminKey ? <section className="panel connection-auth">
      <h2>دسترسی مدیریت اتصال</h2>
      <p className="hint">ابتدا متغیر محیطی <code dir="ltr">SCHEMAFORGE_CONNECTIONS_ADMIN_KEY</code> را روی Backend تنظیم کنید. نمایش آدرس‌ها و نام‌های کاربری فقط پس از احراز هویت ممکن است.</p>
      <form className="connection-auth-form" onSubmit={e => {e.preventDefault(); void authenticate();}}>
        <label className="field grow">کلید مدیریت اتصالات
          <input type="password" autoComplete="off" spellCheck={false} value={keyEditor}
            onChange={e => setKeyEditor(e.target.value)} placeholder="کلید سمت سرور" />
        </label>
        <button className="btn primary" disabled={!keyEditor.trim() || busy} type="submit">{busy ? 'در حال دریافت...' : 'نمایش تنظیمات پیش‌فرض'}</button>
      </form>
    </section> : <>
      <div className="toolbar connection-toolbar"><div><strong>تنظیمات فعال در زمان راه‌اندازی Java</strong>
        <p className="hint">مقادیر مؤثر ConfigurationProperties پس از اعمال متغیرهای محیطی؛ رمز عبور هیچ‌گاه از سرور بازگردانده نمی‌شود.</p></div>
        <div className="export-actions"><button className="btn secondary" onClick={() => void refresh()} disabled={busy}>بازخوانی</button>
          <button className="btn subtle" onClick={() => {setAdminKey('');setKeyEditor('');setConnections([]);setPassword('');setProbeResult(null);setError('');}} type="button">خروج امن از فرم</button></div>
      </div>
      <div className="connection-layout">
        <nav className="panel connection-list" aria-label="انتخاب DBMS">
          {connections.map(db => <button type="button" key={db.platform} className={`connection-choice ${selected === db.platform ? 'selected' : ''}`}
            onClick={() => choose(db)} aria-current={selected === db.platform ? 'true' : undefined}>
            <span className="connection-choice-main"><strong>{db.displayName}</strong><small dir="ltr">{db.platform}</small></span>
            <span className="connection-choice-state">{db.enabled ? 'فعال در Java' : 'غیرفعال در Java'} · {db.driverAvailable ? 'درایور موجود' : 'درایور موجود نیست'}</span>
          </button>)}
        </nav>
        <section className="panel connection-editor">
          {current ? <>
            <div className="panel-header"><div><h2>{current.displayName}</h2><p className="hint">مقادیر اولیه از تنظیمات فعلی Backend بارگذاری شده‌اند.</p></div></div>
            <div className="connection-flags"><span className="status candidate">{current.enabled ? 'فعال هنگام Startup' : 'غیرفعال هنگام Startup'}</span>
              <span className={`status ${current.driverAvailable ? 'approved' : 'rejected'}`}>{current.driverAvailable ? 'JDBC Driver موجود' : 'JDBC Driver نصب نشده'}</span>
              <span className="status candidate">{current.passwordConfigured ? 'رمز روی سرور تنظیم شده' : 'رمز سرور موجود نیست'}</span></div>
            <form className="form-stack" onSubmit={e => void testConnection(e)}>
              <label className="field">JDBC URL
                <input dir="ltr" spellCheck={false} value={url} required maxLength={1500}
                  onChange={e => {setUrl(e.target.value);setPassword('');setUseConfiguredPassword(false);setProbeResult(null);}} />
              </label>
              <div className="form-grid"><label className="field grow">نام کاربری دیتابیس
                <input dir="ltr" spellCheck={false} value={username} required maxLength={128}
                  onChange={e => {setUsername(e.target.value);setPassword('');setUseConfiguredPassword(false);setProbeResult(null);}} />
              </label>
              <label className="field grow">درایور JDBC (ثابت برای DBMS)
                <input dir="ltr" value={current.driverClassName} readOnly title="انتخاب درایور توسط Backend انجام می‌شود" />
              </label></div>
              <label className="field">رمز عبور جدید (فقط برای همین آزمون، ارسال مستقیم به Backend)
                <input type="password" autoComplete="new-password" spellCheck={false} value={password}
                  disabled={effectiveConfiguredPassword} onChange={e => setPassword(e.target.value)} maxLength={1024}
                  placeholder="رمز پیش‌فرض نمایش داده نمی‌شود" />
              </label>
              <label className="inline-checkbox"><input type="checkbox" checked={effectiveConfiguredPassword}
                disabled={!matchesConfigured || !current.passwordConfigured}
                onChange={e => {setUseConfiguredPassword(e.target.checked);setPassword('');}} />
                استفاده از رمز پیکربندی‌شده در Java (فقط با URL و نام کاربری بدون تغییر)
              </label>
              {!matchesConfigured && <p className="hint">آدرس یا کاربر تغییر کرده است؛ رمز فعلی سرور برای مقصد جدید استفاده نمی‌شود. در صورت نیاز رمز جدید را وارد کنید.</p>}
              <div className="connection-buttons"><button type="submit" className="btn primary" disabled={busy || !current.driverAvailable || !url.trim() || !username.trim()}>
                  {busy ? 'در حال بررسی...' : 'آزمون اتصال JDBC'}</button>
                <button type="button" className="btn secondary" onClick={() => choose(current)} disabled={busy}>بازگرداندن مقادیر پیش‌فرض</button></div>
            </form>
            {probeResult && <div className={`message ${probeResult.success ? 'success' : 'error'}`} role="status">
              <strong>{probeResult.success ? 'اتصال برقرار شد' : 'اتصال برقرار نشد'} — {probeResult.code}</strong>
              <p>{probeResult.message}</p>
              {probeResult.success && <p dir="ltr">{probeResult.product} {probeResult.version} · {probeResult.elapsedMillis}ms</p>}
            </div>}
            <details className="import-details"><summary>تهیه تنظیمات برای راه‌اندازی بعدی Java</summary>
              <p className="hint">این بخش فقط مقادیر غیرمحرمانه را آماده می‌کند. برای اعمال، DBA باید آن‌ها را به پیکربندی سمت سرور منتقل کند و سرویس را مجدداً راه‌اندازی کند؛ رمز را فقط از مسیر امن محیط سرور تنظیم کنید.</p>
              <label className="inline-checkbox"><input type="checkbox" checked={enableNextRestart} onChange={e => setEnableNextRestart(e.target.checked)} /> فعال‌سازی Metadata این DBMS پس از Restart</label>
              <div className="connection-buttons"><button className="btn secondary" type="button" disabled={Boolean(snippetError)} onClick={() => {setShowSnippet(!showSnippet);setCopied(false);}}>{showSnippet ? 'بستن متن' : 'نمایش YAML بدون رمز'}</button>
                {showSnippet && <button type="button" className="btn secondary" onClick={async () => {try {await navigator.clipboard.writeText(snippet);setCopied(true);} catch {setCopied(false);setError('کپی خودکار در مرورگر ممکن نشد؛ متن را دستی انتخاب کنید.');}}}>کپی تنظیمات</button>}</div>
              {snippetError && <p className="message error">{snippetError}</p>}
              {showSnippet && <><pre className="connection-yaml" dir="ltr">{snippet}</pre>{copied && <p className="hint">متن غیرمحرمانه کپی شد.</p>}</>}
            </details>
          </> : <p className="empty">تنظیمات DBMS موجود نیست.</p>}
        </section>
      </div>
    </>}
    {error && <p className="message error" role="alert">{error}</p>}
  </section>;
}
