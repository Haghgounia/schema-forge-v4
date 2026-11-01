/**
 * SchemaForge UI: verify the Vite production import graph.
 * Pure Node.js ESM with no third-party dependencies.
 *
 * The entrypoint may statically load only lightweight pages. Heavy rendering
 * engines and the 12 deliberately lazy views must remain outside the eager
 * graph. This check fails the release build rather than hiding oversized UI.
 */
import {readFileSync} from 'node:fs';
import {gzipSync} from 'node:zlib';
import {resolve, sep} from 'node:path';
import {fileURLToPath} from 'node:url';

export const MAX_EAGER_GZIP_BYTES = 240 * 1024;

const LAZY_MODULES = [
  ...['ConnectionsView', 'GenerationView', 'ConformanceView', 'CrudView',
      'DiagramView', 'QueueView', 'TermsView', 'HistoryView', 'GlossaryAdminView']
    .map(name => `src/views/${name}.tsx`),
  ...['GenerationZipReports', 'GenerationDiagrams', 'RenderedDiagram']
    .map(name => `src/components/${name}.tsx`),
];

function heavyModule(name) {
  return /(?:^|[\\/_-])(?:graphviz|mermaid|jszip|viz)(?:[\\/._-]|$)/i.test(name);
}

/**
 * @param {object} manifest Vite manifest (as parsed JSON)
 * @param {(relativePath: string) => Uint8Array|Buffer} readAsset
 * @returns {{eagerGzipBytes:number,lazyViews:number,eagerFiles:string[]}}
 */
export function inspectLazyBuild(manifest, readAsset) {
  if (!manifest || typeof manifest !== 'object' || !manifest['index.html']?.isEntry) {
    throw new Error('Vite manifest must include an index.html entry');
  }
  const absent = LAZY_MODULES.filter(name => !manifest[name]?.file);
  if (absent.length) throw new Error(`Missing lazy UI modules in Vite manifest: ${absent.join(', ')}`);

  const reachable = new Set();
  const stack = ['index.html'];
  const files = new Set();
  while (stack.length) {
    const name = stack.pop();
    if (reachable.has(name)) continue;
    const item = manifest[name];
    if (!item || !item.file) throw new Error(`Missing eager dependency in Vite manifest: ${name}`);
    if (LAZY_MODULES.includes(name)) throw new Error(`Lazy UI module loaded eagerly: ${name}`);
    if (heavyModule(name) || heavyModule(item.file)) {
      throw new Error(`Graphviz/Mermaid/JSZip module must not be eager: ${name}`);
    }
    reachable.add(name);
    files.add(item.file);
    for (const css of item.css ?? []) files.add(css);
    // A dynamic import is intentionally not an eager dependency.
    for (const dependency of item.imports ?? []) stack.push(dependency);
  }
  let eagerGzipBytes = 0;
  for (const file of files) {
    if (file.startsWith('/') || file.includes('\\') || file.split('/').includes('..')) {
      throw new Error(`Unsafe Vite artifact path: ${file}`);
    }
    if (heavyModule(file)) {
      throw new Error(`Graphviz/Mermaid/JSZip asset included in eager graph: ${file}`);
    }
    const bytes = readAsset(file);
    eagerGzipBytes += gzipSync(Buffer.from(bytes)).length;
  }
  if (eagerGzipBytes > MAX_EAGER_GZIP_BYTES) {
    throw new Error(`First-load gzip budget exceeded: ${eagerGzipBytes} > ${MAX_EAGER_GZIP_BYTES} bytes`);
  }
  return {eagerGzipBytes, lazyViews: LAZY_MODULES.length, eagerFiles: [...files].sort()};
}

const mainFile = process.argv[1] ? resolve(process.argv[1]) : '';
if (mainFile === fileURLToPath(import.meta.url)) {
  const dist = resolve(fileURLToPath(new URL('../dist/', import.meta.url)));
  const manifest = JSON.parse(readFileSync(resolve(dist, '.vite/manifest.json'), 'utf8'));
  const result = inspectLazyBuild(manifest, path => {
    const candidate = resolve(dist, path);
    if (!candidate.startsWith(`${dist}${sep}`)) throw new Error(`Unsafe build artifact: ${path}`);
    return readFileSync(candidate);
  });
  console.log(`[PASS] Initial UI: ${result.eagerGzipBytes} gzip bytes, ${result.lazyViews} lazy modules, ${result.eagerFiles.length} eager assets`);
}
