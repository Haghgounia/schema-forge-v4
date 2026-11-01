# SchemaForge V4 Linux operational deployment (operator-only baseline)

This is a limited, **loopback-only** acceptance example for Linux. It deliberately
preserves the server's `/api/v1/connections` local-only protection and does not
provide multi-user/public production authentication.

## Confirmed from the 2026-10-31 14:24 source ZIP

- The UI's REST transport uses absolute-path same-origin `/api/v1/...` requests;
  the browser does not need to know the Linux host's internal port 9090.
- `ui/vite.config.ts` and `npm run dev` bind to `127.0.0.1` and proxy to the Java
  service only in development. `vite preview` is not a production web server.
- The Java source contains no static `src/main/resources/static` UI bundle.
  Static `ui/dist` must be served separately (for example, via Nginx).
- The UI's `index.html` uses `/assets/...`; the deploy example serves at `/`
  rather than a `/schemaforge/` subpath. Subpath deployment needs an explicit
  Vite `base` and REST routing review.
- `DatabaseConnectionController.requireLocal()` checks the network peer,
  `Host`, and `Origin`. A remote public hostname therefore does not work for
  JDBC connection selection, by design. NEVER rewrite these headers to bypass
  this protection.
- `ui/scripts/check-lazy-build.mjs` is missing from the supplied ZIP and
  required by `npm run build` and `ui/tests/lazy-loading.test.mjs`. This delta
  restores the missing checker and preserves the 240 KiB gzip release gate.
- `mvnw` may lose its executable bit in the distributed ZIP; `chmod +x ./mvnw`.
- `src/main/resources/application.yml` contains development JDBC credentials.
  Do not use it as production configuration. Use safe external config and rotate
  any exposed credentials before server deployment.

## Prerequisites

- Java **21**; Node.js **22.12+** and npm for a source UI build; Maven Wrapper.
- Nginx, a non-privileged Linux account `schemaforge`, writable persistent H2
  directory with backup, and isolated secrets via external configuration.
- The ZIP's `ui/dist` is prebuilt but may be stale. For a verified release,
  rebuild it from the exact current source and record the checksum.

## Build and test

```sh
cd /opt/schemaforge/source
chmod +x ./mvnw
./mvnw clean test
./mvnw -DskipTests package
cd ui
npm ci
npm test
npm run typecheck
npm run build
node scripts/check-lazy-build.mjs
```

The safe runtime config template `distribution/config/application-example.yml`
uses disabled JDBC connections by default. Copy it under `/etc/schemaforge`
with root-controlled permissions, and configure the H2 path and any enabled
connection secrets using protected environment variables (not source files).
Never copy the development passwords into a shared installation.

## Operator-only service example

1. Copy the verified JAR to `/opt/schemaforge/bin/schema-forge-v4-4.0.1.jar`.
2. Copy UI `dist/` to `/opt/schemaforge/ui/dist/` preserving the `assets/` tree.
3. Customize `schemaforge.service.example`, copy it under systemd, run
   `sudo systemctl daemon-reload && sudo systemctl enable --now schemaforge`.
4. Copy and enable `nginx-loopback.conf` under Nginx's `conf.d` directory,
   then run `sudo nginx -t && sudo systemctl reload nginx`.
5. On an authorized workstation, use an SSH local port forward:

```sh
ssh -L 8080:127.0.0.1:8080 operator@linux-server
```

Access `http://127.0.0.1:8080` from that workstation. The API is proxied to
`127.0.0.1:9090` on Linux; no public API port is needed. Ensure local firewall,
SSH access and operator privileges are configured appropriately.

Check the health/API availability locally:

```sh
curl -f http://127.0.0.1:9090/v3/api-docs >/dev/null
curl -f http://127.0.0.1:8080/ >/dev/null
```

## Not production-ready for remote multi-user access

For a shared hostname or LAN/public production system, first design and test
SSO/OIDC, RBAC (including privileged Glossary changes), CSRF/session protections,
JDBC target allowlisting, request/connection rate limits, audit logging, TLS,
and a trusted proxy identity contract. Do not weaken `requireLocal()` until an
authenticated backend enforcement mechanism replaces it. Keep `9090` bound to
loopback, isolate the database network, and avoid exposing browser-entered
JDBC credentials to an unsecured endpoint.

## Release gate status

- Confirmed in authoring environment: the five lazy-loader tests and existing
  distribution `ui/dist` manifest pass the restored checker.
- Full `npm test` required external node modules; with no `npm ci` available,
  65 tests ran successfully and three tests could not load the `jszip` package.
- Full `npm run build`, Maven and browser/real Linux Nginx acceptance remain
  mandatory on the target host. The sample is not a completed production release.
