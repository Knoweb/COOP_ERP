# Kernel migrations â€” who may use which version numbers

The kernel is the one migration folder several tickets write to at the same time. K-01 to K-13
are built in four lanes (`docs/PLAN_TO_M2.md`, Part B, wave 2) and two lanes that both pick
`V0002` produce a conflict that no test catches until the files meet on `main`. So the numbers
are reserved per lane before the lanes start.

Only `V0001__baseline.sql` exists today (schemas, extensions, ICU collations, roles, the
row-level-security helper functions of 17A section 6.1). Everything below `V0010` stays free
for whatever the baseline still needs.

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