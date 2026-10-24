import {useEffect, useState, type FormEvent} from 'react';
import {coreApi, type SelectedAuditPlan, type SelectedAuditResult, type SelectedAuditTarget} from '../coreApi';
import type {ActiveMetadataConnection} from '../connectionApi';
import DatabaseTargetPicker from '../components/DatabaseTargetPicker';
import {getActiveMetadataConnection, saveDownload} from '../transport';

const RULE_LABELS: Record<string, string> = {
  STRUCTURAL: 'صحت ساختار جدول‌ها و اجزای مدل',
  METADATA_CONVENTION: 'قواعد و سازگاری متادیتای واقعی',
  DATATYPE_COMPATIBILITY: 'سازگاری انواع داده با DBMS',
  CONSTRAINT_REFERENCES: 'اعتبار ارجاعات ستون در CHECK',
  KEY_CONSTRAINTS: 'وجود PK و ویژگی‌های ستون‌های کلیدی',
  REFERENTIAL_INTEGRITY: 'درستی ارجاع FK و یکتایی کلید مقصد',
  INDEX_COVERAGE: 'پوشش ایندکس برای FK',
  PHYSICAL_NAMING: 'قواعد نام‌گذاری آبجکت‌های فیزیکی',
};

function TargetIdentity({target}: {target: SelectedAuditTarget}) {
  return <div className="audit-target-grid">
    <div><small>DBMS</small><strong dir="ltr">{target.platform.toUpperCase()}</strong></div>
    <div><small>JDBC Server / Target</small><strong dir="ltr" className="audit-long-value">{target.jdbcTarget || '—'}</strong></div>
    <div><small>Database / Catalog</small><strong dir="ltr">{target.databaseName || 'از JDBC گزارش نشده؛ نشانی بالا ملاک است'}</strong></div>
    <div><small>حساب اتصال</small><strong dir="ltr">{target.username || '—'}</strong></div>
    <div><small>Schema تأییدشده هنگام اتصال</small><strong dir="ltr">{target.verifiedSchema || 'انتخاب نشده'}</strong></div>
    <div><small>محصول دیتابیس</small><strong dir="ltr">{target.databaseProduct || target.platform.toUpperCase()}
      {target.databaseVersion ? ` ${target.databaseVersion}` : ''}</strong></div>
  </div>;
}

type Props = {
  activeConnection: ActiveMetadataConnection | null;
  onGoConnections: () => void;
  connectionSessions: ActiveMetadataConnection[];
  onSelectConnection: (value: ActiveMetadataConnection | null) => void;
};

