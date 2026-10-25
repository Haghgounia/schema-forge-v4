import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {coreApi, parseSelectedTableNames} from '../src/coreApi.ts';
import {setActiveMetadataConnection} from '../src/transport.ts';

const connection={token:'selected-only-token',platform:'oracle',verifiedSchema:'FEE3'};

test('paste parser accepts FCE lists and never silently discards case-distinct names',()=>{
  assert.deepEqual(parseSelectedTableNames(' FCE_CALC_ITEM\nFCE_CHANGE_REQUEST, FCE_CALC_ITEM;FCE_REF_CODE '),
    ['FCE_CALC_ITEM','FCE_CHANGE_REQUEST','FCE_REF_CODE']);
  assert.deepEqual(parseSelectedTableNames('Foo,foo'),['Foo','foo']);
});

test('cohort request uses POST, same-origin selected-session token, no platform override',async t=>{
  const old=globalThis.fetch, calls=[];
  setActiveMetadataConnection(connection);
  globalThis.fetch=async(path,options)=>{
    calls.push({path,options});
    return new Response(JSON.stringify({report:{scope:'TABLES',summary:{tablesScanned:2}},
      requestedTables:['FCE_CALC_ITEM','FCE_CHANGE_REQUEST']}),{status:200});
  };
  t.after(()=>{globalThis.fetch=old;setActiveMetadataConnection(null);});
  const result=await coreApi.selectedConformance({scope:'tables',schema:'FEE3',
    tables:['FCE_CALC_ITEM','FCE_CHANGE_REQUEST']});
  assert.equal(result.report.scope,'TABLES');
  assert.equal(calls.length,1);
  assert.equal(calls[0].path,'/api/v1/conformance/selected/tables');
  assert.equal(calls[0].options.method,'POST');
  assert.equal(calls[0].options.headers.get('X-SchemaForge-Metadata-Connection'),connection.token);
  assert.equal(calls[0].options.headers.has('X-Glossary-Admin-Key'),false);
  assert.deepEqual(JSON.parse(calls[0].options.body),{schema:'FEE3',tables:['FCE_CALC_ITEM','FCE_CHANGE_REQUEST']});
});

test('rejects unauthenticated or empty/oversized cohorts before sending any request',()=>{
  setActiveMetadataConnection(null);
  assert.throws(()=>coreApi.selectedConformance({scope:'tables',schema:'FEE3',tables:['FCE_A']}),/اتصال/);
  setActiveMetadataConnection(connection);
  try {
    assert.throws(()=>coreApi.selectedConformance({scope:'tables',schema:'FEE3',tables:[]}),/Table/);
    assert.throws(()=>coreApi.selectedConformance({scope:'tables',schema:'FEE3',tables:Array(101).fill('FCE_A')}),/Table/);
  }finally{setActiveMetadataConnection(null);}
});

test('UI exposes explicit scope for selected cohort and fail-closed complete count check',()=>{
  const view=readFileSync(new URL('../src/views/ConformanceView.tsx',import.meta.url),'utf8');
  assert.match(view,/<option value="tables">/);
  assert.match(view,/parseSelectedTableNames/);
  assert.match(view,/received\.report\.summary\.tablesScanned !== requestedTables\.length/);
  assert.match(view,/received\.requestedTables\.length !== requestedTables\.length/);
});

test('missing or inaccessible table names are shown without any database write',async t=>{
  const old=globalThis.fetch;setActiveMetadataConnection(connection);
  globalThis.fetch=async()=>new Response(JSON.stringify({contract:'schemaforge-rest-error/v1',
    code:'INVALID_REQUEST', message:'Requested live table(s) missing or inaccessible in FEE3: FCE_MISSING'}),{status:400});
  t.after(()=>{globalThis.fetch=old;setActiveMetadataConnection(null);});
  await assert.rejects(coreApi.selectedConformance({scope:'tables',schema:'FEE3',tables:['FCE_MISSING']}),
    /FCE_MISSING/);
});
