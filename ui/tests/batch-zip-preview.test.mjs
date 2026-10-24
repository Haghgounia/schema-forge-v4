import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {test} from 'node:test';
import JSZip from 'jszip';
import {
  inspectGenerationZip, readReportPreview, extractReportDownload, MAX_MANIFEST_BYTES,
} from '../src/zipReports.ts';

const makeArchive = async (files) => {
  const zip = new JSZip();
  for (const [path, content] of Object.entries(files)) zip.file(path, content);
  return new Blob([await zip.generateAsync({type: 'uint8array', compression:'DEFLATE'})]);
};

test('large Batch manifest >2 MiB keeps validated status, outcome totals, and first 100 exceptions', async () => {
  const artifacts = Array.from({length: 23_000}, (_, i) => ({
    type:'DDL', platform:'ORACLE', logicalName:`S.TABLE_${i.toString().padStart(6,'0')}_${'ABC'.repeat(14)}`,
    path:`scripts/oracle/table_${i}.sql`, status: i % 71 === 0 ? 'BLOCKED' : 'GENERATED',
    outcomeReason: i % 71 === 0 ? 'VALIDATION_BLOCKED: CHECK expression failure' : null,
    provenance: {origin:'ZIP_BATCH', sourceName:`table${i}.docx`, producer:'generator'},
  }));
  const blocked = artifacts.filter(item => item.status === 'BLOCKED').length;
  const manifestText = JSON.stringify({manifestContract:'schemaforge-manifest/v1',
    source:{name:'documents.zip'}, requestStatus:'PARTIAL_SUCCESS',
    validation:{errorCount:1,warningCount:11},
    artifactOutcomes:{generated:artifacts.length - blocked, blocked, skipped:3, failed:0},
    models:[], artifacts,
    extensions:{batchInput:{regularFileCount:1200,processableDocumentCount:1100,successCount:1000,failedCount:1,skippedCount:199}}});
  assert.ok(manifestText.length > 2*1024*1024);
  const archive = await makeArchive({'manifest.json':manifestText,
    'reports/batch-generation-summary.csv':'sequence,document,status,generated_files,error\n1,a.docx,SUCCESS,3,\n'});
  const info = await inspectGenerationZip(archive);
  assert.equal(info.manifest.requestStatus,'PARTIAL_SUCCESS');
  assert.equal(info.manifest.artifactOutcomes.generated,artifacts.length-blocked);
  assert.equal(info.manifest.exceptionalCount,blocked);
  assert.equal(info.manifest.artifacts.length,100);
  assert.equal(info.manifest.artifactDetailsTruncated,true);
  assert.equal(info.manifest.batchInput.successCount,1000);
  assert.equal(info.manifest.summarySource,'manifest');
  const report = info.reports.find(item=>item.path==='manifest.json');
  const preview = await readReportPreview(info,report);
  assert.equal(preview.kind,'text');
  assert.equal(preview.isJson,true);
  assert.equal(preview.truncated,true);
  assert.ok(preview.text.includes('PARTIAL_SUCCESS'));
  const downloaded = await extractReportDownload(info,report);
  assert.equal(downloaded.size, Buffer.byteLength(manifestText));
});

test('large Batch CSV shows bounded complete rows, not error or invented total', async () => {
  const csv = 'sequence,document,status,generated_files,error\n'
    + Array.from({length:19_000},(_,i)=>`${i+1},document_${i}_${'x'.repeat(120)}.docx,SKIPPED,0,duplicate`).join('\n')+'\n';
  assert.ok(csv.length > 2 *1024*1024);
  const info = await inspectGenerationZip(await makeArchive({
    'manifest.json': JSON.stringify({requestStatus:'SUCCESS',artifactOutcomes:{generated:1,blocked:0,skipped:0}}),
    'reports/batch-generation-summary.csv': csv,
  }));
  const report = info.reports.find(item => item.path==='reports/batch-generation-summary.csv');
  const preview = await readReportPreview(info,report);
  assert.equal(preview.kind,'csv');
  assert.equal(preview.truncated,true);
  assert.equal(preview.totalRows,null);
  assert.equal(preview.rows.length,75);
  assert.equal(preview.rows[0][0],'1');
  assert.equal((await extractReportDownload(info,report)).size,Buffer.byteLength(csv));
});

test('large >10 MiB individual report is downloadable without increasing preview memory cap', async () => {
  const text = 'row\n'.repeat(3_000_000);
  const info = await inspectGenerationZip(await makeArchive({
    'manifest.json': JSON.stringify({requestStatus:'SUCCESS'}),
    'reports/schemaforge-generation-summary.txt':text,
  }));
  const report = info.reports.find(item=>item.path==='reports/schemaforge-generation-summary.txt');
  const preview = await readReportPreview(info,report);
  assert.equal(preview.kind,'text');
  assert.equal(preview.truncated,true);
  assert.ok(preview.text.length <= 150_000);
  const downloaded = await extractReportDownload(info,report);
  assert.equal(downloaded.size,Buffer.byteLength(text));
});

test('manifest larger than safety cap falls back to Java summary, never inventing counters', async () => {
  const excessive = JSON.stringify({requestStatus:'PARTIAL_SUCCESS',
    models:[{junk:'B'.repeat(MAX_MANIFEST_BYTES + 32768)}],artifactOutcomes:{generated:12,blocked:2}});
  const info = await inspectGenerationZip(await makeArchive({
    'manifest.json': excessive,
    'reports/schemaforge-generation-summary.txt':
      'SchemaForge Generation Summary\n==================\n'
      +'Generation ID        : batch\n'
      +'Request Status       : PARTIAL_SUCCESS\n'
      +'Source File          : batch.zip\n'
      +'Input Documents      : 42\n'
      +'Successful Documents : 35\n'
      +'Failed Documents     : 2\n'
      +'Skipped Inputs       : 5\n',
  }));
  assert.equal(info.manifest.requestStatus,'PARTIAL_SUCCESS');
  assert.equal(info.manifest.summarySource,'generation-summary');
  assert.equal(info.manifest.artifactOutcomes,undefined);
  assert.equal(info.manifest.batchInput.successCount,35);
  assert.ok(info.warnings.some(item => item.includes('Manifest کامل')));
});

test('Generation form supports re-opening an existing ZIP locally, with no new REST generation', async () => {
  const source = readFileSync(new URL('../src/views/GenerationView.tsx', import.meta.url),'utf8');
  assert.match(source, /function openExistingZip\(/);
  assert.match(source, /onDownloadChange\(offline\)/);
  assert.match(source, /MAX_ARCHIVE_BYTES/);
  assert.match(source, /باز کردن ZIP خروجی قبلی/);
  const oldFetch = globalThis.fetch;
  globalThis.fetch = () => { throw new Error('Offline ZIP reading must not call REST'); };
  try {
    const zip = await makeArchive({'manifest.json':JSON.stringify({requestStatus:'SUCCESS',artifactOutcomes:{generated:5,blocked:0}})});
    const parsed = await inspectGenerationZip(zip);
    assert.equal(parsed.manifest.requestStatus,'SUCCESS');
  } finally { globalThis.fetch = oldFetch; }
});
