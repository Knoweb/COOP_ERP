# m3pricing — M3 Pricing & Rules

The living guide of the module (AGENTS.md): with the tests and the seed files it supersedes 23A for day-to-day work. Read `hello/README.md` first: its rules apply here unchanged. Design: doc 23; procedure: 23A.

## What is built

| Ticket | What | Where |
|---|---|---|
| M3-01, M3-02 (JVM) | The engine: `Money`, `Quantity`, `Tax`, `Rounding`, the model, `PricingSnapshotIndex`, `PriceResolver`, `RuleEvaluator`, `BasketResolver`, `TradePriceResolver` | `backend/shared-engine` (package `lk.coopfed.knoweb.engine`) |
| M3-03 | `pricing.price_list` and `pricing.price_list_line` with RLS, the trade-list seeds | `db/migration/m3pricing/V0003__price_lists.sql`, `seed/m3pricing/` |
| M3-04 | TRADE price lists: create, draft a new version, set lines (tiers), publish; the trade price of an order line; M3's answer to M1's `TradePriceListCheck` | `internal/list`, `internal/queries`, `internal/relationship`, `web/PricingController` |

Not built yet: rules (M3-05), control prices (M3-06), MRP policy and adjustment types (M3-07), reviews (M3-08), the snapshot contributor and labels (M3-09), the other screens (M3-10), the freeze (M3-11).

## Package map

| Package | What |
|---|---|
| `api/` | commands `CreatePriceList`, `DraftNewVersion`, `SetLines` (answers `SetLinesResult`), `PublishPriceList`; events `price_list.drafted.v1`, `price_list.lines_set.v1`, `price_list.published.v1` |
| `query/` | `PricingQueries`: `getPriceList`, `listPriceLists`, `lines`, `resolveTradePrice(relationship, sku, uom, qty, date)` and `resolveTradePrice(seller, buyer, sku, uom, qty, date)`; the views |
| `internal/list/` | the four handlers, `AuthoringValidator`, `PriceListStore` (reads only), `PriceListRules` |
| `internal/queries/` | `PricingQueriesImpl`: loads lines, calls `TradePriceResolver` of the engine |
| `internal/relationship/` | `PublishedTradeListCheck`, M3's implementation of M1's `api.TradePriceListCheck` |
| `web/` | `PricingController`, implementing the generated `PricingApi` |

## For M4: pricing an order line

Call `PricingQueries.resolveTradePrice(seller, buyer, skuId, uom, orderedQty, date, scope)` in the buyer's (or seller's) scope. It finds the relationship in force on the date (M1 `lookupRelationship`), reads the TRADE list it binds, takes the newest version whose `apply_from` is on or before the date, and returns the line whose tier is the highest not above the quantity (doc 10 A-03: the ordered quantity). The price is per unit, tax-exclusive, four decimals; empty means no price (not sellable under that relationship). The buyer reads the list through the `buyer_read` policy: the published versions of the list its relationship binds, nothing else of the seller's.

## Rules the code keeps

- A relationship binds the id of **version 1** of a list (`root_price_list_id`); every later version carries it, so a new version needs no relationship change.
- A line is written only while its list is a DRAFT: the handlers guard it and the trigger `pricing.line_of_a_draft` refuses anything else, even for the migrator.
- SetLines stores the whole set or nothing: when any line is refused the answer lists every outcome and nothing is written, audited or published.
- Publication runs the validator again (a SKU may have been deactivated since), dates the lines `apply_from`, publishes the draft and supersedes the previous published version.
- "Today" for a list is the calendar date in the business time zone (an entity has no business date, CR-19A-8).

## Deviations from the implementation guide

1. **`root_price_list_id`** is a column 23A section 3 does not have. Reason: relationships bind one id (M1 `price_list_id`) while every version is its own row; walking `source_version_id` in a policy and in every lookup is costlier and harder to read.
2. **Draft lines carry `effective_from`** = the day they were set, because the column is NOT NULL in 23A's DDL while 23A section 7 says "effective_from = null until publish"; publication sets it to `apply_from`.
3. **Events and audit for drafts.** Doc 23 section 4.1 leaves draft edits unaudited and names no event for CreatePriceList, DraftNewVersion or SetLines; the build requires every handler to audit and publish (AGENTS.md), so `PRICELIST_LINES_SET` and the events `price_list.drafted.v1` and `price_list.lines_set.v1` exist.
4. **`GET /v1/pricing/resolve/trade`**, not POST as 23A section 5 writes: it changes nothing and so needs no Idempotency-Key.
5. **Paths** are `/v1/pricing/lists...` as 23A section 5; the scaffold's `/v1/pricing/price-lists` is gone.
6. **Permission codes**: `prc.pricelist.author` and `prc.pricelist.publish` of 23A section 3.1, and `prc.pricelist.view` for the reads, which the guide does not name (as M2's `cat.sku.view`, CR-22A-3). `prc.pricelist.publish` requires the second factor in the catalogue and on the handler: only TRADE lists exist so far, and 23A asks MFA for TRADE.
7. **The engine's model is in the package `lk.coopfed.knoweb.engine`**, not `engine.model` and `engine.snapshot` (23A section 4): Spring Modulith treats a sub-package of a module as internal, and the engine is pure Kotlin with no way to declare a named interface.
8. **`app_rw` may DELETE from `pricing.price_list_line`** (listed in `SchemaRulesIntegrationTest.DELETE_BY_DESIGN`): SetLines replaces a draft's lines ("upsert draft lines", 23A section 7). The trigger `pricing.line_of_a_draft` refuses a delete once the list is published, so no published line is ever deleted.

Deferred for the demo (also in `docs/PROGRESS.md` and `docs/PLAN_TO_M2.md`): RETAIL and ADVISORY lists (they need the ceiling checks of M3-06); carry-forward of unchanged lines into a new version and the closure of the previous version's lines at `apply_from − 1` (the lookup reads the newest version in force instead); the control-price ceiling in the AuthoringValidator (M3-06); units other than the SKU's base unit on a trade line (M2 publishes no conversion query); `pricing.review_raised.v1` for a trade price above the lowest MRP (the line carries the review flag only); WithdrawDraft; the publish fan-out consumer (RETAIL only, no RETAIL lists yet).
