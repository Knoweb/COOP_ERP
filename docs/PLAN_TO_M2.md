# PLAN_TO_M2.md — tasks from today to the end of the first two modules, and how to run them in parallel

Proposed on 18 September 2026; statuses refreshed on 19 September against `main` at `82f6ceb` (pull requests 14 to 17 merged). Built from the state in `docs/PROGRESS.md`, the ticket tables of 17A §14, 19A §14, 21A §10 and 22A §10, and the gates of doc 20 §6. It is a planning list for the architect to adopt, cut or reorder; it does not change the build order fixed in `AGENTS.md`. Estimates are the guides' own figures in developer-days and are there for sizing, not for a calendar. Each task has a prerequisite and a "done when"; `docs/PROGRESS.md` stays the record of what actually happened.

Part A is the work, in phases: **0** close the open ends of this week · **1** finish 17A · **2** build 19A · **3** the module gate · **4** M1 · **5** M2 · **6** documents and programme items that run alongside · **7** decisions only the architect can take. Part B, placed before Phase 7, is how to run Part A in parallel with a larger team.

## Phase 0 — open ends to close now

| # | Task | Prerequisite | Done when |
|---|---|---|---|
| 0.1 | Done: `feat/s0-foundation-fixes` was squash-merged as pull request 14. Remaining: bring `docs/PROGRESS.md` up to date for pull requests 14 to 17 (the compose stack, the hello module and the scaffolder were merged without a progress entry) | – | PROGRESS.md Done and Next match `main` |
| 0.2 | Restart the build machine, start Docker Desktop once, confirm `docker compose version` and a `hello-world` container run | Reboot (pending from the WSL feature enable) | Docker engine answers; noted in PROGRESS.md |
| 0.3 | Free C: to at least 10 GB (browser caches, JetBrains caches, the unused Temurin 26 and OpenJDK 27 in `~/.jdks` after IntelliJ's project SDK is moved to JDK 21) | – | Gradle, pnpm and Docker no longer fail on space |
| 0.4 | Decide CR-00_Start_Here-1 and CR-28A-1; if accepted, re-issue the documents from a Markdown source | Architect | CRs closed in `docs/change-requests/`, register bumped |
| 0.5 | Record the build-machine toolchain (JDK 21, make 4.4.1, Node 24 LTS, pnpm 12, Docker on D:) in PROGRESS.md and close Checkpoint 1 decision (3) | 0.2 | PROGRESS.md Next list no longer carries the toolchain decision |

## Phase 1 — finish Sprint 0, 17A part (tickets S0-01 to S0-13)

Status is from the 18 September review recorded in PROGRESS.md, refreshed on 19 September from what is on `main`. "Merged, unverified" means the code is on `main` but its done criterion has not been run here, because the build machine cannot start Docker until it is restarted. "Left" is what still has to be built to meet the guide's done criterion.

| Ticket | Guide scope | Status | Left to do | Est. | Done when (17A) |
|---|---|---|---|---|---|
| S0-01 | Repository, Makefile, version catalogue, README skeleton | **done** (close-out 20 Sep: version catalogue, ADR-052, README entry points; `docs/PROGRESS.md`) | Nothing | 0.5 | `make up` prints URLs on an empty stack |
| S0-02 | Backend Gradle project, Boot app, three profiles, health and metrics | **done** (close-out 20 Sep: the roles now activate profiles, metrics and probes exposed; `docs/PROGRESS.md`) | Nothing | 1 | App starts under each profile |
| S0-03 | Kernel package with `api` interfaces and stubs (§4.3) | **done** (close-out 20 Sep: standard `Clock`, `BusinessDate.current(locationId)`, message fallback, stub guard; `docs/PROGRESS.md`) | Nothing | 1.5 | Hello handler compiles against them |
| S0-04 | Flyway per module, baseline migration, roles, collations, RLS helpers | **done** (close-out 20 Sep: the schema test exists; `docs/PROGRESS.md`) | Decide with doc 35 whether migrations run from every instance or as a deployment step | 1 | Fresh DB migrates to baseline; schema list matches |
| S0-05 | ArchitectureTests and pipeline scripts (schema ownership, i18n completeness) | **done** (close-out 20 Sep: both scripts rewritten and tested, four rules added; `docs/PROGRESS.md`) | Nothing | 1 | A deliberate violation fails the build |
| S0-06 | OpenAPI-first toolchain: generator config, `hello.yaml`, controller stub, `gen-clients` | done on `feat/s0-06-openapi-server-generation` (19 September) | – | 1 | Client generated; stale check works |
| S0-07 | Web shell: PKCE auth, scope context and banner, module registration, i18n loader, design tokens, component seeds | in progress: steps 1 and 2 of 5 (login; API client) done 20 Sep; `docs/PROGRESS.md` | Verify each item against 17A §9 and doc 30; fix gaps | 3 | Login → banner → hello page in three languages |
| S0-08 | Till shell: project, Compose skeleton, Room mirror tables, fonts, hello screen, simulator peripherals | partly | Room mirror tables, fonts, hello screen against 17A §10; JVM tests | 3 | APK builds; hello screen on emulator; JVM tests |
| S0-09 | Shared engine: interfaces, stubs, tests, published artefact | partly | Publish the artefact so the till consumes it; tests for every interface | 1 | Consumed by backend and till |
| S0-10 | Local compose: PostgreSQL, PgBouncer, RabbitMQ, MinIO, Keycloak, Mailpit; dev realm; seeds | done on `feat/s0-10-local-environment` (19 September), verified from an empty state | Seeds for the permission catalogue arrive with 19A and 21A | 1.5 | `make up` and `make up-2` pass |
| S0-11 | CI pipeline; nightly fresh-clone test | done on `feat/s0-11-pipeline` (19 September), to be confirmed by its first run on GitHub and one nightly | Playwright and emulator smoke (after S0-07, S0-08); publish (after a registry is chosen); coverage floors; Java format check | 1 | Green on `main`; nightly fresh-clone test |
| S0-12 | Hello module end to end: the proof table of 17A §12 | done on `feat/s0-12-hello-module` (19 September) except three proof rows | Till hello screen; till-side shared-engine parity test; two-instance Playwright run (needs S0-07) | 1.5 | All rows of the proof table pass |
| S0-13 | `make new-module` | done on `feat/s0-13-scaffolder-rework` (19 September): Node tool, proved by `make test-scaffold` | Scaffold the till screen once hello has one | 0.5 | Copies hello with renames; boundary tests still pass |

Order inside the phase: S0-01 and S0-10 first (everything else is verified through them), then S0-03/S0-05/S0-06 on the backend, S0-07/S0-08/S0-09 on the shells, S0-12 last, S0-13 once S0-12 is green. Phase exit: the fifteen-minute start of 17A §13 works on a fresh clone.

## Phase 2 — Sprint 0, 19A part (tickets K-01 to K-13)

Each ticket replaces a 17A stub and must keep the hello module green. Order follows the guide; the "needed by" column shows which module tickets block on it, so the platform pair can prioritise if a third developer joins (19A §14 suggests one for K-05, K-07 and K-08).

| Ticket | Scope | Est. | Done when (19A) | Needed by |
|---|---|---|---|---|
| K-01 | Scope filter, connection customizer, granted entities, PARTY policy template, masking view convention | 1.5 | RLS matrix incl. PARTY and NONE passes | every module migration |
| K-02 | JWT claims mapping, provider mapper endpoint, identity-provider admin wrapper with scope rule, step-up interceptor, device auth, PIN hasher | 3 | Hello logs in as each principal; step-up round trip in the web shell | M1-06, M1-07 |
| K-03 | Permission resolver and cache, command interceptor order, SoD helpers, till snapshot signer | 2 | Interceptor tests; snapshot verifiable on the till | M1-02, M1-08 |
| K-04 | Audit tables, facade, correlation, exception queue query, partition job, privilege test | 1.5 | One row per command; UPDATE denied | every handler |
| K-05 | Outbox writer, relay, broker adapter, consumer framework with inbox, dead-letter, replayer | 3 | Two-instance exactly-once test; DLQ; replay | M1-06, M2-05, M2-09 |
| K-06 | ICU messages with fallback, NFC converter, collation and search helpers, formats, A4 renderer worker | 2.5 | Golden trilingual PDF; sort test set | M2-02 search, M2-08 |
| K-07 | Document tables and DDL, immutability trigger, issuance protocol, numbering service, links, gap-check job, type seed | 3 | Concurrent issuance dense; immutability; link rules | M1-05 |
| K-08 | Sync API, enrolment, batch ingestor with quarantine, snapshot builder and contributors, change-log fan-out, heartbeat, rate limiter, till simulator | 5 | Doc 32 §11 conformance suite green | M1-06, M1-10, M2-09 |
| K-09 | Attachment presign and verifier | 0.5 | PENDING → COMPLETE on upload | M2-06 |
| K-10 | Notification dispatcher, log, retry sweep, channel interface | 1.5 | Once per event; suppression | – (later modules) |
| K-11 | Config item and value tables, resolver, cache, setter with audit, seed loader | 1.5 | Precedence and schema tests | M1-02, M2-01 |
| K-12 | Scheduled job tables, runner with ShedLock, registry, day-close trigger, phase-1 job seed | 1.5 | One run across two instances | M1-09, M2-06, M2-09 |
| K-13 | Clock, offset correction, business date service | 0.5 | Business date from day-close | M1-05 |

Critical path to M1: K-01, K-04, K-11, K-03, K-02, K-07. K-08 is the longest ticket and gates M1-10 and M2-09 only, so it can run in parallel with early M1 work if the team has the people. Phase exit: 19A "what done looks like" (§1) holds and the doc 32 conformance suite passes against the till simulator.

## Phase 3 — the module gate

| # | Task | Done when |
|---|---|---|
| 3.1 | Hello module integration tests pass against real kernel services (scoped user cannot read outside scope, command interceptor order, audit row per command, outbox relay) | `make test-int` green with no stub on the path |
| 3.2 | PROGRESS.md records Sprint 0 done, with every deviation from 17A and 19A listed and justified | Architect reads it and gives the go for M1 |
| 3.3 | Team allocation for the module stage (doc 10 P-03): who is the platform pair, who builds M1, who builds M2, who owns the till track | Names in PROGRESS.md |

No M1 work starts before 3.2 is signed off (AGENTS.md).

## Phase 4 — M1 Party, Tenancy and Security (21A §10, tickets M1-01 to M1-13)

Prerequisite: Sprint 0 complete. One full-stack developer, about 19.5 days. The contract freeze (M1-13) is targeted for the end of the second week so M2 can start against the `api` package while screens finish.

| Ticket | Scope | Est. | Done when (21A) |
|---|---|---|---|
| M1-01 | Scaffold from hello (`make new-module`); party and security migrations with RLS and grants; schema-ownership check | 1.5 | Migrations apply; RLS matrix skeleton runs |
| M1-02 | Seed files: permission catalogue, role templates, SoD pairs, config items; SeedLoader wiring | 1 | Catalogue visible via ResolvePermissions |
| M1-03 | Entities: aggregate, repository, four handlers, queries, controller, tests | 1.5 | Register → activate flow |
| M1-04 | Relationships: aggregate, four handlers incl. AmendTerms, M3 query stub, exclusion tests | 2 | Scenarios 6.2 and 6.4 |
| M1-05 | Locations and positions: handlers, NumberingService hooks, primary till | 1.5 | Series registered on position create |
| M1-06 | Devices: enrol, assign with counter transfer and drained check, suspend and retire; gateway revoke event | 1.5 | Scenario 6.6 |
| M1-07 | Users and credentials: handlers with the provider wrapper; PIN policy; deactivation guard | 2 | Create, reset, deactivate against the dev realm |
| M1-08 | Roles, assignments, SoD pairs: handlers with guardrails; template diff; property tests | 2 | Scenario 6.3; property tests |
| M1-09 | External grants; scope resolution for EXTERNAL; expiry job registration | 0.5 | Scenario 6.5 |
| M1-10 | Snapshot contributors and change-log producers; till snapshot verified by the till simulator | 1 | Operator snapshot round trip |
| M1-11 | BulkRegister with validation report; performance test | 1.5 | 40 shops under 60 s |
| M1-12 | Web screens (nine) on the shell; Playwright flows | 3 | Both Playwright flows green |
| M1-13 | Contract freeze: publish `m1party.yaml` v1, `api` package review with the M2 developer, doc 33 slice registered | 0.5 | M2 acknowledges |

Before M1-01, confirm or accept the 21A §11 assumptions and cite them in code: entity activation prerequisites (doc 21 DR-6, Federation admins), direct Federation→MPCS trading flag default off (DR-3), external grant maximum 12 months (DR-4), returning staff as a new user with `succeeds_user_id` (DR-5), responsible officer fields and `sod_pair` per doc 18 (DR-1, DR-2), the RLS variant for MPCS admins reading shop users, and the M3 price-list stub. Add `m1party/README.md` as the living guide from M1-01 on.

## Phase 5 — M2 Catalogue and Batch (22A §10, tickets M2-01 to M2-11)

Prerequisite: Sprint 0 and M1-13 (M1's `api` and `query` packages). One full-stack developer, about 22 days. Freeze the contract after M2-05 so M3 and M5 can design against `api` while the steward tooling and screens finish. M3's price-list query and M5's lot query are stubs until those modules land.

| Ticket | Scope | Est. | Done when (22A) |
|---|---|---|---|
| M2-01 | Scaffold; migration V0001 with RLS and partitioned batch; seeds (uom, tax, tags, permissions); schema-ownership check | 1.5 | Migrations and seeds apply |
| M2-02 | SKU aggregate: create, activate (local and shared), update, deactivate and reactivate; code generator; search and get queries | 2.5 | Search in three scripts works on fixtures |
| M2-03 | Units and conversions; tags; tax categories and rates with `apply_from` | 1.5 | Exclusion constraints tested |
| M2-04 | Barcodes: registry, uniqueness rules, GtinParser, lookup incl. GTIN plus lot batch resolution | 2 | Lookup contract tests |
| M2-05 | Batch: RegisterBatch internal command (existing-or-create, synthetic), corrections with replacement batch; supplier table | 2 | Correction chain; M5 stub consumes `batch.corrected` |
| M2-06 | Images: attach with presign, verifier hook, ThumbnailJob, local override | 1.5 | Thumbnail produced and keyed |
| M2-07 | Promotion and merge with alias; barcode migration; M5 stub re-point | 1.5 | Flow 6.3 |
| M2-08 | Steward: import service, row validator, transliterator, duplicate scorer, suspects, translation dashboard | 3.5 | Flows 6.1 and 6.2; data-quality targets |
| M2-09 | Assortment maintainer consumers and reconcile job; snapshot contributor; change-log producers | 2.5 | Snapshot tests; reconcile finds drift |
| M2-10 | Web screens (eight) and Playwright flows | 3 | Both flows green |
| M2-11 | Contract freeze: `m2catalogue.yaml` v1; `api`/`query` review with the M3 and M5 developers; doc 33 slice | 0.5 | M3 acknowledges |

Before M2-01, confirm or accept the 22A §11 assumptions: deactivation sell-through on (DR-3), duplicate threshold 0.85 with transliteration v1 in the repository (DR-4), local image override allowed (DR-5), label content code plus weight/batch (DR-6), brand list seeded from the import sheet. M2-08 needs the catalogue data steward named (doc 10 P-01) and an import sheet to test against.

## Phase 6 — documents and programme items alongside the build

| # | Task | Needed by | Source |
|---|---|---|---|
| 6.1 | Choose and script the Markdown-to-PDF render (docs/README.md procedure) | First document re-issue (0.4) | PROGRESS.md Deviations |
| 6.2 | Create doc 33 (API bundle) as a Markdown source that collects the frozen slices; register it | M1-13 | Doc 20 §6 week 7; 21A M1-13 |
| 6.3 | Add ADR-37 to ADR-51 to doc 09; mirror them in `docs/adr/` | Before M1 review | PROGRESS.md Deviations |
| 6.4 | Design terms into glossary doc 12 | Before M1 review | PROGRESS.md Deviations |
| 6.5 | Name the catalogue data steward and the cleansing owner (P-01); obtain the import sheet | M2-08 | Doc 10 P-01; doc 16 T-03 |
| 6.6 | Re-plan doc 20 with the measured pace of Sprint 0 and M1 (P-04) | After M1-13 | Doc 10 P-04 |
| 6.7 | Till track: spike 1 (local DB, counter, power loss) and the doc 32 conformance suite against the simulator | K-08 | Doc 20 §6 Track B |
| 6.8 | Resolve the register's two missing supporting documents (numbering RFC, tax-advisor briefing): locate or strike | Register housekeeping | PROGRESS.md Deviations |
| 6.9 | Update `docs/PROGRESS.md` after every ticket; module READMEs from M1-01 and M2-01 on | Continuous | AGENTS.md |

## Part B — running the plan in parallel with a larger team

Part A (phases 0 to 6) is the work. Part B is how to spread it over more people without breaking the rules in `AGENTS.md`. It is written in waves with an entry condition and a "done when", not in dates. Ticket ids are those of Part A.

### B.1 What limits parallel work in this repository

1. **The environment comes first.** Nobody new is productive until `make up` works (S0-01, S0-10) and CI is a required check (S0-11). Adding people before that blocks all of them on the same missing stack.
2. **The kernel has one owner.** `kernel/` is changed only by the platform group, and every module compiles against `kernel.api`. That package must stop moving (S0-03) before module developers multiply.
3. **Review capacity.** Three people know the code today. Ten graduates will open more pull requests than three people can review, so review has to be organised (B.5), and joiners have to arrive in waves, not at once.
4. **Shared files.** A few files are touched by almost every ticket (B.4). Left alone they turn parallel work into merge conflicts.

### B.2 Team shape: ten developers plus the architect

Roles, not names; the architect assigns people (doc 10 P-03). The two developers who built the skeleton are the only ones who know it, so they stop working as one pair and each lead half of the platform group.

| Stream | People | Owns (paths) | Work |
|---|---|---|---|
| Platform | 4: the two existing developers as leads, two joiners | `backend/app/.../kernel/`, `db/migration/kernel/`, `ArchitectureTests`, `backend/shared-engine/` | S0-03, S0-04, S0-05, S0-06, S0-09, S0-12, S0-13; then K-01 to K-13 in four lanes |
| Environment and CI | 1 | `Makefile`, `infra/`, `.github/`, Gradle build files, version catalogue | S0-01, S0-10, S0-11; integration and e2e jobs; later Playwright infrastructure and release packaging |
| Web | 2 | `web/` | S0-07; then M1-12 and M2-10 screens, built against the OpenAPI slice while the handlers are still being written |
| Till | 1 | `till/` | S0-08; consuming the shared engine; till spike 1; the till simulator and conformance suite with platform lane D |
| Modules | 2 joiners, later joined by platform developers rolling over | `m1party/`, `m2catalogue/` and their migrations, slices, seeds | Contract-first preparation in wave 1; M1 and M2 tickets after the gate |

With eight developers: one web developer and one module joiner fewer; M2 starts only when platform developers roll over. With twelve: a second till developer and a test-automation developer who owns the RLS matrix, property tests and Playwright flows across modules.

### B.3 The waves

**Wave 0 — make the repository ready for a crowd.** People: the three who are here today. Entry: now.

| # | Task | Done when |
|---|---|---|
| W0.1 | Phase 0 of Part A (merge the fixes branch, Docker, disk space, change requests) | Phase 0 closed in PROGRESS.md |
| W0.2 | S0-01 and S0-10 finished and verified: version catalogue, README start, compose stack with the dev realm and seeds | `make up` prints the URLs on a second machine from a fresh clone |
| W0.3 | S0-11 as a required check; branch protection on `main` (squash merge, CI green, one approving review) | A failing PR cannot be merged |
| W0.4 | `CODEOWNERS`: `kernel/**` and `ArchitectureTests` to the platform leads; every `*/api/**`, `openapi/**` and `db/migration/**` to the architect; build files and `infra/**` to the environment developer | GitHub requests the right reviewer automatically |
| W0.5 | The collision rules of B.4 applied (union merge for PROGRESS.md, migration number ranges, i18n key convention) | Rules written into `docs/README.md` |
| W0.6 | `docs/ONBOARDING.md`: machine specification (16 GB RAM, 40 GB free disk for Docker images, Gradle and Android SDK), toolchain versions, the first-day exercise of wave 1 | A joiner can follow it without asking |

**Wave 1 — finish 17A in four streams.** Joiners: two platform, one environment, two web, one till. Entry: W0.2 and W0.3 done. Every joiner starts with the same first-day exercise: the fifteen-minute start on their own machine, then break one architecture rule on purpose and watch the build fail.

| Stream | Tickets | Waits on |
|---|---|---|
| Platform | S0-03, S0-04 leftover, S0-05, S0-06, then S0-12 and S0-13 | S0-12 waits on the web and till shells |
| Environment | S0-11 remainder: integration-test job, nightly fresh clone, Android build | – |
| Web | S0-07 verified against 17A §9 and doc 30 | compose stack for the PKCE login |
| Till | S0-08, S0-09 consumption | – |
| Modules (two joiners, arriving in the second half) | Reading: `AGENTS.md`, 17A, their own nA guide. Then work that needs no kernel: the complete `m1party.yaml` and `m2catalogue.yaml` from 21A §5 and 22A §5, seed files (permission catalogue, role templates, units, tax, tags), test fixtures, message ids in three languages | – |

Done when: the fifteen-minute start of 17A §13 passes on a fresh clone and every row of the hello proof table passes (S0-12). `kernel.api` is declared stable; from here a change to it needs the architect to review it and a line in PROGRESS.md.

**Wave 2 — the kernel in four lanes.** One platform developer per lane. Lanes share no package and no migration file. Pull requests from one lane are reviewed by a developer from another lane, then by a lead.

| Lane | Tickets in order | Guide estimate | Waits on |
|---|---|---|---|
| A security | K-01 scope and RLS, K-02 identity, K-03 permissions | 6.5 | – |
| B ledgers and events | K-04 audit, K-05 outbox and consumers, K-10 notifications | 6 | K-12 for the partition job registration only |
| C configuration and documents | K-11 config, K-12 scheduling, K-13 clock and business date, K-07 documents and numbering, K-09 attachments | 7 | – |
| D language and sync | K-06 messages, collation and A4 renderer, then K-08 sync gateway with the till developer | 7.5 | K-08 needs device auth from K-02, the snapshot signer from K-03 and the change-log fan-out from K-05; enrolment, ingestor and simulator start against the stubs |

Meanwhile: the web stream builds the M1 screens against a mock served from `m1party.yaml`; the till stream runs spike 1 and builds the simulator; the environment developer adds the two-instance and Testcontainers jobs the kernel tests need; the module joiners finish fixtures and review the slices with the architect.

Done when: Phase 3 of Part A, the module gate.

**Wave 3 — M1 in three backend lanes plus web.** M1-01 and M1-02 are done first by one developer, because every other ticket needs the migration, the seeds and the fixture builders. The complete OpenAPI slice is already in the repository from wave 1, so the lanes do not edit it concurrently.

| Lane | Tickets | Guide estimate | Staffed by |
|---|---|---|---|
| M1-a parties | M1-03 entities, M1-04 relationships, M1-11 bulk register | 5 | module joiner 1 |
| M1-b places and devices | M1-05 locations and positions, M1-06 devices, M1-10 snapshot contributors | 4 | lane D developer rolling over (knows sync and device enrolment) |
| M1-c people and rights | M1-07 users and credentials, M1-08 roles and SoD, M1-09 external grants | 4.5 | lane A developer rolling over (knows identity and permissions) |
| M1-web | M1-12 nine screens and both Playwright flows, delivered as each group of operations lands | 3 | web stream |
| – | M1-13 contract freeze | 0.5 | M1-a developer with the architect |

**Wave 4 — M2 in three backend lanes plus web.** Entry: the M1 contract (decision 9 in Phase 7 settles whether that means the early publication or the final freeze). M2-01 first by one developer.

| Lane | Tickets | Guide estimate | Staffed by |
|---|---|---|---|
| M2-a products | M2-02 SKU aggregate and search, M2-08 steward tooling | 6 | module joiner 2 |
| M2-b identifiers | M2-03 units, tags and tax, M2-04 barcodes, M2-05 batch | 5.5 | lane B developer rolling over (M2-05 publishes and consumes events) |
| M2-c media and distribution | M2-06 images, M2-07 promotion and merge, M2-09 assortment, reconcile job and snapshot | 5.5 | lane C developer rolling over (attachments, scheduling, snapshot contributors) |
| M2-web | M2-10 eight screens and both flows | 3 | web stream |
| – | M2-11 contract freeze, pulled forward to follow M2-05 as 22A proposes | 0.5 | M2-b developer with the architect |

The two platform leads do not roll over. They keep the kernel, review every module pull request that touches a kernel service, and start the next contract reviews (M3, M5).

### B.4 Rules that stop parallel work from colliding

| Shared file or resource | Problem | Rule |
|---|---|---|
| `docs/PROGRESS.md` | Every ticket appends to it | `docs/PROGRESS.md merge=union` in `.gitattributes`; Done entries are single lines grouped by stream |
| Kernel migrations | Two lanes pick the same version number | Reserved ranges per ticket: K-01 takes V0010 to V0019, K-02 V0020 to V0029, and so on; module migrations live in their own directories already |
| `i18n/en.json`, `si.json`, `ta.json` | Every handler and screen adds keys to the same three files | Keys prefixed by module and kept sorted (the i18n check enforces it); one small commit per ticket. Splitting into per-module files is cleaner but deviates from 17A and needs a change request |
| `openapi/<module>.yaml` | Three lanes edit one slice | The whole slice is written before the lanes start (wave 1); later changes go through the lane that owns the operation and the architect |
| Gradle build files, version catalogue, `ci.yml`, compose | Anyone adds a dependency or a service | Single owner (environment developer); others ask by issue or a one-line PR that the owner reviews |
| `kernel.api`, `ArchitectureTests` | A change breaks every module at once | Platform leads only; architect review; announced in PROGRESS.md the same day |
| A published module contract | A dependant is already building on it | The rule of doc 20: a finding that changes a contract is raised the same day, as a change request |

### B.5 Review and quality with a junior team

- **Two-step review.** First a peer from another lane, against a checklist taken from `AGENTS.md` (handler order, RLS policy, idempotency key and permission in the slice, message ids in three languages, the required tests). Then a lead. The architect reviews only contracts: `api/`, `query/`, slices, migrations and `kernel.api`.
- **Small pull requests.** One ticket or less; a ticket estimated above two days is split into reviewable parts before it starts.
- **One ticket in progress per developer.** A blocked developer reviews or writes tests; they do not open a second ticket.
- **Pairing on the first ticket.** Each joiner does their first ticket with a lead or with someone who has already merged one.
- **A short dependency check each day per stream**, and the weekly design review of doc 20 kept for contracts.
- **Definition of done stays as written in `AGENTS.md`.** More people is not a reason to merge without the failing-case tests, the contract test or the RLS matrix rows.

### B.6 What it buys

Arithmetic on the estimates printed in the guides, in working days from today to the end of M2. The estimates are unmeasured (doc 10 P-04) and assume experienced developers; for graduates multiply every row by the same factor, to be measured in wave 1.

| Way of working | About |
|---|---|
| As staffed today: platform pair, then one developer per module, M2 starting at the M1 freeze | 50 |
| Ten developers, waves as above, modules start only after the whole kernel passes the gate | 28 to 33 |
| The same, with module tickets allowed to start once the kernel services they use are real (decision 8) | 23 to 25 |

The gain comes from four things: four streams in wave 1 instead of one pair, four kernel lanes instead of one pair, three lanes per module instead of one developer, and the web screens built against the slice instead of after the handlers.

### B.7 Risks

- **Onboarding drag.** Every joiner costs a lead time before they add any. Joiners arrive in two groups (wave 1 start, wave 1 second half), never all together.
- **Review bottleneck.** If the queue of open pull requests grows beyond about two per reviewer, stop starting tickets and review.
- **Early contract publication.** Starting M2 against a contract that then changes costs rework in two modules. The contract is taken from 21A §5 and §6, which are already complete, which keeps the risk low but not zero.
- **Machines.** The full compose stack plus Testcontainers needs 16 GB of RAM. A developer without it runs PostgreSQL only and relies on CI for the rest; a Postgres-only compose profile is an addition to 17A.
- **Sinhala and Tamil text.** The build fails on a missing translation. Ten developers will add message ids faster than one person can translate them. Someone has to be named, or a marked-placeholder convention agreed and checked before release.

## Phase 7 — decisions only the architect can take

1. Adopt this plan as the Next list of PROGRESS.md, or cut it.
2. Team allocation (3.3): platform pair for phases 1 and 2, one developer each for M1 and M2, till track owner; whether a third developer joins for K-05, K-07 and K-08.
3. Whether M2 starts at the M1-13 freeze in parallel with M1-12, as 21A proposes, or after M1 is merged.
4. The Gradle version: keep the 9.7.1 wrapper (deviation from 17A's 8.10+) or downgrade; record either as an ADR.
5. CR-00_Start_Here-1 and CR-28A-1 (0.4).
6. The 21A §11 and 22A §11 assumptions that name the Federation admins or the data steward as decider: accept the assumptions for the build or seek the decisions now.
7. Team size and names for Part B (eight, ten or twelve developers), and who the two platform leads are.
8. Gating: keep the rule of `AGENTS.md` that no module starts before the whole kernel passes the gate, or allow a module ticket to start once the kernel services it uses are real. The second is faster (B.6) and changes `AGENTS.md`, so it needs an ADR.
9. Whether M2 may start against an early publication of the M1 contract (slice and `api` package reviewed after M1-02) or only at the final M1-13 freeze.
10. One set of `i18n` files with sorted, module-prefixed keys, or per-module files by change request against 17A; and who supplies the Sinhala and Tamil text.
11. Whether a Postgres-only compose profile and a mock server for the web stream are added to 17A's tooling.
