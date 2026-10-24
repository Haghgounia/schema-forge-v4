import {useEffect, useState} from 'react';
import {connectionApi, type ActiveMetadataConnection, type ConnectionSummary} from '../connectionApi';
import {PLATFORMS} from '../coreApi';

type Props = {
  activeConnection: ActiveMetadataConnection | null;
  sessions: ActiveMetadataConnection[];
  onSelect: (value: ActiveMetadataConnection | null) => void;
  onGoConnections: () => void;
  allowedPlatforms?: readonly string[];
};

/** One browser-scoped target selection shared by Audit, CRUD and live Diagrams. */
export default function DatabaseTargetPicker({activeConnection, sessions, onSelect,
  onGoConnections, allowedPlatforms}: Props) {
  const [available, setAvailable] = useState<ConnectionSummary[]>([]);
  const [platform, setPlatform] = useState(
    activeConnection && (!allowedPlatforms || allowedPlatforms.includes(activeConnection.platform))
      ? activeConnection.platform : '');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => {
    let stale = false;
    connectionApi.defaults().then(result => {if (!stale) setAvailable(result);})
      .catch(err => {if (!stale) setError(err instanceof Error ? err.message : 'خواندن تنظیمات اتصال ناموفق بود.');});
    return () => {stale = true;};
  }, []);
  useEffect(() => {
    if (activeConnection && permitted(activeConnection.platform)) setPlatform(activeConnection.platform);
  }, [activeConnection?.token, activeConnection?.platform]);
  const permitted = (dbms: string) => !allowedPlatforms || allowedPlatforms.includes(dbms);
  const candidates = sessions.filter(session => session.platform === platform);
  const configured = available.find(item => item.platform === platform);
  const configReady = Boolean(configured?.enabled && configured?.driverAvailable && configured?.username && configured?.jdbcUrl);
  const total = candidates.length + (configReady ? 1 : 0);

  async function useConfigured(targetPlatform: string) {
    setLoading(true); setError('');
    try {
      const activated = await connectionApi.activateConfigured(targetPlatform);
      if (activated.platform !== targetPlatform || !activated.token) {
        throw new Error('هویت اتصال فعال با DBMS درخواستی یکسان نیست.');
      }
      onSelect({...activated,origin:'configured'});
    } catch (err) {
      onSelect(null);
      setError(err instanceof Error ? err.message : 'فعال‌سازی اتصال پیش‌فرض موفق نبود.');
    } finally {setLoading(false);}
  }

  function choosePlatform(targetPlatform: string) {
    setPlatform(targetPlatform); setError('');
    onSelect(null);
    const found = sessions.filter(session => session.platform === targetPlatform);
    const defaultForPlatform = available.find(item => item.platform === targetPlatform);
    const ready = Boolean(defaultForPlatform?.enabled && defaultForPlatform?.driverAvailable
      && defaultForPlatform?.username && defaultForPlatform?.jdbcUrl);
    if (found.length === 1 && !ready) onSelect(found[0]);
    else if (found.length === 0 && ready) void useConfigured(targetPlatform);
  }

  const activeIsValid = Boolean(activeConnection?.token && activeConnection.platform === platform);
  return <section className="panel content-panel target-picker" aria-label="انتخاب دیتابیس مقصد">
    <h3>اتصال دیتابیس مقصد</h3>
    <div className="form-grid">
      <label className="field grow">نوع دیتابیس <span className="required">*</span>
        <select aria-label="نوع دیتابیس مقصد" value={platform} disabled={loading}
          onChange={event => choosePlatform(event.target.value)}>
          <option value="">— انتخاب DBMS —</option>
          {PLATFORMS.filter(([value]) => permitted(value)).map(([value, name]) =>
            <option key={value} value={value}>{name}</option>)}
        </select>
      </label>
      {platform && (total > 1 || (!activeIsValid && total > 0)) &&
        <label className="field grow">پروفایل اتصال
          <select value={activeIsValid ? activeConnection?.token : ''} disabled={loading}
            aria-label="انتخاب پروفایل اتصال"
            onChange={event => {
              const chosen = event.target.value;
              if (chosen === '__configured__') {onSelect(null);void useConfigured(platform);}
              else onSelect(candidates.find(session => session.token === chosen) || null);
            }}>
            <option value="">— انتخاب اتصال —</option>
            {configReady && <option value="__configured__">تنظیمات پیش‌فرض برنامه — {configured?.displayName}</option>}
            {candidates.map(session => <option key={session.token} value={session.token}>
              {session.jdbcTarget || session.platform} · {session.username || 'JDBC'} ({session.origin==='configured'?'تنظیمات برنامه':'اتصال کاربر'})
            </option>)}
          </select>
        </label>}
    </div>
    {loading && <p className="message neutral" role="status">در حال تأیید اتصال و دریافت Schemaها…</p>}
    {activeIsValid && <div className="message success" role="status">
      <strong dir="ltr">{activeConnection?.platform.toUpperCase()} · {activeConnection?.jdbcTarget || 'JDBC target'}</strong>
      {activeConnection?.databaseName && <span> · Database: <b dir="ltr">{activeConnection.databaseName}</b></span>}
      {activeConnection?.username && <span> · User: <b dir="ltr">{activeConnection.username}</b></span>}
      <p>Schema مقصد مستقل از حساب JDBC انتخاب می‌شود؛ دسترسی‌ها توسط DBMS کنترل می‌شوند.</p>
    </div>}
    {platform && !activeIsValid && !loading && total === 0 &&
      <p className="message neutral">برای این DBMS اتصال آماده‌ای وجود ندارد؛ در تنظیمات اتصال، یک پروفایل فعال کنید.</p>}
    {error && <p className="message error" role="alert">{error}</p>}
    <button type="button" className="btn secondary" onClick={onGoConnections}>تنظیمات اتصال به دیتابیس</button>
  </section>;
}
