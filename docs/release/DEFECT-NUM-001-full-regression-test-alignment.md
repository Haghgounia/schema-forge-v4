# DEFECT-NUM-001 full-regression test alignment

This overlay updates five pre-NUM-001 tests that still expected missing exact-numeric precision to be blocking.
No production source is changed.

Current frozen contract:
- Canonical precision remains null / unspecified.
- Db2 LUW: DECIMAL(31,0).
- Db2 z/OS: DECIMAL(31,0).
- SQL Server: DECIMAL(38,0).
- MySQL: DECIMAL(65,0).
- DatatypeCompatibilityAnalyzer reports NUMERIC_PRECISION_UNSPECIFIED as WARNING.
- Other truly unsupported/out-of-range mappings remain blocking.
