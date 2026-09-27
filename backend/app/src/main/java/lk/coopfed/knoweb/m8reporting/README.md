# m8reporting — M8 Reporting Pipeline

The living guide of the module (AGENTS.md): the read side. Projections built from the events of the other modules, the reports read from them, and the dashboard. Once code exists, this file and the tests supersede 28A for day-to-day work; every deviation from the guide is listed at the end with its reason. Read `hello/README.md` first: its rules apply here, with the one exemption below.

Built so far, for the demo (phase 4, "administration and reporting for administration"): M8-01 (the projection base) with the stock position as its sample projection (M8-03, part), the trading projections (M8-04, part), three reports with CSV, A4 print and the dashboard (M8-06 and M8-07, demo part), and their web screens (M8-09, demo part). The demo-minimal scope and what was deferred are in `docs/PROGRESS.md` and under "Deferred after the demo" in `docs/PLAN_TO_M2.md`.

## The one rule

**Every row in `reporting` is derived from events and can be dropped** (28A "Read this first"). A projection that cannot be rebuilt from the archive is a second source of truth and does not belong here. So every projection has an equivalence test: the rows the live consumer leaves after a stream of events, with redeliveries, equal the rows a rebuild from zero leaves after the same events once each (`ProjectionHarness` in the tests).

## What is where

| Path | What it holds |
|---|---|
| `internal/projection/Projection` | The pattern of 28A section 6: a consumer of events that applies each one idempotently in the OWN scope of its owner and advances the projection's state. |
| `internal/projection/ProjectionEvent` | The kernel's envelope (type, id, owner, time) with the payload, read as JSON by field name. |
| `internal/projection/ProjectionStateStore` | `projection_state`: per projection and entity, the last event applied. |
| `internal/projection/StockPositionProjection` | `stock_position` from `stock.moved.v1`. |
| `internal/projection/TradeProjection` | `trade_document_event` and `trade_line_fact` from M4's events. |
| `resources/db/migration/m8reporting/V0002__trade_projections.sql` | The two trading tables, with `party_read`. |
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

### Trade (`trade_document_event`, `trade_line_fact`)

From M4's events (`TradeProjection`, consumer `m8.trade`): order submitted, accepted, rejected and cancelled; delivery note dispatched; GRN confirmed; invoice issued. The field names are those of M4's event records.

- **One row per event, on the side of its owner.** `trade_document_event` has a row per (document, kind, owner): the buyer's SUBMITTED and CONFIRMED rows, the seller's ACCEPTED, DISPATCHED and ISSUED rows, each naming the other party as `counterparty_entity_id`, who reads it through `party_read`. 28A's `trade_document_fact` is one row per document with a status; that row would be written by both parties, and row-level security lets no party update another's row. A document's status is its latest event, which the reports work out.
- **Volume** is `trade_line_fact`: ACCEPTED lines (the seller's allocated quantity at the tier price) and RECEIVED lines (the buyer's received quantity at the unit cost the GRN carries, the trade price). The GRN is where ownership passes (AGENTS.md idea 2), so trade volume in the reports is RECEIVED.
- **No cost columns**: both tables are PARTY-readable. `value` is quantity times the price both parties agreed, which both see on the invoice.
- **Business date**: the GRN's `confirmedAt`, the invoice's tax point date, otherwise the time of the event, as a date in `coop-erp.business-timezone`.
- Insert-only: `app_rw` has SELECT and INSERT; a replay inserts nothing (`ON CONFLICT DO NOTHING` on the keys).

### Rebuilding a projection

Until the rebuild service exists (M8-01 rest, below), a rebuild is an operator's procedure, and it is what the equivalence test does:

1. As the migration user (a privileged role: `app_rw` may not delete), stop the worker's consumer `m8.<name>` and empty the projection's tables and its `projection_state` rows.
2. Remove the consumer's rows from `kernel.event_inbox` (the platform pair's step: the inbox would otherwise refuse the replayed events as duplicates).
3. Replay the consumer from the start of the archive: `Replayer.replay("m8.<name>", 0)` on the worker.

## Reports, CSV, A4 and the dashboard

