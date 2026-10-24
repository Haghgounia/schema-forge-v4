import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFileSync } from 'node:fs';
import { coreApi } from '../src/coreApi.ts';
import { MAX_GENERATION_UPLOAD_BYTES, generationUploadError } from '../src/uploadLimits.ts';

const MIB = 1024 * 1024;

test('500 MiB files are allowed, 500 MiB + one byte are rejected', () => {
  assert.equal(MAX_GENERATION_UPLOAD_BYTES, 500 * MIB);
  assert.equal(generationUploadError(0), null);
  assert.equal(generationUploadError(500 * MIB), null);
  assert.match(generationUploadError(500 * MIB + 1), /۵۰۰/);
  assert.match(generationUploadError(-1), /معتبر نیست/);
});

test('oversized generation file is stopped before network request, without allocating 500 MiB', async t => {
  const oldFetch = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () => { calls++; throw new Error('must not fetch'); };
  t.after(() => { globalThis.fetch = oldFetch; });
  const file = new File(['not actually huge'], 'input.zip');
  Object.defineProperty(file, 'size', { value: MAX_GENERATION_UPLOAD_BYTES + 1 });
  assert.throws(
    () => coreApi.generate({file, source:'zip', platforms:['oracle'], auditFields:'source', timestampSemantics:'source'}),
    /۵۰۰/
  );
  assert.equal(calls, 0);
});

test('jar runtime and both distribution templates default to 500 MB file and 520 MB request', () => {
  for (const name of [
    '../../src/main/resources/application.yml',
    '../../distribution/config/application.yml',
    '../../distribution/config/application-example.yml',
  ]) {
    const text = readFileSync(new URL(name, import.meta.url), 'utf8');
    assert.match(text, /max-file-size:\s*\$\{SCHEMAFORGE_UPLOAD_MAX_FILE_SIZE:500MB\}/);
    assert.match(text, /max-request-size:\s*\$\{SCHEMAFORGE_UPLOAD_MAX_REQUEST_SIZE:520MB\}/);
    assert.doesNotMatch(text, /max-file-size:\s*100MB/);
  }
});
