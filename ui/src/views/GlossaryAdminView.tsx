import { useEffect, useState, type FormEvent } from 'react';
import { glossaryApi } from '../api';
import type { GlossaryKind, GlossaryStatus, GlossaryTerm, GlossaryTermRequest } from '../types';

const blank: GlossaryTermRequest = { english: '', persian: '', module: '', kind: 'ATOMIC', status: 'CANDIDATE' };
export default function GlossaryAdminView({ adminKey, editId, onSaved }: {
  adminKey: string; editId: number | null; onSaved: () => void;
}) {
  const [idText, setIdText] = useState('');
  const [loaded, setLoaded] = useState<GlossaryTerm | null>(null);
  const [form, setForm] = useState<GlossaryTermRequest>({...blank});
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const [file, setFile] = useState<File|null>(null);
  const [importStatus, setImportStatus] = useState<GlossaryStatus>('CANDIDATE');
  const [bulkKind, setBulkKind] = useState<GlossaryKind|''>('');
  const [bulkFrom, setBulkFrom] = useState<GlossaryStatus>('CANDIDATE');
  const [bulkTo, setBulkTo] = useState<GlossaryStatus>('APPROVED');
  const [bulkCode, setBulkCode] = useState('');
  const authorized = Boolean(adminKey);

  async function getTerm(id: number) {
    setBusy(true); setError(''); setMessage('');
    try {
      const result = await glossaryApi.term(id);
      setLoaded(result); setIdText(String(result.id));
      setForm({ english: result.english, persian: result.persian || '', module: result.module || '',
        kind: result.kind, status: result.status });
    } catch (err) { setError(err instanceof Error ? err.message : 'دریافت واژه ناموفق بود.'); }
    finally { setBusy(false); }
  }
  useEffect(() => { if (editId !== null) void getTerm(editId); }, [editId]);
  function newTerm() { setLoaded(null); setForm({...blank}); setIdText(''); setError(''); setMessage(''); }

  async function saveTerm(e: FormEvent<HTMLFormElement>) {
    e.preventDefault(); if (!authorized || busy || !form.english.trim()) return;
    const operation = loaded ? `ویرایش ${loaded.key}` : `ایجاد ${form.english}`;
    if (form.status !== 'CANDIDATE' && !window.confirm(
      'این عملیات وضعیت را بدون ثبت رویداد Review Decision تغییر می‌دهد. برای تصمیم‌گیری ترجیحاً از صف بازبینی استفاده کنید. ادامه می‌دهید؟')) return;
    if (loaded && form.status !== loaded.status && !window.confirm('تغییر وضعیت مستقیم، تاریخچه تصمیم Steward ایجاد نمی‌کند. ادامه؟')) return;
    setBusy(true); setError(''); setMessage('');
    try {
      const output = loaded ? await glossaryApi.update(adminKey, loaded.id, form) : await glossaryApi.create(adminKey, form);
      setLoaded(output); setIdText(String(output.id));
      setMessage(`${operation} با موفقیت در REST Service ثبت شد (ID: ${output.id}).`);
      onSaved();
    } catch (err) { setError(err instanceof Error ? err.message : 'ذخیره واژه ناموفق بود.'); }
    finally { setBusy(false); }
  }
  async function removeTerm() {
    if (!loaded || !authorized || busy) return;
    if (!window.confirm(`واژه ${loaded.key} به صورت Soft Delete حذف شود؟ این عملیات از مسیر Review History نمی‌گذرد.`)) return;
    setBusy(true); setError(''); setMessage('');
    try {
      await glossaryApi.remove(adminKey, loaded.id);
      setMessage(`واژه ${loaded.key} به صورت Soft Delete حذف شد.`);
      setLoaded(null); setForm({...blank}); onSaved();
    } catch (err) { setError(err instanceof Error ? err.message : 'حذف واژه ناموفق بود.'); }
    finally { setBusy(false); }
  }
  async function importExcel(e: FormEvent<HTMLFormElement>) {
    e.preventDefault(); if (!file || !authorized || busy) return;
    if (!/\.xlsx$/i.test(file.name)) { setError('فایل ورودی باید XLSX باشد.'); return; }
    if (importStatus !== 'CANDIDATE' && !window.confirm('Import با وضعیت تأیید/رد انجام می‌شود و تاریخچه فردی Steward ایجاد نمی‌کند. ادامه؟')) return;
    setBusy(true); setError(''); setMessage('');
    try {
      const outcome = await glossaryApi.importWorkbook(adminKey, file, importStatus);
      setMessage(`Import انجام شد: ${outcome.inserted} واژه جدید، ${outcome.alreadyPresent} مورد قبلی، ${outcome.sourceRows} سطر.`);
      onSaved();
    } catch (err) { setError(err instanceof Error ? err.message : 'Import Excel ناموفق بود.'); }
    finally { setBusy(false); }
  }
  async function bulkChange(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!authorized || busy || bulkCode !== 'BULK' || bulkFrom === bulkTo) return;
    const scope = bulkKind || 'تمام نوع‌ها';
    if (!window.confirm(`تغییر گروهی همه رکوردهای ${scope} از ${bulkFrom} به ${bulkTo}؟ ممکن است تعداد زیادی رکورد تغییر کند. هیچ رکوردی را به طور جداگانه تأیید نکرده‌اید.`)) return;
    setBusy(true); setError(''); setMessage('');
    try {
      const outcome = await glossaryApi.bulkStatus(adminKey,
        {kind: bulkKind || undefined, from: bulkFrom, to: bulkTo});
      setMessage(`Bulk Status انجام شد: ${outcome.updated} رکورد تغییر کرد.`);
      setBulkCode(''); onSaved();
    } catch (err) { setError(err instanceof Error ? err.message : 'تغییر گروهی ناموفق بود.'); }
    finally { setBusy(false); }
  }

  return <section>
    <div className="page-heading"><div><h2>مدیریت واژه‌ها</h2>
      <p>ایجاد، ویرایش، حذف نرم، Import Excel و Bulk Status با RESTهای موجود.</p></div></div>
    {!authorized && <p role="status" className="message neutral">برای عملیات مدیریتی ابتدا کلید مدیر Glossary را در نوار دسترسی وارد کنید. جست‌وجو و نمایش جزئیات بدون کلید قابل استفاده است.</p>}
    <div className="panel content-panel form-stack">
      <div className="page-heading"><h3>{loaded ? `ویرایش واژه #${loaded.id}` : 'ایجاد واژه جدید'}</h3>
        <button type="button" className="btn secondary" onClick={newTerm}>فرم جدید</button></div>
      <form className="lookup-row" onSubmit={e => { e.preventDefault(); if (Number(idText) > 0) void getTerm(Number(idText)); }}>
        <label className="field">دریافت جزئیات با ID
          <input dir="ltr" type="number" min="1" value={idText} onChange={e => setIdText(e.target.value)} placeholder="Term ID" /></label>
        <button type="submit" className="btn secondary" disabled={!idText || busy}>دریافت</button>
      </form>
      <form className="form-stack" onSubmit={saveTerm}>
        <div className="form-grid">
          <label className="field grow">نام انگلیسی <span className="required">*</span>
            <input dir="ltr" required value={form.english} maxLength={256}
              onChange={e => setForm({...form, english: e.target.value})} placeholder="CUSTOMER" /></label>
          <label className="field">نوع
            <select value={form.kind} onChange={e => setForm({...form, kind: e.target.value as GlossaryKind})}>
              <option value="ATOMIC">ATOMIC</option><option value="PHRASE">PHRASE</option></select></label>
          <label className="field">وضعیت
            <select value={form.status} onChange={e => setForm({...form, status: e.target.value as GlossaryStatus})}>
              <option value="CANDIDATE">CANDIDATE</option><option value="APPROVED">APPROVED</option><option value="REJECTED">REJECTED</option>
            </select></label>
        </div>
        <div className="form-grid">
          <label className="field grow">معادل فارسی (اختیاری)
            <input value={form.persian} maxLength={2000} onChange={e => setForm({...form, persian: e.target.value})}/></label>
          <label className="field grow">ماژول (اختیاری)
            <input dir="ltr" value={form.module} maxLength={1000} onChange={e => setForm({...form, module: e.target.value})}/></label>
        </div>
        <p className="hint">ویرایش مستقیم وضعیت، تاریخچه Review Decision ایجاد نمی‌کند. برای تأیید/رد دارای دلیل از «صف بازبینی» استفاده کنید.</p>
        <div className="form-actions"><button type="submit" className="btn primary" disabled={!authorized || busy || !form.english.trim()}>
          {busy ? 'در حال انجام…' : loaded ? 'ذخیره ویرایش' : 'ایجاد واژه'}</button>
          {loaded && <button type="button" className="btn danger-outline" disabled={!authorized || busy} onClick={removeTerm}>Soft Delete</button>}
        </div>
      </form>
      {error && <p role="alert" className="message error">{error}</p>}
      {message && <p role="status" className="message success">{message}</p>}
    </div>
    <details className="import-details"><summary>Import فایل Excel (فقط شیت 01_Atomic_Terms)</summary>
      <form className="form-stack" onSubmit={importExcel}>
        <div className="form-grid"><label className="field grow">فایل XLSX
          <input type="file" accept=".xlsx" required onChange={e => setFile(e.target.files?.[0] ?? null)}/></label>
          <label className="field">وضعیت مدخل‌های جدید
            <select value={importStatus} onChange={e => setImportStatus(e.target.value as GlossaryStatus)}>
              <option value="CANDIDATE">CANDIDATE</option><option value="APPROVED">APPROVED</option><option value="REJECTED">REJECTED</option>
            </select></label></div>
        <p className="hint">سایر شیت‌های Excel توسط سرویس Import نادیده گرفته می‌شوند. مدخل‌های موجود overwrite نمی‌شوند.</p>
        <div className="form-actions"><button className="btn primary" disabled={!authorized || !file || busy}>Import Excel</button></div>
      </form></details>
    <details className="import-details destructive"><summary>تغییر گروهی وضعیت (Bulk Status) — پرخطر</summary>
      <p className="message neutral">این API تمام رکوردهای مطابق فیلتر را تغییر می‌دهد و برای هر واژه Review Event فردی ثبت نمی‌کند. فقط برای عملیات کنترل‌شده مدیریتی.</p>
      <form className="form-stack" onSubmit={bulkChange}>
        <div className="form-grid">
          <label className="field">نوع واژه
            <select value={bulkKind} onChange={e => setBulkKind(e.target.value as GlossaryKind|'')}>
              <option value="">همه (گسترده)</option><option value="ATOMIC">ATOMIC</option><option value="PHRASE">PHRASE</option></select></label>
          <label className="field">از وضعیت
            <select value={bulkFrom} onChange={e => setBulkFrom(e.target.value as GlossaryStatus)}>
              <option value="CANDIDATE">CANDIDATE</option><option value="APPROVED">APPROVED</option><option value="REJECTED">REJECTED</option></select></label>
          <label className="field">به وضعیت
            <select value={bulkTo} onChange={e => setBulkTo(e.target.value as GlossaryStatus)}>
              <option value="APPROVED">APPROVED</option><option value="REJECTED">REJECTED</option><option value="CANDIDATE">CANDIDATE</option></select></label>
        </div>
        <label className="field">برای فعال‌کردن عملیات، عبارت <code dir="ltr">BULK</code> را تایپ کنید
          <input dir="ltr" value={bulkCode} autoComplete="off" onChange={e => setBulkCode(e.target.value)} placeholder="BULK" /></label>
        <div className="form-actions"><button type="submit" className="btn danger-outline" disabled={!authorized || busy || bulkFrom === bulkTo || bulkCode !== 'BULK'}>اجرای تغییر گروهی</button></div>
      </form></details>
  </section>;
}
