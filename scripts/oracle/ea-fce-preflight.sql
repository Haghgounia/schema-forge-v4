-- SchemaForge v0.6.0 Oracle EA deployment preflight (READ ONLY).
-- Run manually using SQL*Plus/SQLcl with a DBA-approved read-only dictionary account.
-- This script DOES NOT create users/tablespaces or execute deployment DDL.
-- FCE fixture contract: schema FEE3, data tablespace TS_FEE3, index tablespace ITS_FEE3.
-- Reported statuses must be reviewed by the DBA BEFORE run-all is executed.
-- DBA_* and V$PARAMETER views require corresponding dictionary permissions.
WHENEVER OSERROR EXIT FAILURE ROLLBACK
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
SET PAGESIZE 100
SET LINESIZE 180
SET VERIFY OFF
SET DEFINE OFF
COLUMN ITEM FORMAT A26
COLUMN STATUS FORMAT A16
COLUMN VALUE FORMAT A96
PROMPT =============================================================
PROMPT SchemaForge EA FCE Oracle preflight - READ ONLY / manual gate
PROMPT =============================================================
SELECT 'CONNECTION_USER' AS ITEM, USER AS VALUE FROM DUAL;
SELECT 'DATABASE_NAME' AS ITEM, SYS_CONTEXT('USERENV','DB_NAME') AS VALUE FROM DUAL;
SELECT 'TARGET_SCHEMA' AS ITEM,
       CASE WHEN EXISTS (SELECT 1 FROM ALL_USERS WHERE USERNAME = 'FEE3')
            THEN 'EXISTS' ELSE 'MISSING' END AS STATUS
FROM DUAL;
SELECT 'TS_FEE3' AS ITEM,
       CASE WHEN EXISTS (SELECT 1 FROM DBA_TABLESPACES WHERE TABLESPACE_NAME = 'TS_FEE3')
            THEN 'EXISTS' ELSE 'MISSING' END AS STATUS
FROM DUAL;
SELECT 'ITS_FEE3' AS ITEM,
       CASE WHEN EXISTS (SELECT 1 FROM DBA_TABLESPACES WHERE TABLESPACE_NAME = 'ITS_FEE3')
            THEN 'EXISTS' ELSE 'MISSING' END AS STATUS
FROM DUAL;
SELECT 'COMPATIBLE' AS ITEM, VALUE FROM V$PARAMETER WHERE NAME = 'compatible';
SELECT 'NLS_CHARACTERSET' AS ITEM, VALUE
FROM NLS_DATABASE_PARAMETERS WHERE PARAMETER = 'NLS_CHARACTERSET';
PROMPT --- Tablespace quota: -1 means unlimited; no row may mean missing quota ---
SELECT USERNAME, TABLESPACE_NAME, BYTES, MAX_BYTES
FROM DBA_TS_QUOTAS
WHERE USERNAME = 'FEE3' AND TABLESPACE_NAME IN ('TS_FEE3','ITS_FEE3')
ORDER BY TABLESPACE_NAME;
PROMPT --- System privileges granted directly to target schema (also check roles/environment) ---
SELECT GRANTEE, PRIVILEGE
FROM DBA_SYS_PRIVS
WHERE GRANTEE = 'FEE3'
  AND PRIVILEGE IN ('CREATE SESSION', 'CREATE TABLE', 'CREATE SEQUENCE',
                    'CREATE VIEW', 'CREATE PROCEDURE', 'CREATE TRIGGER')
ORDER BY PRIVILEGE;
PROMPT --- Manually verify: COMPATIBLE >= 12.2 and required quotas/privileges ---
PROMPT --- Recommended Unicode character set: AL32UTF8 ---
PROMPT --- Preflight is observational; it does not guarantee deployment success ---
