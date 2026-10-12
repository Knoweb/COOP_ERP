# Code review tracker

One row per merged pull request: whether it has had an independent code review, in which wave, and where its findings and fixes are. The building agent's own tests and CI are not a review here.

**Statuses**
- **Reviewed (wave 1)**: covered by the review of 25 to 26 September 2026 (the whole codebase as merged then, plus per-PR and deep reviews). Findings: `.handover/wave1-findings.md` and `.handover/findings-*.md` (outside git); fixed in the "findings of the review" PRs.
- **Review fix (not re-reviewed)**: a PR that fixed review findings; the fix itself has not had a second review.
- **Reviewed (wave 2)**: the module the PR belongs to was reviewed by area on 6 October 2026; the findings file(s) are named in the row. A finding fixed later is named in the Fixed in column.
- **Reviewed (wave 3)**: covered by the review of 9 October 2026 (wave 3: every merged code PR not reviewed before, by area, each verified by a second pass); the findings file is named in the row. A wave 2 fix PR reviewed in wave 3 was also checked against the findings it claimed to close.
- **Docs (no code review)**: a documentation-only PR with nothing to code-review.
- **Not reviewed**: no independent review yet.
- **Dependency update (CI)**: a Dependabot bump, checked by CI only.
- **Docs (wave 2)**: a document of the wave 2 review itself (this tracker, the findings, the decisions and the fix plan); docs only, nothing in it to review as code.

When a wave reviews a PR, change its row: status `Reviewed (wave N)`, the findings file under `docs/reviews/`, and the PR(s) that fixed them.

Wave 2's Fixed in column: for a PR that findings cite, the fix PRs of those findings; "(area; no finding names this PR)" when no finding cites it, so the column lists its area's fix PRs; "none: deferred" when every finding citing it was deferred or found invalid. Findings also cite #135, #155, #184 and #249, which wave 2 did not review as a whole; their rows keep their status.

## Waves

