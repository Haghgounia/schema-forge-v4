/** Guard the Vite production entry: large diagram engines must remain lazy.
 * Runs AFTER `vite build`, using Vite's own manifest. No extra npm dependency.
 */
import { readFileSync } from 'node:fs';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';

const uiRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
export const MAX_EAGER_GZIP_BYTES = 240 * 1024;
const LAZY_VIEW_MODULES = [
  'src/views/GenerationView.tsx', 'src/views/ConnectionsView.tsx',
  'src/views/ConformanceView.tsx', 'src/views/CrudView.tsx',
  'src/views/DiagramView.tsx', 'src/views/TermsView.tsx',
  'src/views/QueueView.tsx', 'src/views/HistoryView.tsx',
  'src/views/GlossaryAdminView.tsx',
  'src/components/GenerationZipReports.tsx',
  'src/components/GenerationDiagrams.tsx',
  'src/components/RenderedDiagram.tsx',
];
const LARGE_ENGINE_FILE = /(?:^|\/)(?:viz|mermaid\.core|jszip\.min)-[^/]+\.js$/i;

/** @param {Record<string,any>} manifest @param {(path:string)=>Uint8Array} getBytes */
export function inspectLazyBuild(manifest, getBytes) {
  const entryKey = Object.keys(manifest).find(key => key === 'index.html' && manifest[key]?.isEntry)
    ?? Object.keys(manifest).find(key => manifest[key]?.isEntry && key.endsWith('.html'));
  if (!entryKey) throw new Error('Missing Vite HTML entry in production manifest');
  const eager = new Set();
  const visit = key => {
    if (eager.has(key)) return;
    const record = manifest[key];
    if (!record || typeof record.file !== 'string') throw new Error(`Missing static import in manifest: ${key}`);
    eager.add(key);
    for (const child of record.imports ?? []) visit(child);
  };
  visit(entryKey);
  // Fail if Vite hoists a route or any diagram engine into the first page.
  const eagerFiles = new Set();
  for (const key of eager) {
    const part = manifest[key];
    eagerFiles.add(part.file);
    for (const style of part.css ?? []) eagerFiles.add(style);
  }
  for (const key of LAZY_VIEW_MODULES) {
    if (!manifest[key]) throw new Error(`Expected a dedicated lazy UI module in Vite manifest: ${key}`);
    if (eager.has(key)) throw new Error(`Route/diagram module loaded eagerly: ${key}`);
  }
  if ([...eagerFiles].some(file => LARGE_ENGINE_FILE.test(file))) {
    throw new Error('Graphviz, Mermaid or JSZip engine is in the initial static import graph');
  }
  let eagerGzipBytes = 0;
  for (const file of eagerFiles) {
    const bytes = getBytes(file);
    if (!(bytes instanceof Uint8Array)) throw new Error(`Invalid emitted asset: ${file}`);
    eagerGzipBytes += gzipSync(bytes, { level: 9 }).byteLength;
  }
  if (eagerGzipBytes > MAX_EAGER_GZIP_BYTES) {
    throw new Error(`First-load gzip ${eagerGzipBytes} B exceeds ${MAX_EAGER_GZIP_BYTES} B budget`);
  }
  return {eagerGzipBytes, eagerAssets: [...eagerFiles], lazyViews: LAZY_VIEW_MODULES.length};
}

function cli() {
  const dist = join(uiRoot, 'dist');
  const manifest = JSON.parse(readFileSync(join(dist, '.vite/manifest.json'), 'utf8'));
  const result = inspectLazyBuild(manifest, asset => readFileSync(join(dist, asset)));
  console.log(`[PASS] Initial UI: ${result.eagerGzipBytes} gzip bytes across ${result.eagerAssets.length} assets; ${result.lazyViews} routes/panels lazy.`);
  console.log('[PASS] Mermaid / Graphviz are absent from the static entry graph.');
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) cli();
