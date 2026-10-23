import { test } from 'node:test';
import assert from 'node:assert/strict';
import JSZip from 'jszip';
import { inspectGenerationZip, readDiagramSource, extractDiagramDownload, reviewCsvFromZip, MAX_DIAGRAM_BYTES } from '../src/zipReports.ts';
import { prepareReviewHandoff } from '../src/reviewHandoff.ts';

const report = '"source_name","object_type","table_name","column_name","identifier","unknown_term","token_position","review_status"\r\n' +
  '"M.xml","COLUMN","T","ISIC_CODE","ISIC_CODE","ISIC","1","NEW"\r\n';
async function fixture({includeReport = true, tooLargeDiagram = false} = {}) {
  const archive = new JSZip();
  archive.file('manifest.json', JSON.stringify({requestStatus:'SUCCESS',source:{name:'M.xml'}}));
  archive.file('reports/glossary/summary.json', JSON.stringify({uniqueNewTerms:1, newTerms:['ISIC'], candidateRegistration:{mode:'DRY_RUN',insertedCandidates:0}}));
  if (includeReport) archive.file('reports/glossary/unknown-terms.csv', report);
  archive.file('diagram/mermaid/tables/M.conceptual-erd.mmd', 'erDiagram\n    A ||--o{ B : contains\n');
  archive.file('diagram/graphviz/tables/M.er.dot', tooLargeDiagram ? 'x'.repeat(MAX_DIAGRAM_BYTES+1) : 'digraph { A -> B }');
  archive.file('other/unknown.dot', 'digraph { BAD -> BAD }');
  return new Blob([await archive.generateAsync({type:'uint8array'})]);
}
test('only supported Mermaid / Graphviz paths appear, without any external fetch', async () => {
  const oldFetch = globalThis.fetch;
  globalThis.fetch = () => { throw new Error('Any network call is a bug'); };
  try {
    const inspected = await inspectGenerationZip(await fixture());
    assert.equal(inspected.diagrams.length, 2);
    assert.equal(inspected.diagrams.find(x=>x.engine==='graphviz').filename, 'M.er.dot');
    assert.equal(inspected.diagrams.find(x=>x.engine==='mermaid').filename, 'M.conceptual-erd.mmd');
    assert.match(await readDiagramSource(inspected, inspected.diagrams[0]), /digraph|erDiagram/);
    await assert.rejects(readDiagramSource(inspected, {path:'other/unknown.dot',engine:'graphviz',filename:'bad.dot'}), /معتبر/);
  } finally { globalThis.fetch = oldFetch; }
});
test('explicit Glossary handoff preserves exact CSV bytes for existing REST import', async () => {
  const inspected = await inspectGenerationZip(await fixture());
  const file = await reviewCsvFromZip(inspected);
  assert.equal(file.name, 'unknown-terms.csv');
  assert.equal(file.size, new TextEncoder().encode(report).byteLength);
  assert.equal(await file.text(), report);
  const handoff = await prepareReviewHandoff(inspected, 'ISIC');
  assert.equal(handoff.term, 'ISIC');
  assert.equal(handoff.newTermCount,1);
  assert.equal(handoff.sourceName, 'M.xml');
  assert.equal(await handoff.report.text(), report);
});
test('missing Glossary CSV still permits read-only queue lookup', async () => {
  const inspected = await inspectGenerationZip(await fixture({includeReport:false}));
  const handoff = await prepareReviewHandoff(inspected, 'ISIC');
  assert.equal(handoff.report, null);
  assert.match(handoff.note, /unknown-terms\.csv/);
});
test('oversized diagram cannot be decompressed for rendering', async () => {
  const inspected = await inspectGenerationZip(await fixture({tooLargeDiagram:true}));
  const graph = inspected.diagrams.find(x=>x.engine==='graphviz');
  await assert.rejects(readDiagramSource(inspected, graph), /حجم/);
  const original = await extractDiagramDownload(inspected, graph);
  assert.equal(original.size, MAX_DIAGRAM_BYTES+1);
});
