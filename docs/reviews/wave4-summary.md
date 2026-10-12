# Code review wave 4: summary

Reviewed 12 October 2026 against `main` at 73f89f58. Scope: the team pull requests merged after wave 3: #313, #314, #315, #334, #335 and #336 (full review of each), and whether their merged versions closed the wave 3 findings made on them while they were open. Documentation and Dependabot PRs were not code-reviewed.

Each area had one reviewer, who then verified each finding in a second pass. Docker was down, so every verdict rests on reading the code.

**Status: findings verified; no fix has been made yet.**

## Counts (after verification)

| Area | File | PRs | High | Medium | Low |
|---|---|---|---|---|---|
| Till data sync | `wave4-till-data.md` | #314, #336 | 2 | 1 (plausible) | 2 |
| M3 pricing | `wave4-m3.md` | #334 | 1 | 5 | 4 |
| Kernel and sync | `wave4-kernel.md` | #313, #315, #335 | 0 | 1 | 2 |
| **Total** | | | **3** | **7** | **8** |

No critical findings.

## Wave 3 findings on these PRs: closed or not

| Finding | PR | Closed? |
|---|---|---|
| TILLM6-01 | #314 | PARTLY: assignments, revocations, PIN resets and deactivations reach the tills; a Federation template amendment does not (M1M2M3M5-26) |
| M1M2M3M5-26 | #314 | NO: #314 merged without the fix; the fix is #337 (the replacement of #321) |
| M1M2M3M5-27 | #314, #336 | NO |
| M1M2M3M5-10 | #336 | NO: #336 sends corrected batches to the tills, but no lot, shelf ceiling or price check follows a correction |
| KRN-09 | #313 | NO: every event consumer is still exempt from the "only command handlers write" rule, with no recorded deviation |
| KRN-10 | #313 | NO |
| KRN-11 | #315 | NO: only the version-floor path was fixed; four other unscoped readers still get defaults |
| KRN-12 | #315 | NO |

## High findings, one line each

- **TD-01** (#336): M2 changes reach only ACTIVE shops; a till that enrols before its shop is activated, or a dormant shop's till, misses them for good, as nothing forces a full snapshot later.
- **TD-02** (#336): the till snapshot's `batch` table carries every batch of the federation the till can read, superseded ones included; with millions of batches a year a full snapshot can exhaust memory, and every batch of a shared SKU is logged at every shop.
- **M3R-01** (#334): central basket resolution looks up the control price with a null unit (`ceiling_uom_code = NULL` never matches), so a gazetted ceiling is never applied centrally and central disagrees with the till.

## Notable mediums

- **M3R-08** (#334): two new kernel API interfaces without a change request; `ScopedExecution` lets any module open a cross-tenant scope.
- **M3R-02** (#334): KHATA, CHEQUE or VOUCHER tenders and quantities with more than three decimals return a 500.
- **K4-01** (#335): after the grace period the version-floor ALERT is written on every upload attempt, with no de-duplication (about 300 a day for a busy till), while a till that only sends heartbeats never alerts.
