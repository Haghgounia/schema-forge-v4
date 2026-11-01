SchemaForge V4 Collateral DDL Portability R1 - delta patch
Baseline: schema-forge-v4-2026-11-01-0919.zip / 4.0.1
Changes: XMI 1.1 TIMESTAMP length=6, MySQL+MariaDB fractional defaults,
         SQL Server 2022-safe identifier regex checks, PostgreSQL POSIX regex.
No UI or database files modified. No automatic DB writes.
Install: unpack into project root, then run:
    call scripts\accept-collateral-portability-20261101.cmd
Full detail: docs\COLLATERAL-DDL-PORTABILITY-R1-20261101.md
