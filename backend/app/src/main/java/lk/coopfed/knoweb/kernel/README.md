# Kernel living guide

The kernel is layer 0 of the application. It supplies scope and identity, command security,
audit and event ledgers, documents and numbering, configuration, scheduling, business dates,
sync, attachments, notifications, rendering and release metadata. Modules depend only on the
published types in `kernel.api` and on the generated operations in the kernel OpenAPI slices;
`kernel.internal` is private.

This README describes the code as it exists. It is the day-to-day companion to doc 19 and the
19A implementation guide. The accepted change requests in `docs/change-requests/` amend those
documents until they are re-issued. When this file, 19A and the code disagree, stop and resolve
the discrepancy rather than copying the older design into a new feature.

## Architecture and implemented services

| Area | Published surface | Implementation and storage |
|---|---|---|
| Scope and identity | `Scope`, `ScopeContext`, `CurrentScope`, `PolicyClass`, `IdentityProviderClient` | The JWT filter resolves user scopes from M1 at request time. `ScopeSession` writes transaction-local PostgreSQL settings. The system entity is named by `kernel.system_identity` and `kernel.system_entity()`. |
| Commands and permissions | `CommandHandler`, `PermissionResolver`, `IdempotencyStore`, `PinHasher`, `Sod` | `CommandInterceptor` checks authentication, active scope, permission, MFA, separation of duties and the idempotency key before a handler runs. `kernel.idempotency_key` is partitioned by UTC day. |
| Audit | `AuditFacade`, `AuditReviewReader` | `kernel.audit_event` is an append-only, monthly partitioned ledger. Event types come from `seed/kernel/audit-event-types.yaml`. REVIEW reads use narrow security-definer functions owned by the NOLOGIN `kernel_audit_reader` role. |
| Events | `DomainEvent`, `EventPublisher`, `EventConsumer` | Publishing inserts into `kernel.event_outbox` in the caller transaction. `coop_relay`, on a separate data source, publishes through `BrokerAdapter`; consumers claim `kernel.event_inbox` in their business transaction. RabbitMQ is the implemented broker. |
| Messages and formats | `Messages`, `Formats`, `TextNormaliser`, `HistoricalTime` | ICU catalogues are file based under `i18n/<module>/{en,si,ta}.json`. Every `String` request value is NFC-normalised. English field-level fallback is explicit in `Messages.Text.fallback`. |
| Documents and numbering | `DocumentIssuance`, `DocumentRepository`, `DocumentLinks`, `DocumentStates`, `DocumentTypeHandler`, `NumberingService` | Kernel document headers, lines, links, state history and series live in `kernel.document*` and `kernel.numbering_series`. Issuance hashes immutable content and consumes a number in one transaction. Device-held numbers raise the central high-water mark through `observeDeviceNumber`. |
| Configuration | `ConfigRegistry`, `ConfigSeeder` | Registered items come from `seed/kernel/config-items.yaml`; values are append-only in `kernel.config_value`. Resolution is location, entity, federation, default. A per-instance 30-second cache is invalidated locally after commit and across instances by `config.changed.v1`. |
| Jobs and dates | `ScheduledJob`, `JobExecution`, `BusinessDate`, `DayClose` | The job registry, run history and ShedLock coordinate workers. Jobs discover fleet work in `FEDERATION_VIEW` and act on each owned row with `JobExecution.ownScopeOf`. Business dates are stored per location and close from session events or the safety cut-off job. |
| Attachments and objects | `Attachments`, `ObjectStorage` | Document attachments and the general `kernel.object_upload` ledger share presign, bounded verification and failed-object cleanup rules. S3/MinIO is behind `ObjectStore`; modules never write kernel ledgers directly. |
| Notifications | `Notifications` and notification query/audience/channel contracts | Pending recipient data and placeholders are AES-GCM sealed under a key outside the database. Quiet hours defer; retries, terminal outcomes and audit records are durable. Channel delivery is supplied by the owning module, not by a provider-specific kernel adapter. |
| A4 rendering | `A4Renderer` | Worker-side Thymeleaf HTML is printed by command-line Chromium with bundled Noto fonts and stored through the object store. Successful output is audited and publishes `report.rendered.v1`. |
| Sync | `SyncStatus`, `SnapshotContributor`, `ChangeLog`, device and sync contracts | `/v1/sync/**` handles enrolment, signed batches, sequence gaps, quarantine, heartbeat, changes, snapshots and attachment presigns. Facts are applied once and forwarded through the outbox; business rejection never discards a till fact. |
| Releases | `CentralUpdateService`, release records, commands and events | The release registry exposes signed central update metadata and enforces the app-version floor used by sync and assignment checks. |

