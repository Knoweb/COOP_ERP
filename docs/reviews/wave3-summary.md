# Code review wave 3: summary

Reviewed 9 October 2026 against `main` at ec4c5758. Scope: every merged code pull request that had not had an independent review: the wave 2 fix PRs (#262 to #287), the wave 1 fix PRs (#116 to #131), the older unreviewed code PRs (M2, the M3 price-list screen, the demo loader, CI and tooling), and the team's PRs #258 and #296 to #312. 71 merged PRs in all. The open PRs #313, #314 and #315 were read but are not merged; their findings are marked "(open PR)". Documentation-only PRs and Dependabot bumps were not code-reviewed.

Each area had one reviewer and a second-pass verifier. The findings files are `docs/reviews/wave3-<area>.md`; each finding carries its verdict, a "Verified by" line and a suggested fix scope. Docker was down during verification, so every verdict rests on reading the code, except two pricing-engine findings reproduced with a unit test (M1M2M3M5-05 and -06) and the nightly CI diagnosis, which rests on the CI logs. The web unit tests were run and pass.

**Status: findings verified; the urgent findings and the small ones without a design decision were fixed on 9 October 2026 (see "Fixes" below); the rest are open.**

## Counts (after verification)

| Area | File | High | Medium | Low | Dropped or duplicate |
|---|---|---|---|---|---|
| M4 trading | `wave3-m4.md` | 1 | 5 | 5 | 0 |
| Kernel, sync, RLS | `wave3-kernel.md` | 2 | 13 | 9 | 0 |
| Till and M6 | `wave3-till-m6.md` | 1 | 3 | 5 | 0 |
| M7, M8, M9 | `wave3-m7-m8-m9.md` | 1 | 3 | 4 | 1 duplicate (of M4-04) |
| M1, M2, M3, M5 | `wave3-m1-m2-m3-m5.md` | 2 | 12 | 15 | 0 |
| Web, CI, demo | `wave3-web-ci-demo.md` | 0 | 6 | 17 | 1 dropped |
| **Total** | | **7** | **42** | **55** | **2** |

No critical findings. 104 live findings; 3 are PLAUSIBLE (races that need a concurrent test), the rest CONFIRMED.

## Did the wave 2 fixes close their findings?

| Area | Closed | Partly closed | Not closed |
|---|---|---|---|
| M4 | 16 | 0 | 0 |
| Kernel, sync, RLS | 26 | 1 | 0 |
| Till and M6 | 19 | 0 | 0 |
| M7, M8, M9 | 39 | 0 | 0 |
| M1, M2, M3, M5 | 23 | 7 | 0 |
| Web, CI, deploy | 19 | 8 | 0 |
| **Total** | **142** | **16** | **0** |

The partly closed ones are listed in each file's "closed or not" table, with what is still missing (for example M3-07: an amount-off rule larger than the price still sells at 0.00, reproduced).

## High findings, one line each

- **M4-01** (#297): AmendOrder requires `ord.order.amend`, which is in no permission seed, role template or demo user; with enforcement on (the default) every amendment, including from the web, is refused with 403.
- **KRN-01** (#308 on the #124 gap check): the nightly numbering-gap check reads `kernel.document`, but till receipts (M6) and till customer payments (M7) live elsewhere while raising the series counters, so every number of every till RCT and CPR series is reported missing every night; the scan also walks every number of every series.
- **KRN-07** (#87, #118): the outbox relay serves one source per poll, chosen alphabetically, with no fairness rule; under steady till traffic central events (invoices, payments, notifications) can wait indefinitely.
- **TILLM6-01** (#304, #301): role assignments, revocations, PIN resets and user deactivations reach a till only in a full snapshot; a removed supervisor keeps their PIN and `pos.session.manage` on every till. Open PR #314 would close it once merged and fixed (next item).
- **M1M2M3M5-26** (open PR #314): the template-holder lookup runs in the Federation's own scope, where row-level security hides every society's role assignments, so a Federation template amendment reaches no society till.
- **M1M2M3M5-10** (#135, #146): nothing consumes `batch.corrected.v1`; a corrected MRP or expiry never reaches the lots, shelf ceilings, the till cap or first-expiry-first-out picking, although the CorrectBatch endpoint is live.
- **M7M8M9-01** (#278 with #267): the backend refuses to start without `COOP_ERP_CUSTOMERS_NIC_PEPPER` on any non-development issuer, and nothing in the deploy kit generates or passes it; any server set up or redeployed from the kit will not start.

## Notable mediums

- **M4-09** (#310): the buyer-side invoice masking view filters on document type `'INVOICE'`, but invoices are stored as `'INV'`: a buyer reading an invoice sees no lines. The test passes only because it inserts with foreign-key checks off.
- **M4-04 / M7M8M9-07** (#258, #300): M8 does not project debit notes, so receivables, ageing and exposure in reporting disagree with what M4 says is due.
- **TILLM6-03**: no role a society administrator holds can assign the till templates (assigning needs every permission of the role), so outside the development data nobody can hold `pos.session.manage`.
- **WCD-01**: after a step-up sign-in the stored command is replayed as whoever signed in; the callback never compares the user.

## The nightly CI job

#302 fixed the dirty-tree refusal that had stopped the nightly run since 26 September. The run now reaches the full suite on the scaffolded copy and shows five failures, none of them an application defect: two tests assume the built M9 module that the scaffold proof replaces (WCD-23), and three print tests hang because the nightly step does not set `CHROMIUM_PATH` (WCD-24; one line of configuration).

## Open pull requests

- **#314** (M1 change-log producers): would close TILLM6-01, but has the high finding M1M2M3M5-26; its other changes to CI and Gradle settings are unrelated to its title.
- **#313** (device heartbeats into `party.device`): medium finding KRN-09, it exempts every event consumer from the "only command handlers write" build rule.
- **#315** (narrow `config_value` own_read): medium finding KRN-11, unscoped jobs would silently use defaults; its own CI fails on six tests.

## What was not covered

Documentation-only PRs (the demo flows and change requests keep "Not reviewed"; the review records are "Docs (wave 2)"; the go-live list PRs are "Docs (no code review)"), Dependabot bumps, and the open PR #247.

## Fixes (9 October 2026)

Each fix PR was built from `main`, ran the whole CI, and was merged one at a time; none has had a second review yet (tracker status "Review fix (not re-reviewed)").

| PR | Findings closed |
|---|---|
| #317 | M7M8M9-01 (high: the deploy kit generates and passes the NIC pepper), WCD-14, WCD-17 |
| #318 | M4-01 (high: AmendOrder asks `ord.order.submit` again), M4-02, M4-07, M4-09 (the buyer sees invoice lines), M4-11 |
| #319 | KRN-04, KRN-06, KRN-16, KRN-18, KRN-21, KRN-23, M7M8M9-04, M7M8M9-09 |
| #320 | M1M2M3M5-01, -04, -08, -14, -16 to -23, -29 |
| #322 | WCD-01, -02, -03, -04, -05, -06, -07, -09, -10, -11, -12, -13, -15, -16, -20, -23, -24, -25 |
| #323 | TILLM6-02, -03, -05, -07, -08, -09 |
| #321 | M1M2M3M5-26 (high), on the branch of open PR #314; it closes when #314's author merges it and #314 lands |

Also #325: the unit test task runs on a 1 GB heap (main failed once with "Java heap space" after #319; not a finding).

Deviations taken on the architect's delegation are in `docs/progress/deviations/2026-10-09-wave3-*.md`. The WCD-23, WCD-24 and WCD-25 changes run only in the nightly job; the first nightly after #322 is their proof.

**Still open (52):**
- High: KRN-01 (the gap check reads only `kernel.document`), KRN-07 (outbox relay fairness), M1M2M3M5-10 (nothing consumes `batch.corrected.v1`), TILLM6-01 and M1M2M3M5-26 (with open PR #314).
- With the open PRs #313 and #315: KRN-09, KRN-10, KRN-11, KRN-12.
- Medium and low, larger than S or needing a design decision: M4-03, -04, -05, -06, -08, -10; KRN-02, -03, -05, -08, -13, -14, -15, -17, -19, -20, -22, -24; TILLM6-04, -06; M7M8M9-02, -03, -05, -06, -07, -08; M1M2M3M5-02, -03, -05, -06, -07, -09, -11, -12, -13, -15, -24, -25, -27, -28; WCD-18, -21, -22.
