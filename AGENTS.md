# AGENTS.md — Cooperative Retail and Distribution System (Knoweb / COOPFED)

Standing instructions for working in this repository. They apply to every person and to every AI coding tool; the tool-specific files at the root (`CLAUDE.md`, `GEMINI.md`) only point here. Read fully before any task. `docs/HANDOVER.md` is the one-time briefing; `docs/DECISIONS_PENDING.md` lists the assumptions you build to; `docs/README.md` explains how the documentation is organised, read and maintained.

## What this system is (five sentences)

A national retail and distribution platform for Sri Lanka's cooperative federation: the Federation (COOPFED) sells to about 75 distributors, who sell to about 800 multi-purpose cooperative societies (MPCS), who run about 4,000 shops growing to 10,000. Goods are ordered, delivered, received, sold at the till and paid for; stock is counted, written off and repacked; customers buy on credit (the khata); everything is reported to the federation. Shops must keep selling for two days with no connectivity, in Sinhala, Tamil or English. The backend is one Spring Boot application divided into nine modules with strict boundaries; the till is a native Android application; they talk through a sync contract that never merges data. Version 1 records money and never moves it.

## Three ideas that explain every design choice

1. **A shop is a location, not a legal entity.** Every row carries `owner_entity_id` (the MPCS, distributor or Federation). Row-level security in PostgreSQL filters by the caller's entity and location; application code never filters by tenant itself.
2. **Ownership of goods transfers when the receiver confirms the goods received note (GRN).** Batches are registered, stock lots created and invoicing enabled from that one event (`grn.confirmed.v1`). Nothing on a confirmed GRN is ever edited.
3. **No record is ever written by two parties.** The till writes its own receipts, sessions, counts and receiving; central writes everything else and pushes it to tills read-only. There is no merge logic anywhere; never write code that reconciles two versions of a record.

Consequences enforced by the build: documents are immutable once issued (corrections are new, linked documents); ledgers (stock movements, account postings, audit log, outbox) are insert-only; balances are sums, never edited.

## Where the truth lives

| Question | Authoritative source |
|---|---|
| What does module n do and why | `docs/design/<n>_*.pdf` (design document) — sections 3–6 |
| What exactly do I build for module n | `docs/design/<n>A_*.pdf` (implementation guide) — self-contained; section 3 DDL, 5 API slice, 6 handler specs, 10 tickets, 11 assumptions |
| Shared tables, invariants, which schema owns what | `docs/design/18_Core_Data_Model.pdf` v0.3 (Part F is the schema register) |
| Kernel services (scope, identity, permissions, audit, events, documents, sync) | `docs/design/19_Kernel_Services.pdf` v0.2 and `19A_*.pdf` |
| Repository layout, build, conventions, hello module | `docs/design/17A_Build_Skeleton_Implementation_Guide.pdf` |
| Till ↔ central protocol | `docs/design/32_Sync_Contract.pdf` |
| Which document version is current | `docs/design/00_Knoweb_Document_Register.pdf` — the register wins |
| Decisions not yet taken, and the assumption in force | `docs/DECISIONS_PENDING.md` (mirror of doc 10 Open Items Register) |
| Decisions already taken | doc 09 Decisions Log in the requirements baseline; ADR numbers are cited in the design documents |
| How the documentation is organised, extracted, revised and published | `docs/README.md` |

Requirements baseline (docs 00–13, 15, 16): Google Drive folder `1DKsOlf4k8vlOJCjp2oVaEQlg-XCs1fgh`, mirrored in `docs/requirements/`.
System design (docs 14, 17–32, nA guides, 24B): Google Drive folder `14pTgorZIGEZ9FrJGlmhaLy6Eh1o5FdFj`, mirrored in `docs/design/`.
Tools read the text extracts in `docs/requirements/txt/` and `docs/design/txt/`, never the PDF or Word files directly (see `docs/README.md`).

If two documents disagree: the register decides versions; the design document's *reasoning* and the implementation guide's *procedure* both hold; if they contradict, stop and raise it, do not pick one silently.

## Stack (fixed)

Java 21, Spring Boot 3.3, Spring Modulith 1.2, PostgreSQL 16 (ICU collations, `pg_trgm`, `btree_gist`, `pgcrypto`), Flyway, Gradle (Kotlin DSL), Testcontainers, ArchUnit. Web: React 18, TypeScript 5, Vite, TanStack Query, OpenAPI-generated clients. Till: Kotlin 2, Jetpack Compose, Room + SQLCipher, WorkManager, Hilt, minSdk 30. Shared engine: pure Kotlin JVM library used by backend and till. Local: docker compose (PostgreSQL, PgBouncer, RabbitMQ, MinIO, Keycloak, Mailpit). Identity provider and broker are assumptions behind interfaces (`IdentityProviderClient`, `BrokerAdapter`); do not couple code to Keycloak or RabbitMQ specifics.

## Repository layout (from 17A)

