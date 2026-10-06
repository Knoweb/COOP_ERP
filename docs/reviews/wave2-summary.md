# Code review wave 2: summary

Reviewed 6 October 2026 against `main` at 91e550d8. Each area had one reviewer and a second-pass verifier that read the findings adversarially. The findings files are `docs/reviews/wave2-<area>.md`; each finding carries its verdict (CONFIRMED or PLAUSIBLE), a "Verified by" line and a "Suggested fix scope".

**Status: findings verified; no fix PR has merged yet.** Nothing here is fixed.

A second pass reviewed the proposed fix of every finding (is the issue real, is the fix correct, is it the best business solution): `docs/reviews/wave2-fixreview-<area>.md`. Those files group the findings into fix groups, list the corrections to the verifiers' suggested fixes, and recommend decisions. The recommendations are not accepted until recorded in `docs/progress/deviations/`.

**Decided 6 October 2026, on the architect's delegation:** every "decide then build" item is recorded in `docs/progress/deviations/2026-10-06-wave2-*.md` (23 files); the decisions that change a design document are the change requests `CR-18-2`, `CR-19A-12`, `CR-19A-13`, `CR-21A-7`, `CR-23A-1`, `CR-24A-3`, `CR-25A-1`, `CR-27A-1`, `CR-28A-2`, `CR-29-1`, `CR-30-2`, `CR-32-1`; the fix pull requests, their migration numbers, order and parallel sets are `docs/reviews/wave2-fix-plan.md`. Where two fix reviews disagreed (the buyer's postings, the contact door, the notification hash's legacy rows, the projections' PARTY policy) the decision file says which was taken and why.

## Counts (after verification; four findings were dropped)

| Area | File | High | Medium | Low | Dropped |
|---|---|---|---|---|---|
| M4 money paths | `wave2-m4-money.md` | 1 | 7 | 6 | 0 |
| Row-level security | `wave2-rls.md` | 1 | 4 | 12 | 0 |
| M7 credit book | `wave2-m7-credit.md` | 1 | 9 | 4 | 1 |
| M9 export, notifications | `wave2-m9-integration.md` | 0 | 4 | 6 | 1 |
| Deployment kit | `wave2-deploy.md` | 0 | 8 | 10 | 0 |
| M5 inventory, M3 pricing | `wave2-m5-m3.md` | 2 | 14 | 14 | 0 |
| M6, M8, M1 admin | `wave2-m6-m8-m1admin.md` | 4 | 16 | 7 | 2 |
| Till, web, kernel | `wave2-till-web-kernel.md` | 2 | 18 | 10 | 0 |
| **Total** | | **11** | **80** | **69** | **4** |

No critical findings. 160 live findings; 13 of them are PLAUSIBLE (could be settled only by a runtime test or by library behaviour), the rest CONFIRMED.

## How strong the evidence is

Docker Desktop was down during verification (a known socket fault on this machine; a Windows restart is the cure). Every CONFIRMED rests on reading the code, except M7CR-01 (reproduced by a script). Reproduction tests for M4, M7 and RLS compile but have not run; they are kept outside the repository until Docker is up.

## Critical and high, one line each

- **M4MONEY-01**: the same damaged goods can be credited twice (discrepancy settlement, then a DAMAGED claim); the only cap is the invoice total.
- **M7CR-01 / RLS-01**: the NIC hash is unsalted SHA-256 beside the last four digits, recoverable in about a second by anyone who can read the row; doc 27 asks for a salted hash. A keyed hash (pepper) works with the duplicate lookup and can re-key existing rows.
- **M5-01**: FEFO, delivery and transfer picks, and availability never check expiry; expired stock is picked first.
- **M5-09**: write-off and adjustment approval bands are never enforced and the approver limit fails open.
- **M6-08**: the receipt list is unbounded and N+1, and the receipt page searches the whole list in the browser.
- **M8-06**: the dashboard cache key omits the user and granted entities; two external auditors can share one cached dashboard.
- **M1A-01**: `gov.user.manage` can reset any user's credentials, including higher-privileged users, and may get the temporary password back.
- **M1A-03**: `UpdateUser` can move the last user manager to kind TILL with no last-holder guard.
- **TWK-01**: the till never checks the signed snapshot's `since` or that versions only rise.
- **TWK-11**: the user menu sends each user's display name to ui-avatars.com on every page load.

## Verification changes worth knowing

Lowered: M6-01 high to medium; M9-01, M9-06, RLS-02, RLS-03, RLS-06, M3-04, TWK-23, DEPLOY-07 to low. Dropped: M8-01 (the event is stamped with central's clock), M1A-02 (the temporary password never reaches the idempotency store), M7CR-12 (Federation read matches docs 18 and 27), M9-04 (the inbox stops redelivery). The first two of these were reported high or medium and did not hold.

## Needs a design decision before a fix

Not decided by reviewers or verifiers; each is for the architect. Items marked ★ are gaps the build already records as deferred, not regressions.

- Money: M4MONEY-03 ★ (credit after full payment, unapplied credits), M4MONEY-09 (cheque duplicate guard), M4MONEY-10 ★ (buyer-side postings).
- M7: M7CR-09 and M7CR-10 (erasure leaves a balance guard gap and free-text personal data; conflicts with the insert-only rule), M7CR-13 (an account at two societies, ADR-15).
- M5/M3: M5-01 (expiry cut-off rule and config), M5-09 (approval bands and seeds), M3-03 (backdated control prices).
- M9: M9-02, M9-06, M9-08 (quiet hours: defer or drop; 19A and doc 29 disagree).
- RLS: RLS-02 (Federation view of member data), RLS-07 (where the batch correction function lives), RLS-09 (extension tables after issue).
- Till sync contract: TWK-01, 02, 04, 05, 24 and the others listed in `wave2-till-web-kernel.md`.
- Deployment: DEPLOY-07 (may a public issuer ever run the #184 shortcut), and operating choices in 02, 04, 05, 13.

## Needs a migration to fix

M9-05, M9-06, TWK-20, M8-03, M8-05, M8-08, M1A-05, the NIC re-key (M7CR-01), RLS-04, RLS-07, RLS-08, and probably M6-07. Migrations are new files only; no merged migration is edited.

## Not reviewed in wave 2

M2 catalogue (#135, #137, #146, #160, #249), the M3 and M1 screens, the demo loader and demo data, the review-fix PRs #118–#131 outside the areas above, CI beyond the GHCR publish job, and every docs-only PR. These rows stay "Not reviewed" in the tracker. Open PRs #247 (CI/CD) and #258 (debit notes) were not reviewed; a note in `wave2-deploy.md` says #247 would be high if merged, because its deploy workflow runs the laptop stack on a server.
