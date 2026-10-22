import assert from 'node:assert/strict';
import {test} from 'node:test';
import {coreApi} from '../src/coreApi.ts';
import {glossaryApi} from '../src/api.ts';
import {ApiError} from '../src/transport.ts';
const response = (body = {}, status = 200) => status === 204
  ? new Response(null, {status})
  : new Response(JSON.stringify(body), {status, headers: {'content-type': 'application/json'}});
const downloadable = (content = 'sample') => new Response(content, {
  status: 200, headers: {'content-type': 'application/octet-stream',
    'content-disposition': 'attachment; filename="server-output.sql"'},
});

// Node's test runner uses sequential mocks; handlers are restored after each test.
test('all five generation actions use the corresponding existing multipart REST paths', async t => {
  const old = globalThis.fetch;
  const captures = [];
  globalThis.fetch = async (path, options) => {captures.push({path, options}); return downloadable();};
  t.after(() => {globalThis.fetch = old;});
  for (const source of ['word', 'legacy-word', 'legacy-word/zip', 'zip', 'ea-xml']) {
    const file = new File(['x'], source + '.zip');
    await coreApi.generate({source, file, platforms: ['oracle', 'postgresql'], auditFields: 'source',
      timestampSemantics: 'source', schema: 'TST', primaryKeyAsIdentity: false});
  }
  assert.equal(captures.length, 5);
  captures.forEach(({path, options}, i) => {
    const names = ['word', 'legacy-word', 'legacy-word/zip', 'zip', 'ea-xml'];
    const parsed = new URL(path, 'http://local.test');
    assert.equal(parsed.pathname, '/api/v1/generate/' + names[i]);
    assert.deepEqual(parsed.searchParams.getAll('platform'), ['oracle', 'postgresql']);
    assert.equal(parsed.searchParams.get('auditFields'), 'source');
    assert.equal(options.method, 'POST');
    assert.equal(options.body.get('file').name, names[i] + '.zip');
    assert.equal(options.headers.has('Content-Type'), false);
  });
});

test('conformance table/schema, CRUD Oracle/SQLServer and mermaid map to Java controllers', async t => {
  const old = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (path, options) => {
    calls.push({path, options});
    return /conformance/.test(path) ? response({summary: {errorCount: 0}}) : downloadable();
  };
  t.after(() => {globalThis.fetch = old;});
  await coreApi.conformance({scope: 'table', schema: 'X', table: 'T', platforms: ['mysql', 'oracle']});
  await coreApi.conformance({scope: 'schema', schema: 'X', platforms: ['all']});
  await coreApi.crud('oracle', 'X', 'T');
  await coreApi.crud('sqlserver', 'X', 'T');
  await coreApi.mermaid({file: new File(['{}'], 'a.json'), type:'conceptual-erd',
    scope:'table-with-dependencies', root:'X.T', depth:2,
    includeColumns:true, includeDataTypes:true, includePrimaryKeys:true,
    includeForeignKeys:true, includeLogicalForeignKeys:false});
  assert.equal(new URL(calls[0].path, 'http://local').pathname, '/api/v1/conformance/table');
  assert.deepEqual(new URL(calls[0].path, 'http://local').searchParams.getAll('platform'), ['mysql','oracle']);
  assert.equal(new URL(calls[1].path, 'http://local').pathname, '/api/v1/conformance/schema');
  assert.equal(new URL(calls[2].path, 'http://local').pathname, '/api/v1/generate/oracle/crud');
  assert.deepEqual(JSON.parse(calls[2].options.body), {schema: 'X',table:'T'});
  assert.equal(new URL(calls[3].path, 'http://local').pathname, '/api/v1/generate/sqlserver/crud');
  const diagram = new URL(calls[4].path,'http://local');
  assert.equal(diagram.pathname, '/api/v1/diagram/mermaid/canonical-json');
  assert.equal(diagram.searchParams.get('type'), 'conceptual-erd');
  assert.equal(diagram.searchParams.get('root'), 'X.T');
  assert.equal(diagram.searchParams.get('depth'), '2');
  assert.ok(calls[4].options.body instanceof FormData);
});

test('all previously missing Glossary admin routes use server key header and suitable methods', async t => {
  const old = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (path, options) => {
    calls.push({path,options});
    if (options.method === 'DELETE') return response({}, 204);
    return response({id:17, updated:2, inserted:1});
  };
  t.after(() => {globalThis.fetch = old;});
  const value = {english:'TEST',persian:'',module:'',kind:'ATOMIC',status:'CANDIDATE'};
  await glossaryApi.create('dummy-key',value);
  await glossaryApi.update('dummy-key',17,value);
  await glossaryApi.remove('dummy-key',17);
  await glossaryApi.bulkStatus('dummy-key',{kind:'ATOMIC',from:'CANDIDATE',to:'APPROVED'});
  await glossaryApi.importWorkbook('dummy-key',new File(['book'],'words.xlsx'),'CANDIDATE');
  assert.deepEqual(calls.map(x=>new URL(x.path,'http://local').pathname), [
    '/api/v1/glossary/terms', '/api/v1/glossary/terms/17', '/api/v1/glossary/terms/17',
    '/api/v1/glossary/bulk-status', '/api/v1/glossary/import',
  ]);
  assert.deepEqual(calls.map(x=>x.options.method), ['POST','PUT','DELETE','POST','POST']);
  assert.ok(calls.every(x=>x.options.headers.get('X-Glossary-Admin-Key') === 'dummy-key'));
  assert.ok(calls.every(x=>!x.path.includes('dummy-key')));
  assert.deepEqual(JSON.parse(calls[1].options.body),value);
  assert.equal(new URL(calls[3].path,'http://local').searchParams.get('kind'),'ATOMIC');
  assert.equal(new URL(calls[4].path,'http://local').searchParams.get('status'),'CANDIDATE');
  assert.equal(calls[4].options.headers.has('Content-Type'),false);
});

test('glossary CSV/XLSX exports use GET; server filename is respected', async t => {
  const old = globalThis.fetch;
  const calls=[];
  globalThis.fetch=async (url, options)=>{calls.push({url,options});return downloadable();};
  t.after(()=>{globalThis.fetch=old;});
  const csv=await glossaryApi.exportCsv();
  const xlsx=await glossaryApi.exportXlsx();
  assert.equal(csv.filename, 'server-output.sql');
  assert.equal(xlsx.blob.size, 6);
  assert.deepEqual(calls.map(x=>new URL(x.url,'http://local').pathname),
    ['/api/v1/glossary/export.csv', '/api/v1/glossary/export.xlsx']);
  assert.ok(calls.every(x=>x.options.method==='GET'));
});

test('HTTP 502 proxy failure explains backend connectivity without echoing server secrets', async t => {
  const old=globalThis.fetch;
  globalThis.fetch=async()=>response({detail:'DO_NOT_LEAK'},502);
  t.after(()=>{globalThis.fetch=old;});
  await assert.rejects(glossaryApi.term(1), error=>error instanceof ApiError && error.status===502
    && error.message.includes('9090') && !error.message.includes('DO_NOT_LEAK'));
});
