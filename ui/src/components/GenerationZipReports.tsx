import { lazy, Suspense, useEffect, useMemo, useState } from 'react';
import type { ReviewHandoff } from '../reviewHandoff';
import { prepareReviewHandoff } from '../reviewHandoff';
import type { Download } from '../transport';
import { saveDownload } from '../transport';
import {
  extractReportDownload, readReportPreview, readTermEvidence,
  type ReportFile, type ReportPreview, type TermEvidence, type ZipInspection,
} from '../zipReports';

const GenerationDiagrams = lazy(() => import('./GenerationDiagrams'));


type Tab = 'glossary' | 'status' | 'files' | 'diagrams';
type TermGroup = 'new' | 'eligible' | 'manual' | 'typos' | 'pending' | 'plural';
const format = (value: number | undefined): string => value === undefined ? '—' : value.toLocaleString('fa-IR');
const safeTerms = (list: unknown): string[] => Array.isArray(list) ? list.filter((word): word is string => typeof word === 'string') : [];
const groups: {id: TermGroup; title: string}[] = [
  {id:'new', title:'جدید'}, {id:'eligible', title:'واجد شرایط Candidate'},
  {id:'manual', title:'بازبینی انسانی'}, {id:'typos', title:'مشکوک به املا'},
  {id:'pending', title:'در انتظار تأیید'}, {id:'plural', title:'شکل جمع در ستون'},
];
function displayStatus(status?: string): string {
  return status === 'SUCCESS' ? 'SUCCESS — تولید کامل'
    : status === 'PARTIAL_SUCCESS' ? 'PARTIAL_SUCCESS — تولید ناقص'
    : status ? `${status} — وضعیت Backend` : 'وضعیت تولید در Manifest مشخص نیست';
}