```
backend/app/src/main/java/lk/coopfed/knoweb/
  kernel/            layer 0 — used by all modules, changed only by the platform pair
  hello/             the template module; `make new-module` copies it
  m1party/ m2catalogue/ m3pricing/ m4trading/ m5inventory/ m6pos/ m7customers/ m8reporting/ m9integration/
backend/app/src/main/resources/db/migration/<module>/   openapi/<module>.yaml   seed/<module>/   i18n/{en,si,ta}.json
backend/shared-engine/                                   pure Kotlin: money, tax, price resolution, rules, envelope, counters
web/src/shell/  web/src/modules/<module>/  web/src/generated/
till/app/ till/core/ till/peripherals/ till/sync/
docs/design/  docs/adr/  docs/sources/                   design PDFs, ADR markdown mirrors, document sources
```

Each module package: `api/` (published: command records, query interfaces, events), `query/` (published, read-only), `internal/` (private), `web/` (controllers implementing generated OpenAPI interfaces). Modulith `package-info.java` declares allowed dependencies. Layers: kernel → master data (M1–M3) → transactions (M4–M7) → read side (M8, M9). Sideways access is query-only.

## Non-negotiable rules (the build enforces most of them)

- Never `UPDATE` or `DELETE` a document, line, stock movement, account posting, outbox or audit row. The `app_rw` role has no such grant; do not add one.
- Never store a balance you do not recompute from the same events that change it.
- Never write to another module's schema; never import another module's `internal` package. Use its `api`/`query` or publish an event.
- Never put a phone number, NIC, password, PIN or token in an event payload, log line or audit row.
- Never reject a fact uploaded by a till for a business reason; apply it and flag it.
- Never hard-code a price, threshold, window or limit; it is a configuration item (`ConfigRegistry`) or a data table.
- Every user-visible string is a message id with `en`, `si`, `ta` translations; a missing one fails the build.
- Every mutating operation carries an `Idempotency-Key` and an `x-permission` in its OpenAPI slice that matches the `@CommandHandler` annotation.
- Every command handler: guards → mutation → `audit.record(...)` → `events.publish(...)`, in one `@Transactional` method, in that order. Nothing else.
- Every operational table: `ENABLE ROW LEVEL SECURITY; FORCE ROW LEVEL SECURITY;` plus the four-class policy template (17A §6.3). A table without a policy fails the schema test.
- Money `numeric(14,2)`, unit cost/price `numeric(14,4)`, quantity `numeric(14,3)`; UUIDv7 keys; `snake_case`; timestamps stored as UTC plus a local reading; sequence numbers, not clocks, order events.

## How to work here

- **Build order is fixed.** Sprint 0 first (17A tickets S0-01…S0-13, then 19A K-01…K-13). Modules only after the hello module's integration tests pass against real kernel services. Within a module, follow the nA guide's section 10 ticket order.
- **Write for fresh graduates.** The team is newly graduated developers and trainees. Prefer explicit over clever, conventional over novel, one obvious way per task. Comment the *why* where it is not obvious from the guide; never comment the *what*.
- **Tests are the definition of done.** Every guard in a handler spec has a failing-case unit test; every operation has a contract test through the generated client; every table has RLS matrix rows; ledgers have the property test "balance equals the sum of movements under random interleavings".
- **When a decision is missing**, build to the assumption in `docs/DECISIONS_PENDING.md`, cite the item id in a code comment (`// assumes doc 10 E-05`), and do not decide silently. If no assumption exists, stop and ask.
- **When you think the design is wrong**, write a change request (what, why, what changes, which documents) into `docs/change-requests/` and raise it. Do not fix it locally; the same problem exists in the documents and other modules.
- **Document sources live here.** Any document you re-issue or create is written as Markdown (or the generator script) under `docs/sources/` and rendered to PDF into `docs/design/`; upload the PDF to the Drive folder and bump the register. Never edit a PDF's content without a source. The step-by-step procedure is in `docs/README.md`.
- **Keep the module README as the living guide.** Once a module is built, `backend/app/src/main/java/lk/coopfed/knoweb/<module>/README.md` (plus the seed files and tests) supersedes the nA guide for day-to-day work; note deviations from the guide there.
- **Record progress in the repository.** `docs/PROGRESS.md` (done, next, deviations) is updated after every ticket so that any person or tool can resume from it; nothing that matters lives only in a conversation.
- Conventional commits (`feat(m1): …`, `fix(kernel): …`, `docs(18): …`); short-lived branches; squash merge to `main`.

## Vocabulary

Entity (legal party) · Location (warehouse/shop/office; a shop is a location) · SKU · Batch (physical supply identity with expiry and printed MRP; global) · Lot (an entity's holding of a batch at a location in a condition, at that entity's cost) · GRN · Control price (gazetted maximum, hard ceiling by date) · Snapshot (read-only reference data a till receives, applied whole) · Bundle (a complete document a till uploads as one event) · Kernel (layer 0) · Contract (a module's `api`/`query` packages plus its OpenAPI slice; frozen once depended on) · nA guide (implementation guide for design document n).
