import {test} from 'node:test';
import assert from 'node:assert/strict';
import {connectionApi, metadataYaml} from '../src/connectionApi.ts';

function mocked(body) {
  return new Response(JSON.stringify(body), {status: 200, headers: {'content-type':'application/json'}});
}

test('connection defaults are requested only with a distinct administrator header', async t => {
  const original = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url, options) => {
    calls.push({url,options});
    return mocked([{platform:'oracle',jdbcUrl:'jdbc:oracle:thin:@//example:1521/X',username:'dbreader',
      passwordConfigured:true,enabled:true,driverAvailable:true}]);
  };
  t.after(() => {globalThis.fetch = original;});
  const entries = await connectionApi.defaults('connection-key');
  assert.equal(entries.length, 1);
  assert.equal(calls[0].url, '/api/v1/connections');
  assert.equal(calls[0].options.method, 'GET');
  assert.equal(calls[0].options.headers.get('X-SchemaForge-Connections-Admin-Key'), 'connection-key');
  assert.equal(calls[0].options.headers.has('X-Glossary-Admin-Key'), false);
  assert.ok(!calls[0].url.includes('connection-key'));
});

test('probe sends credentials in same-origin POST body and never sends GET query or application logs', async t => {
  const original = globalThis.fetch;
  let captured;
  globalThis.fetch = async (url, options) => {
    captured = {url,options};return mocked({success:true,code:'CONNECTED',product:'Oracle',version:'21',elapsedMillis:120});
  };
  t.after(() => {globalThis.fetch = original;});
  const request = {platform:'oracle',jdbcUrl:'jdbc:oracle:thin:@//dbhost:1521/APP',username:'local',
    password:'temporary-secret',useConfiguredPassword:false};
  const result = await connectionApi.test('connection-key', request);
  assert.equal(result.success, true);
  assert.equal(captured.url, '/api/v1/connections/test');
  assert.equal(captured.options.method, 'POST');
  assert.equal(captured.options.headers.get('X-SchemaForge-Connections-Admin-Key'), 'connection-key');
  assert.equal(captured.options.headers.get('content-type'), 'application/json');
  assert.deepEqual(JSON.parse(captured.options.body), request);
  assert.ok(!captured.url.includes('temporary-secret'));
});

test('YAML export contains nonsecret settings and a credential environment hint only', () => {
  const yaml = metadataYaml('db2zos','jdbc:db2://host:50000/DEV','technical',true);
  assert.match(yaml, /SCHEMAFORGE_METADATA_DB2ZOS_PASSWORD/);
  assert.match(yaml, /jdbc:db2:\/\/host:50000\/DEV/);
  assert.match(yaml, /enabled: true/);
  assert.ok(!yaml.includes('password:'));
  assert.throws(() => metadataYaml('h2','jdbc:h2:mem:x','sa',true));
  assert.throws(() => metadataYaml('oracle','abc\npassword: leaked','user',false));
  assert.throws(() => metadataYaml('sqlserver','jdbc:sqlserver://host;password=hidden','sa',true));
});
