# Kernel migrations â€” who may use which version numbers

The kernel is the one migration folder several tickets write to at the same time. K-01 to K-13
are built in four lanes (`docs/PLAN_TO_M2.md`, Part B, wave 2) and two lanes that both pick
`V0002` produce a conflict that no test catches until the files meet on `main`. So the numbers
are reserved per lane before the lanes start.

Below `V0010` are the baseline and what it still needed (`V0001` to `V0006`). Taken so far:
`V0010` (K-03a idempotency), `V0030` and `V0031` (K-04 audit, K-05 events), `V0050` (K-07
documents and numbering), `V0051` (K-12 jobs, K-13 business date), `V0052` (K-11 configuration), `V0053` (CR-17A-3, the class test on the ledger policies), `V0054` (K-10 notification log).

The policies every table carries are in `../RLS_POLICY_TEMPLATE.md` (17A section 6.3 completed by
19A K-01, corrected by CR-17A-3); `RlsMatrixIntegrationTest` proves the five classes against it.

| Range | Lane | Tickets | Tables of 19A |
|---|---|---|---|
| `V0010`â€“`V0029` | A, security | K-01 scope, K-02 identity, K-03 permissions | the PARTY policy template and the masking-view convention (section 1); the idempotency table (section 3) |
| `V0030`â€“`V0049` | B, ledgers and events | K-04 audit, K-05 events, K-10 notifications | `audit_event`, `audit_event_type` (section 4); `event_outbox`, `event_inbox`, `central_source_seq` (section 5); `notification_log` (section 10) |
| `V0050`â€“`V0079` | C, configuration, documents and jobs | K-11 config, K-12 scheduling, K-13 clock, K-07 documents, K-09 attachments | `config_item`, `config_value` (section 11); `scheduled_job`, `job_run`, `shedlock` (section 12); `document_type`, `numbering_series`, `document`, `document_line`, `document_link`, `document_state_history`, `document_attachment` (section 7) |
| `V0080` and up | D, language and sync | K-06 i18n, K-08 sync gateway | `message_catalogue` if the file catalogue is ever outgrown (section 6); `device_sync_cursor`, `sync_quarantine`, `snapshot_change_log` (section 8) |

Lane C is the widest range because it carries five tickets and the document base is seven
tables. A lane that needs more numbers than its range holds takes the next free block and
changes the table above in the same pull request.

Module migrations are not affected: each module has its own folder and its own Flyway history
table, so every module starts again at `V0001`.

## The lanes may merge in any order: locally yes, deployed no

Flyway is strict by default (`coop-erp.migration.out-of-order`, default `false`, with
`validateOnMigrate = true`). Measured against PostgreSQL 16 on 21 September 2026: under the
strict setting, after `V0030` has been applied, adding `V0010` and migrating again does **not**
apply it and does **not** skip it quietly. It fails:

```
FlywayValidateException: Validate failed: Migrations have failed validation
Detected resolved migration not applied to database: 0010.
```

Flyway runs that validation inside `migrate()`, and `FlywayConfig.migrate()` is a
`@PostConstruct`, so the application does not start at all. The same run with
`outOfOrder = true` applies `V0010` and logs that the schema is no longer reproducible.

A fresh database is never affected â€” CI, Testcontainers and `make reset` migrate in ascending
order whatever the merge order was â€” so under the strict setting this would fail on developer
machines while the pipeline stays green.

Decided by the architect on 21 September 2026: **the local stack allows it, nothing else does.**
`infra/compose/compose.yml` sets `MIGRATION_OUT_OF_ORDER=true` for the backend, so a developer's
database survives the lanes merging in any order. The application's own default stays strict,
because a deployed database must be reproducible from the files in ascending order;
`MigrationOrderDefaultTest` pins both halves. Two consequences:

- A backend started outside compose (from the IDE, against the compose database) is strict. Set
  `MIGRATION_OUT_OF_ORDER=true` for it, or run `make reset`.
- **Before the first deployed environment exists, the question closes by itself:** from then on
  a migration is only ever added above the highest version that environment has applied, which
  is the ordinary Flyway rule, and the ranges above have served their purpose. Doc 35
  (deployment) is where that is written down.

## The rest of the conventions

`tools/check-schema-ownership.mjs` reads every `.sql` file here and fails when it names a table,
index, policy, trigger, view, function, sequence, type or grant without its schema, or in a
schema the kernel does not own. It ignores files that are not `.sql`, which is why this README
is safe to keep here; Flyway ignores them too (`validateMigrationNaming` is off).

`SchemaRulesIntegrationTest` reads the migrated database and fails for a table without
`ENABLE ROW LEVEL SECURITY`, without `FORCE ROW LEVEL SECURITY` or without a policy, and for a
`DELETE`, `TRUNCATE` or `CREATE` grant to `app_rw`. It needs no registration: a new kernel table
is covered the moment it is created.

## K-04 partition RLS verification

K-04 includes a PostgreSQL 16 integration control for later-created audit
partitions. The control creates a future `audit_event` partition without
enabling RLS or copying policies onto the child. An INSERT routed through the
partitioned parent succeeds under the parent's entity-scoped INSERT policy.
When SELECT is temporarily granted on the parent, reads through
`kernel.audit_event` remain filtered by the parent's RLS policy: the owning
entity sees the row and another entity does not.

Therefore callers must access audit rows through the partitioned parent/view;
the parent's RLS remains the security boundary for routed parent access.
The partition job still applies RLS/policies to children as defence in depth
and for protection against accidental direct child-table access.

## K-03a idempotency partitions

`kernel.idempotency_key` is partitioned by UTC day. The command interceptor
claims the key inside the command transaction before calling the handler and
records the serialized command result before that transaction commits. A
concurrent request on another instance therefore waits on PostgreSQL rather
than executing the command twice.

Partition maintenance runs with the configured migration credentials. The
retention window is configurable with
`coop-erp.idempotency.retention-hours` (default `24`), future partition creation
with `coop-erp.idempotency.partition-days-ahead`, and the maintenance schedule
with `coop-erp.idempotency.partition-cron`. Expiry drops old partitions; it does
not DELETE rows.

## K-05 event backbone

`kernel.event_outbox` is the caller-transaction archive for versioned domain
events. Central events take `source_seq` from `kernel.central_source_seq`; it is
strictly increasing but is not required to be dense. Device-source density is a
sync concern and is checked separately.

The application role may insert outbox rows but cannot mark them published.
Worker instances open a separate pool as `coop_relay`, a login that has
`app_relay` only. The database relay takes one advisory lock per source, reads
unpublished rows in `source_seq` order, publishes through `BrokerAdapter`, and
sets `published_at` only after the broker confirms the message.

The RabbitMQ adapter publishes persistent messages to topic exchange `domain`
with the event type as routing key. Each `@EventConsumer` gets a durable quorum
queue with single-active-consumer enabled. Consumers claim
`kernel.event_inbox (consumer, event_id)` in the same transaction as their
handler; duplicate delivery is therefore harmless. The third failed delivery
records an ALERT audit event and sends the message to `domain.dlq`.
`Replayer` can republish archived events directly to one consumer queue.