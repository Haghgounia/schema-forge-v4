/**
 * SchemaForge UI - explicit, safe recovery from Vite's Windows dependency-cache
 * rename EPERM. Not used on every normal dev startup.
 *
 * - Checks default loopback port before deleting cache (avoid racing a Vite server).
 * - Deletes ONLY ui/node_modules/.vite, never node_modules or project data.
 * - Restarts Vite with --force and the existing 127.0.0.1 host restriction.
 * - Does not kill unrelated Node processes or bypass operating-system security.
 */
import {createServer} from 'node:net';
import {spawn} from 'node:child_process';
import {lstat, access, rm} from 'node:fs/promises';
import {resolve, join} from 'node:path';
import {fileURLToPath} from 'node:url';

export const DEFAULT_HOST = '127.0.0.1';
export const DEFAULT_PORT = 5173;
export const UI_ROOT = fileURLToPath(new URL('../', import.meta.url));

export async function assertPortAvailable(port = DEFAULT_PORT, host = DEFAULT_HOST) {
  return new Promise((ok, fail) => {
    const server = createServer();
    server.once('error', err => {
      if (err.code === 'EADDRINUSE') {
        fail(new Error(`Port ${host}:${port} is already in use. Stop the existing Vite process with Ctrl+C before cache recovery.`));
      } else {
        fail(err);
      }
    });
    server.listen(port, host, () => server.close(err => err ? fail(err) : ok()));
  });
}

export async function clearViteCache(root = UI_ROOT) {
  const modulesDir = join(resolve(root), 'node_modules');
  const moduleInfo = await lstat(modulesDir).catch(err => {
    if (err.code === 'ENOENT') {
      throw new Error('node_modules does not exist. Run npm ci in ui/ before starting the development server.');
    }
    throw err;
  });
  if (!moduleInfo.isDirectory() || moduleInfo.isSymbolicLink()) {
    throw new Error('node_modules must be a real local directory for safe cache recovery.');
  }
  const cacheDir = join(modulesDir, '.vite');
  await rm(cacheDir, {recursive: true, force: true, maxRetries: 6, retryDelay: 350});
  return cacheDir;
}

export async function recoverDev() {
  const viteCli = join(UI_ROOT, 'node_modules', 'vite', 'bin', 'vite.js');
  try {
    await access(viteCli);
  } catch {
    throw new Error('Vite CLI is missing. Run npm ci in ui/ before starting the development server.');
  }
  await assertPortAvailable();
  const cacheDir = await clearViteCache();
  console.log(`[SchemaForge] Removed only Vite dependency cache: ${cacheDir}`);
  console.log('[SchemaForge] Starting Vite on 127.0.0.1:5173 with --force.');
  const child = spawn(process.execPath, [viteCli, '--host', DEFAULT_HOST, '--force'], {
    cwd: UI_ROOT,
    stdio: 'inherit',
    windowsHide: false,
  });
  child.once('error', err => {
    console.error(`[SchemaForge] Failed to start Vite: ${err.message}`);
    process.exitCode = 1;
  });
  child.once('exit', (code, signal) => {
    process.exitCode = code ?? (signal === 'SIGINT' ? 130 : 1);
  });
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))) {
  recoverDev().catch(err => {
    console.error(`[SchemaForge] UI recovery stopped: ${err.message}`);
    console.error('[SchemaForge] If EPERM persists, check active Node/Vite instances and Windows antivirus file locks.');
    process.exitCode = 1;
  });
}
