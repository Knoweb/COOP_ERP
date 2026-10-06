# Code review wave 2: summary

Reviewed 6 October 2026 against `main` at 91e550d8. Each area had one reviewer and a second-pass verifier that read the findings adversarially. The findings files are `docs/reviews/wave2-<area>.md`; each finding carries its verdict (CONFIRMED or PLAUSIBLE), a "Verified by" line and a "Suggested fix scope".

**Status: closed out 7 October 2026.** The eighteen fix pull requests of the plan (seventeen, with M5 split in two) merged on 6 October; what they fixed and what stays open is the last two sections below.

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

## What was fixed, and what was not

Eighteen pull requests, all merged on 6 October 2026: #262 (web quick fixes), #264 (web shell guards), #265 (kernel sync gateway), #263 (kernel notifications), #268 (RLS cross-tenant functions), #269 (M6 receipt flags), #267 (deployment kit), #266 (till trust), #276 (M4 credit once per line), #277 (M1 resets and limits), #275 (M3 pricing rules), #281 (M4 payments and the buyer's GRN posting), #278 (M7 NIC and erasure), #280 and #282 (M5, split in two), #283 (M9 export and counterparty delivery), #284 (M8 projections), #285 (RLS matrices and the last two definer functions). Each closes the findings its body lists; `TRACKER.md` names them per reviewed pull request. None of the fixes has had a second review.

Counted per finding against the findings files and the fix plan's "Deferred, with the reason" table. "In part" is a finding whose fix shipped with one piece deferred by decision; "invalid" is a finding the fix review showed not to be a defect.

| Area | Live | Closed | In part | Deferred | Invalid | Dropped at verification |
|---|---|---|---|---|---|---|
| M4 money paths | 14 | 14 | 0 | 0 | 0 | 0 |
| Row-level security | 17 | 15 | 0 | 2 (RLS-10, RLS-15) | 0 | 0 |
| M7 credit book | 14 | 12 | 2 (M7CR-07, M7CR-13) | 0 | 0 | 1 |
| M9 export, notifications | 10 | 9 | 1 (M9-03) | 0 | 0 | 1 |
| Deployment kit | 18 | 18 | 0 | 0 | 0 | 0 |
| M5 inventory, M3 pricing | 30 | 22 | 1 (M5-04) | 5 (M5-08, M5-15, M5-19, M3-04, M3-08) | 2 (M5-21, M3-06) | 0 |
| M6, M8, M1 admin | 27 | 25 | 0 | 2 (M6-07, M8-05) | 0 | 2 |
| Till, web, kernel | 30 | 28 | 2 (TWK-23, TWK-28) | 0 | 0 | 0 |
| **Total** | **160** | **143** | **6** | **9** | **2** | **4** |

The pieces deferred by decision: M7CR-07's dedupe table (not planned; the reorder shipped) and M7CR-13's cross-society credit (after doc 10 L-02; `nic_holders` shipped); M9-03's "closed" by every location's day-close (the provisional flag shipped); M5-04's `SaleReversalConsumer` (with the till's voids and refunds; the `TILL_FACT_NOT_APPLIED` flag shipped); TWK-23's M6 `SessionHook` fact (the envelope rule shipped); TWK-28's document-id predicate on `presignGetOfParty` and the Chromium sandbox (an ArchUnit rule pins the callers). The deployment kit's "before real data" items (PITR, encrypted dumps, named administrators with OTP, `read_only` roots, the management-port split, a docker-only publish job with provenance, a maintained MinIO image) stay deferred to staging with real parties, as the fix plan says. Two findings the review did not raise also came out of the fix PRs: #285 found `kernel.change_log_append` and `security.user_has_any_assignment` testing no scope class and fixed both (`2026-10-06-wave2-last-definer-functions-17.md`).

The decisions are the 23 files `docs/progress/deviations/2026-10-06-wave2-*.md` without a number; each fix PR's own deviations are the numbered files beside them. The notable builder deviations:

- The plan's file names were wrong in several places; the builders followed the code: `CostService` and `MovementType` live in `m5inventory`, not the shared engine (#280); M1's user and role handlers in `m1party` (#277); the web step-up rule in `pendingCommand.ts`/`oidc.ts` and the M8 guard in its `module.tsx` (#264); `SmsChannel` in `m9integration` (#263).
- A RETAIL price above zero is a handler guard only, not `exclusiveMinimum` in the slice, because the request does not name the list's kind; the "fixed price at or above list" warning was not built; the till does not use the shared engine yet and must take 0.2.0 when it does (#275, `pricing-rules-01`).
- A credit note applies to one invoice once (`m4.creditnote.applied_already`); the applied and unapplied figures live on the credit note, not the invoice view (#276, `credit-once-per-line-01`).
- M9 records the side of the document type's issuer role, not a plain seller-only rule; M1's frozen contract took its additive change as `m1party.yaml` 1.4.2 (#281, `payments-and-buyer-postings-07`).
- `nic_key_id` is text, and the `x-federation-view` mark is read per permission code, fail closed; `RecaptureNic` has an endpoint and no screen (#278, `m7-identity-and-credit-book-09`).
- `CreditLimitBackfillJob` publishes the opening limit of every pair activated before #277 once, kept one-off by a new marker table `security.credit_limit_announcement` (#277, `m1-administration-01`).
- The sign-in page uses the web client's Noto Sans, not Inter; Keycloak's default roles and `CONFIGURE_TOTP` are set by kcadm, never in `realm-dev.json` (it broke the import and CI's e2e); objects are backed up by `objects.sh`, not `mc mirror`, which skipped M2 thumbnails (#267, `deploy-kit-15`).
- Decimals in a till bundle must be text, as `Facts.kt` writes them; the old conformance fixture was changed (#265, `sync-gateway-01`).
- A hand-issued transfer may still name an expired batch (only the picks skip them); the demo's write-off is witnessed by the society office (#282, `stock-control-01`).

## Still open for the architect

- A one-administrator society whose only user manager loses their second factor (CR-21A-7): a second holder at activation, or a helpdesk procedure in doc 38.
- Whether one shop offline for two days should block an entity's final journal export once location-dated postings reach M9 (CR-29-1).
- Whether to pull K-08-F2 (the pre-built full snapshot file) forward now that a till exists on a real link.
- PR #247 (CI/CD): recommend close (`wave2-deploy-kit.md` (9)); its deploy workflow runs the laptop stack on a server, and #267 now covers the pinned actions and the deploy path.
- PR #41 (the till's Gradle wrapper from 8.14.4 to 9.8): held, because the Android Gradle Plugin 8.13.2 the till uses does not run on Gradle 9.6 or later. Moving the till to AGP 9 is the till owner's decision.
- Follow-ups from the fix PRs:
  - The SMTP environment (`COOP_ERP_SMTP_USERNAME`, `_PASSWORD`, `_SECURITY`) now passes through `infra/deploy/compose.yml` (in #283's merged commit; its deviation file (4) still says this was left to PR 15). A server that relays to a real mail host must set them in `.env`.
  - `web/package-lock.json` is tracked but unused: CI installs with pnpm from `web/pnpm-lock.yaml`. Remove it, or say why it stays.
  - The deployment kit's browser flows were never exercised in a browser: the printed PDF opening inline under the new headers, the phase-4 temporary-password page through the Caddy allowlist, and a till enrolling through the public token endpoint (#267, "Not run"). Try them on a throwaway host before the demo server is redeployed.
  - Keycloak 26.8 (open Dependabot #251 and #271): its database migration is one way, so once the demo server moves to it, going back to 26.7 needs a restore of the Keycloak database from before the move.
