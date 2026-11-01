import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, mkdir, writeFile, access, readFile, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createServer} from 'node:net';
import {clearViteCache, assertPortAvailable} from '../scripts/dev-recover.mjs';

async function sandbox(t) {
  const root = await mkdtemp(join(tmpdir(), 'schemaforge-vite-recover-'));
  t.after(() => rm(root, {recursive: true, force: true}));
  return root;
}

test('only .vite cache is deleted; packages and source remain untouched', async t => {
  const root = await sandbox(t);
  await mkdir(join(root,'node_modules','.vite','deps_temp_a'), {recursive: true});
  await mkdir(join(root,'node_modules','react'), {recursive: true});
  await mkdir(join(root,'src'), {recursive: true});
  await writeFile(join(root,'node_modules','.vite','deps_temp_a','metadata.json'), '{}');
  await writeFile(join(root,'node_modules','react','index.js'), 'react');
  await writeFile(join(root,'src','App.tsx'), 'preserve');
  const deleted = await clearViteCache(root);
  assert.equal(deleted, join(root,'node_modules','.vite'));
  await assert.rejects(access(deleted), /ENOENT/);
  assert.equal(await readFile(join(root,'node_modules','react','index.js'),'utf8'),'react');
  assert.equal(await readFile(join(root,'src','App.tsx'),'utf8'),'preserve');
});

test('cache clear is idempotent when .vite does not exist', async t => {
  const root = await sandbox(t);
  await mkdir(join(root,'node_modules'));
  await clearViteCache(root);
  await clearViteCache(root);
});

test('missing node_modules is rejected; no implicit reinstall', async t => {
  const root = await sandbox(t);
  await assert.rejects(clearViteCache(root), /Run npm ci/);
});

test('concurrent Vite port is detected before recovery', async () => {
  const server = createServer();
  await new Promise((resolve, reject) => {
    server.once('error',reject);
    server.listen(0,'127.0.0.1',resolve);
  });
  const {port} = server.address();
  try {
    await assert.rejects(assertPortAvailable(port), /already in use/);
  } finally {
    await new Promise(resolve => server.close(resolve));
  }
  await assertPortAvailable(port);
});
