/** Render DOT and MMD entirely inside this browser. No remote renderer, no REST request.
 * The SVG is subsequently displayed via <img> (image context, never innerHTML).
 */
import { MAX_DIAGRAM_BYTES } from './zipReports.ts';

let mermaidLoader: Promise<(typeof import('mermaid'))['default']> | undefined;
let vizLoader: Promise<Awaited<ReturnType<(typeof import('@viz-js/viz'))['instance']>>> | undefined;
let serial = 0;
const MAX_SVG_BYTES = 12 * 1024 * 1024;

function mermaidLibrary() {
  if (!mermaidLoader) {
    mermaidLoader = import('mermaid').then(({default: mermaid}) => {
      mermaid.initialize({
        startOnLoad: false,
        securityLevel: 'strict',
        theme: 'default',
        maxTextSize: MAX_DIAGRAM_BYTES,
        maxEdges: 1500,
        suppressErrorRendering: true,
        flowchart: { htmlLabels: false },
      });
      return mermaid;
    }).catch(error => { mermaidLoader = undefined; throw error; });
  }
  return mermaidLoader;
}

function graphvizLibrary() {
  if (!vizLoader) {
    vizLoader = import('@viz-js/viz').then(module => module.instance())
      .catch(error => { vizLoader = undefined; throw error; });
  }
  return vizLoader;
}

export type DiagramEngine = 'mermaid' | 'graphviz';
export async function renderDiagramSvg(engine: DiagramEngine, source: string): Promise<string> {
  if (!source.trim() || new TextEncoder().encode(source).byteLength > MAX_DIAGRAM_BYTES) {
    throw new Error('منبع نمودار خالی یا بیش از حد مجاز ۲۵۶ کیلوبایت است. فایل اصلی را دریافت کنید.');
  }
  let svg: string;
  if (engine === 'graphviz') {
    const viz = await graphvizLibrary();
    svg = viz.renderString(source, {format: 'svg', engine: 'dot'});
  } else {
    const mermaid = await mermaidLibrary();
    svg = (await mermaid.render(`schemaforge-preview-${++serial}`, source)).svg;
  }
  if (!svg.includes('<svg') || new TextEncoder().encode(svg).byteLength > MAX_SVG_BYTES) {
    throw new Error('خروجی SVG معتبر نیست یا بیش از حد بزرگ است؛ فایل منبع را دریافت کنید.');
  }
  return svg;
}
