# DEFECT-NUM-001 - SQL hint for unspecified numeric precision

Final contract:
- Canonical precision remains null / UNSPECIFIED.
- Missing exact-numeric precision is WARNING, never ERROR.
- Common issue code: NUMERIC_PRECISION_UNSPECIFIED.
- Existing DdlGenerator -> SqlIssueCatalog -> InlineIssueRenderer flow renders the warning in generated SQL.
- Runtime fallback mappings remain unchanged from the previous NUM-001 overlay.