export default function ConformanceView({activeConnection, onGoConnections, connectionSessions, onSelectConnection}: Props) {
  const [scope, setScope] = useState<'table' | 'schema'>('table');
  const [schema, setSchema] = useState('');
  const [table, setTable] = useState('');
  const [plan, setPlan] = useState<SelectedAuditPlan | null>(null);
  const [result, setResult] = useState<SelectedAuditResult | null>(null);
  const [acknowledged, setAcknowledged] = useState(false);
  const [busy, setBusy] = useState(false);
  const [loadingPlan, setLoadingPlan] = useState(false);
  const [error, setError] = useState('');
  const [planError, setPlanError] = useState('');

  useEffect(() => {
    let stale = false;
    setPlan(null); setResult(null); setError(''); setPlanError(''); setAcknowledged(false);
    setSchema(activeConnection?.verifiedSchema || activeConnection?.defaultSchema || '');
    setTable('');
    if (!activeConnection?.token) {setLoadingPlan(false); return;}
    setLoadingPlan(true);
    coreApi.selectedAuditPlan().then(next => {
      if (!stale) {
        if (next.target.platform !== activeConnection.platform) {
          setPlanError('هویت DBMS پاسخ Backend با اتصال انتخاب‌شده یکسان نیست. اتصال را دوباره برقرار کنید.');
        } else {
          setPlan(next);
        }
      }
    }).catch(err => {
      if (!stale) setPlanError(err instanceof Error ? err.message : 'تأیید مقصد اتصال امکان‌پذیر نیست.');
    }).finally(() => {if (!stale) setLoadingPlan(false);});
    return () => {stale = true;};
  }, [activeConnection?.token, activeConnection?.platform]);

  function invalidateReview() {setAcknowledged(false); setResult(null); setError('');}
  async function audit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setError(''); setResult(null);
    if (!activeConnection?.token || !plan || !plan.readOnly || !acknowledged) {
      setError('اتصال معتبر و تأیید مقصد و قوانین قبل از شروع ممیزی الزامی است.'); return;
    }
    if (!schema.trim() || (scope === 'table' && !table.trim())) {
      setError('Schema و در حالت ممیزی جدول، Table الزامی است.'); return;
    }
    setBusy(true);
    try {
      const requestToken = activeConnection.token;
      const received = await coreApi.selectedConformance({scope, schema, table});
      if (getActiveMetadataConnection()?.token !== requestToken) {
        throw new Error('اتصال فعال هنگام اجرای ممیزی تغییر کرده است؛ درخواست باید دوباره اجرا شود.');
      }
      if (received.target.jdbcTarget !== plan.target.jdbcTarget ||
          received.target.platform !== plan.target.platform ||
          received.target.username !== plan.target.username) {
        throw new Error('مقصد ممیزی در طول درخواست تغییر کرده است؛ نتیجه قابل پذیرش نیست.');
      }
      setResult(received);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'ممیزی انجام نشد؛ اتصال و سطح دسترسی را بررسی کنید.');
    } finally {setBusy(false);}
  }
  function downloadJson() {
    if (!result) return;
    saveDownload({blob: new Blob([JSON.stringify(result, null, 2)], {type:'application/json'}),
      filename:`schemaforge-selected-audit-${scope}.json`});
  }

  return <section className="conformance-page">
    <div className="page-heading"><div><h2>ممیزی دیتابیس موجود</h2>
      <p>بررسی متادیتای واقعی آبجکت‌های ساخته‌شده توسط DBA یا ابزارهای دیگر؛ بدون فایل Word، بدون اجرای DDL و بدون تغییر دیتابیس.</p>
    </div></div>

    <DatabaseTargetPicker activeConnection={activeConnection} sessions={connectionSessions}
      onSelect={onSelectConnection} onGoConnections={onGoConnections}/>
    {!activeConnection?.token ? <section className="panel content-panel">
      <p className="message neutral" role="status">هیچ اتصال دیتابیس فعالی انتخاب نشده است. برای جلوگیری از ممیزی اشتباه، این صفحه به تنظیمات پیش‌فرض سرور متصل نمی‌شود.</p>
      <button type="button" className="btn primary" onClick={onGoConnections}>انتخاب و آزمایش اتصال دیتابیس</button>
    </section> : <>
      <section className="panel content-panel audit-context-panel" aria-label="هویت دیتابیس مقصد">
        <div className="toolbar"><h3>۱. مقصد ممیزی — اتصال انتخاب‌شده</h3>
          <button type="button" className="btn secondary" onClick={onGoConnections}>تغییر اتصال</button></div>
        {loadingPlan && <p className="message neutral" role="status">در حال بررسی نشست اتصال و دریافت قواعد ممیزی…</p>}
        {planError && <p className="message error" role="alert">{planError} <button type="button" className="btn secondary" onClick={onGoConnections}>اتصال مجدد</button></p>}
        {plan && <>
          <TargetIdentity target={plan.target}/>
          <p className="small muted">این هویت هنگام فعال‌سازی اتصال ثبت شده است؛ دسترسی واقعی به Schema/Table هنگام ممیزی بررسی می‌شود.</p>
          <p className="small muted">اعتبار اتصال تا: {new Date(plan.target.expiresAt).toLocaleString('fa-IR')}. فقط همین اتصال برای خواندن متادیتا استفاده می‌شود.</p>
        </>}
      </section>

      <form className="panel content-panel form-stack" onSubmit={audit} aria-label="تعریف محدوده و اجرای ممیزی">
        <h3>۲. محدوده ممیزی</h3>
        <div className="form-grid">
          <label className="field">محدوده
            <select value={scope} onChange={e => {setScope(e.target.value as 'table'|'schema'); invalidateReview();}}>
              <option value="table">یک Table موجود</option>
              <option value="schema">تمام Tableهای یک Schema</option>
            </select>
          </label>
          <label className="field grow">Schema / Database <span className="required">*</span>
            <input dir="ltr" required maxLength={128} value={schema} list="audit-schema-hints"
              onChange={e => {setSchema(e.target.value); invalidateReview();}} placeholder="BANK" />
            <datalist id="audit-schema-hints">
              {activeConnection.availableSchemas?.map(name => <option value={name} key={name}/>)}
            </datalist>
          </label>
          {scope === 'table' && <label className="field grow">Table <span className="required">*</span>
            <input dir="ltr" maxLength={128} required value={table}
              onChange={e => {setTable(e.target.value); invalidateReview();}} placeholder="ACCOUNTS" />
          </label>}
        </div>
        {plan?.target.verifiedSchema && schema.trim().toUpperCase() !== plan.target.verifiedSchema.toUpperCase() &&
          <p className="message neutral">Schema انتخاب‌شده با Schema تأییدشده هنگام اتصال متفاوت است. وجود و مجوز آن هنگام ممیزی از دیتابیس مقصد بررسی می‌شود.</p>}
        <p className="hint">در ممیزی Schema، جدول‌های قابل‌مشاهده با دسترسی حساب JDBC انتخاب‌شده بررسی می‌شوند؛
          برای جدول‌های بدون مجوز، نتیجه ممکن است ناقص یا درخواست ناموفق باشد.</p>

        <h3>۳. قواعدی که واقعاً اجرا می‌شوند</h3>
        {plan ? <>
          <div className="audit-rule-grid" aria-label="خانواده قواعد ممیزی">
            {plan.ruleFamilies.map(rule => <div className="audit-rule" key={rule.id}>
              <strong dir="ltr">{rule.id}</strong>
              <span>{RULE_LABELS[rule.id] || rule.description}</span>
              <small dir="ltr">{rule.description}</small>
            </div>)}
          </div>
          <p className="hint">این قواعد روی ساختار استخراج‌شده از Catalog اجرا می‌شوند. اسکن محتویات رکوردها، ممیزی امنیت و کارایی و اجرای SQL تغییردهنده در این مرحله انجام نمی‌شود.</p>
          <div className="audit-confirmation">
            <label className="inline-checkbox"><input type="checkbox" checked={acknowledged}
              onChange={event => setAcknowledged(event.target.checked)}/>
              <span>مقصد JDBC، محدوده و ۸ خانواده قاعده بالا را بررسی و تأیید کردم.</span></label>
            <div className="audit-final-target" dir="ltr">{plan.target.platform.toUpperCase()} · {plan.target.jdbcTarget} · {schema || '(schema)'}{scope === 'table' ? `.${table || '(table)'}` : ' (all tables)'}</div>
          </div>
        </> : <p className="small muted">قواعد تا زمان تأیید اتصال از Backend بارگذاری نمی‌شوند.</p>}
        <div className="form-actions"><button className="btn primary" type="submit"
          disabled={busy || !plan?.readOnly || !acknowledged || !schema.trim() || (scope === 'table' && !table.trim())}>
          {busy ? 'در حال خواندن و ممیزی متادیتا…' : 'اجرای ممیزی فقط‌خواندنی'}</button></div>
        {error && <p className="message error" role="alert">{error}</p>}
      </form>
    </>}

    {result && <section className="panel content-panel result-block" aria-label="نتایج ممیزی">
      <div className="page-heading"><h3>۴. نتیجه ممیزی و محل اجرا</h3>
        <button type="button" className="btn secondary" onClick={downloadJson}>دانلود گزارش JSON همراه با هویت مقصد</button></div>
      <TargetIdentity target={result.target}/>
      <p className="small muted">زمان پایان: {new Date(result.auditedAt).toLocaleString('fa-IR')}</p>
      <div className="audit-report">
        <div className="toolbar"><strong dir="ltr">{result.report.platform} · {result.report.schema}{result.report.table ? `.${result.report.table}` : ''}</strong>
          <span className={`status ${result.report.summary.compliant ? 'approved' : 'rejected'}`}>
            {result.report.summary.compliant ? 'بدون خطا/هشدار' : 'یافته دارد'}</span></div>
        <div className="stat-strip">
          <span>جدول: <strong>{result.report.summary.tablesScanned}</strong></span>
          <span>ستون: <strong>{result.report.summary.columnsScanned}</strong></span>
          <span>خطا: <strong>{result.report.summary.errorCount}</strong></span>
          <span>هشدار: <strong>{result.report.summary.warningCount}</strong></span>
          <span>اطلاعات: <strong>{result.report.summary.infoCount}</strong></span>
        </div>
        {result.report.ruleFamilySummaries && <div className="audit-family-summary" aria-label="خلاصه نتایج هر خانواده قاعده">
          {result.report.ruleFamilySummaries.map(item => <div key={item.ruleFamily}>
            <strong dir="ltr">{item.ruleFamily}</strong>
            <span>{item.findingCount} یافته · {item.errorCount} خطا · {item.warningCount} هشدار</span>
          </div>)}
        </div>}
        {result.report.findings?.length > 0 ? <div className="table-scroll"><table>
          <thead><tr><th>شدت</th><th>خانواده</th><th>کد</th><th>مسیر</th><th>پیام</th></tr></thead>
          <tbody>{result.report.findings.map((finding, index) => <tr key={index}>
            <td>{finding.severity}</td><td dir="ltr">{finding.ruleFamily}</td><td dir="ltr">{finding.code}</td>
            <td dir="ltr">{finding.path}</td><td>{finding.message}</td></tr>)}</tbody>
        </table></div> : <p className="small muted">برای آبجکت‌های قابل مشاهده، یافته‌ای ثبت نشده است.</p>}
      </div>
    </section>}
  </section>;
}
