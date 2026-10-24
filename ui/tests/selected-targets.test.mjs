import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {connectionApi} from '../src/connectionApi.ts';
import {coreApi} from '../src/coreApi.ts';
import {setActiveMetadataConnection} from '../src/transport.ts';

const json = obj => new Response(JSON.stringify(obj), {status:200,
  headers:{'content-type':'application/json'}});

test('server configured DBMS activation never sends browser credentials', async t => {
  const original=globalThis.fetch;const calls=[];
  globalThis.fetch=async (url,opts)=>{calls.push({url,opts});return json({token:'opaque',platform:'oracle'});};
  t.after(()=>globalThis.fetch=original);
  const chosen=await connectionApi.activateConfigured('oracle');
  assert.equal(chosen.platform,'oracle');
  assert.equal(calls.length,1);
  assert.equal(calls[0].url,'/api/v1/connections/configured/activate');
  assert.equal(calls[0].opts.method,'POST');
  assert.deepEqual(JSON.parse(calls[0].opts.body),{platform:'oracle'});
  assert.equal(calls[0].opts.headers.has('X-SchemaForge-Metadata-Connection'),false);
});

test('CRUD refuses implicit configured fallback, binds selected token and DBMS', async t => {
  const original=globalThis.fetch;const calls=[];
  globalThis.fetch=async (url,opts)=>{calls.push({url,opts});return new Response('SELECT 1', {status:200});};
  t.after(()=>{globalThis.fetch=original;setActiveMetadataConnection(null);});
  setActiveMetadataConnection(null);
  assert.throws(()=>coreApi.crud('oracle','APP','T1'),/اتصال/);
  setActiveMetadataConnection({token:'only-selected',platform:'oracle',expiresAt:'2030-01-01T00:00:00Z'});
  await coreApi.crud('oracle','APP','T1');
  assert.equal(calls[0].url,'/api/v1/generate/oracle/crud/selected');
  assert.equal(calls[0].opts.headers.get('X-SchemaForge-Metadata-Connection'),'only-selected');
  assert.throws(()=>coreApi.crud('sqlserver','APP','T1'),/active metadata connection/);
  assert.equal(calls.length,1);
});

test('live diagram reads selected metadata; offline diagram endpoint remains distinct', async t => {
  const original=globalThis.fetch;const calls=[];
  globalThis.fetch=async(url,opts)=>{calls.push({url,opts});return new Response('erDiagram', {status:200});};
  t.after(()=>{globalThis.fetch=original;setActiveMetadataConnection(null);});
  setActiveMetadataConnection(null);
  assert.throws(()=>coreApi.liveDiagram({schema:'BANK',type:'er',includeColumns:true,includeDataTypes:true,
    includePrimaryKeys:true,includeForeignKeys:true}),/اتصال/);
  setActiveMetadataConnection({token:'live-only',platform:'postgresql',expiresAt:'2030-01-01T00:00:00Z'});
  await coreApi.liveDiagram({schema:'public',table:'accounts',type:'er',includeColumns:true,
    includeDataTypes:true,includePrimaryKeys:true,includeForeignKeys:true});
  assert.equal(new URL(calls[0].url,'http://local').pathname,'/api/v1/diagram/mermaid/selected');
  assert.equal(new URL(calls[0].url,'http://local').searchParams.get('schema'),'public');
  assert.equal(calls[0].opts.headers.get('X-SchemaForge-Metadata-Connection'),'live-only');
  assert.equal(calls[0].opts.method,'GET');
});

test('three UI forms share profile selection and never ask for duplicate passwords',()=>{
  const picker=readFileSync(new URL('../src/components/DatabaseTargetPicker.tsx',import.meta.url),'utf8');
  const app=readFileSync(new URL('../src/App.tsx',import.meta.url),'utf8');
  for(const view of ['ConformanceView','CrudView','DiagramView']) {
    const source=readFileSync(new URL(`../src/views/${view}.tsx`,import.meta.url),'utf8');
    assert.match(source,/DatabaseTargetPicker/);
  }
  assert.match(app,/تنظیمات اتصال به دیتابیس/);
  assert.match(picker,/activateConfigured/);
  assert.match(picker,/sessions.filter/);
  assert.doesNotMatch(picker,/<input[^>]*type="password"/);
});
