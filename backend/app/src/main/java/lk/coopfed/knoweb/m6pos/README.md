# m6pos — M6 Point of Sale, the central side

The living guide of the module (AGENTS.md). The till itself (selling, printing, offline) is the till track's, under `till/`; this package is what central does with what a till uploads (26A section 10). Built so far: the minimum demo phase 3 needs ("the shop sells at the till; after sync the shop's stock goes down and the sale shows centrally"). Read `hello/README.md` first: its rules apply unchanged.

## The path of a sale

```
till: open session -> scan, scan, scan, cash, complete -> close session
  |  POST /v1/sync/devices/{id}/batches (doc 32; K-08 BatchIngestor, EventApplier: content hash checked)
  v
kernel.event_outbox (source = the device, owner = its entity, location = its shop)
  |  consumer framework: OWN scope of the entity at the shop, device on the scope
  +--> m6.sessions  till_session.opened.v1 / .closed.v1 -> RecordSessionHandler -> pos.till_session(_close)
  +--> m6.receipts  receipt.issued.v1                  -> RecordReceiptHandler -> pos.receipt, _line, _tender
  +--> m5.sales     receipt.issued.v1                  -> M5 ApplySaleHandler  -> SALE movements at the shop
```

M5 deducts from the till's own bundle, as 25A section 7.4 says ("m5.sales consumes receipt.issued.v1"); M6 does not re-publish it. M6 publishes `receipt.recorded.v1` and `till_session.recorded.v1` (named apart from the till's types, so M6 never consumes its own events).

## What is where

| Path | What it holds |
|---|---|
| `api/` | `ReceiptRecorded` (`receipt.recorded.v1`), `TillSessionRecorded` (`till_session.recorded.v1`). |
| `query/` | `PosQueries`: a location's receipts (with lines and flags) and its sessions. |
| `internal/ingest/` | `PosIngestConsumer` (reads the bundles), `RecordReceiptHandler`, `RecordSessionHandler`, `IngestGuards`. |
| `web/` | `PosController`: `GET /v1/pos/receipts?locationId=`, `GET /v1/pos/sessions?locationId=` (`pos.receipt.view`). |
| `resources/db/migration/m6pos/V0001__pos.sql` | `till_session`, `till_session_close`, `receipt`, `receipt_line`, `receipt_tender`: insert-only, template RLS. |
| `resources/seed/m6pos/audit-event-types.yaml` | `RECEIPT_RECORDED`, `RECEIPT_FLAGGED` (REVIEW), `TILL_SESSION_OPENED`, `TILL_SESSION_CLOSED`. |

## The receipt bundle (doc 32 section 3.1)

`receipt.issued.v1`, `content_hash` on the envelope (and in the payload), `payload`:

- `document`: doc 18's columns, the ones `BundleHash` covers: `document_id`, `doc_type_code` RCT, `series_id` (the till position's RCT series), `doc_number`, `doc_number_display`, `owner_entity_id`, `location_id`, `till_position_id`, `device_id`, `issued_at`, `business_date`, `operator_user_id`, `currency`, `net_amount`, `tax_amount`, `gross_amount`, `origin` OFFLINE, `device_seq`;
- `lines[]`: `line_no`, `sku_id`, `batch_id` (the batch the till resolved; may be absent), `uom_code`, `qty`, `unit_price`, `tax_amount`, `line_total`;
- `tenders[]`: `seq`, `kind`, `amount`; `session_id`.

Sessions: `till_session.opened.v1` {`session_id`, `till_position_id`, `operator_user_id`, `business_date`, `opened_at`, `float_amount`}; `till_session.closed.v1` {`session_id`, ..., `closed_at`, `counted_cash`, `expected_cash`, `variance`}.

## Apply and flag, never refuse (AGENTS.md)

A receipt is kept whatever central thinks of it. `RecordReceiptHandler` flags `LOCATION_MISMATCH` (the document names another location than the device's shop; the receipt is kept at the device's shop), `SESSION_UNKNOWN` (its session has not arrived), `NO_LINES`, with a REVIEW audit record. M5 flags an oversell (the lot goes negative: `STOCK_LOT_NEGATIVE`, `lot.negative.v1`), a sale from a batch the shop held no lot of (`SALE_WITHOUT_LOT`), and a line with no batch and no lot of its item (`SALE_LINE_UNRESOLVED`, not posted, for a person). The only refusals are of shape: `m6.scope.device_required`, `m6.receipt.malformed`, `m6.session.malformed` (a fact the gateway would have quarantined).

## Tests

| Test | What it proves |
|---|---|
| `TillSaleEndToEndIntegrationTest` | The `TillSimulator` takes the snapshot, opens a session, sells three items by barcode, closes and uploads; the outbox's events are delivered to `m6.sessions`, `m6.receipts` and `m5.sales` as the consumer framework delivers them: the shop's stock down by what was sold in the device's movement sequence, the receipt with its till-series number and origin OFFLINE, the session closed with its variance, both reads over HTTP, the audit and events, a redelivery changing nothing; an oversell applied and flagged. |
| `IngestHandlersPostgresIntegrationTest` | A receipt with a location mismatch and an unknown session kept and flagged; every guard with nothing committed. |

## Deviations from 26A, with reasons

1. **The receipt lives in `pos.receipt`, not in the kernel's document base.** 26A's ReceiptBundleHook inserts `doc_receipt` extending an OFFLINE `kernel.document`; the kernel's bundle handler that writes an offline document there (numbered by the till, lines after the header) is K-08-F4 in `docs/PLAN_TO_M2.md`, not built, and M6 may not write the kernel's schema. M6 keeps the issued receipt with its document identity (series, number, device sequence, content hash, origin) until K-08-F4 lands; then the header and lines move to the document base and `pos.receipt` becomes the `doc_receipt` extension.
2. **M5 consumes the till's bundle directly**, not an event M6 re-publishes after its hook (26A section 10 says the hook publishes `receipt.issued.v1`): with no kernel bundle path in between, a second event of the same type would reach M5 twice. 25A section 7.4 reads the same way.
3. **A session's close is a row of its own** (`till_session_close`), so no row is updated.
4. **Deferred for the demo**: re-validation of each line at the reported snapshot and engine versions, voids and refunds (`receipt.voided.v1`, `receipt.refunded.v1`, SALE_REVERSAL), the cross-session refund, the variance threshold and its REVIEW, cash movements, device health and sync-health queries, M7's account tenders, park/resume, weigh-and-price, the Android till itself.
