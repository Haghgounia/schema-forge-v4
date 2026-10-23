import { useEffect, useState } from 'react';
import { renderDiagramSvg, type DiagramEngine } from '../diagramRender';
import { saveDownload } from '../transport';

export default function RenderedDiagram({engine, source, filename}: {
  engine: DiagramEngine; source: string; filename: string;
}) {
  const [url, setUrl] = useState('');
  const [svg, setSvg] = useState('');
  const [zoom, setZoom] = useState(100);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => {
    let alive = true;
    let blobUrl: string | undefined;
    setUrl(''); setSvg(''); setError(''); setBusy(true); setZoom(100);
    renderDiagramSvg(engine, source).then(value => {
      if (!alive) return;
      blobUrl = URL.createObjectURL(new Blob([value], {type: 'image/svg+xml;charset=utf-8'}));
      setSvg(value); setUrl(blobUrl);
    }).catch(reason => {
      if (alive) setError(reason instanceof Error ? reason.message : 'نمایش نمودار ناموفق بود.');
    }).finally(() => { if (alive) setBusy(false); });
    return () => { alive = false; if (blobUrl) URL.revokeObjectURL(blobUrl); };
  }, [engine, source]);
  const svgName = filename.replace(/\.(dot|gv|mmd)$/i, '.svg').replace(/[\\/:*?"<>|\x00-\x1f]/g, '_');
  return <div className="diagram-render">
    <div className="diagram-toolbar">
      <span className="hint">رندر محلی {engine === 'mermaid' ? 'Mermaid' : 'Graphviz'} · بدون سرویس خارجی</span>
      <label className="diagram-zoom">بزرگ‌نمایی
        <input aria-label="بزرگ‌نمایی نمودار" type="range" min="50" max="250" step="25"
          value={zoom} onChange={event => setZoom(Number(event.target.value))}/>
        <output>{zoom}٪</output>
      </label>
      <button type="button" className="btn secondary" disabled={!svg}
        onClick={() => saveDownload({ filename: svgName, blob: new Blob([svg], {type: 'image/svg+xml'}) })}>
        دریافت SVG
      </button>
    </div>
    {busy && <p role="status" className="loading">در حال ترسیم نمودار در مرورگر…</p>}
    {error && <p role="alert" className="message error">{error} فایل DOT/MMD اصلی همچنان قابل دریافت است.</p>}
    {url && <div className="diagram-viewport" role="region" aria-label="نمایش نمودار" tabIndex={0}>
      <img src={url} alt={`نمودار ${filename}`} draggable={false}
        style={{width: `${zoom}%`}} referrerPolicy="no-referrer" />
    </div>}
    <details><summary>نمایش متن اصلی نمودار</summary>
      <pre className="code-preview report-text" dir="ltr">{source}</pre>
    </details>
  </div>;
}
