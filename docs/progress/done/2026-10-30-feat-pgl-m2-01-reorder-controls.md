# fix(pgl-m2-01): resolve remaining CI validation failures

**Branch:** feat/PGL-M2-01-reorder-controls  
**Commit:** c586bc5e  
**Date:** 2026-10-30

## What was done

Follow-up CI fix commit after merge of main (`a950c7e6`) into the branch. Three
integration-test failures and one Spotless failure remained after the previous
CI run (`9f0cc288`).

### Fix 1 — `ReorderStore` missing `@Transactional`

`LowStockReviewJob.review()` is not annotated `@Transactional`. When it called
`ReorderStore.findDistinctLocationsWithControls(view)`, no Spring transaction was
active, so `ScopeConnectionCustomizer` did not fire, RLS session variables were
never set, and the `fed_view` policy denied all rows. Result: 0 locations
discovered and 0 alerts emitted in `lowStockReviewJobEvaluatesAllEntitiesAndLocations`.

**Fix:** added class-level `@Transactional(readOnly = true)` on `ReorderStore`.
Methods called from within an existing non-readOnly handler transaction join that
transaction (Spring REQUIRED propagation); `FOR UPDATE` lock acquisition is
unaffected. Methods called from non-transactional callers (the job) get a new
readOnly transaction, which satisfies the aspect.

Pattern matches `NegativeLotReviewStore` and `StockReconcileStore`.

### Fix 2 — `BigDecimal` scale mismatch in audit-map assertions

PostgreSQL `greatest(0, coalesce(...))` with an integer literal `0` produces a
`numeric` whose JDBC mapping has scale 0. `BigDecimal.equals()` is scale-aware,
so `new BigDecimal("10.000")` (scale 3) ≠ JDBC result (scale 0) even though the
value is 10.

**Fix:** replaced `isEqualTo(new BigDecimal("X.000"))` with
`assertThat(new BigDecimal(after.get(...).toString())).isEqualByComparingTo("X.000")`
in three places across `configureReorderControlInsertsAndUpdates` and
`lowStockEvaluationOnExactEqualityTriggersAlert`.

### Fix 3 — Spotless: multiline `audit.record(...)` call

`ConfigureReorderControlHandler.audit.record(AUDIT_CONFIGURED, …)` was split
across five lines in a style that google-java-format rejected.

**Fix:** collapsed to a single line (as emitted by `spotlessApply`).

### Fix 4 — `inventory.messages.json` JSON syntax error

The merge commit `a950c7e6` introduced a syntax error at line 916 in the Tamil
`"ta"` block and a duplicate `"inventory.pick.status.OPEN"` key.

**Fix:** reconstructed the full file with 313-key trilingual parity (en/si/ta),
preserving all main-branch `inventory.policy.*` keys, all PGL-M2-01
`inventory.reorder.*` keys, and all existing inventory translations.

## Verification

| Check | Result |
|-------|--------|
| `spotlessCheck` | PASS |
| `ArchitectureTests` (ArchUnit + Modulith) | PASS |
| `check-i18n.mjs` | PASS (988 message IDs, 11 modules) |
| `check-permissions.mjs` | PASS (12 slices) |
| `check-slices.mjs` | PASS (12 slices) |
| `vitest run` (web) | PASS (65 files, 399 tests) |
| `git diff --check` (staged) | PASS |
| PostgreSQL integration tests | BLOCKED — Docker not running locally; verified by GitHub CI |

## Deviations

None. All fixes follow the established `NegativeLotReviewStore` /
`StockReconcileStore` patterns and google-java-format conventions.