The database evolution is under `db/migration/kernel`. Its README records the historical
migration lanes and the current rule: every new kernel migration is numbered above the highest
existing version. Kernel seed files are data owned by the kernel and are loaded idempotently.

## Request and command rules

HTTP request shape belongs in OpenAPI. `RequestValidationHandler` turns generated-model and
Bean Validation failures into the standard problem document. A command handler repeats only a
business invariant, or a rule needed because the same command can arrive through a non-HTTP
path.

A normal mutating operation has a real `Idempotency-Key`, an OpenAPI `x-permission` equal to its
`@CommandHandler` permission and this order inside one transaction:

1. guards;
2. mutation;
3. `audit.record(...)`;
4. `events.publish(...)`.

The command interceptor refuses a normal command nested inside another normal command. A
handler marked `CommandHandler.INTERNAL` has no operation of its own and may run only while an
intercepted outer command is active on the same thread. Architecture and permission checks keep
INTERNAL contracts out of controllers and require their callers to be command handlers.

GET operations are also protected centrally from their OpenAPI `x-permission`. `GET
/v1/session` returns the scopes and permission set that the server resolved; web visibility is
only a convenience and never the security boundary.

## Scope, RLS and database roles

Every operational table has `ENABLE ROW LEVEL SECURITY`, `FORCE ROW LEVEL SECURITY` and an
explicit policy. The canonical policy classes and SQL are in
`db/migration/RLS_POLICY_TEMPLATE.md`:

- `OWN`: read and write only the active entity, and the active location when one is selected;
- `PARTY`: read the active party's shared rows, never write them;
- `FEDERATION_VIEW`: federation-wide read-only operational visibility, excluding protected
  natural-person data and operations marked out of federation view;
- `EXTERNAL_TIMEBOXED`: read-only visibility constrained by a live grant;
- `NONE`: no tenant rows.

Application code does not add tenant predicates as a replacement for RLS. It sets the active
scope on the transaction and lets PostgreSQL decide visibility. Child rows normally follow
their header through security-invoker queries. Narrow `SECURITY DEFINER` functions are allowed
only for named cross-owner operations; each validates its inputs and is owned by a NOLOGIN role.

`coop_app` inherits `app_rw`. It may insert ledger facts but receives no general `UPDATE` or
`DELETE` on audit, outbox, document, stock or account ledgers. `coop_relay` inherits only
`app_relay` and may read outbox rows and set `published_at`. Seed and migration credentials are
separate. Scheduled work must not mint arbitrary user scope: it uses `JobExecution.systemScope`,
`ownScopeOf` for an owner read from that job's module table, or `federationView` for discovery.

## Audit and events

Audit records describe the authenticated actor, active owner/location, subject, before/after
state and reason. A handler test must assert the exact committed audit and event; a failure test
must assert that neither committed. Device-captured audit facts retain their device identity and
sequence and are quarantined when their schema or audit type is invalid.

Central outbox source sequences are strictly increasing but need not be dense. Device source
sequences are dense except for an explicitly recorded reset gap. Publishing is at-least-once;
the inbox claim makes each consumer idempotent. A counterparty consumer may run in the OWN scope
named by a central document event only after the kernel verifies the document and counterparty.

## Documents and numbering

`DocumentIssuance` registers the module-owned type handler, validates the draft, obtains the
location or entity series, assigns its business date and number, writes the immutable header and
lines, and invokes the module callback in the same transaction. Locationless documents use the
calendar date in the configured business time zone. The central series counter rolls back with a
failed issuance. Any gap that does exist is detected and reviewed, never filled by reusing a
number.

