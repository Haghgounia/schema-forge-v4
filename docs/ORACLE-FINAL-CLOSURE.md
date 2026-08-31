# Oracle final closure

Oracle is closed for the SchemaForge V4 baseline using retained evidence only. No new Oracle production behavior is introduced by this closure step.

Evidence frozen by `OracleFinalClosureTest`:

- R7.2 optimized strict corpus: 5,321 canonical snapshots; 5,294 clean Oracle scripts, 2 warning-bearing generated scripts, 25 evidence-blocked mappings, 0 failures.
- Historical real-Oracle baseline: 4,768 historical definitions covered; main 115,804/115,804 statements succeeded plus 86/86 collision-coverage statements.
- Final-state FK R2: 242/242 evidence-valid FKs succeeded, 142 structural blockers and 169 dependency skips remained reported, 0 live failures, 0 cleanup failures, no synthetic keys.
- Oracle M2 live: 16 statements, residual diff 0, seeded data preserved, cleanup successful.
- Current ordinary regression remains the project-wide safety net; DB2 z/OS live remains separately environment-deferred.

The closure test is read-only and does not connect to Oracle, parse Legacy Word, regenerate canonical JSON, or regenerate SQL.
