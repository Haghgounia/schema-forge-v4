import assert from 'node:assert/strict';
import { test } from 'node:test';
import { glossaryApi, ApiError, API_ROOT } from '../src/api.ts';

function mockJson(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status, headers: { 'Content-Type': 'application/json' },
  });
}

test('dictionary search stays on same-origin API and encodes terms', async (t) => {
  const previous = globalThis.fetch;
  let observed;
  globalThis.fetch = async (url, init) => {
    observed = { url, init };
    return mockJson({ items: [], page: 0, size: 25, total: 0 });
  };
  t.after(() => { globalThis.fetch = previous; });
  const value = await glossaryApi.terms({ q: 'BANK & CASH', status: 'CANDIDATE', page: 0, size: 25 });
  assert.equal(value.total, 0);
  assert.ok(observed.url.startsWith(`${API_ROOT}/terms?`));
  const url = new URL(observed.url, 'http://example.test');
  assert.equal(url.searchParams.get('q'), 'BANK & CASH');
  assert.equal(url.searchParams.get('status'), 'CANDIDATE');
  assert.equal(observed.init.credentials, 'same-origin');
  assert.equal(observed.init.cache, 'no-store');
  assert.equal(observed.init.headers.has('X-Glossary-Admin-Key'), false);
});

test('review queue sends admin header, never URL parameter', async (t) => {
  const previous = globalThis.fetch;
  let observed;
  globalThis.fetch = async (url, init) => {
    observed = { url, init };
    return mockJson({items: [], page: 0, size: 25, total: 0});
  };
  t.after(() => { globalThis.fetch = previous; });
  await glossaryApi.queue('NOT_A_REAL_KEY', 0, 25, false);
  assert.match(observed.url, /\/review\/queue\?/);
  assert.equal(observed.url.includes('NOT_A_REAL_KEY'), false);
  assert.equal(observed.init.headers.get('X-Glossary-Admin-Key'), 'NOT_A_REAL_KEY');
});

test('decision POST uses existing server payload and explicit status', async (t) => {
  const previous = globalThis.fetch;
  let observed;
  globalThis.fetch = async (url, init) => {
    observed = { url, init };
    return mockJson({ id: 1, kind: 'ATOMIC', key: 'DEALER', status: 'APPROVED', reason: 'checked' });
  };
  t.after(() => { globalThis.fetch = previous; });
  const decision = {
    term: 'DEALER', kind: 'ATOMIC', to: 'APPROVED', persian: '', module: '', reason: 'checked',
  };
  const result = await glossaryApi.decision('NOT_A_REAL_KEY', decision);
  assert.equal(result.status, 'APPROVED');
  assert.equal(observed.url, `${API_ROOT}/review/decision`);
  assert.equal(observed.init.method, 'POST');
  assert.equal(observed.init.headers.get('Content-Type'), 'application/json');
  assert.deepEqual(JSON.parse(observed.init.body), decision);
});

test('report upload is multipart, not a raw filename or forced content type', async (t) => {
  const previous = globalThis.fetch;
  let observed;
  globalThis.fetch = async (url, init) => {
    observed = { url, init };
    return mockJson({ createdCandidates: 2, recordedOccurrences: 2 });
  };
  t.after(() => { globalThis.fetch = previous; });
  const file = new File(['header,row\n'], 'unknown-terms.csv', {type: 'text/csv'});
  const result = await glossaryApi.importReport('NOT_A_REAL_KEY', file);
  assert.equal(result.createdCandidates, 2);
  assert.equal(observed.url, `${API_ROOT}/review/import-report`);
  assert.ok(observed.init.body instanceof FormData);
  assert.equal(observed.init.headers.has('Content-Type'), false);
  assert.equal(observed.init.body.get('file')?.name, 'unknown-terms.csv');
});

test('HTTP 401/409 are explicit; network errors do not leak raw internals', async (t) => {
  const previous = globalThis.fetch;
  t.after(() => { globalThis.fetch = previous; });
  globalThis.fetch = async () => mockJson({detail: 'secret that should not be shown'}, 401);
  await assert.rejects(glossaryApi.queue('bad', 0, 25, false),
    (error) => error instanceof ApiError && error.status === 401 && !error.message.includes('secret'));
  globalThis.fetch = async () => mockJson({}, 409);
  await assert.rejects(glossaryApi.decision('bad', {
    term: 'TEST', kind: 'ATOMIC', to: 'REJECTED', persian: '', module: '', reason: 'checked',
  }), (error) => error instanceof ApiError && error.status === 409);
  globalThis.fetch = async () => { throw new TypeError('Internal network detail'); };
  await assert.rejects(glossaryApi.term(1),
    (error) => error instanceof ApiError && error.status === 0 && !error.message.includes('Internal'));
});

test('spelling and history use GET and encode filter values', async (t) => {
  const previous = globalThis.fetch;
  const observed = [];
  globalThis.fetch = async (url, init) => {
    observed.push({url, init});
    return mockJson({ items: [], total: 0, size: 25, page: 0 });
  };
  t.after(() => { globalThis.fetch = previous; });
  await glossaryApi.history('key', 1, 25, 'SOME TERM');
  await glossaryApi.spelling('CUTOMER', 'COLUMN');
  assert.equal(new URL(observed[0].url, 'http://example.test').searchParams.get('term'), 'SOME TERM');
  assert.equal(observed[0].init.method, 'GET');
  assert.equal(new URL(observed[1].url, 'http://example.test').searchParams.get('term'), 'CUTOMER');
});
