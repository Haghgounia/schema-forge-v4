import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {coreApi} from '../src/coreApi.ts';
import {setActiveMetadataConnection} from '../src/transport.ts';

const sampleTarget = {
  platform:'postgresql', jdbcTarget:'jdbc:postgresql://example.invalid:5432/bank',
  databaseName:'bank', username:'reader', databaseProduct:'PostgreSQL', databaseVersion:'17',
  verifiedSchema:'public', defaultSchema:'public', expiresAt:'2030-01-01T00:00:00Z',
};
const connection = {token:'opaque-ephemeral-token', platform:'postgresql',
  verifiedSchema:'public', expiresAt:'2030-01-01T00:00:00Z'};
const json = body => new Response(JSON.stringify(body), {status:200,headers:{'content-type':'application/json'}});

test('selected database auditing cannot call REST without a verified explicit connection token', () => {
  setActiveMetadataConnection(null);
  assert.throws(()=>coreApi.selectedAuditPlan(), /اتصال|JDBC/);
  assert.throws(()=>coreApi.selectedConformance({scope:'schema',schema:'public'}), /اتصال/);
});

test('plan and table/schema auditing use only selected session header with no hidden DBMS fallback', async t => {
  const old=globalThis.fetch; const calls=[];
  setActiveMetadataConnection(connection);
  globalThis.fetch=async(path,options)=>{
    calls.push({path,options});
    return path.includes('selected/plan')
      ? json({target:sampleTarget, readOnly:true, ruleFamilies:Array.from({length:8}, (_,i)=>({id:'RULE'+i}))})
      : json({target:sampleTarget, auditedAt:'2030-01-01T00:00:00Z', report:{summary:{tablesScanned:1}}});
  };
  t.after(()=>{globalThis.fetch=old;setActiveMetadataConnection(null);});
  const plan=await coreApi.selectedAuditPlan();
  const table=await coreApi.selectedConformance({scope:'table',schema:'public',table:'accounts'});
  const schema=await coreApi.selectedConformance({scope:'schema',schema:'public'});
  assert.equal(plan.target.databaseName,'bank');
  assert.equal(plan.ruleFamilies.length,8);
  assert.equal(table.target.platform,'postgresql');
  assert.equal(schema.report.summary.tablesScanned,1);
  assert.deepEqual(calls.map(x=>new URL(x.path,'http://local').pathname), [
    '/api/v1/conformance/selected/plan',
    '/api/v1/conformance/selected/table',
    '/api/v1/conformance/selected/schema',
  ]);
  for(const {path,options} of calls){
    const url=new URL(path,'http://local');
    assert.equal(url.searchParams.has('platform'),false);
    assert.equal(options.headers.get('X-SchemaForge-Metadata-Connection'),connection.token);
    assert.equal(options.method,'GET');
    assert.equal(options.headers.has('X-Glossary-Admin-Key'),false);
  }
  assert.equal(new URL(calls[1].path,'http://local').searchParams.get('table'),'accounts');
  assert.equal(new URL(calls[2].path,'http://local').searchParams.has('table'),false);
  assert.equal(new URL(calls[2].path,'http://local').searchParams.get('schema'),'public');
});

test('selected audit input rejects missing schema/table before any metadata request', async t => {
  const old=globalThis.fetch; const calls=[];
  globalThis.fetch=async(...args)=>{calls.push(args);return json({});};
  setActiveMetadataConnection(connection);
  t.after(()=>{globalThis.fetch=old;setActiveMetadataConnection(null);});
  assert.throws(()=>coreApi.selectedConformance({scope:'schema',schema:' '}), /Schema/);
  assert.throws(()=>coreApi.selectedConformance({scope:'table',schema:'public',table:' '}), /Table/);
  assert.equal(calls.length,0);
});

test('audit UI shows safe target, actual eight families, human confirmation and evidence-proven result',()=>{
  const code=readFileSync(new URL('../src/views/ConformanceView.tsx',import.meta.url),'utf8');
  assert.match(code,/coreApi\.selectedAuditPlan\(\)/);
  assert.match(code,/coreApi\.selectedConformance\(/);
  assert.match(code,/!activeConnection\?\.token/);
  assert.match(code,/!acknowledged/);
  assert.match(code,/RULE_LABELS/);
  assert.match(code,/databaseName/);
  assert.match(code,/jdbcTarget/);
  assert.match(code,/username/);
  assert.match(code,/ruleFamilySummaries/);
  assert.match(code,/downloadJson/);
  assert.doesNotMatch(code,/<Platforms/);
  assert.doesNotMatch(code,/\['oracle'\]/);
});
