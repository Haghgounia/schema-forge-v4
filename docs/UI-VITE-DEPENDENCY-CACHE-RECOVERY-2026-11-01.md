# SchemaForge V4 UI: Windows Vite Dependency Cache Recovery

Based on `schema-forge-v4-2026-11-01-0919.zip` (`ui` package 0.5.9, Vite 8.x).

## Symptoms

`VITE ready` followed by `EPERM: operation not permitted, rename '.../.vite/deps_temp_*' -> '.../.vite/deps'`.
This is a local development dependency-optimizer cache/file-lock error. The log does not identify the process holding any lock.

## Safe one-step recovery

1. Stop the running Vite window with Ctrl+C.
2. In CMD:

   ```cmd
   cd /d D:\Projects\schema-forge-v4\ui
   npm run dev:recover
   ```

This command checks that the standard Vite loopback port (5173) is free, deletes **only** `ui/node_modules/.vite`, and restarts Vite with `--force`. It does not modify React, Java, database files, or packages. After successful startup, use normal `npm run dev` again; recovery is optional, not a mandatory pre-step.

If the command says `Port ... already in use`, stop the other Vite session; **do not terminate all Node processes**. If the cache cannot be deleted (EPERM/Access denied), investigate Windows Defender/antivirus quarantine and the process holding the directory (Resource Monitor, `resmon.exe`, CPU > Associated Handles). Do not disable AV or broadly elevate user privileges.

If `npm run dev:recover` still fails at Vite's optimizer rename, a Windows file lock still exists. That cannot be proven or removed by changing React components. Keep the error log for investigation; no forced removal of `node_modules` is performed.

## Additional packaging fix

The input ZIP omitted `ui/scripts/check-lazy-build.mjs` although it is required by `npm run build` and `tests/lazy-loading.test.mjs`. This delta restores the previously approved build-graph checker unchanged.

## Local checks

```cmd
cd /d D:\Projects\schema-forge-v4\ui
node --test tests/dev-recover.test.mjs tests/lazy-loading.test.mjs
node scripts/check-lazy-build.mjs
```

Live Windows lock reproduction and full npm dependency installation are not verified by this ZIP.
