import {test} from 'node:test';
import assert from 'node:assert/strict';
import {connectionApi} from '../src/connectionApi.ts';
import {getActiveMetadataConnection, setActiveMetadataConnection} from '../src/transport.ts';
import {coreApi} from '../src/coreApi.ts';

const mocked = data => new Response(JSON.stringify(data), {status:200,
  headers:{'content-type':'application/json'}});

test('seven DBMS defaults require no connections admin key or secret header', async t => {
  const original = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url, options) => {calls.push({url,options});return mocked([
    {platform:'oracle',jdbcUrl:'jdbc:oracle:thin:@//server:1521/DB',username:'dbreader',passwordConfigured:true}
  ]);};
  t.after(() => {globalThis.fetch = original;});
  const entries = await connectionApi.defaults();
  assert.equal(entries.length, 1);
  assert.equal(calls[0].url,'/api/v1/connections');
  assert.equal(calls[0].options.method,'GET');
  assert.equal(calls[0].options.headers.has('X-SchemaForge-Connections-Admin-Key'),false);
});

test('test and activation do not expose JDBC credentials in URLs or selection token', async t => {
  const original = globalThis.fetch;
  const calls = [];
  const selected = {token:'random-session-token', platform:'oracle',verifiedSchema:'BANK',expiresAt:'2026-10-24T09:00:00Z'};
  globalThis.fetch = async (url, options) => {
    calls.push({url,options});
    return mocked(url.endsWith('/activate') ? selected : {success:true,code:'METADATA_READY'});
  };
  t.after(() => {globalThis.fetch = original;});
  const input = {platform:'oracle',jdbcUrl:'jdbc:oracle:thin:@//test:1521/APP',username:'reader',
    password:'secret-only-for-post',useConfiguredPassword:false,schema:'BANK'};
  const probe = await connectionApi.test(input);
  const selectedResult = await connectionApi.activate(input);
  assert.equal(probe.code,'METADATA_READY');
  assert.equal(selectedResult.token,selected.token);
  for (const call of calls) {
    assert.equal(call.options.method,'POST');
    assert.equal(call.options.headers.has('X-SchemaForge-Connections-Admin-Key'),false);
    assert.equal(call.options.headers.has('X-SchemaForge-Metadata-Connection'),false);
    assert.equal(JSON.parse(call.options.body).schema,'BANK');
    assert.equal(JSON.parse(call.options.body).password,'secret-only-for-post');
    assert.ok(!call.url.includes('secret-only-for-post'));
  }
});

test('only same-browser selected token is carried on metadata operations, never Glossary', async t => {
  const original = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url, options) => {
    calls.push({url,options});
    return url.includes('/crud') ? new Response('SELECT 1', {status:200}) : mocked({});
  };
  t.after(() => {globalThis.fetch=original;setActiveMetadataConnection(null);});
  setActiveMetadataConnection({token:'opaque-metadata-token',platform:'oracle',verifiedSchema:'APP',expiresAt:'2030-01-01T00:00:00Z'});
  await coreApi.conformance({scope:'table',schema:'APP',table:'T1',platforms:['oracle']});
  await coreApi.crud('oracle','APP','T1');
  assert.equal(calls[0].options.headers.get('X-SchemaForge-Metadata-Connection'),'opaque-metadata-token');
  assert.equal(calls[1].options.headers.get('X-SchemaForge-Metadata-Connection'),'opaque-metadata-token');
  assert.throws(() => coreApi.crud('sqlserver','APP','T1'),/active metadata connection/);
  assert.throws(() => coreApi.conformance({scope:'schema',schema:'APP',platforms:['mysql']}),/active metadata connection/);
  const before = calls.length;
  await connectionApi.defaults();
  assert.equal(calls.length,before+1);
  assert.equal(calls.at(-1).options.headers.has('X-SchemaForge-Metadata-Connection'),false);
  assert.equal(getActiveMetadataConnection()?.platform,'oracle');
});

test('blank schema is permitted for activation, and discovered candidates remain nonsecret', async t => {
  const original = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url, options) => {
    calls.push({url,options});
    return mocked(url.endsWith('/activate')
      ? {token:'opaque-token',platform:'postgresql',verifiedSchema:'',expiresAt:'2030-01-01T00:00:00Z',
          defaultSchema:'public',availableSchemas:['public','bank'],schemaListTruncated:false}
      : {success:true,code:'METADATA_DISCOVERED',product:'PostgreSQL',version:'17',elapsedMillis:20,
          defaultSchema:'public',availableSchemas:['public','bank'],schemaListTruncated:false});
  };
  t.after(() => {globalThis.fetch=original;});
  const input = {platform:'postgresql',jdbcUrl:'jdbc:postgresql://localhost:5432/db',
    username:'reader',password:'demo-password',useConfiguredPassword:false,schema:''};
  const discovered = await connectionApi.test(input);
  const activated = await connectionApi.activate(input);
  assert.equal(discovered.defaultSchema,'public');
  assert.deepEqual(discovered.availableSchemas,['public','bank']);
  assert.equal(activated.verifiedSchema,'');
  assert.equal(activated.defaultSchema,'public');
  assert.deepEqual(activated.availableSchemas,['public','bank']);
  assert.equal(calls.length,2);
  for (const {url,options} of calls) {
    assert.ok(url.startsWith('/api/v1/connections/'));
    assert.equal(JSON.parse(options.body).schema,'');
    assert.equal(options.method,'POST');
    assert.ok(!url.includes('demo-password'));
    assert.equal(options.headers.has('X-SchemaForge-Connections-Admin-Key'),false);
  }
});

test('connection form no longer requires a schema and provides JDBC-discovered datalist', async () => {
  const fs = await import('node:fs/promises');
  const source = await fs.readFile(new URL('../src/views/ConnectionsView.tsx', import.meta.url),'utf8');
  assert.match(source,/Schema for metadata \(optional\)/);
  assert.match(source,/list="schemaforge-visible-schemas"/);
  assert.match(source,/suggestedSchemas\.map/);
  assert.ok(!source.includes('|| !schema.trim()'));
  assert.match(source,/Schema selection deferred/);
});
