import { lazy, Suspense, useEffect, useState } from 'react';
import { readDiagramSource, extractDiagramDownload, type DiagramFile, type ZipInspection } from '../zipReports';
import { saveDownload } from '../transport';
const RenderedDiagram = lazy(() => import('./RenderedDiagram'));

export default function GenerationDiagrams({inspection}: {inspection: ZipInspection}) {
  const [filePath, setFilePath] = useState(inspection.diagrams[0]?.path ?? '');
  const [source, setSource] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const selected = inspection.diagrams.find(value => value.path === filePath);
  useEffect(() => {
    let alive = true;
    setSource(''); setError(''); setLoading(false);
    if (!selected) return;
    setLoading(true);
    readDiagramSource(inspection, selected).then(text => { if (alive) setSource(text); })
      .catch(reason => { if (alive) setError(reason instanceof Error ? reason.message : 'نمودار قابل خواندن نیست.'); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [inspection, filePath]);
  async function downloadSource(file: DiagramFile) {
    setError('');
    try {
      saveDownload({ filename: file.filename, blob: await extractDiagramDownload(inspection, file) });
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'دریافت فایل نمودار ممکن نبود.'); }
  }
  return <div className="diagram-viewer">
    {inspection.diagrams.length === 0 ? <p className="empty">این ZIP فایل Mermaid یا Graphviz ندارد.</p> : <>
      <div className="lookup-row result-filters">
        <label className="field grow">انتخاب نمودار داخل ZIP ({inspection.diagrams.length.toLocaleString('fa-IR')})
          <select dir="ltr" value={filePath} onChange={e => setFilePath(e.target.value)}>
            {inspection.diagrams.map(file => <option key={file.path} value={file.path}>{file.path}</option>)}
          </select>
        </label>
        {selected && <button type="button" className="btn secondary" onClick={() => void downloadSource(selected)}>
          دریافت DOT / MMD</button>}
      </div>
      {loading && <p role="status" className="loading">در حال خواندن فایل نمودار از ZIP…</p>}
      {error && <p className="message error" role="alert">{error}</p>}
      {source && selected && <Suspense fallback={<p className="message neutral" role="status">در حال بارگذاری موتور نمایش نمودار…</p>}><RenderedDiagram key={filePath} source={source}
        engine={selected.engine} filename={selected.filename}/></Suspense>}
    </>}
  </div>;
}