Corrections are new documents joined by `DocumentLinks`. Links are not part of the issued
document hash. `NumberingService.observeDeviceNumber` advances, but never lowers, the high-water
mark for a device-held series so replacement devices cannot reuse an observed number.

## Configuration, scheduling and business time

Only registered keys are readable or writable. The seed declares type, bounds, allowed scope,
default, sensitivity and till visibility. `ConfigRegistry.set` is transactional and append-only,
checks owner and scope, requires a fresh second factor for sensitive items, audits
`CONFIG_CHANGED` and publishes `config.changed.v1`. Law-like values belong in module tables, not
in configuration.

Scheduled jobs have a definition, distributed lock and durable run record. A job scans in a
read-only federation view, then processes each tenant row in that row's OWN transaction. The
business zone is `Asia/Colombo` by configuration. Session close events advance a location's
business date; the safety job runs every 15 minutes and closes overdue days after the fleet-wide
cut-off.

## Sync conventions

Sync authenticates a device separately from a user request, bounds request and response sizes,
checks signatures and payload hashes, and allows one in-flight batch per device. A whole bundle
is claimed once. Individual facts are accepted and either applied or quarantined; the gateway
does not merge competing records and never rejects a validly uploaded fact for a later business
rule. Sequence resets and quarantine resolutions are audited commands.

Snapshots are signed, versioned whole views. Deltas come from the append-only change log and a
till below the retained floor receives a full snapshot. The currently implemented full snapshot
is returned inline. Attachments use expiring presigned URLs and are settled by verification,
not by trusting the client.

## Testing conventions

- Integration tests extend `PostgresIntegrationTest`, run against PostgreSQL 16 and exercise
  the production application role, RLS settings and command interceptor.
- `RlsMatrixIntegrationTest`, `SchemaRulesIntegrationTest` and the policy-specific suites
  discover new tables automatically. A new table is not complete until their matrix passes.
- Handler tests assert successful audit/event contents and assert no committed side effects for
  every guard failure.
- Idempotency, two-instance event/job behaviour, numbering, sync conformance, simulator
  power-loss, attachment/object verification, notification retries, configuration invalidation,
  business dates and release floors have focused PostgreSQL suites.
- `ArchitectureTests` protects module boundaries and the command/internal-command rules.
- `tools/check-i18n.mjs`, `tools/check-permissions.mjs`, slice/schema checks and module-diagram
  generation validate the non-Java contracts.

## Deviations from 19A and the authoritative designs

The following are the verified kernel deviations still relevant to the current code. “Accepted”
means the repository implementation is the decision, but the affected design document has not
yet been re-issued. Historical findings that were subsequently fixed are not deviations and are
not listed as current gaps.

