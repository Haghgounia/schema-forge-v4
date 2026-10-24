import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {dirname, resolve, join} from 'node:path';
import {inspectLazyBuild, MAX_EAGER_GZIP_BYTES} from '../scripts/check-lazy-build.mjs';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const read = path => readFileSync(join(root,path),'utf8');

test('project routes and all Glossary panels are react-lazy, home stays eager',()=>{
  const code=read('src/App.tsx');
  const modules=['ConnectionsView','GenerationView','ConformanceView','CrudView','DiagramView','QueueView','TermsView','HistoryView','GlossaryAdminView'];
  for(const name of modules){
    assert.match(code, new RegExp(`const ${name}\\s*=\\s*lazy\\(\\(\\)\\s*=>\\s*import\\(`),name);
    assert.doesNotMatch(code,new RegExp(`import ${name} from`),name);
  }
  assert.match(code,/import OverviewView, \{type ProjectSection\}/);
  assert.match(code,/<Suspense fallback=/);
  assert.match(code,/setActiveMetadataConnection\(selection\)/); // preserve connection state wiring
  assert.match(code,/key=\{route\.tab\}/); // preserve tabs behavior
});

test('ZIP report panel, diagram ZIP tab and renderer remain separate lazy boundaries',()=>{
  for(const [path,moduleName] of [
    ['src/views/GenerationView.tsx','GenerationZipReports'],
    ['src/components/GenerationZipReports.tsx','GenerationDiagrams'],
    ['src/components/GenerationDiagrams.tsx','RenderedDiagram'],
    ['src/views/DiagramView.tsx','RenderedDiagram']]){
    const code=read(path);
    assert.match(code,new RegExp(`const ${moduleName}\\s*=\\s*lazy\\(\\(\\)\\s*=>\\s*import\\(`),path);
    assert.match(code,/<Suspense fallback=/,path);
  }
  const renderer=read('src/diagramRender.ts');
  assert.match(renderer,/import\('mermaid'\)/);
  assert.match(renderer,/import\('@viz-js\/viz'\)/);
  assert.doesNotMatch(read('src/App.tsx'),/from ['"](?:mermaid|@viz-js\/viz)['"]/);
});

test('Vite has a real build-graph budget instead of silently ignoring heavy engines',()=>{
  const config=read('vite.config.ts');
  assert.match(config,/manifest:\s*true/);
  assert.match(config,/chunkSizeWarningLimit:\s*1400/);
  const packageData=JSON.parse(read('package.json'));
  assert.match(packageData.scripts.build,/node scripts\/check-lazy-build\.mjs/);
  assert.equal(packageData.version,'0.5.9');
});

test('production manifest check accepts separated lazy pages and rejects eager Graphviz',()=>{
  const names=['GenerationView','ConnectionsView','ConformanceView','CrudView','DiagramView','TermsView','QueueView','HistoryView','GlossaryAdminView'];
  const manifest={'index.html':{isEntry:true,file:'assets/main.js',imports:['_react.js'],dynamicImports:names.map(name=>`src/views/${name}.tsx`)},'_react.js':{file:'assets/react.js'}};
  for(const name of names) manifest[`src/views/${name}.tsx`]={file:`assets/${name}.js`};
  for(const name of ['GenerationZipReports','GenerationDiagrams','RenderedDiagram']) manifest[`src/components/${name}.tsx`]={file:`assets/${name}.js`};
  const smallAssets=()=>new Uint8Array(200);
  const ok=inspectLazyBuild(manifest,smallAssets);
  assert.ok(ok.eagerGzipBytes < MAX_EAGER_GZIP_BYTES);
  assert.equal(ok.lazyViews,12);
  manifest['_react.js'].imports=['_viz.js'];
  manifest['_viz.js']={file:'assets/viz-1234.js'};
  assert.throws(()=>inspectLazyBuild(manifest,smallAssets),/Graphviz|Mermaid|JSZip/);
  delete manifest['_viz.js']; delete manifest['_react.js'].imports;
  manifest['src/views/GenerationView.tsx'].file='assets/x.js';
  manifest['_react.js'].imports=['src/views/GenerationView.tsx'];
  assert.throws(()=>inspectLazyBuild(manifest,smallAssets),/loaded eagerly/);
});

test('production manifest check enforces total eager gzip byte limit',()=>{
  const names=['GenerationView','ConnectionsView','ConformanceView','CrudView','DiagramView','TermsView','QueueView','HistoryView','GlossaryAdminView'];
  const manifest={'index.html':{isEntry:true,file:'assets/main.js'}};
  for(const name of names)manifest[`src/views/${name}.tsx`]={file:`assets/${name}.js`};
  for(const name of ['GenerationZipReports','GenerationDiagrams','RenderedDiagram'])manifest[`src/components/${name}.tsx`]={file:`assets/${name}.js`};
  const pseudoRandom=new Uint8Array(MAX_EAGER_GZIP_BYTES+3000);
  let v=0x12345678;
  for(let i=0;i<pseudoRandom.length;i++){
    v^=v<<13;v^=v>>>17;v^=v<<5;pseudoRandom[i]=v&255;
  }
  assert.throws(()=>inspectLazyBuild(manifest,()=>pseudoRandom),/First-load gzip/);
});