export default function GenerationZipReports({inspection, download, onReviewTerm}: {
  inspection: ZipInspection; download: Download; onReviewTerm: (handoff: ReviewHandoff) => void;
}) {
  const [tab, setTab] = useState<Tab>(inspection.glossary ? 'glossary' : 'status');
  const [group, setGroup] = useState<TermGroup>('new');
  const [search, setSearch] = useState('');
  const [term, setTerm] = useState('');
  const [evidence, setEvidence] = useState<{total:number; rows:TermEvidence[]} | null>(null);
  const [evidenceError, setEvidenceError] = useState('');
  const [filePath, setFilePath] = useState(
    inspection.reports.find(item => item.path === 'reports/batch-generation-summary.csv')?.path
    ?? inspection.reports.find(item => item.path === 'reports/schemaforge-generation-summary.txt')?.path
    ?? inspection.reports[0]?.path ?? '');
  const [preview, setPreview] = useState<ReportPreview | null>(null);
  const [previewError, setPreviewError] = useState('');
  const [previewBusy, setPreviewBusy] = useState(false);
  const [fileDownloadBusy, setFileDownloadBusy] = useState(false);
  const [reviewBusy, setReviewBusy] = useState(false);
  const [reviewError, setReviewError] = useState('');
  const summary = inspection.glossary;
  const candidate = summary?.candidateRegistration;
  const manifest = inspection.manifest;
  const candidates: Record<TermGroup, string[]> = useMemo(() => ({
    new: safeTerms(summary?.newTerms),
    eligible: safeTerms(candidate?.eligibleTerms), manual: safeTerms(candidate?.manualReviewTerms),
    typos: safeTerms(candidate?.suspectedTypos), pending: safeTerms(summary?.pendingTerms),
    plural: safeTerms(summary?.pluralColumnTerms),
  }), [summary, candidate]);
  const visibleTerms = candidates[group].filter(word => word.toLocaleUpperCase('en').includes(search.trim().toLocaleUpperCase('en')));
  const selectedFile = inspection.reports.find(item => item.path === filePath);
  useEffect(() => {
    let active = true;
    const controller = new AbortController();
    setEvidence(null); setEvidenceError('');
    if (!term) return;
    readTermEvidence(inspection, term, controller.signal)
      .then(value => { if (active) { if (value) setEvidence(value); else setEvidenceError('فایل unknown-terms.csv در ZIP موجود نیست.'); } })
      .catch(error => { if (active) setEvidenceError(error instanceof Error ? error.message : 'شواهد این واژه قابل خواندن نیست.'); });
    return () => { active = false; controller.abort(); };
  }, [inspection, term]);
  useEffect(() => {
    let active = true;
    setPreview(null); setPreviewError(''); setPreviewBusy(false);
    if (tab !== 'files' || !selectedFile || selectedFile.kind === 'download-only') return;
    setPreviewBusy(true);
    readReportPreview(inspection, selectedFile).then(value => { if (active) setPreview(value); })
      .catch(err => { if (active) setPreviewError(err instanceof Error ? err.message : 'خواندن گزارش ممکن نبود.'); })
      .finally(() => { if (active) setPreviewBusy(false); });
    return () => { active = false; };
  }, [tab, inspection, filePath]);
  async function downloadReport(file: ReportFile) {
    setFileDownloadBusy(true); setPreviewError('');
    try { saveDownload({filename: file.filename, blob: await extractReportDownload(inspection, file)}); }
    catch (err) { setPreviewError(err instanceof Error ? err.message : 'دریافت فایل گزارش ممکن نبود.'); }
    finally { setFileDownloadBusy(false); }
  }
  async function goReview() {
    if (!term || reviewBusy) return;
    setReviewBusy(true); setReviewError('');
    try { onReviewTerm(await prepareReviewHandoff(inspection, term)); }
    catch (err) { setReviewError(err instanceof Error ? err.message : 'انتقال به صف بازبینی ممکن نبود.'); }
    finally { setReviewBusy(false); }
  }
  const exceptional = manifest?.artifacts ?? [];
  const exceptionalCount = manifest?.exceptionalCount ?? exceptional.length;
  return <section className="panel content-panel result-block generation-results" aria-label="نتایج تولید و گزارش‌های ZIP">
    <div className="page-heading">
      <div><h3>نتایج تولید و گزارش‌ها</h3>
        <p>اطلاعات مستقیماً از فایل ZIP همین درخواست خوانده شده‌اند؛ هیچ تغییری در REST یا H2 ایجاد نشده است.</p>
      </div>
      <button type="button" className="btn secondary" onClick={() => saveDownload(download)}>دریافت دوباره ZIP</button>
    </div>
    <p className={`message ${manifest?.requestStatus === 'SUCCESS' ? 'success' : manifest?.requestStatus === 'PARTIAL_SUCCESS' ? 'warning' : 'neutral'}`} role="status">
      <strong>{displayStatus(manifest?.requestStatus)}</strong>
      <span className="result-source" dir="ltr">{manifest?.source?.name ?? download.filename}</span>
      <span className="result-small">دریافت موفق ZIP لزوماً به معنی تولید کامل تمام Scriptها نیست.</span>
      {manifest?.summarySource === 'generation-summary' && <span className="result-small">این وضعیت از گزارش متنی Backend خوانده شده؛ Manifest بزرگ یا نامعتبر است و وضعیت تک‌تک Artifactها تأیید نشده.</span>}
    </p>
    {inspection.warnings.length > 0 && <div className="message neutral" role="note">{inspection.warnings.map(message => <p key={message}>{message}</p>)}</div>}
    <div className="result-metrics">
      <div><span>تولیدشده</span><strong>{format(manifest?.artifactOutcomes?.generated)}</strong></div>
      <div><span>مسدودشده</span><strong>{format(manifest?.artifactOutcomes?.blocked)}</strong></div>
      <div><span>تولیدنشده / Skip</span><strong>{format(manifest?.artifactOutcomes?.skipped)}</strong></div>
      <div><span>خطاهای Validation</span><strong>{format(manifest?.validation?.errorCount)}</strong></div>
      <div><span>هشدارهای Validation</span><strong>{format(manifest?.validation?.warningCount)}</strong></div>
    </div>
    {manifest?.batchInput && (manifest.batchInput.processableDocumentCount !== undefined || manifest.batchInput.successCount !== undefined) && <>
      <p className="hint">خلاصه پردازش اسناد Batch (مستقل از شمارنده‌های Artifact)</p>
      <div className="result-metrics minor">
        <div><span>اسناد قابل پردازش</span><strong>{format(manifest.batchInput.processableDocumentCount)}</strong></div>
        <div><span>اسناد موفق</span><strong>{format(manifest.batchInput.successCount)}</strong></div>
        <div><span>اسناد ناموفق</span><strong>{format(manifest.batchInput.failedCount)}</strong></div>
        <div><span>ورودی‌های کنارگذاشته‌شده</span><strong>{format(manifest.batchInput.skippedCount)}</strong></div>
      </div>
    </>}
    <div className="tabs result-tabs" role="tablist" aria-label="بخش‌های گزارش خروجی">
      <button type="button" role="tab" aria-selected={tab === 'glossary'} className={`tab ${tab === 'glossary' ? 'active' : ''}`} onClick={() => setTab('glossary')}>واژگان Glossary</button>
      <button type="button" role="tab" aria-selected={tab === 'status'} className={`tab ${tab === 'status' ? 'active' : ''}`} onClick={() => setTab('status')}>وضعیت Artifact</button>
      <button type="button" role="tab" aria-selected={tab === 'files'} className={`tab ${tab === 'files' ? 'active' : ''}`} onClick={() => setTab('files')}>فایل‌های گزارش</button>
      <button type="button" role="tab" aria-selected={tab === 'diagrams'} className={`tab ${tab === 'diagrams' ? 'active' : ''}`}
        onClick={() => setTab('diagrams')}>نمودارها ({format(inspection.diagrams.length)})</button>
    </div>
    {tab === 'glossary' && <div className="result-tab-body" role="tabpanel">
      {!summary ? <p className="empty">گزارش Glossary در ZIP وجود ندارد؛ تولید و دانلود فایل همچنان معتبر است.</p> : <>
        <div className="result-metrics minor">
          <div><span>واژه جدید</span><strong>{format(summary.uniqueNewTerms)}</strong></div>
          <div><span>Eligible</span><strong>{format(candidate?.eligibleCandidates)}</strong></div>
          <div><span>بازبینی انسانی</span><strong>{format(candidate?.requiresManualReview)}</strong></div>
          <div><span>مشکوک به املا</span><strong>{format(candidate?.possibleTypos)}</strong></div>
          <div><span>ثبت‌شده در این اجرا</span><strong>{format(candidate?.insertedCandidates)}</strong></div>
        </div>
        <p className="hint"><strong>حالت ثبت: {candidate?.mode ?? 'نامشخص'}</strong> — فهرست واژه‌های جدید، نتیجه اسکن این درخواست است؛ به معنی تأیید Steward یا ثبت قطعی در H2 نیست.</p>
        <div className="lookup-row result-filters">
          <label className="field">گروه واژه
            <select value={group} onChange={e => {setGroup(e.target.value as TermGroup); setTerm('');}}>
              {groups.map(item => <option key={item.id} value={item.id}>{item.title} ({format(candidates[item.id].length)})</option>)}
            </select>
          </label>
          <label className="field">جست‌وجوی واژه
            <input value={search} onChange={e => setSearch(e.target.value)} placeholder="مثلاً ISIC" dir="ltr"/>
          </label>
        </div>
        <div className="term-chip-list" aria-label="فهرست واژگان یافت‌شده">
          {visibleTerms.slice(0, 250).map(value => <button type="button" key={value}
            className={`term-chip ${term === value ? 'chosen' : ''}`} aria-pressed={term === value}
            onClick={() => setTerm(value)} dir="ltr">{value}</button>)}
          {visibleTerms.length === 0 && <span className="meta">موردی یافت نشد.</span>}
        </div>
        {visibleTerms.length > 250 && <p className="hint">۲۵۰ واژه نخست نمایش داده شده‌اند؛ برای محدودکردن نتایج جست‌وجو کنید.</p>}
        {term && <div className="evidence-block">
          <div className="page-heading"><div><h3 dir="ltr">{term}</h3><p>شواهد استفاده در جدول‌ها و ستون‌ها از `unknown-terms.csv`</p></div>
            <div className="form-actions">
              <button type="button" className="btn secondary" disabled={reviewBusy}
                onClick={() => void goReview()}>{reviewBusy ? 'در حال انتقال…' : 'پیگیری در صف Glossary'}</button>
              <button type="button" className="btn subtle" onClick={() => setTerm('')}>بستن</button>
            </div>
          </div>
          {reviewError && <p role="alert" className="message error">{reviewError}</p>}
          <p className="hint">پیگیری در صف، واژه را خودکار تأیید یا ثبت نمی‌کند؛ در صورت نیاز، ورود گزارش فقط با مجوز و تأیید شما انجام می‌شود.</p>
          {evidenceError && <p role="alert" className="message error">{evidenceError}</p>}
          {evidence === null && !evidenceError && <p className="loading">در حال خواندن شواهد…</p>}
          {evidence && <><p className="hint">{format(evidence.total)} رخداد. {evidence.total > 25 ? '۲۵ رخداد نخست نمایش داده شده است.' : ''}</p>
            {evidence.total > 0 ? <div className="table-scroll"><table><thead><tr><th>فایل منبع</th><th>جدول</th><th>ستون / Identifier</th><th>نوع</th><th>وضعیت</th></tr></thead><tbody>
              {evidence.rows.map((item, index) => <tr key={`${item.identifier}:${index}`}>
                <td dir="ltr">{item.source}</td><td dir="ltr">{item.table}</td><td dir="ltr">{item.column || item.identifier}</td>
                <td dir="ltr">{item.objectType}</td><td dir="ltr">{item.status}</td>
              </tr>)}
            </tbody></table></div> : <p className="hint">برای این واژه در فایل CSV رخدادی ثبت نشده است.</p>}
          </>}
          {!evidence && !evidenceError && !inspection.reports.some(item => item.path === 'reports/glossary/unknown-terms.csv') &&
            <p className="hint">فایل جزئیات در ZIP موجود نیست.</p>}
        </div>}
      </>}
    </div>}
    {tab === 'status' && <div className="result-tab-body" role="tabpanel">
      {!manifest ? <p className="empty">فایل Manifest خوانده نشده است.</p> : <>
        <p className="hint">وضعیت این درخواست براساس {manifest.summarySource === 'generation-summary' ? 'گزارش متنی Backend (خلاصه موقت)' : 'manifest.json'} گزارش می‌شود، نه وضعیت HTTP دانلود.</p>
        {manifest.summarySource === 'generation-summary' ? <p className="message neutral">Manifest کامل قابل بررسی نبود؛ برای جزئیات Artifact از فایل‌های گزارش یا ZIP اصلی استفاده کنید.</p>
        : exceptionalCount === 0 ? <p className="message success">Artifact مسدود یا ردشده‌ای در Manifest ثبت نشده است.</p> : <>
          <p className="hint">{format(exceptionalCount)} Artifact با وضعیت غیر از GENERATED؛ حداکثر ۱۰۰ مورد نخست نمایش داده می‌شود:</p>
          <div className="table-scroll"><table><thead><tr><th>وضعیت</th><th>نوع</th><th>نام</th><th>دلیل</th></tr></thead><tbody>
            {exceptional.slice(0, 100).map((item, index) => <tr key={`${item.logicalName}:${index}`}>
              <td dir="ltr">{item.status}</td><td dir="ltr">{item.type}</td>
              <td dir="ltr">{item.logicalName}</td><td className="reason-cell" dir="ltr">{item.outcomeReason ?? '—'}</td>
            </tr>)}
          </tbody></table></div>
          {exceptionalCount > exceptional.length && <p className="hint">برای تمام جزئیات، فایل اصلی manifest.json را جداگانه دریافت کنید.</p>}
        </>}
      </>}
    </div>}
    {tab === 'diagrams' && <div className="result-tab-body" role="tabpanel"><Suspense fallback={<p className="message neutral" role="status">در حال بارگذاری نمایشگر نمودارها…</p>}><GenerationDiagrams inspection={inspection}/></Suspense></div>}
    {tab === 'files' && <div className="result-tab-body" role="tabpanel">
      <div className="lookup-row result-filters">
        <label className="field grow">گزارش داخل ZIP
          <select dir="ltr" value={filePath} onChange={e => setFilePath(e.target.value)}>
            {inspection.reports.map(item => <option key={item.path} value={item.path}>{item.path}</option>)}
          </select>
        </label>
        {selectedFile && <button type="button" className="btn secondary" disabled={fileDownloadBusy}
          onClick={() => void downloadReport(selectedFile)}>{fileDownloadBusy ? 'در حال استخراج…' : 'دریافت همین گزارش'}</button>}
      </div>
      {selectedFile?.kind === 'download-only' && <p className="message neutral">این فایل برای نمایش متنی مناسب نیست؛ می‌توانید آن را جداگانه دریافت کنید.</p>}
      {previewBusy && <p className="loading">در حال خواندن گزارش…</p>}
      {previewError && <p className="message error" role="alert">{previewError}</p>}
      {preview?.kind === 'csv' && <>
        <p className="hint">{preview.totalRows === null ? 'تعداد کل ردیف‌ها در گزارش حجیم محاسبه نشده' : `${format(preview.totalRows)} رکورد`}؛ {format(preview.previewRows)} ردیف پیش‌نمایش. فایل کامل قابل دریافت است.</p>
        {preview.truncated && <p className="message neutral">فقط ابتدای گزارش بزرگ خوانده شده است؛ برای بررسی کامل، فایل اصلی را دریافت کنید.</p>}
        <div className="table-scroll report-table"><table><thead><tr>{preview.headers.map((h,i) => <th key={i} dir="ltr">{h}</th>)}</tr></thead><tbody>
          {preview.rows.map((row,i) => <tr key={i}>{preview.headers.map((_,j) => <td key={j} dir="ltr">{row[j] ?? ''}</td>)}</tr>)}
        </tbody></table></div>
      </>}
      {preview?.kind === 'text' && <>
        {preview.truncated && <p className="message neutral">پیش‌نمایش این گزارش حجیم محدود است؛ نمایش خلاصه به معنی بررسی تمام محتوای فایل نیست. برای محتوای کامل «دریافت همین گزارش» را بزنید.</p>}
        <pre className="code-preview report-text" dir="ltr">{preview.text}</pre>
      </>}
      {inspection.reports.length === 0 && <p className="empty">گزارش متنی در آرشیو موجود نیست.</p>}
    </div>}
  </section>;
}
