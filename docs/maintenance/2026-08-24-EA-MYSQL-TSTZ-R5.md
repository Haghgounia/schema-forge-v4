# EA/MySQL TIMESTAMP WITH TIME ZONE adaptation - R5

## Scope

Real EA acceptance reached canonical `TIMESTAMP_WITH_TIME_ZONE` columns that MySQL cannot represent
losslessly with its native temporal types. R5 keeps the canonical model unchanged and adapts only
MySQL physical/logical DDL storage.

## Policy

- `TIMESTAMP_WITH_TIME_ZONE` -> `VARCHAR(128)` in MySQL DDL.
- Every adapted column carries DBA-visible marker `MYSQL-TSTZ-TEXT-001`.
- Values must be serialized with an explicit offset or region (for example an ISO-8601 representation).
- MySQL temporal ordering/functions are not implied by the textual envelope and require explicit conversion.
- `TIMESTAMP_WITH_LOCAL_TIME_ZONE` remains rejected.
- `MySqlTypeMapper` remains strict and continues to reject timezone-aware timestamps as lossless logical mappings;
  the adaptation is intentionally owned by `MySqlDialect`.

## Real EA evidence

`Party-Operation_Froms-14050601.xml` contains six `TIMESTAMP WITH TIME ZONE` columns across three tables:

- `PARTY_CONSENT.GRANTED_AT`
- `PARTY_CONSENT.REVOKED_AT`
- `PARTY_CONSENT.CREATED_AT`
- `PARTY_CONSENT.UPDATED_AT`
- `ORGANIZATION_OFFICER.CREATED_AT`
- `KYC_CASE.UPDATED_AT`

## Exit gate

Targeted MySQL/EA regression must pass, followed by REST generation on the real EA input and then a full clean regression.