`query.ReportingQueries` (`internal/report/ReportQueriesImpl`), read-only, in the caller's scope under row-level security; `web/ReportingController` implements the slice `openapi/m8reporting.yaml`.

| Report (`ReportCatalogue`) | Rows | Parameters |
|---|---|---|
| `stock-position` | `stock_position` summed by entity, location and SKU: quantity, and the value at the entity average (a cost: only for the owner's users and the Federation view) | `locationId?` |
| `trade-by-distributor` | `trade_line_fact` RECEIVED lines summed by day, seller, buyer and SKU: quantity and value. A seller sees its buyers, a buyer its sellers, the Federation view everybody | `from`, `to` (required) |
| `invoices-issued` | `trade_document_event` INVOICE/ISSUED rows: tax point, number, seller, buyer, net, VAT, total | `from`, `to` (required) |

Each answer carries its columns (key, message id, kind TEXT, DATE, QTY or MONEY), the rows as text (dates ISO, amounts and quantities as stored, never rounded), the totals of the QTY and MONEY columns, the freshness (the latest event the projection applied that the caller can see) and when it was made. Names are resolved when the report is read, through M1's and M2's queries in the caller's scope and language (`Names`); a name the scope cannot read shows as the end of its id. Guards: `m8.report.unknown`, `m8.report.period_required`, `m8.report.period_invalid`.

| Operation | Permission | What |
|---|---|---|
| `GET /v1/reporting/reports` | `rpt.report.run` | The three definitions (id, title and decision message ids, whether they take a period or a location). |
| `GET /v1/reporting/reports/{id}/data` | `rpt.report.run` | The rows. |
| `GET /v1/reporting/reports/{id}/csv` | `rpt.export.run` | The same rows as RFC 4180 CSV, UTF-8, with a header line in the caller's language; a text cell that starts with `= + - @` gets a leading apostrophe so a spreadsheet never runs it. |
| `POST /v1/reporting/reports/{id}/runs` | `rpt.export.run` | `RequestReportRun`: records `report_run` REQUESTED, audits `REPORT_RUN_REQUESTED`, publishes `report_run.requested.v1`. Guards also `m8.run.own_required` (a user in an OWN scope: the PDF belongs to an entity) and `m8.run.language_invalid`. |
| `GET /v1/reporting/runs/{runId}` | `rpt.export.run` | The run; once READY, a fresh pre-signed link to the PDF (`A4Renderer.presignGet`, any role). |
| `GET /v1/reporting/dashboard` | `rpt.report.run` | Tiles: orders awaiting acceptance (SUBMITTED with no ACCEPTED, REJECTED or CANCELLED), deliveries in transit (DISPATCHED with no GRN confirmed against the note), GRNs confirmed today (business date in `coop-erp.business-timezone`), and the stock value for the owner's users and the Federation view. |

**Printing** (`ReportRunWorker`, consumer `m8.render`, only where `coop-erp.report.enabled` is true, the worker): reads the report in the OWN scope of the run's entity at the requester's location, fills M8's template `reports/templates/m8-report.html` through `ReportPrintModel` (labels from the kernel's messages in the run's language, values through `Formats`) and prints it with the kernel's `A4Renderer` (K-06b). `CompleteReportRun` (a command handler run by the worker with no user, as M5's GRN receipt) records READY with the object key or FAILED with the renderer's message id, once (`m8.run.not_open`), audits `REPORT_RUN_COMPLETED` and publishes `report_run.completed.v1`. The events are `report_run.*`, not 28A's `report.requested.v1`, because the kernel's renderer already publishes `report.rendered.v1`.

Permissions `rpt.report.run` and `rpt.export.run` (28A section 3.1) are in `seed/m1party/permissions.yaml`, in the Federation Viewer (read only), Entity Administrator, Trading Buyer and Trading Seller templates, and in the demo's sales, accounts, distributor and society manager roles (`seed/m1security/demo-users.demo.sql`).

## Web screens (`web/src/modules/m8reporting`)

The navigation entry "Reports" shows for a user holding `rpt.report.run` or `rpt.export.run` (the shell reads the session's permissions). **Dashboard** (`/reporting`): the tiles with their values (money through `MoneyDisplay`), the data's freshness, a link from a tile to its report, and the list of reports with the decision each supports. **Report** (`/reporting/reports/{id}`): the parameters the definition takes (a period, opening on the month so far; a location, "all" by default), the rows with the server's totals, dates and money formatted by the shell and nothing added up in the browser; with `rpt.export.run`, "Download CSV" (fetched with the token, saved as the server names it) and "Print (A4 PDF)", which requests a run in the user's language and asks for it every two seconds until it is READY (a link to the PDF) or FAILED. The texts are in `reporting.messages.json`; the report, column and tile names the API answers with are the backend's `m8.*` ids with the same texts, so screen and PDF agree.

## Tests

| Test | What it proves |
|---|---|
| `ReportingHttpPostgresIntegrationTest` | Every operation over HTTP: the three definitions; the stock position for its owner with the value, by location, nothing for the counterparty, everything with the value for the Federation view; trade by distributor for both parties and nobody else, in and out of the period; the invoices with their totals; the period and unknown-report refusals (422); the CSV header in English and its rows; the dashboard of seller, buyer and a third entity; a print run REQUESTED, audited and published, not visible to another entity; a run without its period refused with nothing committed. |
| `web/src/modules/m8reporting/reportView.test.ts` | The default period, the parameters sent per definition, the CSV file name, the numeric columns. |
| `ReportRunPostgresIntegrationTest` | The worker with a stand-in renderer: the report printed with `m8-report` in the run's language (Tamil title), READY with the key, audited and published, a second outcome refused; a renderer failure recorded FAILED with its message id; an unknown run; the request guards (no user, unknown report, a language outside the catalogue) commit nothing; the print model formats cells and totals. |
| `TradeProjectionPostgresIntegrationTest` (with `TradeFlows`) | Rebuild equivalence over five sets of twelve random order flows (submitted only, rejected or cancelled, in transit, received and invoiced) with redeliveries; one whole flow leaves one row per event on its owner's side with the counterparty, the business date in Colombo, the accepted and received volume and the invoice totals. |
| `StockPositionProjectionPostgresIntegrationTest` | Rebuild equivalence over five random streams of 60 movements (two entities, three locations each, two sources, redeliveries); a late or repeated movement changes nothing; an event of another type is ignored; a delivery in another entity's scope is refused by row-level security; nothing is audited or published. |
| `RlsMatrixIntegrationTest`, `SchemaRulesIntegrationTest` | Both tables follow the template (found by their `owner_entity_id`), and `app_rw` cannot delete from them. |

## Deviations from 28A

- `projection_state` is keyed by (name, owner_entity_id), not by name: a consumer writes in the scope of the event's owner, and row-level security admits only that entity's row. `last_seq`, `shadow_table` and `rebuild_started_at` wait for the rebuild service: the consumer is handed no source sequence, and the shadow table swap needs a privileged role.
- `stock_position` takes the lot's quantity from the event instead of adding deltas (above), and carries `last_source` and `last_movement_seq` for it. `expiry_date`, `received_at`, `days_held` and `negative_since` wait for the expiry reports. `canonical_sku_id` is the SKU itself until M2 publishes SKU merges.
- The projections subscribe to `"*"` and filter, for the envelope (above).
- `trade_document_fact` (one row per document, updated) is `trade_document_event` (one row per event, inserted) plus `trade_line_fact`: row-level security lets no party update the other's row, and an inserted row per event is idempotent by its key. `settled`, `credited`, `committed_eta`, `delivered_at` and `fill_rate` wait for the payment, credit note and fill-rate reports. `dispute_register` and `exposure_snapshot` are not built.
- The report definitions are code (`ReportCatalogue`, three), not data in `report_definition` with the validator, the audience check and the twelve placeholders of 28A section 3.1. `report_run` has `location_id` (a shop's run stays the shop's), `error_code` and `completed_at`, and no `scope`, `freshness` or `stale_locations` (no freshness resolver yet). The events are `report_run.requested.v1` and `report_run.completed.v1`. CSV stands in for the xlsx export worker, and is answered at once rather than as an export job.
