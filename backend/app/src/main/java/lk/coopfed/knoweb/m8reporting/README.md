# m8reporting — M8 Reporting Pipeline

The living guide of the module (AGENTS.md): the read side. Projections built from the events of the other modules, the reports read from them, and the dashboard. Once code exists, this file and the tests supersede 28A for day-to-day work; every deviation from the guide is listed at the end with its reason. Read `hello/README.md` first: its rules apply here, with the one exemption below.

Built so far, for the demo (phase 4, "administration and reporting for administration"): M8-01 (the projection base) with the stock position as its sample projection (M8-03, part). The demo-minimal scope and what was deferred are in `docs/PROGRESS.md` and under "Deferred after the demo" in `docs/PLAN_TO_M2.md`.

## The one rule

**Every row in `reporting` is derived from events and can be dropped** (28A "Read this first"). A projection that cannot be rebuilt from the archive is a second source of truth and does not belong here. So every projection has an equivalence test: the rows the live consumer leaves after a stream of events, with redeliveries, equal the rows a rebuild from zero leaves after the same events once each (`ProjectionHarness` in the tests).

## What is where

| Path | What it holds |
|---|---|
| `internal/projection/Projection` | The pattern of 28A section 6: a consumer of events that applies each one idempotently in the OWN scope of its owner and advances the projection's state. |
| `internal/projection/ProjectionEvent` | The kernel's envelope (type, id, owner, time) with the payload, read as JSON by field name. |
| `internal/projection/ProjectionStateStore` | `projection_state`: per projection and entity, the last event applied. |
| `internal/projection/StockPositionProjection` | `stock_position` from `stock.moved.v1`. |
| `resources/db/migration/m8reporting/V0001__projection_base.sql` | `projection_state`, `stock_position`; row-level security from the template. |

## Projections

A projection subclasses `Projection` and declares its consumer on a method of its own (the kernel registers the `@EventConsumer` methods a class declares):

```java
@EventConsumer(types = "*", consumer = "m8.stock_position")
@Transactional
public void on(JsonNode envelope, ScopeContext scope) { consume(envelope, scope); }
```

- **Every type, then filtered.** A consumer of `"*"` is handed the envelope (eventType, eventId, ownerEntityId, occurredAt) with the payload inside (EventConsumerDispatcher). A projection needs the event's time for the freshness of its rows and the event's id for its state, and a typed consumer is handed the payload only. `consume` ignores the types the projection does not read.
- **The scope is the event owner's.** The dispatcher runs the consumer in the OWN scope of the event's owner, at the location of the event, with no user; the rows are written in that scope under row-level security, so a projection writes only the rows of the entity whose event it applies. The owner of a row is read from the payload (`ownerEntityId` of `stock.moved.v1`), so a wrongly addressed event is refused by the database rather than filed under the wrong entity.
- **Idempotent by construction.** The kernel's inbox delivers an event once per consumer; a rebuild replays it, and an operator may replay a range twice. A fact is inserted with `ON CONFLICT DO NOTHING` on the key of its event; a current figure is overwritten with the value the event reports, guarded by the event's order. Never "add the delta".
- **Payloads by field name.** M8 imports no event record of another module (28A section 4: the read side calls no layer-2 api); the field names of the publishing record are the contract.

### Why projections write without a command handler

The build lets only `@CommandHandler` classes write (`ArchitectureTests.onlyHandlersWriteRule`), because a write is a business fact that must be audited and published. A projection row is neither: it copies facts that the owning module's handler already audited and published, and auditing each copy would double the audit log and add nothing. The rule exempts the package `lk.coopfed.knoweb.m8reporting.internal.projection` and nothing else (decided 27 September 2026 on the architect's delegation). Everything else in M8 that writes (a report run, later) is a command handler as usual.

### The stock position (`stock_position`)

One row per (location, batch, condition) from M5's `stock.moved.v1`: the lot's quantity after the movement (`lotQtyOnHand`, which M5 sends so a projection needs no read back), the entity average at that movement as the unit cost (CR-28A-1), the SKU, and the time of the event as the row's freshness. The movement's number decides which event is newer: numbers are dense per location and source (25A), so within a source the higher number wins, and between sources (a till's and the centre's) the later event does. A redelivered old movement changes nothing.

### Rebuilding a projection

Until the rebuild service exists (M8-01 rest, below), a rebuild is an operator's procedure, and it is what the equivalence test does:

1. As the migration user (a privileged role: `app_rw` may not delete), stop the worker's consumer `m8.<name>` and empty the projection's tables and its `projection_state` rows.
2. Remove the consumer's rows from `kernel.event_inbox` (the platform pair's step: the inbox would otherwise refuse the replayed events as duplicates).
3. Replay the consumer from the start of the archive: `Replayer.replay("m8.<name>", 0)` on the worker.

## Tests

| Test | What it proves |
|---|---|
| `StockPositionProjectionPostgresIntegrationTest` | Rebuild equivalence over five random streams of 60 movements (two entities, three locations each, two sources, redeliveries); a late or repeated movement changes nothing; an event of another type is ignored; a delivery in another entity's scope is refused by row-level security; nothing is audited or published. |
| `RlsMatrixIntegrationTest`, `SchemaRulesIntegrationTest` | Both tables follow the template (found by their `owner_entity_id`), and `app_rw` cannot delete from them. |

## Deviations from 28A

- `projection_state` is keyed by (name, owner_entity_id), not by name: a consumer writes in the scope of the event's owner, and row-level security admits only that entity's row. `last_seq`, `shadow_table` and `rebuild_started_at` wait for the rebuild service: the consumer is handed no source sequence, and the shadow table swap needs a privileged role.
- `stock_position` takes the lot's quantity from the event instead of adding deltas (above), and carries `last_source` and `last_movement_seq` for it. `expiry_date`, `received_at`, `days_held` and `negative_since` wait for the expiry reports. `canonical_sku_id` is the SKU itself until M2 publishes SKU merges.
- The projections subscribe to `"*"` and filter, for the envelope (above).
