# Code review wave 4: summary

Reviewed 12 October 2026 against `main` at 73f89f58. Scope: the team pull requests merged after wave 3: #313, #314, #315, #334, #335 and #336 (full review of each), and whether their merged versions closed the wave 3 findings made on them while they were open. Documentation and Dependabot PRs were not code-reviewed.

Each area had one reviewer, who then verified each finding in a second pass. Docker was down, so every verdict rests on reading the code.

**Status: findings verified; the highs and the small findings were fixed on 12 and 13 October 2026 (see "Fixes" below).**

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

## Fixes (12 and 13 October 2026)

Each fix PR ran the whole CI and was merged one at a time; none has had a second review yet.

| PR | Findings closed |
|---|---|
| #337 | M1M2M3M5-26 (wave 3, high): the fix of #321 rebuilt on main, since #314 merged without it; with it TILLM6-01 is closed |
| #339 | K4-01, K4-02, K4-03; CR-32-2 (the attachment upload window, below) |
| #340 | M3R-01 to M3R-06, M3R-07 (the contract part), M3R-08, M3R-09, M3R-10; CR-19A-14 |
| #341 | TD-01 to TD-05 |

Decisions taken on the architect's delegation (deviation files `docs/progress/deviations/2026-10-13-wave4-*.md`):
- **M3R-08** (CR-19A-14): `kernel.api.ScopedExecution` removed. Only a `@ScheduledJob` takes another entity's scope, through `JobExecution` (CR-19A-7), and the build rule now covers every kernel API that hands out a scope. M3's review consumers record a `pricing.review_trigger` row and `PricingReviewJob` raises each review in its owner's scope, so a review appears within a minute rather than in the consumer's transaction. `AuditReviewReader` stays, with the event codes passed by the caller. A consumer that must read across entities asks a SECURITY DEFINER function of the owning module that decides from `kernel.scope_class()` (#337 `security.role_holder_shops`, #341 `party.change_log_shops`); a module may not build a FEDERATION_VIEW scope itself.
- **The attachment upload window** (CR-32-2, from wave 3 KRN-16): renewal stays refused after the window and the default stays 15 minutes, configurable per scope (`attachment.upload_url_minutes`, CR-19A-8). A photo is at most about 300 KB (doc 32 section 4), so 15 minutes is ample; a till that comes back later asks for a new attachment on the same document, and a FAILED attachment does not block a write-off. Two gaps are recorded in the CR: an object that never arrives stays PENDING for 24 hours, and an M4 claim decision still needs every photo COMPLETE.
- **TD-02 and TD-04**: the till contract carries no batch rows at all (doc 32 section 5.1 puts a till's batches with M5's lots), rather than a narrowed set.

**Still open:** M1M2M3M5-27 and M1M2M3M5-10 (wave 3), KRN-09 to KRN-12 (wave 3, not closed by #313 and #315), M3R-07's settle flow (design), the two attachment gaps above.
