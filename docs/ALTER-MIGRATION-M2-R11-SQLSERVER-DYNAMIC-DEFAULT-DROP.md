# ALTER Migration M2-R11 - SQL Server dynamic default-constraint DROP

The SQL Server live M2 pilot reached default-constraint replacement and failed with `Incorrect syntax near 'QUOTENAME'`.

The generated runtime DROP now follows this shape:

```sql
DECLARE @df sysname;
-- discover constraint name
IF @df IS NOT NULL BEGIN
    DECLARE @sql nvarchar(max);
    SET @sql = N'ALTER TABLE ... DROP CONSTRAINT ' + QUOTENAME(@df);
    EXEC sys.sp_executesql @sql;
END;
```

The whole block is still wrapped in one outer `sys.sp_executesql` statement so JDBC/Flyway splitting does not break local variable scope.