| Wave | Dates | Scope | Findings | Fixes |
|---|---|---|---|---|
| 1 | 25–26 Sep 2026 | everything merged up to #117 | `.handover/wave1-findings.md`, `.handover/findings-*.md` | #116, #118–#131, #138, #139, #148 |
| 2 | 6–7 Oct 2026 | by module, the code on main at 91e550d8: M4 money paths, RLS, M7, M9, deployment kit, M5/M3, M6/M8/M1 admin, till/web/kernel. Not covered: M2 (#135, #137, #146, #160, #249), M3/M1 screens, the demo loader and its data, #118–#131 review fixes outside the areas above, CI beyond the publish job | `docs/reviews/wave2-*.md`, summary in `docs/reviews/wave2-summary.md` | #262, #263, #264, #265, #266, #267, #268, #269, #275, #276, #277, #278, #280, #281, #282, #283, #284, #285 (what was fixed and what was not: `docs/reviews/wave2-summary.md`) |
| 3 | 9 Oct 2026 | every merged code PR not reviewed before: the wave 2 fixes (#262 to #287), the wave 1 fixes (#116 to #131), the older unreviewed code PRs, and the team PRs #258 and #296 to #312; open PRs #313 to #315 read-only | `docs/reviews/wave3-*.md`, summary in `docs/reviews/wave3-summary.md` | #317, #318, #319, #320, #322, #323; #321 on the branch of open PR #314 (what was fixed and what was not: `docs/reviews/wave3-summary.md`) |

## Pull requests

| PR | Merged | Area | Title | Review status | Findings | Fixed in |
|---|---|---|---|---|---|---|
| #1 | 2026-09-16 | — | Feat/s0 database foundation | Reviewed (wave 1) | wave 1 | |
| #2 | 2026-09-16 | db | feat(db): integrate Flyway migrations | Reviewed (wave 1) | wave 1 | |
| #3 | 2026-09-16 | backend | feat(backend): add sprint zero runtime profiles | Reviewed (wave 1) | wave 1 | |
| #4 | 2026-09-16 | backend | feat(backend): add sprint zero runtime profiles | Reviewed (wave 1) | wave 1 | |
| #5 | 2026-09-16 | kernel | feat(kernel): add sprint zero kernel API scaffold | Reviewed (wave 1) | wave 1 | |
| #6 | 2026-09-16 | architecture | test(architecture): enforce sprint zero boundaries | Reviewed (wave 1) | wave 1 | |
| #7 | 2026-09-17 | engine | feat(engine): add sprint zero shared engine | Reviewed (wave 1) | wave 1 | |
| #8 | 2026-09-17 | — | Feat/s0 web shell | Reviewed (wave 1) | wave 1 | |
| #9 | 2026-09-17 | till | feat(till): add Sprint 0 Android till shell | Reviewed (wave 1) | wave 1 | |
| #10 | 2026-09-17 | repo | chore(repo): remove obsolete gitkeep files | Reviewed (wave 1) | wave 1 | |
| #11 | 2026-09-17 | ci | ci: add Sprint 0 GitHub Actions pipeline | Reviewed (wave 1) | wave 1 | |
| #12 | 2026-09-17 | — | Docs/step0 instruction files | Reviewed (wave 1) | wave 1 | |
| #13 | 2026-09-17 | docs | docs: retire HANDOVER.md; raise CR-28A-1 on cost of goods sold | Reviewed (wave 1) | wave 1 | |
| #14 | 2026-09-18 | — | Feat/s0 foundation fixes | Reviewed (wave 1) | wave 1 | |
| #15 | 2026-09-18 | — | Feat/s0 hello module | Reviewed (wave 1) | wave 1 | |
| #16 | 2026-09-18 | feat | feat: S0-12 Complete Hello module end-to-end | Reviewed (wave 1) | wave 1 | |
| #17 | 2026-09-18 | scaffolder | feat(scaffolder): implement new-module scaffolding script (S0-13) | Reviewed (wave 1) | wave 1 | |
| #18 | 2026-09-18 | docs | docs: proposed task plan through M2 with a parallel team plan | Reviewed (wave 1) | wave 1 | |
| #19 | 2026-09-19 | — | Feat/s0 10 local environment | Reviewed (wave 1) | wave 1 | |
| #20 | 2026-09-19 | hello | feat(hello): make the hello module a working template (S0-12) | Reviewed (wave 1) | wave 1 | |
| #21 | 2026-09-19 | — | Feat/s0 13 scaffolder rework | Reviewed (wave 1) | wave 1 | |
| #22 | 2026-09-19 | ci | ci: make every pipeline job do real work and call the make targets (S… | Reviewed (wave 1) | wave 1 | |
| #23 | 2026-09-19 | openapi | feat(openapi): generate server interfaces from the slices and enforce… | Reviewed (wave 1) | wave 1 | |
| #24 | 2026-09-20 | build | fix(build): make the boundary rules and pipeline checks catch what they claim (S0-05) | Reviewed (wave 1) | wave 1 | |
| #25 | 2026-09-20 | kernel | fix(kernel): standard Clock, business date per location, safe message fallback (S0-03) | Reviewed (wave 1) | wave 1 | |
| #26 | 2026-09-20 | deps-dev | chore(deps-dev): bump vite from 5.4.21 to 6.4.3 in /web | Reviewed (wave 1) | wave 1 | |
| #28 | 2026-09-20 | repo | chore(repo): automate dependency updates, code style, scans and team notification | Reviewed (wave 1) | wave 1 | |
| #30 | 2026-09-21 | deps | chore(deps): bump keycloak/keycloak from 25.0.6 to 26.7.4 in /infra/compose | Dependency update (CI) |  | |
| #31 | 2026-09-20 | deps | chore(deps): bump nginx from 1.27-alpine to 1.29-alpine in /infra/compose | Dependency update (CI) |  | |
| #33 | 2026-09-21 | deps | chore(deps): bump eslint-plugin-react-hooks from 5.2.0 to 7.1.1 in /web | Dependency update (CI) |  | |
| #37 | 2026-09-21 | deps | chore(deps): bump @vitejs/plugin-react from 4.7.0 to 5.2.0 in /web | Dependency update (CI) |  | |
| #38 | 2026-09-20 | deps | chore(deps): hold Kotlin on 2.0.x and Node on its LTS line in Dependabot | Dependency update (CI) |  | |
| #43 | 2026-09-20 | deps | chore(deps): bump the backend-minor-and-patch group across 1 directory with 7 updates | Dependency update (CI) |  | |
| #44 | 2026-09-20 | deps-dev | chore(deps-dev): bump vitest from 1.6.1 to 4.1.11 in /web | Reviewed (wave 1) | wave 1 | |
| #45 | 2026-09-20 | build | chore(build): add the version catalogue, ADR-052 and the README entry points (S0-01) | Reviewed (wave 1) | wave 1 | |
| #46 | 2026-09-20 | kernel | fix(kernel): make the three roles real and add the schema test (S0-02, S0-04) | Reviewed (wave 1) | wave 1 | |
| #47 | 2026-09-20 | web | feat(web): sign in with PKCE and read the session from the token (S0-07, step 1) | Reviewed (wave 1) | wave 1 | |
| #48 | 2026-09-20 | web | feat(web): one typed API client for every module (S0-07, step 2) | Reviewed (wave 1) | wave 1 | |
| #49 | 2026-09-20 | web | feat(web): scope banner, module definitions and navigation (S0-07, step 3) | Reviewed (wave 1) | wave 1 | |
| #50 | 2026-09-20 | web | feat(web): design tokens, bundled fonts and the first three components (S0-07, step 4) | Reviewed (wave 1) | wave 1 | |
| #51 | 2026-09-20 | web | test(web): Playwright smoke test of login, scope and hello, and the e2e stage (S0-07, step 5) | Reviewed (wave 1) | wave 1 | |
| #52 | 2026-09-20 | deps | chore(deps): hold Kotlin, KSP and the Android plugin for the till; two small repairs | Dependency update (CI) |  | |
| #55 | 2026-09-21 | deps | chore(deps): move to Spring Boot 3.5 and Spring Modulith 1.4 (CR-17A-2) | Dependency update (CI) |  | |
| #56 | 2026-09-21 | deps | chore(deps): make the Kotlin hold for the till work; move the till off kotlinOptions | Dependency update (CI) |  | |
| #58 | 2026-09-21 | deps | chore(deps): take the Tomcat and PostgreSQL driver security patches ahead of Spring Boot | Dependency update (CI) |  | |
| #60 | 2026-09-21 | ci | fix(ci): keep web/node_modules the caller's, so the nightly fresh clone passes again | Reviewed (wave 1) | wave 1 | |
| #61 | 2026-09-21 | kernel | chore(kernel): prepare the 19A phase: API declarations, catalogue, migration ranges, test recorder | Reviewed (wave 1) | wave 1 | |
| #62 | 2026-09-21 | deps | chore(deps): bump @tanstack/react-query from 5.103.0 to 5.103.1 in /web in the web-minor-and-patch group acros | Dependency update (CI) |  | |
| #63 | 2026-09-24 | docs | docs: six change requests from the preparation of the 19A phase | Reviewed (wave 1) | wave 1 | |
| #65 | 2026-09-21 | — | Feat/m1 01 foundation | Reviewed (wave 1) | wave 1 | |
| #66 | 2026-09-21 | kernel | feat(kernel): implement scoped database sessions | Reviewed (wave 1) | wave 1 | |
| #68 | 2026-09-22 | m1 | feat(m1): add permission role and security seeds | Reviewed (wave 1) | wave 1 | |
| #69 | 2026-09-21 | kernel | fix(kernel): run the scope filter after the request context filter, so the API answers again | Reviewed (wave 1) | wave 1 | |
| #72 | 2026-09-22 | m1 | feat(m1): implement entity lifecycle | Reviewed (wave 1) | wave 1 | |
| #73 | 2026-09-22 | m1 | feat(m1): implement idempotent seed loader and permission catalogue | Reviewed (wave 1) | wave 1 | |
| #74 | 2026-09-22 | — | M1-03: Complete governance infrastructure | Reviewed (wave 1) | wave 1 | |
| #75 | 2026-09-23 | m1 | fix(m1): seeds write the catalogue as the migrator through app_seed; restore the action versions | Reviewed (wave 1) | wave 1 | |
| #76 | 2026-09-23 | m1 | fix(m1): the party directory shows counterparties only; lifecycle commands are the Federation's; the lost tran | Reviewed (wave 1) | wave 1 | |
| #77 | 2026-09-23 | docs | docs: CR-21A-1, four decisions M1-03 took locally that belong to the documents | Reviewed (wave 1) | wave 1 | |
| #78 | 2026-09-23 | kernel | revert(kernel): roll back the kernel services of #74 (K-03, K-04, K-05 are built per 19A) | Reviewed (wave 1) | wave 1 | |
| #79 | 2026-09-23 | m1 | feat(m1): add permission role and security seeds | Reviewed (wave 1) | wave 1 | |
| #81 | 2026-09-23 | m1 | feat(m1): add permission role and security seeds (m1.entity.responsible_officer_invalid) | Reviewed (wave 1) | wave 1 | |
| #82 | 2026-09-23 | m1 | feat(m1): add responsible officer appointment | Reviewed (wave 1) | wave 1 | |
| #83 | 2026-09-24 | kernel | feat(kernel): implement K-04 audit service | Reviewed (wave 1) | wave 1 | |
| #84 | 2026-09-24 | chore | chore: clean up after the pull requests of 22 and 23 September | Reviewed (wave 1) | wave 1 | |
| #85 | 2026-09-24 | chore | chore: make sync for the start of the day; say "make reset" in a pull request when it is needed | Reviewed (wave 1) | wave 1 | |
| #86 | 2026-09-24 | kernel | feat(kernel): make command idempotency transactional | Reviewed (wave 1) | wave 1 | |
| #87 | 2026-09-24 | kernel | feat(kernel): add transactional event outbox and relay | Reviewed (wave 1) | wave 1 | #319 |
| #88 | 2026-09-24 | kernel | fix(kernel): database users on every make up; consumers stop redelivering for ever; payload word list; no user | Reviewed (wave 1) | wave 1 | |
| #89 | 2026-09-24 | kernel | feat(kernel): the five-class RLS policy template and the matrix test that proves it (K-01 close-out) | Reviewed (wave 1) | wave 1 | |
| #90 | 2026-09-24 | m1 | feat(m1): bulk registration of entities from a CSV file with a validation report (M1-11) | Reviewed (wave 1) | wave 1 | |
| #91 | 2026-09-24 | m1 | feat(m1): society register screens on the shell and the three document components behind them (M1-12 part 1) | Reviewed (wave 1) | wave 1 | |
| #92 | 2026-09-24 | ci | ci: answer a pull request in the time of the longest job, not the sum of two | Reviewed (wave 1) | wave 1 | |
| #93 | 2026-09-24 | kernel | feat(kernel): the document base and gapless numbering (K-07) | Reviewed (wave 1) | wave 1 | |
| #94 | 2026-09-25 | kernel | feat(kernel): the job runner and the business day (K-12, K-13) | Reviewed (wave 1) | wave 1 | |
| #95 | 2026-09-25 | kernel | feat(kernel): the configuration register (K-11) | Reviewed (wave 1) | wave 1 | |
| #96 | 2026-09-25 | kernel | feat(kernel): attachment presign and verifier (K-09) | Reviewed (wave 1) | wave 1 | |
| #97 | 2026-09-25 | kernel | feat(kernel): ICU messages, NFC, formats and collation helpers (K-06 part 1) | Reviewed (wave 1) | wave 1 | |
| #98 | 2026-09-25 | kernel | fix(kernel): every own_* policy tests the scope class (CR-17A-3), kept so by a test | Reviewed (wave 1) | wave 1 | |
| #99 | 2026-09-25 | kernel | feat(kernel): notifications delivery (K-10 part 1) | Reviewed (wave 1) | wave 1 | |
| #100 | 2026-09-25 | kernel | feat(kernel): the permission resolver, the interceptor order and separation of duties (K-03b) | Reviewed (wave 1) | wave 1 | |
| #101 | 2026-09-25 | kernel | feat(kernel): the resource server, the claims mapper and the PIN hasher (K-02, first pull request) | Reviewed (wave 1) | wave 1 | |
| #102 | 2026-09-25 | m1 | feat(m1): locations and till positions with their numbering series (M1-05) | Reviewed (wave 1) | wave 1 | |
| #103 | 2026-09-25 | m1 | feat(m1): external grants, scope resolution for EXTERNAL and the expiry job (M1-09) | Reviewed (wave 1) | wave 1 | |
| #104 | 2026-09-25 | m1 | feat(m1): trading relationships with effective-dated terms (M1-04) | Reviewed (wave 1) | wave 1 | |
| #105 | 2026-09-25 | kernel | feat(kernel): every request carries a token, scopes from the records, the provider client (K-02, second pull r | Reviewed (wave 1) | wave 1 | |
| #107 | 2026-09-25 | ci | chore(ci): allow the one-time-password alphabet in the secret scan | Reviewed (wave 1) | wave 1 | |
| #108 | 2026-09-25 | m2 | feat(m2): catalogue scaffold, schema V0001 and reference seeds (M2-01) | Reviewed (wave 1) | wave 1 | |
| #109 | 2026-09-25 | m1 | feat(m1): devices: enrol, assign with counter transfer and drained check, suspend, retire; the revoke event (M | Reviewed (wave 1) | wave 1 | |
| #110 | 2026-09-25 | m1 | feat(m1): roles, assignments and separation-of-duties pairs with guardrails and the template diff (M1-08) | Reviewed (wave 1) | wave 1 | |
| #111 | 2026-09-25 | build | build: the OpenAPI generator tracks its slice as an input | Reviewed (wave 1) | wave 1 | |
| #112 | 2026-09-25 | m1 | feat(m1): users and credentials, PIN policy, deactivation guard (M1-07) | Reviewed (wave 1) | wave 1 | |
| #113 | 2026-09-25 | e2e | test(e2e): the federation view is refused by the permission check, not the handler guard | Reviewed (wave 1) | wave 1 | |
| #114 | 2026-09-25 | kernel | feat(kernel): sync gateway, first part: device auth, enrolment, heartbeat, batch ingest, change log (K-08) | Reviewed (wave 1) | wave 1 | |
| #115 | 2026-09-25 | m2 | feat(m2): implement SKU aggregate and search (M2-02) | Reviewed (wave 1) | wave 1 | |
| #116 | 2026-09-25 | kernel | fix(kernel): the identity and permission findings of the review | Reviewed (wave 3) | `docs/reviews/wave3-kernel.md` | |
| #117 | 2026-09-26 | m2 | feat(m2): implement conversions and barcode registry | Reviewed (wave 1) | wave 1 | |
| #118 | 2026-09-26 | kernel | fix(kernel): the event backbone findings of the review: payload word list, dead letters, relay and inbox | Reviewed (wave 3) | `docs/reviews/wave3-kernel.md` | #319 |
| #119 | 2026-09-26 | kernel | test(kernel): the RLS matrix runs against every real table, with update and delete (review of K-01 close-out) | Reviewed (wave 2) | `docs/reviews/wave2-rls.md` | #268, #278, #281, #283, #284, #285 (area; no finding names this PR) |
| #120 | 2026-09-26 | m2 | fix(m2): the M2-02 findings of the review: Federation guard on the shared path, code collisions, search, SKU_S | Reviewed (wave 3) | `docs/reviews/wave3-m1-m2-m3-m5.md` | |
| #121 | 2026-09-26 | kernel | fix(kernel): identity follow-ups of the review: a location must belong to the entity, test tokens carry no ent | Reviewed (wave 3) | `docs/reviews/wave3-kernel.md` | |
| #122 | 2026-09-26 | ci | ci: a pull request answers in the time of its longest job; parallel integration forks; a narrower scaffolder p | Reviewed (wave 3) | `docs/reviews/wave3-web-ci-demo.md` | #322 |
| #123 | 2026-09-26 | web | fix(web): the web shell findings of the review: the step-up replays the command, server-side search, no pre-se | Reviewed (wave 3) | `docs/reviews/wave3-web-ci-demo.md` | #322 |
| #124 | 2026-09-26 | kernel | fix(kernel): the document and numbering findings of the review: nothing changes an issued document behind the  | Reviewed (wave 3) | `docs/reviews/wave3-kernel.md` | |
| #125 | 2026-09-26 | kernel | fix(kernel): the job runner and business-day findings of the review: locks on timeout and retry, a scheduler p | Reviewed (wave 3) | `docs/reviews/wave3-kernel.md` | #319 |
| #126 | 2026-09-26 | kernel | fix(kernel): the sync gateway and configuration findings of the review: bounded gzip, a replayable enrolment,  | Reviewed (wave 3) | `docs/reviews/wave3-kernel.md` | |
| #127 | 2026-09-26 | kernel | fix(kernel): the notification, attachment and i18n findings of the review: retries every instance can run, upl | Reviewed (wave 3) | `docs/reviews/wave3-kernel.md` | #319 |
| #128 | 2026-09-26 | m1 | fix(m1): the relationship, location and device findings of the review: amendments on the latest row, counters  | Reviewed (wave 3) | `docs/reviews/wave3-m1-m2-m3-m5.md` | |
| #129 | 2026-09-26 | m2 | fix(m2): the catalogue schema findings of the review: child rows follow the SKU's owner, one identity per batc | Reviewed (wave 3) | `docs/reviews/wave3-m1-m2-m3-m5.md` | |
| #130 | 2026-09-26 | m1 | fix(m1): the role, user and grant findings of the review: entity-wide holdings, guards under a lock, deactivat | Reviewed (wave 3) | `docs/reviews/wave3-m1-m2-m3-m5.md` | |
| #131 | 2026-09-26 | m1 | test(m1): the test and documentation findings of the review, and the pipeline's concurrency group and gates | Reviewed (wave 3) | `docs/reviews/wave3-m1-m2-m3-m5.md` | |
| #132 | 2026-09-26 | kernel | feat(kernel): notifications, second part (K-10) | Reviewed (wave 2) | `docs/reviews/wave2-m9-integration.md` | #263 |
| #133 | 2026-09-27 | m1 | docs(m1): freeze the M1 contract, m1party.yaml v1 (M1-13) | Reviewed (wave 2) | `docs/reviews/wave2-m6-m8-m1admin.md` | #264, #277 (area; no finding names this PR) |
| #135 | 2026-09-26 | m2 | feat(m2): batches and suppliers: RegisterBatch, CorrectBatch, the supplier table (M2-05) | Reviewed (wave 3) | `docs/reviews/wave3-m1-m2-m3-m5.md` | |
| #136 | 2026-09-26 | kernel | feat(kernel): sync gateway, second part: snapshots, rate limits, till simulator (K-08) | Reviewed (wave 2) | `docs/reviews/wave2-till-web-kernel.md` | #265, #266, #267 |
| #137 | 2026-09-26 | m2 | feat(m2): SKU images: attach through the kernel's presign, verifier hook, thumbnails, local override (M2-06) | Reviewed (wave 3) | `docs/reviews/wave3-m1-m2-m3-m5.md` | |
| #138 | 2026-09-27 | kernel | fix(kernel): an internal command runs only inside another command; the build refuses a public path to one (CR- | Reviewed (wave 2) | `docs/reviews/wave2-till-web-kernel.md` | #263, #265, #278 (area; no finding names this PR) |
| #139 | 2026-09-27 | kernel | fix(kernel): module-owned objects keep the attachment rules: an upload ledger, scoped keys, no overwrite after | Reviewed (wave 2) | `docs/reviews/wave2-till-web-kernel.md` | #263, #265, #278 (area; no finding names this PR); wave 3: #319 |
| #140 | 2026-09-27 | contracts | chore(contracts): the M1 freeze is enforced, cross-slice checks in the build, doc 33 waits for M9 | Reviewed (wave 3) | `docs/reviews/wave3-web-ci-demo.md` | #322 |
| #141 | 2026-09-27 | 19a | docs(19a): decide the kernel change requests built by convention (CR-18-1, CR-19A-1 to CR-19A-5) | Not reviewed |  | |
| #142 | 2026-09-27 | kernel | feat(kernel): RLS decisions: the Federation named in the database, the policy template's read-only classes, sh | Reviewed (wave 2) | `docs/reviews/wave2-rls.md` | #284 |
| #143 | 2026-09-27 | m1 | feat(m1): M1 decisions: the permission catalogue (CR-21A-1), deletes (CR-21A-3), backdated amendments (CR-21A- | Reviewed (wave 2) | `docs/reviews/wave2-m6-m8-m1admin.md` | #277 |
| #144 | 2026-09-27 | kernel | feat(kernel): identity and permission decisions: external read permissions, the resolved permission set, GET p | Reviewed (wave 2) | `docs/reviews/wave2-till-web-kernel.md` | #265, #278 |
| #145 | 2026-09-27 | kernel | feat(kernel): notification, attachment and business-date decisions: no clear recipients at rest, settle time,  | Reviewed (wave 2) | `docs/reviews/wave2-m9-integration.md` | #263, #265 |
| #146 | 2026-09-27 | m2 | feat(m2): catalogue decisions: the tag key (CR-22A-1), batch correction before M5, suppliers, thumbnails, perm | Reviewed (wave 3) | `docs/reviews/wave3-m1-m2-m3-m5.md` | |
| #147 | 2026-09-27 | kernel | feat(kernel): sync decisions: full snapshot files, fairness, one version floor, device staging | Reviewed (wave 2) | `docs/reviews/wave2-till-web-kernel.md` | #265, #266, #268 |
| #148 | 2026-09-27 | rls | fix(rls): a shop writes only at its own location; M1's Federation policies use kernel.system_entity() | Reviewed (wave 2) | `docs/reviews/wave2-rls.md` | #268, #278, #281, #283, #285 |
| #150 | 2026-09-27 | engine | feat(engine): the pricing engine on the JVM: price resolution, rules, tax, rounding and trade tiers (M3-01, M3 | Reviewed (wave 2) | `docs/reviews/wave2-m5-m3.md` | none: deferred, see wave2-summary |
| #151 | 2026-09-27 | m5 | feat(m5): the inventory schema and the ledger (M5-01, M5-02) | Reviewed (wave 2) | `docs/reviews/wave2-m5-m3.md` | #268, #278, #280, #281, #283 |
| #152 | 2026-09-27 | m4 | feat(m4): trading scaffold: schema, RLS, seeds, the api events and the M3/M2/M5 seams (M4-01) | Reviewed (wave 2) | `docs/reviews/wave2-m4-money.md` | #268, #281 |
| #153 | 2026-09-27 | m3 | feat(m3): trade price lists with tiers and versions, the trade price lookup for M4, and M3's price-list check  | Reviewed (wave 2) | `docs/reviews/wave2-m5-m3.md` | #268, #275 |
| #154 | 2026-09-27 | m5 | feat(m5): the inventory queries, and M5 answers M2's lot questions (M5-04) | Reviewed (wave 2) | `docs/reviews/wave2-m5-m3.md` | #268 |
| #155 | 2026-09-27 | m3 | feat(m3): the trade price list screen: lists, tiered lines, the draft editor and publication (M3-10, part) | Reviewed (wave 3) | `docs/reviews/wave3-m1-m2-m3-m5.md` | |
| #156 | 2026-09-27 | m4 | feat(m4): orders: create, submit from the buyer's series, cancel (M4-02) | Reviewed (wave 2) | `docs/reviews/wave2-m4-money.md` | #276 |
| #157 | 2026-09-27 | m4 | feat(m4): acceptance: the seller accepts or rejects a submitted order (M4-03) | Reviewed (wave 2) | `docs/reviews/wave2-m4-money.md` | #276 |
| #158 | 2026-09-27 | m5 | feat(m5): the GRN and delivery consumers, and opening balances (M5-03, M5-10) | Reviewed (wave 2) | `docs/reviews/wave2-m5-m3.md` | #282 |
| #159 | 2026-09-27 | m4 | feat(m4): delivery notes: draft, issue, dispatch; M3 answers the trade price (M4-04) | Reviewed (wave 2) | `docs/reviews/wave2-m4-money.md` | #276 |
| #160 | 2026-09-27 | m2 | feat(m2): the catalogue screens: browser, SKU view and editor, activate and share (M2-10, part) | Reviewed (wave 3) | `docs/reviews/wave3-m1-m2-m3-m5.md` | |
| #161 | 2026-09-27 | kernel | feat(kernel): the A4 renderer: HTML templates to PDF in the worker, Sinhala and Tamil fonts (K-06b) | Reviewed (wave 2) | `docs/reviews/wave2-till-web-kernel.md` | #265 |
| #162 | 2026-09-27 | m5 | feat(m5): the stock screens: stock position and the opening balance flow (M5-12, part) | Reviewed (wave 2) | `docs/reviews/wave2-m5-m3.md` | none: deferred, see wave2-summary |
| #163 | 2026-09-27 | m4 | feat(m4): goods received note, the pivot: capture, confirm, batches, discrepancy (M4-05) | Reviewed (wave 2) | `docs/reviews/wave2-m4-money.md` | #276, #281, #283 (area; no finding names this PR) |
| #164 | 2026-09-27 | m4 | feat(m4): invoices from the confirmed GRN, and the posting mapper (M4-08) | Reviewed (wave 2) | `docs/reviews/wave2-m4-money.md` | #276, #281, #283 |
| #165 | 2026-09-27 | demo | feat(demo): a demo data loader: the Federation, distributors, societies, shops, users, catalogue, price lists  | Reviewed (wave 3) | `docs/reviews/wave3-web-ci-demo.md` | |
| #167 | 2026-09-27 | m4 | feat(m4): trading screens: requisition book, order desk, delivery note, goods received note (M4-11, part) | Reviewed (wave 2) | `docs/reviews/wave2-m4-money.md` | #276, #281, #283 (area; no finding names this PR) |
| #168 | 2026-09-27 | m8 | feat(m8): the projection base, with the stock position as its sample projection (M8-01) | Reviewed (wave 2) | `docs/reviews/wave2-m6-m8-m1admin.md` | #284 |
| #169 | 2026-09-27 | m8 | feat(m8): the trading projections, trade documents by event and trade lines (M8-04, part) | Reviewed (wave 2) | `docs/reviews/wave2-m6-m8-m1admin.md` | #284 |
| #170 | 2026-09-27 | m5 | feat(m5): transfers between two locations of an entity, issue and receive (M5-09) | Reviewed (wave 2) | `docs/reviews/wave2-m5-m3.md` | #280, #282 |
| #171 | 2026-09-27 | m4 | feat(m4): the invoice screen with its print link, the phase 1 e2e and the demo click path (M4-11) | Reviewed (wave 2) | `docs/reviews/wave2-m4-money.md` | #276, #281, #283 (area; no finding names this PR) |
| #172 | 2026-09-27 | m8 | feat(m8): three reports with CSV and A4 print, and the dashboard (M8-06, M8-07) | Reviewed (wave 2) | `docs/reviews/wave2-m6-m8-m1admin.md` | #284 |
| #173 | 2026-09-27 | demo | docs(demo): how to set up and release the demo on a server | Not reviewed |  | |
| #174 | 2026-09-27 | m8 | feat(m8): the reporting screens, dashboard and report viewer with CSV and print (M8-09, part) | Reviewed (wave 2) | `docs/reviews/wave2-m6-m8-m1admin.md` | #264 |
| #175 | 2026-09-27 | m6 | feat(m6): a till sale reaches central: receipt and session ingestion, and M5's sale deductions | Reviewed (wave 2) | `docs/reviews/wave2-m6-m8-m1admin.md` | #265, #269, #282 |
| #176 | 2026-09-27 | demo | feat(demo): phase 3, the shop: stock to the town shop by transfer, and a till sale without a device | Reviewed (wave 3) | `docs/reviews/wave3-web-ci-demo.md` | |
| #179 | 2026-09-27 | m4 | fix(m4): invoices carry the buyer's VAT number, from M1's counterparty view (CR-21A-6) | Reviewed (wave 2) | `docs/reviews/wave2-m4-money.md` | #276, #281, #283 (area; no finding names this PR) |
| #180 | 2026-09-27 | smoke | test(smoke): make the two-instance idempotency replay check reliable | Reviewed (wave 3) | `docs/reviews/wave3-web-ci-demo.md` | |
| #181 | 2026-09-27 | kernel | fix(kernel): problem titles fill their placeholders | Reviewed (wave 3) | `docs/reviews/wave3-kernel.md` | |
| #182 | 2026-09-27 | repo | chore(repo): progress entries as files, translations per module, the seed test counts itself, CI path filters | Reviewed (wave 3) | `docs/reviews/wave3-web-ci-demo.md` | #322 |
| #183 | 2026-09-27 | web | test(web): the phase 1 trading e2e waits for what it needs | Reviewed (wave 3) | `docs/reviews/wave3-web-ci-demo.md` | #322 |
| #184 | 2026-09-27 | kernel | fix(kernel): a password-grant token counts as a fresh sign-in on the development realm; the demo till sale rea | Reviewed (wave 3) | `docs/reviews/wave3-kernel.md` | |
| #185 | 2026-09-27 | m6 | fix(m6): central tracks a till's receipt numbers and flags a duplicate | Reviewed (wave 2) | `docs/reviews/wave2-m6-m8-m1admin.md` | #265, #269 |
| #186 | 2026-09-27 | demo | fix(demo): users who see stock can list their locations, and a refused list says so | Reviewed (wave 3) | `docs/reviews/wave3-web-ci-demo.md` | |
| #187 | 2026-09-27 | m4 | fix(m4): an invoice line's VAT comes from the item's tax category and the rate in force (22A, 24A) | Reviewed (wave 2) | `docs/reviews/wave2-m4-money.md` | #276, #281, #283 (area; no finding names this PR) |
| #188 | 2026-09-27 | web | fix(web): the demo reads right in every language: party names, item names, Rs, the printed invoice | Reviewed (wave 2) | `docs/reviews/wave2-till-web-kernel.md` | #264, #275 |
| #190 | 2026-09-28 | deps | ci(deps): bump the actions group with 3 updates | Reviewed (wave 3) | `docs/reviews/wave3-web-ci-demo.md` | #317 |
| #191 | 2026-09-28 | deps | chore(deps): bump the backend-minor-and-patch group in /backend with 5 updates | Dependency update (CI) |  | |
| #193 | 2026-09-28 | deps | chore(deps): bump the web-minor-and-patch group in /web with 4 updates | Dependency update (CI) |  | |
| #194 | 2026-09-28 | deps | chore(deps): bump vitest from 4.1.11 to 5.0.1 in /web | Dependency update (CI) |  | |
| #196 | 2026-10-06 | deps | chore(deps): bump eslint from 9.39.5 to 10.11.0 in /web | Dependency update (CI) |  |  |
| #197 | 2026-09-28 | web | fix(web): Sinhala dates use the Gregorian month names; a system batch is not shown by its internal number | Reviewed (wave 2) | `docs/reviews/wave2-till-web-kernel.md` | #264 |
| #198 | 2026-09-28 | m5 | feat(m5): the stock card of an item at a location, read and screen | Reviewed (wave 2) | `docs/reviews/wave2-m5-m3.md` | #277, #280, #282 (area; no finding names this PR) |
| #199 | 2026-09-28 | demo | docs(demo): step-by-step demo flows for every story, who signs in and what to click | Not reviewed |  | |
| #200 | 2026-09-28 | demo | feat(demo): the demo starts with history: orders, deliveries, receipts and invoices at both tiers | Reviewed (wave 3) | `docs/reviews/wave3-web-ci-demo.md` | |
| #201 | 2026-09-28 | m3 | feat(m3): discount rules, author, activate and withdraw (M3-05) | Reviewed (wave 2) | `docs/reviews/wave2-m5-m3.md` | #275 |
| #202 | 2026-09-28 | web | feat(web): polish global back-office UI | Reviewed (wave 2) | `docs/reviews/wave2-till-web-kernel.md` | #264 |
| #204 | 2026-09-28 | infra | style(infra): update keycloak login ui | Reviewed (wave 2) | `docs/reviews/wave2-deploy.md` | #267; wave 3: #322 |
| #205 | 2026-09-28 | web | feat(web): modernize ERP dashboard UI | Reviewed (wave 2) | `docs/reviews/wave2-till-web-kernel.md` | #262, #264; wave 3: #322 |
| #206 | 2026-09-28 | m4 | feat(m4): the buyer prints the invoice too | Reviewed (wave 2) | `docs/reviews/wave2-m4-money.md` | #276, #281, #283 (area; no finding names this PR) |
| #207 | 2026-09-28 | demo | feat(demo): the history spreads over eight weeks | Reviewed (wave 3) | `docs/reviews/wave3-web-ci-demo.md` | |
| #208 | 2026-09-28 | m6 | feat(m6): the shop's receipts on screen, and the demo's till history | Reviewed (wave 2) | `docs/reviews/wave2-m6-m8-m1admin.md` | #269 |
| #209 | 2026-09-28 | m4 | feat(m4): a short delivery is settled, with no double credit | Reviewed (wave 2) | `docs/reviews/wave2-m4-money.md` | #276 |
| #210 | 2026-09-28 | demo | docs(demo): step 10, the short delivery is settled | Not reviewed |  | |
| #211 | 2026-09-28 | cr | docs(cr): CR-30-1, the till runs on Android, Windows and Linux and updates from central | Not reviewed |  | |
| #212 | 2026-09-28 | demo | docs(demo): the flows catch up: buyer prints, short delivery settled, shop receipts on screen | Not reviewed |  | |
| #213 | 2026-09-28 | m1 | feat(m1): administration screens, users, roles and grants | Reviewed (wave 2) | `docs/reviews/wave2-m6-m8-m1admin.md` | #264, #277 |
| #214 | 2026-09-28 | m4 | feat(m4): the buyer pays, and the credit limit warns at order acceptance | Reviewed (wave 2) | `docs/reviews/wave2-m4-money.md` | #276, #281 |
| #215 | 2026-09-28 | demo | docs(demo): administration screens, the buyer pays, and the credit limit | Not reviewed |  | |
| #216 | 2026-09-28 | m3 | test(m3): the pricing HTTP test publishes from tomorrow, so midnight cannot backdate it | Reviewed (wave 2) | `docs/reviews/wave2-m5-m3.md` | #275 (area; no finding names this PR) |
| #217 | 2026-09-28 | m3 | feat(m3): retail prices under control prices and the MRP policy | Reviewed (wave 2) | `docs/reviews/wave2-m5-m3.md` | #268, #275 |
| #218 | 2026-09-28 | m8 | feat(m8): the dashboard comes alive, the exception queue and the demo's reports | Reviewed (wave 2) | `docs/reviews/wave2-m6-m8-m1admin.md` | #284 |
| #219 | 2026-09-28 | ci | ci: integration tests in parallel shards, one Keycloak, lighter PR runs | Reviewed (wave 3) | `docs/reviews/wave3-web-ci-demo.md` | #322 |
| #220 | 2026-09-28 | demo | docs(demo): shelf prices under the gazette, and the dashboard with its exception queue | Not reviewed |  | |
| #221 | 2026-09-28 | m7 | feat(m7): the society's members and credit book, back office | Reviewed (wave 2) | `docs/reviews/wave2-m7-credit.md` | #268, #278, #281, #283 |
| #222 | 2026-09-28 | demo | docs(demo): the society's credit book | Not reviewed |  | |
| #223 | 2026-09-28 | m5 | feat(m5): stock counts, write-offs and repack at the society | Reviewed (wave 2) | `docs/reviews/wave2-m5-m3.md` | #277, #280, #282 |
| #224 | 2026-09-28 | m4 | feat(m4): amend an order, on-account money applied, the receipt printed, and the credit limit set | Reviewed (wave 2) | `docs/reviews/wave2-m4-money.md` | #276 |
| #225 | 2026-09-28 | demo | docs(demo): stock control at the society's stores | Not reviewed |  | |
| #227 | 2026-09-28 | demo | docs(demo): amend an order, money on account, the printed receipt and the credit limit | Not reviewed |  | |
| #228 | 2026-09-28 | ci | ci: two main runs finishing together no longer fail the notify job | Reviewed (wave 3) | `docs/reviews/wave3-web-ci-demo.md` | #322 |
| #229 | 2026-09-29 | m9 | feat(m9): the accounting export and notifications | Reviewed (wave 2) | `docs/reviews/wave2-m9-integration.md` | #263, #281, #283 |
| #230 | 2026-09-29 | m7 | feat(m7): account limits and states, privacy requests, the customer snapshot | Reviewed (wave 2) | `docs/reviews/wave2-m7-credit.md` | #278 |
| #231 | 2026-09-29 | till | feat(till): the desktop till trial, one Kotlin Multiplatform codebase (CR-30-1) | Reviewed (wave 2) | `docs/reviews/wave2-till-web-kernel.md` | #265, #266 |
| #232 | 2026-09-29 | m4 | feat(m4): claims and returns, and transfer requests | Reviewed (wave 2) | `docs/reviews/wave2-m4-money.md` | #268, #276, #281, #282 |
| #234 | 2026-09-29 | demo | docs(demo): accounting export and notifications, account controls and privacy, claims, and asking the stores f | Not reviewed |  | |
| #235 | 2026-09-29 | agents | docs(agents): the till is one Kotlin Multiplatform app (CR-30-1) | Not reviewed |  | |
| #236 | 2026-10-06 | deps | chore(deps): bump the till-minor-and-patch group across 1 directory with 18 updates (three versions held back) | Dependency update (CI) |  |  |
| #237 | 2026-09-29 | demo | docs(demo): who receives which mail, as the walkthrough found | Not reviewed |  | |
| #238 | 2026-09-29 | m6 | fix(m6): a till's session is applied before its receipts | Reviewed (wave 2) | `docs/reviews/wave2-m6-m8-m1admin.md` | #265, #269, #278 |
| #239 | 2026-09-29 | web | fix(web): dates, money and names as the demo walkthrough found them | Reviewed (wave 2) | `docs/reviews/wave2-till-web-kernel.md` | #264, #267 |
| #240 | 2026-09-29 | keycloak | fix(keycloak): enable custom login theme | Reviewed (wave 2) | `docs/reviews/wave2-deploy.md` | #265, #267 (area; no finding names this PR) |
| #241 | 2026-09-29 | fix | fix: the officer's name, quantities at issue, and words in the mails | Reviewed (wave 2) | `docs/reviews/wave2-m9-integration.md` | #263, #281, #283 (area; no finding names this PR) |
| #242 | 2026-09-29 | demo | fix(demo): every demo shop has stock to sell, so the till history fills all four | Reviewed (wave 3) | `docs/reviews/wave3-web-ci-demo.md` | |
| #243 | 2026-09-30 | web | feat(web): polish dashboard and application UI | Reviewed (wave 2) | `docs/reviews/wave2-till-web-kernel.md` | #262, #264, #267; wave 3: #322 |
| #246 | 2026-10-01 | m5 | feat(m5): polish inventory UI pages to match premium styling | Reviewed (wave 2) | `docs/reviews/wave2-m5-m3.md` | #277, #280, #282 (area; no finding names this PR) |
| #248 | 2026-10-01 | deploy | feat(deploy): host the demo on one server, in a 2 vCPU / 4 GB and a 4 vCPU / 8 GB size | Reviewed (wave 2) | `docs/reviews/wave2-deploy.md` | #265, #267 |
| #249 | 2026-10-06 | m2catalogue | feat(m2catalogue): add SKU images display and upload | Reviewed (wave 3) | `docs/reviews/wave3-m1-m2-m3-m5.md` | #320 |
| #251 | 2026-10-06 | deps | chore(deps): bump keycloak/keycloak from 26.7.4 to 26.8.0 in /infra/compose | Dependency update (CI) |  |  |
| #253 | 2026-10-06 | deps | chore(deps): bump the backend-minor-and-patch group across 1 directory with 4 updates | Dependency update (CI) |  |  |
| #257 | 2026-10-06 | deps | chore(deps): bump source-map-js from 1.2.1 to 1.2.2 in /web | Dependency update (CI) |  |  |
| #259 | 2026-10-06 | reviews | docs(reviews): a code review tracker, one row per merged pull request | Docs (wave 2) |  |  |
| #260 | 2026-10-06 | reviews | docs(reviews): wave 2 findings, verified, with a second review of every proposed fix | Docs (wave 2) | `docs/reviews/wave2-*.md` |  |
| #261 | 2026-10-06 | reviews | docs(reviews): wave 2 decisions, change requests and the fix plan | Docs (wave 2) | `docs/reviews/wave2-fix-plan.md` |  |
| #262 | 2026-10-06 | web | fix(web): initials instead of ui-avatars, safer server-file tabs, key rotation on problems only; resolve the kernel migration README | Reviewed (wave 3) | `docs/reviews/wave3-web-ci-demo.md` | #322 |
| #263 | 2026-10-06 | kernel | fix(kernel): keyed recipient hash and the recipient on the log, quiet hours defer, provider errors out of the log | Reviewed (wave 3) | `docs/reviews/wave3-kernel.md` |  |
| #264 | 2026-10-06 | web | fix(web): shell guards over shell and modules, one business today, step-up allow-list, M8 route guard | Reviewed (wave 3) | `docs/reviews/wave3-web-ci-demo.md` | #322 |
| #265 | 2026-10-06 | kernel | fix(kernel): validate till payloads at the gateway, bound every sync body, resolve quarantines, accept facts below the floor, fail-closed defaults | Reviewed (wave 3) | `docs/reviews/wave3-kernel.md` |  |
| #266 | 2026-10-06 | till | fix(till): trust only what central signed and verify continuity, keep the outbox and the outcomes, persist the lock-out, the clock, the key file, gzip | Reviewed (wave 3) | `docs/reviews/wave3-till-m6.md` | #323 |
| #267 | 2026-10-06 | deploy | fix(deploy): deploy in lockstep with the clone, a restore that works, Keycloak behind the realm paths, hardening, pinned actions, a sign-in page in three languages | Reviewed (wave 3) | `docs/reviews/wave3-web-ci-demo.md` | #317 |
| #268 | 2026-10-06 | rls | fix(rls): cross-tenant functions know the caller, only the Federation writes a ceiling, definer hygiene, the open-for-write helper | Reviewed (wave 3) | `docs/reviews/wave3-kernel.md` |  |
| #269 | 2026-10-06 | m6 | fix(m6): receipt flags, the replay check, the series guard, paged receipts and sessions with a flagged filter | Reviewed (wave 3) | `docs/reviews/wave3-till-m6.md` | #323 |
| #270 | 2026-10-06 | deps | chore(deps): bump axllent/mailpit from v1.31.1 to v1.31.4 in /infra/deploy | Dependency update (CI) |  |  |
| #271 | 2026-10-06 | deps | chore(deps): bump keycloak/keycloak from 26.7.4 to 26.8.0 in /infra/deploy | Dependency update (CI) |  |  |
| #272 | 2026-10-06 | deps | chore(deps): bump axllent/mailpit from v1.31.1 to v1.31.4 in /infra/compose | Dependency update (CI) |  |  |
| #273 | 2026-10-06 | deps | ci(deps): bump the actions group across 1 directory with 2 updates | Dependency update (CI) |  |  |
| #274 | 2026-10-06 | deps | chore(deps): bump the web-minor-and-patch group across 1 directory with 4 updates | Dependency update (CI) |  |  |
| #275 | 2026-10-06 | m3 | fix(m3): a markdown on the right batch, no free goods through a price, a bounded backdate, a locked publish, pinned test clocks | Reviewed (wave 3) | `docs/reviews/wave3-m1-m2-m3-m5.md` | #320 |
| #276 | 2026-10-06 | m4 | fix(m4): credit an invoice line once across settlement, claim and credit note; apply a credit note up to what is due; one advisory lock per order | Reviewed (wave 3) | `docs/reviews/wave3-m4.md` |  |
| #277 | 2026-10-06 | m1 | fix(m1): who resets whose credentials, the last user manager, limits within the grantor's, one segregation pair, the limit gated at opening | Reviewed (wave 3) | `docs/reviews/wave3-m1-m2-m3-m5.md` | #320 |
| #278 | 2026-10-06 | m7 | fix(m7): a keyed NIC hash, member identity for the society only, erasure that keeps the books, the credit book's states and credits | Reviewed (wave 3) | `docs/reviews/wave3-m7-m8-m9.md` | #317, #319 |
| #280 | 2026-10-06 | m5 | fix(m5): a transfer in at its own cost, one crossing per posting, the repack reversal at the repack's cost | Reviewed (wave 3) | `docs/reviews/wave3-m1-m2-m3-m5.md` | #320 |
| #281 | 2026-10-06 | m4 | fix(m4): settle from any buyer that traded, each cheque once, the buyer's GRN posting, exposure per order line, extension rows only until issue | Reviewed (wave 3) | `docs/reviews/wave3-m4.md` |  |
| #282 | 2026-10-06 | m5 | fix(m5): expiry-aware stock, approval limits that fail closed, counting at the counted moment, the cost rules, locks and flags | Reviewed (wave 3) | `docs/reviews/wave3-m1-m2-m3-m5.md` | #320 |
| #283 | 2026-10-06 | m9 | feat(m9): the stored export file, provisional periods and the supplement signal, the contact door, SMTP auth; the buyer's invoice postings by counterparty delivery | Reviewed (wave 3) | `docs/reviews/wave3-m7-m8-m9.md` | #317, #319 |
| #284 | 2026-10-06 | m8 | fix(m8): projections per shop and per event, sales by item from real tills, the dashboard cache key, exposure from the current limit, bounded and audited exports | Reviewed (wave 3) | `docs/reviews/wave3-m7-m8-m9.md` |  |
| #285 | 2026-10-06 | rls | fix(rls): the last two definer functions test the scope class, and matrices that keep it so | Reviewed (wave 3) | `docs/reviews/wave3-kernel.md` |  |
| #286 | 2026-10-07 | reviews | docs(reviews): wave 2 closed out: tracker, demo flows and what was fixed | Docs (wave 2) |  |  |
| #287 | 2026-10-07 | demo | fix(demo): the society office can open the write-off list it witnesses | Reviewed (wave 3) | `docs/reviews/wave3-web-ci-demo.md` |  |
| #289 | 2026-10-07 | deps | chore(deps): hold the packages that wait for AGP 9 and the Node LTS line | Dependency update (CI) |  |  |
| #258 | 2026-10-07 | m4 | feat(m4): add debit notes | Reviewed (wave 3) | `docs/reviews/wave3-m4.md` | #318, #322 |
| #290 | 2026-10-07 | deps | chore(deps): bump gradle-wrapper from 8.14.4 to 8.14.5 in /till in the till-minor-and-patch group | Dependency update (CI) |  |  |
| #293 | 2026-10-07 | web | chore(web): one lockfile (pnpm) and @types/node 24.19.1 | Reviewed (wave 3) | `docs/reviews/wave3-web-ci-demo.md` |  |
| #294 | 2026-10-07 | docs | docs: the production go-live task list, with load testing and security certification | Docs (no code review) |  |  |
| #295 | 2026-10-07 | docs | docs: take the go-live task list out of the repository | Docs (no code review) |  |  |
| #296 | 2026-10-07 | m4 | feat(m4): add back-order visibility | Reviewed (wave 3) | `docs/reviews/wave3-m4.md` |  |
| #297 | 2026-10-08 | m4 | feat(m4): implement AmendOrder business rules and history tracking | Reviewed (wave 3) | `docs/reviews/wave3-m4.md` | #318 |
| #299 | 2026-10-08 | m4 | test(m4): add cross-feature invoice balance regression | Reviewed (wave 3) | `docs/reviews/wave3-m4.md` |  |
| #300 | 2026-10-08 | m4 | fix(m4): include debit notes in invoice settlement | Reviewed (wave 3) | `docs/reviews/wave3-m4.md` | #318 |
| #301 | 2026-10-08 | m4 | fix(m4): allow supervisor to manage pos session | Reviewed (wave 3) | `docs/reviews/wave3-till-m6.md` | #323 |
| #302 | 2026-10-08 | build | fix(fix-03): keep generated module docs clean after tests | Reviewed (wave 3) | `docs/reviews/wave3-web-ci-demo.md` | #322 |
| #303 | 2026-10-08 | m4 | fix(m4): remove duplicate ORDER_AMENDED audit seed | Reviewed (wave 3) | `docs/reviews/wave3-m4.md` |  |
| #304 | 2026-10-08 | m4 | feat(m4): add TILL-PLAT-01 till roles and permissions | Reviewed (wave 3) | `docs/reviews/wave3-till-m6.md` | #323 |
| #305 | 2026-10-08 | m4 | fix(m4): prevent order cancellation after lock | Reviewed (wave 3) | `docs/reviews/wave3-m4.md` |  |
| #307 | 2026-10-09 | kernel | fix(kernel): FIX-04 set default till.idle_lock to 2 minutes | Reviewed (wave 3) | `docs/reviews/wave3-kernel.md` |  |
| #308 | 2026-10-09 | kernel | fix(fix-06): handle numbering gaps explained by sync quarantine | Reviewed (wave 3) | `docs/reviews/wave3-kernel.md` | #319 |
| #309 | 2026-10-09 | web | fix(fix-12): detect and localise single-word JSX literals | Reviewed (wave 3) | `docs/reviews/wave3-web-ci-demo.md` | #322 |
| #310 | 2026-10-09 | m4 | fix(m4): mask party invoice sensitive fields | Reviewed (wave 3) | `docs/reviews/wave3-m4.md` | #318 |
| #311 | 2026-10-09 | m4 | fix(m4): FIX-09 enforce single sync version floor for device assignment | Reviewed (wave 3) | `docs/reviews/wave3-kernel.md` | #319 |
| #312 | 2026-10-09 | hello | fix(fix-13): add external view policy to hello scaffold | Reviewed (wave 3) | `docs/reviews/wave3-kernel.md` |  |
| #316 | 2026-10-09 | reviews | docs(reviews): wave 3 findings, verified, and the tracker | Docs (no code review) |  |  |
| #317 | 2026-10-09 | deploy | fix(deploy): the kit supplies the NIC pepper and keeps the rendered realm private | Review fix (not re-reviewed) |  |  |
| #318 | 2026-10-09 | m4 | fix(m4): amending an order is permitted again, buyers see invoice lines, debit-note line guards | Review fix (not re-reviewed) |  |  |
| #319 | 2026-10-09 | kernel | fix(kernel): small review findings in identity, sync, jobs and the counterparty path | Review fix (not re-reviewed) |  |  |
| #320 | 2026-10-09 | m5 | fix(m5): small review findings across M1, M2, M3 and M5 | Review fix (not re-reviewed) |  |  |
| #322 | 2026-10-09 | web | fix(web): the step-up replays only for the same user and scope; CI gates and the nightly run | Review fix (not re-reviewed) |  |  |
| #323 | 2026-10-09 | till | fix(till): wrong-PIN count per operator, the business-date move audited, society admins can assign till roles | Review fix (not re-reviewed) |  |  |
| #325 | 2026-10-09 | build | fix(build): the unit test task runs on a 1 GB heap | Not reviewed |  |  |