| Reference | Current rule or status |
|---|---|
| `CR-17A-1` | **Accepted, implemented.** The standard problem document carries field errors and uses `code` for the message id. OpenAPI/Bean Validation owns request shape; Hibernate entity validation is disabled. |
| `CR-17A-3` | **Accepted, implemented.** OWN policies explicitly test the OWN class, and PARTY location visibility is restricted on the owner's side. |
| `CR-18-1` | **Accepted, implemented.** Partitioned audit/outbox primary keys include their partition key; kernel scope UUIDs do not declare foreign keys into module schemas. |
| `CR-18-2` | **Accepted, implemented.** Federation and external views do not receive natural-person data by generic policy; named audited queries/exports provide the permitted cases. |
| `CR-19A-1` | **Accepted, implemented.** Published signatures are the types in `kernel.api`; do not reproduce the older signatures from 19A. |
| `CR-19A-2` | **Accepted, implemented.** Command idempotency was split into K-03a. Keys are in day partitions and expiry drops partitions rather than deleting rows. |
| `CR-19A-3` | **Accepted, implemented.** Central source sequences are increasing, not dense. `GapCheck` checks density for device sources only and accounts for recorded resets and quarantine. |
| `CR-19A-4` | **Accepted, implemented.** Sync uses ordinary `/v1/sync/**` slice rules, a real idempotency header and device-marker permission; there is no `x-auth: device` exemption. |
| `CR-19A-5` | **Accepted, implemented.** The relay is the separate `coop_relay` login with `app_relay` only and a small data source, not the application role. |
| `CR-19A-6` | **Accepted, implemented.** `CommandHandler.INTERNAL` is permitted only inside one intercepted outer command; ordinary nested commands are refused. |
| `CR-19A-7` | **Accepted, implemented.** Non-document objects use `ObjectStorage` and `kernel.object_upload`. Jobs may take an owner scope only for an owner read from their own module's work row. |
| `CR-19A-8` | **Accepted, implemented.** Pending notification data is sealed outside the database key; upload validity, verification and cleanup are configured; every String is NFC-normalised; notification providers live outside the kernel; locationless documents use the calendar business-zone date; A4 rendering was delivered as K-06b before M3. |
| `CR-19A-9` | **Accepted, implemented.** M1 scopes are resolved at request time; no identity-provider role mapper is built. GET permissions are enforced from OpenAPI and `/v1/session` returns resolved scopes and permissions. |
| `CR-19A-10` | **Accepted, implemented.** `kernel.api.SyncStatus` is the published device-outbox status contract. |
| `CR-19A-11` | **Accepted, implemented.** `NumberingService.observeDeviceNumber` maintains the central high-water mark for device-held series. |
| `CR-19A-12` | **Accepted, implemented.** Quiet hours defer delivery to the recipient entity's window end; they never suppress it. |
| `CR-19A-13` | **Accepted, implemented.** A validated central document event may dispatch a counterparty consumer in that counterparty's OWN scope. |
| `CR-19A-14` | **Accepted, implemented.** Modules read their REVIEW audits through `AuditReviewReader` and narrow definer functions. A general module scope-minting API was rejected. |
| `CR-32-1` | **Accepted, implemented.** Facts below the app-version floor are still accepted while snapshots are withheld; forbidden values are redacted in quarantine, resolutions are durable, and reinstatement resumes a revoked till's outbox. |
| `CR-32-2` | **Accepted, implemented.** An attachment URL is renewed only inside its configured live window; after expiry the till creates a new attachment and decisions wait only on live attachments. |
| `docs/progress/deviations/2026-09-20-s0-06-decisions-architect-2026-09.md` | **Accepted contract additions.** `CurrentScope` is controller-only. Generated request validation produces field problems before controllers; business invariants remain in handlers. |
| `docs/progress/deviations/2026-09-26-fix-identity-review-2.md` | **Incomplete.** MFA freshness uses one ENTITY-scoped `security.mfa.freshness` duration (default and maximum twelve hours) for every MFA permission. 19A's shorter per-action windows are not implemented because `StepUp` receives no permission. |
| `docs/progress/deviations/2026-09-26-fix-m1-roles-users-grants-review.md` | **Accepted API growth awaiting re-issue.** `IdentityProviderClient.enableUser` extends the signatures recorded by CR-19A-1, and `Notifications.send` returns a delivery result rather than only an id. |
| `docs/progress/deviations/2026-09-26-fix-documents-numbering-review.md` | **Accepted behaviour.** There is no from-side trigger refusing a link from an issued correcting document: correction links are intentionally made after issue and links are outside the hash. |
| `docs/progress/deviations/2026-09-26-fix-documents-numbering-review-2.md` | **Accepted RLS departure.** Shop scope can read/update its entity's locationless document and ENTITY series; the base template would hide NULL-location rows. The RLS matrix names this exception. |
| `docs/progress/deviations/2026-09-26-fix-job-runner-business-day-review-3.md` | **Accepted storage departure.** ShedLock's `lock_until` and `locked_at` are naive UTC `timestamp`, as its PostgreSQL provider requires, rather than 19A's `timestamptz`. |
| `docs/progress/deviations/2026-09-26-fix-job-runner-business-day-review.md` | **Incomplete.** The safety cut-off is one fleet property (`coop-erp.business-date.cutoff-time`, default 02:30). The registered LOCATION key `business_date.cutoff_time` is not yet read. |
| `docs/progress/deviations/2026-09-26-fix-notifications-attachments-review-3.md` | **Accepted timing departure.** Verification settles an attachment after its presigned PUT expires, not merely on the next five-minute scan; this preserves immutable verified bytes at the cost of the wait. |
| `docs/progress/deviations/2026-09-27-feat-k-06b-a4-renderer.md` | **Accepted implementation departure.** Rendering invokes installed headless Chromium directly rather than Playwright. The worker's `--no-sandbox` use is guarded by the deployment controls recorded there. |
| `docs/progress/deviations/2026-09-27-feat-k-06b-a4-renderer-2.md` | **Partly superseded; remaining work deferred.** The worker image is packaged and published. There is still no rendered-report ledger/retention cleanup, web-role render queue or pixel golden comparison. |
| `docs/progress/deviations/2026-09-27-feat-k-08-sync-gateway-2.md` (`K-08-F2`) | **Deferred.** Full snapshots remain inline. A pre-built compressed object, resumable URL, manifest and cleanup are required before real-link catalogue scale. |
| `docs/progress/deviations/2026-09-27-feat-k-08-sync-gateway-2-2.md` | **Accepted implementation.** Sync uses per-device, per-instance token buckets, a global slot limit and one in-flight batch per device; it does not maintain a round-robin device queue. |
| `docs/progress/deviations/2026-09-27-feat-decisions-sync-2.md` | **Accepted application path.** Till audit facts are written by `DeviceAuditWriter` inside their ingest chunk, rather than later by an outbox consumer or through `AuditFacade`, so invalid facts can be quarantined atomically. |
| `docs/progress/deviations/2026-10-06-wave2-keyed-hashes.md` | **Accepted contract addition.** `KeyedHash` provides purpose-separated HMAC-SHA-256 for sensitive equality/deduplication fields; keys come from the environment, never `ConfigRegistry`, and stored hashes carry key ids. |
| `docs/progress/deviations/2026-10-06-wave2-kernel-defaults-and-limits.md` | **Accepted fail-closed defaults.** Permission/signing defaults, HEAD-as-GET, bounded sync bodies and render concurrency are stricter than the earlier guide. Kernel migrations after the historical lanes increase monotonically above V0083. |
| `docs/progress/deviations/2026-10-06-wave2-cross-tenant-functions.md` | **Accepted security pattern.** Named cross-owner operations use narrow, input-validating security-definer functions rather than broad tenant bypass. |
| `docs/progress/deviations/2026-10-09-wave3-kernel-small-review-findings.md` | **Implemented correction with a retained deviation.** The day-close job runs every 15 minutes so a missed instant is recovered, but it still uses the fleet-wide cut-off described above. Legacy all-zero system-scope compatibility remains until old data no longer needs it. |
| `docs/progress/deviations/2026-10-13-wave4-kernel-floor-alert-and-config-location.md` | **Accepted implementation.** Version-floor alerts are deduplicated per device and floor; a location-scoped config writer cannot write a sibling or locationless value. |
| `docs/progress/done/2026-10-16-feat-till-sell-12-day-close.md` | **Accepted integration departure.** M6 publishes `location.sessions_closed.v1`; the kernel consumes that location-level fact rather than coupling business-date close to a raw till-session event. |

## Deliberately deferred or incomplete kernel work

- **QA-15:** production request/idempotency/problem classes still live in
  `kernel.internal.stub`; renaming that package is a separate roadmap task.
- **QA-17:** RabbitMQ is behind `BrokerAdapter`, but the requested
  `NatsBrokerAdapter` compile-time stub and its test are not implemented.
- **K-08-F2:** full snapshots are not yet pre-built resumable objects, as recorded above.
- **K-06b follow-ups:** rendered-report retention/ledger, a web render queue and pixel-golden
  proof are deferred. Worker-image packaging is implemented.
- **Per-location business cut-off:** the registered location value is not consumed yet.
- **Per-action MFA freshness:** one bounded entity window is implemented; shorter action-specific
  windows wait for an operation that requires one and an approved contract change.
- **Real-till joint sync proof:** the kernel conformance suite and simulator exist, but the
  remaining real Kotlin Multiplatform/two-day-offline gate belongs to the till/QA roadmap and
  must not be inferred from the simulator passing.

These items are not license to fill in an unspecified design. Use the named roadmap task or
recorded follow-up, and stop for an owner decision where no assumption exists.
