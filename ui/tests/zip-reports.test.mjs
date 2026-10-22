import assert from 'node:assert/strict';
import { test } from 'node:test';
import JSZip from 'jszip';
import {
  inspectGenerationZip, readReportPreview, readTermEvidence, extractReportDownload,
  parseCsv, MAX_ARCHIVE_BYTES,
} from '../src/zipReports.ts';

async function fixture({withGlossary = true, status = 'PARTIAL_SUCCESS'} = {}) {
  const zip = new JSZip();
  zip.file('manifest.json', JSON.stringify({requestStatus: status,
    source: {name:'example.xml'}, artifactOutcomes:{generated:2,blocked:1,skipped:0},
    validation:{errorCount:3,warningCount:1},
    artifacts:[{status:'BLOCKED',type:'DDL',logicalName:'T',outcomeReason:'CHECK_INVALID'}]}));
  if (withGlossary) {
    zip.file('reports/glossary/summary.json', JSON.stringify({uniqueNewTerms:2,newTerms:['ISIC','ACTOR'],
      candidateRegistration: {mode:'DRY_RUN',eligibleCandidates:1,insertedCandidates:0,
        eligibleTerms:['ACTOR'],suspectedTypos:['ISIC'],manualReviewTerms:[]}}));
    zip.file('reports/glossary/unknown-terms.csv',
      '"source_name","object_type","table_name","column_name","identifier","unknown_term","token_position","review_status"\r\n' +
      '"e.xml","COLUMN","T","ISIC_ID","ISIC_ID","ISIC","1","NEW"\r\n' +
      '"e.xml","TABLE","T","","ACTOR","ACTOR","1","NEW"\r\n');
  }
  zip.file('reports/generation-summary.txt', 'Generated: 2\nBlocked: 1\n');
  return new Blob([await zip.generateAsync({type:'uint8array',compression:'DEFLATE'})]);
}
test('generated ZIP is read locally: manifest, Glossary and evidence; no REST calls or mutation', async () => {
  const oldFetch = globalThis.fetch;
  globalThis.fetch = () => { throw new Error('No network is allowed'); };
  try {
    const info = await inspectGenerationZip(await fixture());
    assert.equal(info.manifest.requestStatus,'PARTIAL_SUCCESS');
    assert.equal(info.manifest.artifactOutcomes.blocked,1);
    assert.deepEqual(info.glossary.newTerms,['ISIC','ACTOR']);
    assert.equal(info.glossary.candidateRegistration.insertedCandidates,0);
    const evidence = await readTermEvidence(info, 'ISIC');
    assert.equal(evidence.total,1);
    assert.equal(evidence.rows[0].column,'ISIC_ID');
    const file = info.reports.find(x=>x.path==='reports/glossary/unknown-terms.csv');
    const preview = await readReportPreview(info,file);
    assert.equal(preview.kind,'csv');
    assert.equal(preview.totalRows,2);
    const bytes=await extractReportDownload(info,file);
    assert.ok((await bytes.text()).includes('ISIC_ID'));
  } finally { globalThis.fetch = oldFetch; }
});
test('ZIP without Glossary still displays manifest and other reports', async () => {
  const info = await inspectGenerationZip(await fixture({withGlossary:false,status:'SUCCESS'}));
  assert.equal(info.manifest.requestStatus,'SUCCESS');
  assert.equal(info.glossary,null);
  assert.ok(info.warnings.some(x=>x.includes('Glossary')));
  assert.ok(info.reports.some(x=>x.path==='reports/generation-summary.txt'));
});
test('CSV parsing preserves escaped commas, quotes and line breaks', () => {
  assert.deepEqual(parseCsv('"a,b","c""d","line1\nline2"\r\n"x","y","z"'),
    [['a,b','c"d','line1\nline2'],['x','y','z']]);
});
test('bad ZIP and oversized input reject preview but do not affect REST download', async () => {
  await assert.rejects(inspectGenerationZip(new Blob(['not a zip'])));
  const huge = {size: MAX_ARCHIVE_BYTES + 1};
  await assert.rejects(inspectGenerationZip(huge), /۱۰۰|100/);
});
