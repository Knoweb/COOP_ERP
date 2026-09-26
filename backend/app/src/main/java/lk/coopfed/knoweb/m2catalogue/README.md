# m2catalogue — M2 Catalogue & Batch

The living guide of M2 (AGENTS.md): once code exists, this file, the seed files and the tests supersede `docs/design/22A_M2_Implementation_Guide` for day-to-day work. Every deviation from the guide is recorded at the end, with the reason. Read `hello/README.md` first: its six rules apply here unchanged. The reasoning behind the module is `docs/design/22_M2_Catalogue_Batch`.

M2 defines what can be counted and sold: SKU identity in one global namespace, units and conversions, barcodes, tags, tax categories and rates, images, and the batch as physical identity carrying expiry and printed MRP. It owns identity, not holding: stock and cost are M5's, prices are M3's.

## What is where (after M2-01)

| Path | What it holds |
|---|---|
| `api/` | The published contract. Empty until M2-02 adds the SKU commands and events. |
| `internal/seed/M2SeedLoader` | Loads the reference data of `seed/m2catalogue` on start, as the migrator (the same arrangement as `M1SeedLoader`). Refuses to start without `coop-erp.system.entity-id` (the Federation owns the tax rows). |
| `internal/batch/BatchPartitionMaintainer` | The kernel's daily partition-maintenance job calls it (`kernel.api.PartitionMaintainer`); it runs `catalogue.ensure_batch_partitions(3)`. |
| `resources/db/migration/m2catalogue/V0001__catalogue.sql` | The tables of M2-01, their row-level security, grants, and the batch partitions. |
| `resources/db/migration/m2catalogue/V0002__sku_code_prefix_index.sql` | The `varchar_pattern_ops` index for the code prefix search (M2-02 review). |
| `resources/db/migration/m2catalogue/V0003__catalogue_schema_review.sql` | The schema findings of the review of 26 September: child rows follow the SKU's owner, `batch_key`, the default partition and the atomic partition function, the `batch (batch_id)` index, UPDATE narrowed to columns. |
| `resources/db/migration/m2catalogue/V0004__batches_and_suppliers.sql` | M2-05: `supplier`; the correction of a batch by an entity that did not register it (the trigger `batch_correction` and its two policies). |
| `resources/seed/m2catalogue/` | `uom.yaml`, `tax.yaml`, `tags.yaml` (loaded by `M2SeedLoader`), `audit-event-types.yaml` (loaded by the kernel's `AuditEventTypeSeedLoader`). |
| `resources/seed/m1party/permissions.yaml` | The eleven `cat.*` permissions of M2, at the end of M1's file (see "Permissions" below). |
| `resources/openapi/m2catalogue.yaml` | The slice: the SKU operations (M2-02), conversions, barcodes and the lookup (M2-03/M2-04). |
| `internal/sku/`, `internal/unit/`, `internal/barcode/`, `internal/queries/` | The SKU aggregate and its router; the conversion handler; the barcode handlers and `GtinParser`; the reads (`CatalogueQueries`, `BarcodeLookupQuery`, `BatchQueriesImpl`). |
| `internal/batch/`, `internal/supplier/` | M2-05: `RegisterBatchHandler` (also `api.BatchRegistration`), `CorrectBatchHandler`, `BatchGuards`, `BatchStore`; `RegisterSupplierHandler`. |
| `internal/integration/NoInventoryLotQuery` | M2's answer for M5 until M5 exists: no SKU and no entity holds a lot. |
| `web/CatalogueController`, `web/BarcodeController`, `web/BatchController`, `web/SupplierController` | The generated `CatalogueApi` (SKUs, conversions), `BarcodeApi` (barcodes, lookup), `BatchApi` (batches, correction) and `SupplierApi`. |
| `web/src/modules/m2catalogue/` | The module registration only: no route, no navigation entry, until M2-10. |
| `src/test/.../m2catalogue/` | `CatalogueSchemaIntegrationTest` (tables, forced RLS, policies, grants, partitions, the default partition and the concurrent roll-over), `CatalogueRlsIntegrationTest` (rows of the RLS matrix of 22A section 9, the child rows of a SKU, one identity per batch), `internal/seed/M2SeedLoaderTest`, `internal/batch/BatchPartitionMaintainerPostgresIntegrationTest`, `internal/batch/BatchHandlersPostgresIntegrationTest` and `BatchGuardsTest` (M2-05). |

The scaffold of `make new-module NAME=m2catalogue SCHEMA=catalogue ENTITY=sku` was run and its greeting-shaped copy removed: the Sku entity, handler, queries and controller, the greeting migration, the development seed, the copied integration test, the web page and the greeting message ids. None of it was needed to prove the scaffold: hello keeps its own tests, and the M2 tests above prove the schema.

## The schema

V0001 creates `uom`, `tax_category`, `tax_rate`, `sku`, `sku_uom_conversion`, `sku_barcode`, `tag`, `sku_tag` and `batch`, as 22A section 3 writes them; V0003 (the review of 26 September) adds `batch_key` and changes the policies, grants and partitions listed under "Deviations"; V0004 (M2-05) adds `supplier` and the correction trigger. The other tables of section 3 arrive with the ticket that first writes them, each in a new migration of this lane: `sku_image` (M2-06), `sku_alias` (M2-07), `duplicate_suspect` (M2-08), `location_assortment` (M2-09).

Who reads and writes what:

| Table | Reads | Writes (app_rw) |
|---|---|---|
| `uom` | every scope except NONE | nothing; seeded only |
| `tax_category` | every scope except NONE | the owner (the Federation) in OWN: INSERT, UPDATE |
| `tax_rate` | every scope except NONE | the owner (the Federation) in OWN: INSERT, and UPDATE of `effective_to` only (PublishTaxRate closes the rate in force) |
| `sku` | the owner in OWN; everyone when SHARED; Federation view; external grant | the owner in OWN: INSERT, UPDATE |
| `sku_uom_conversion` | the owner of the row in OWN; everyone, the rows the SKU's owner wrote on a SHARED SKU | the SKU's owner in OWN: INSERT, and UPDATE of `effective_to` only |
| `sku_barcode` | the owner of the row in OWN; everyone, the rows the SKU's owner wrote on a SHARED SKU and every factory code (not INTERNAL) on one | in OWN: INSERT by the SKU's owner, or by any entity registering a factory code on a SHARED SKU (22A section 6: "INTERNAL only for own SKUs"); UPDATE of `status` and `batch_id` only |
| `tag` | governed tags by everyone; a local tag by its owner | the owner in OWN (local tags): INSERT, UPDATE |
| `sku_tag` | the owner of the row in OWN; everyone, the rows the SKU's owner wrote on a SHARED SKU | in OWN: INSERT by the SKU's owner, or by an entity putting one of its own local tags on a SHARED SKU (doc 22 section 3.5); never changed |
| `batch` | every scope except NONE (global identity) | the registering entity in OWN: INSERT, and UPDATE of `status` only |
| `batch_key` | every scope except NONE (the identity of a batch, read like the batch) | filled by the trigger `batch_one_identity` on every first registration, as the registering entity: INSERT, and UPDATE of `batch_id` only; a correction re-points it through the trigger `batch_correction` (V0004) |
| `supplier` | every scope except NONE (`authenticated_read`: a batch is global and carries its supplier) | the owner in OWN: INSERT only (RegisterSupplier; no operation changes a supplier) |

Every table carries the template of `db/migration/RLS_POLICY_TEMPLATE.md` (own_read, own_write, fed_view, ext_view, with own_* testing the class OWN) plus `own_update` where the table is updated, and the reference reads named `authenticated_read`, `shared_read`, `governed_read`. The reference tables the seed loader fills (`uom`, `tax_category`, `tax_rate`, `tag`) have `seed_reference TO app_seed`. No table here has a counterparty, so none has `party_read`. The `own_write` of the three child tables of a SKU asks `catalogue.sku` who owns the parent (an EXISTS under the caller's own policies), so `RlsMatrixIntegrationTest` lists them as a departure: the matrix's made-up rows have no parent SKU and their insert is refused.

### One identity per batch

Doc 22 section 3.7: "(sku_id, supplier_id, batch_no) unique". The unique index of `batch` must carry `created_at`, the partition key, so it never held that promise, and RegisterBatch's "return existing or insert" (22A section 6) would race under two GRNs confirmed together. `catalogue.batch_key` is a plain table with a plain unique key on `(sku_id, coalesce(supplier_id), batch_no)`, filled by a `BEFORE INSERT` trigger on `batch`: a first registration inserts the identity row, a second meets the key (`batch_key_identity_uq`), a correction (`corrects_batch_id` set) re-points the identity at the replacement row. Since V0004 the correction branch is a trigger of its own, `batch_correction` (AFTER INSERT, when `corrects_batch_id` is set), which runs as the function's owner: the corrected batch must be a REGISTERED batch of the same SKU, it becomes SUPERSEDED, and its identity row names the replacement. That lets a lot holder or the Federation correct a batch another entity registered (22A section 6, CorrectBatch: "caller owns a lot of the batch or F"); the replacement row itself is the caller's own (`own_write`), and who may correct is `CorrectBatchHandler`'s guard, because the database cannot ask M5. The policies `correction_read`, `correction_supersede` (REGISTERED to SUPERSEDED only) on `batch` and `correction_read`, `correction_repoint` on `batch_key` admit the function's owner (a member of `app_seed`) for exactly that. RegisterBatch looks the identity up in `batch_key` by key, which no longer needs a partition scan; `batch (batch_id)` is indexed for the look-up by id.

### Batch partitions

`catalogue.batch` is partitioned by the month of `created_at` (doc 22 section 9.1). `catalogue.ensure_batch_partitions(months_ahead)` creates the missing monthly partitions, each with RLS forced and the parent's policies copied (`catalogue.secure_batch_partition`), and returns how many it created. Three things keep a registration from failing for want of a partition:

- **The kernel's daily job calls it.** `PartitionJob` (partition-maintenance, 00:10) keeps the kernel's own audit and outbox partitions and then every `kernel.api.PartitionMaintainer` bean; M2's is `internal/batch/BatchPartitionMaintainer`, three months ahead like the kernel's own tables.
- **A default partition** (`batch_default`) catches a row whose month has no partition yet: an import with an old `created_at`, or a day the job did not run. When the month's partition is created later, the function builds it beside the parent, moves those rows into it and attaches it (a default partition holding a row of the range blocks a plain `CREATE TABLE ... PARTITION OF`). The move is a DELETE by the function's owner, the migrator, which the policy `partition_move TO app_seed` on the default partition alone admits; `app_rw` still deletes nothing anywhere.
- **Two instances rolling over together both succeed**: the function takes an advisory transaction lock and creates `IF NOT EXISTS`, so the second finds the month in place instead of failing on "already exists".

## Seeds

| File | Table | Rule |
|---|---|---|
| `uom.yaml` | `catalogue.uom` | inserted when the code is absent; never changed afterwards |
| `tags.yaml` | `catalogue.tag` | governed (owner null); inserted when the code is absent |
| `tax.yaml` | `catalogue.tax_category`, `catalogue.tax_rate` | owner = the Federation (`coop-erp.system.entity-id`); a category when its code is absent, a rate when no rate of the category overlaps it. When the property is not set the application does not start (`M2SeedLoader` refuses it): every SKU cites a category, so an instance without them fails on the first registration instead. The local stack sets `COOP_ERP_SYSTEM_ENTITY_ID`; `PostgresIntegrationTest` sets a test Federation for every test context |
| `audit-event-types.yaml` | `kernel.audit_event_type` | the audit codes of 22A section 6, all INFO |

A new tax rate is published through PublishTaxRate (M2-03), never by editing `tax.yaml`. The transliteration table of 22A section 3.1 (`transliteration-v1.csv`) belongs to M2-08.

### Permissions

22A section 3.1 says the permission catalogue of M2 is "appended to the M1-owned catalogue file". `M1SeedLoader` reads one file, `seed/m1party/permissions.yaml`, and `security.permission` is M1's table, so the eleven `cat.*` codes are at the end of that file under a comment, with `module: m2catalogue`. `M1SeedLoaderTest` counts them.

## Units, conversions and barcodes (M2-03 / M2-04)

Pull request #117, by Shehan, reworked on 26 September against the M2-01 tables. The rows are `catalogue.sku_uom_conversion` and `catalogue.sku_barcode` of V0001; there is no other table. The operations are the ones 22A section 5 names, as sub-resources of the SKU:

| Operation | Handler | Permission | Guards (22A section 6) | Audit / event |
|---|---|---|---|---|
| `POST /v1/catalogue/skus/{skuId}/conversions` | `internal/unit/DefineConversionHandler` | `cat.sku.create_local` | owner; unit exists; factor > 0; not the base unit; a weighed SKU takes no count unit; the exclusion constraint (`m2.conversion.overlap`) | `CONVERSION_DEFINED`, `conversion.defined.v1` |
| `POST /v1/catalogue/skus/{skuId}/barcodes` | `internal/barcode/RegisterBarcodeHandler` | `cat.barcode.manage` | SKU active; symbology valid; check digit for EAN-13, EAN-8, UPC-A (`GtinParser`); unique per rule (B-I2); a factory code by the SKU's owner, an INTERNAL code by the SKU's owner too (22A section 6, V0003 `own_write`) | `BARCODE_REGISTERED`, `barcode.registered.v1` |
| `DELETE /v1/catalogue/skus/{skuId}/barcodes/{barcode}?symbology=` | `internal/barcode/RetireBarcodeHandler` | `cat.barcode.manage` | the caller's own ACTIVE row; a reason | `BARCODE_RETIRED`, `barcode.retired.v1` |
| `POST /v1/catalogue/skus/{skuId}/barcodes/{barcode}/link` | `internal/barcode/LinkBarcodeToBatchHandler` | `cat.barcode.manage` | the caller's own ACTIVE row; the batch belongs to the SKU | `BARCODE_LINKED`, `barcode.linked.v1` |
| `GET /v1/catalogue/lookup` | `internal/queries/BarcodeLookupQuery` (through `CatalogueQueries.lookupByBarcode`) | `cat.sku.view` | exact ACTIVE row; else GTIN + lot resolves the batch by number; INTERNAL codes in the owner's scope only | read |

A new conversion of a unit that already has an open-ended row closes that row the day before the new one starts (doc 22 section 3.2: a case-size change is a new effective-dated row; V0001's `own_update` exists for exactly this). A row dated before the open one, or inside a closed one, is the exclusion problem.

Whose row a barcode is (doc 22 sections 3.3 and 4.2): the row's `owner_entity_id` is always the caller (the `own_write` policy admits nothing else). A factory code identifies the item, so only the SKU's owner registers it and it is unique federation-wide (`barcode_factory_unique`). An INTERNAL code is an entity's own sticker or weigh label on a SKU it owns (22A section 6: "INTERNAL only for own SKUs"; a society's sticker on the Federation's SHARED stock would need a change request), unique within that entity (the primary key), and the lookup resolves it in that entity's scope only. Retire and Link read the row by the caller's entity, so another entity's row of the same code is simply not found. The audit subject of all three is the SKU (a registry row has no id of its own).

`GtinParser` validates the check digit of a GTIN-8, -12, -13 or -14 and splits a GS1 element string (AI 01, 17, 10, group separator U+001D) that a till sends whole; the lookup accepts either the code as scanned or the parsed `gtin`, `lot` and `expiry`. `LookupResult` answers a missing Sinhala or Tamil name with the English one and the fallback flag set; `factorToBase` is null when no conversion of the code's unit is in force today; `sellThrough` is false and `thumbKey` null until `location_assortment` (M2-09) and `sku_image` (M2-06) exist. The per-instance cache of 22A section 7 is not built yet.

## Batches and suppliers (M2-05)

A batch is global identity: one physical batch is held in turn by the Federation, a distributor and a society, each holding an M5 lot (doc 22 section 3.7, ADR-38). Its `owner_entity_id` is the registering entity, the one whose GRN confirmation registered it (AGENTS.md idea 2), never the holder.

| Operation | Handler | Permission | Guards (22A section 6, in order) | Audit / event |
|---|---|---|---|---|
| RegisterBatch (no operation; `api.BatchRegistration` for M4's GRN confirmation and M5's repack) | `internal/batch/RegisterBatchHandler` | `CommandHandler.INTERNAL`: the caller's command was checked (doc 22 section 4.1, CR-19A-6) | an OWN scope (a shop's too); the SKU visible and LOCAL or SHARED (`m2.batch.sku_not_active`); the supplier, when given, visible and ACTIVE; the number, or the synthetic one; the (sku, supplier, batch_no) look-up returns the batch already registered, with nothing audited; the MRP when `has_printed_mrp`, positive; the expiry when `expiry_tracked`, not before the manufacture | `BATCH_REGISTERED` on the batch, `batch.registered.v1` |
| `POST /v1/catalogue/batches/{batchId}/correct` | `internal/batch/CorrectBatchHandler` | `cat.batch.correct`, MFA | an OWN scope; the batch exists and is REGISTERED (`m2.batch.superseded`: correct the replacement); the Federation, or a lot holder by `InventoryLotQuery.holdsLotOf` (`m2.batch.not_holder`); a reason; a positive MRP, an expiry not before the manufacture; a value that differs (`m2.batch.correction_unchanged`) | `BATCH_CORRECTED` on the replacement, before = the old batch, `batch.corrected.v1` (M5 re-points lots from `correctsBatchId` to `batchId`) |
| `POST /v1/catalogue/suppliers` | `internal/supplier/RegisterSupplierHandler` | `cat.supplier.manage` | an entity-wide OWN scope; a name, unique within the entity (`m2.supplier.name_taken`) | `SUPPLIER_REGISTERED`, `supplier.registered.v1` (id and owner, no name) |
| `GET /v1/catalogue/batches?skuId=&batchNo=&expiringBefore=&limit=`, `GET /v1/catalogue/batches/{batchId}`, `GET /v1/catalogue/suppliers` | `internal/queries/BatchQueriesImpl` (`query.BatchQueries`) | `cat.sku.view` | reads; the batch list needs the SKU, newest first, SUPERSEDED rows included with the batch they correct | – |

**Synthetic batches** (doc 22 section 3.7): when the SKU is not batch-tracked, or the supplier printed no number, the batch is `S-<document number>-<line>` with `is_synthetic` set. The hyphen before the line is ours (doc 22 writes "'S-' + document number + line"): without it line 1 of GRN-11 and line 11 of GRN-1 are one batch. A second call for the same line answers the same batch, so a retried GRN confirmation registers nothing twice.

**Two registrations together**: RegisterBatch takes a transaction advisory lock on the identity before the look-up, so a second GRN of the same batch waits and then finds the first one's row instead of failing its whole transaction on `batch_key_identity_uq`.

**A correction** keeps sku, supplier, batch number, manufacture date, origin document and the synthetic flag, and takes the new MRP or expiry; the replacement is the corrector's row. Its `created_at` is `clock_timestamp()`: V0001's unique index on (sku, supplier, batch_no, created_at) would otherwise refuse a replacement beside a batch registered earlier in the same transaction. A superseded batch is not corrected again: the chain grows from its replacement.

**Until M5**: `NoInventoryLotQuery.holdsLotOf` answers false, so only the Federation corrects a batch. The pull request that adds M5's implementation deletes the stub. The "M5 stub consumes `batch.corrected`" of the plan is `BatchHandlersPostgresIntegrationTest.aLotHolderThatDidNotRegisterTheBatchCorrectsItsExpiry`: the published event, read back from JSON, re-points a stub's lots.

## Snapshot contributor (K-08 part 2; the contributor half of M2-09)

`internal/snapshot/CatalogueSnapshotContributor` implements the kernel's `SnapshotContributor` (19A section 8; 22A section 7.3). The kernel's snapshot builder calls it in the device's scope, in one read-only, repeatable-read transaction.

- Table `sku`, row id = SKU id: the item as the till sells it, with `conversions` (in force today or later, factors as text), `barcodes` (ACTIVE only) and `tags` inside the row. Which SKUs: until `location_assortment` exists (M2-09), every LOCAL SKU of the shop's entity and every SHARED one, which is what row-level security shows the device; a DRAFT or INACTIVE SKU is left out, so the till receives a tombstone.
- Table `tax_category`, row id = category id, with its `rates` in force or scheduled (rates as text).
- **Still M2-09's**: the assortment and its change-log producers (`ChangeLog.append` for the shops of an assortment change, an upsert of `sku` for a change to its barcodes, conversions or tags). `batch` is named by M5's lot contributor (22A: "ids supplied by M5") and is not served yet.

## What the next tickets build on

- **M2-02 SKU aggregate**: `catalogue.sku` with its indexes and policies; the units and tax categories a SKU cites are seeded; `SKU_*` audit codes are in place; permissions `cat.sku.create`, `cat.sku.create_local`, `cat.sku.deactivate`. Adds the first operations to the slice and the first `api` records.
- **M2-03 units, tags, tax**: `sku_uom_conversion` and `tax_rate` with their exclusion constraints; `tag` and `sku_tag`. Open: a governed tag written by the Federation (no owner) needs a policy the template does not give; TagSku "replace set" needs a way to remove an assignment, and app_rw may not DELETE.
- **M2-04 barcodes**: `sku_barcode` with `barcode_factory_unique` (B-I2).
- **M2-05 batch** (done, above): RegisterBatch for M4 and M5 through `api.BatchRegistration`; CorrectBatch by the Federation or a lot holder (V0004's trigger, `CatalogueRlsIntegrationTest.anotherEntitysCorrectionSupersedesTheBatchAndMovesItsIdentity`); `supplier`. Left for later: the batch-level duplicate suspect on near-identical numbers (22A section 6.1) needs `duplicate_suspect` (M2-08); M5 implements `InventoryLotQuery.holdsLotOf` and consumes `batch.corrected.v1`; M10 absorbs `supplier`.
- **Dependencies of 22A section 4** still to declare in `package-info.java` when the packages exist: `m1party::api` (no `NamedInterface` on M1's api package yet), `m3pricing::query` and `m5inventory::query` (not built).
- **M2-06 to M2-09**: add `sku_image`, `sku_alias` (with the promotion policy on `sku`: the Federation takes over a LOCAL row, which `own_update` does not admit), `duplicate_suspect`, `location_assortment`.

## Deviations from the implementation guide

Every difference between the schema as migrated (V0001 to V0004) and 22A section 3, and between the handlers and 22A sections 5 and 6. The header of V0001 says "three changes"; it is a merged migration and stays as written, so this list is the one to trust.

1. The collations and the trigram operator class are qualified with `kernel.` (22A writes `en_icu`, `gin_trgm_ops`): the kernel baseline created them in the kernel schema and this lane migrates with `search_path = catalogue`.
2. The (sku, supplier, batch_no, created_at) key of `batch` is a unique index, not a table constraint: a constraint cannot hold the `coalesce` expression. As 22A writes it, it includes `created_at`, which PostgreSQL requires of a unique index on a partitioned table, so it does not stop a second registration of the same batch number; `batch_key` (V0003, not in 22A) holds the identity with a plain unique key, filled by the trigger `batch_one_identity`.
3. `own_update` policies on the tables that are updated: the template has none, and under FORCE ROW LEVEL SECURITY an UPDATE with no UPDATE policy changes no row.
4. `shared_read` tests `kernel.scope_class() <> 'NONE'` besides what 22A writes (`status = 'SHARED'`, `true` for batch): a transaction with no scope reads nothing (RLS template). The conversions, barcodes and tag assignments of a SHARED SKU are readable by everyone as well, through the same policy name (22A says only "SHARED visible to all", and a till cannot sell a shared item without its barcodes), limited since V0003 to the rows the SKU's owner wrote and to factory barcodes: an INTERNAL code is unique per owner only (B-I2) and must not reach another entity's till.
5. `own_write` on `sku_uom_conversion`, `sku_barcode` and `sku_tag` (V0003) asks `catalogue.sku` who owns the parent, as the guards of 22A section 6 say (DefineConversion "owner (F for SHARED)", RegisterBarcode "INTERNAL only for own SKUs", TagSku "governed tags on SHARED only by F"); the 17A template checks the row's own owner only.
6. `tax_rate.effective_from` is the `apply_from` of a publication (22A section 7.3: "applyFrom = effective_from"); the column keeps the name of 22A section 3.
7. Grants narrower than 22A's "SELECT, INSERT, UPDATE on all tables": units are read-only for the application, `sku_tag` and `batch` are never updated except `batch.status` (22A's own note), and since V0003 UPDATE is by column where a handler specification changes one column: `tax_rate (effective_to)`, `sku_uom_conversion (effective_to)`, `sku_barcode (status, batch_id)`, `batch_key (batch_id)`.
8. `batch` has a DEFAULT partition, `batch_default`, and an index on `(batch_id)` (V0003); 22A has neither. The default partition carries one policy the parent does not, `partition_move TO app_seed`, for the migrator's move of rows into a month's partition.
9. `catalogue.ensure_batch_partitions` and `secure_batch_partition` are functions of this schema, not of 22A; the first is what the kernel's partition job runs.
10. `package-info.java` declares `kernel`, `kernel::api` and `m1party::query`; 22A section 4 also names `m1party::api`, `m3pricing::query` and `m5inventory::query`, which do not exist as named interfaces yet (above).
11. The key of `tag` is the global `tag_code` as 22A writes it, although a local tag of one entity then collides with another's and with a later governed tag: raised as `docs/change-requests/CR-22A-1.md`, not changed here.
12. DefineConversion runs under `cat.sku.create_local`: 22A section 3.1 names no conversion code among its eleven, and doc 22 section 5.1 says only "owner"; the owner's SKU-definition permission is the closest. The three barcode commands share `cat.barcode.manage`, as 22A section 3.1 and doc 22 section 4.2 say.
13. `retireBarcode` is a DELETE with `symbology`, `reasonCode` and `reasonText` as query parameters: the row is keyed by barcode and symbology, and a DELETE carries no body.
14. The lookup's per-instance cache (22A section 7, 60 seconds, invalidated by `barcode.*`) is not built: no module has a cache yet and the window would be a configuration item.
15. `supplier` (V0004) is readable by every scope except NONE (`authenticated_read`), where 22A section 3 gives it only the template: a batch is global and carries its supplier, and a society that receives the Federation's batch reads that supplier; the names are business names (doc 22 section 9.3). It has `created_at`, which 22A does not write. The application inserts and never updates it: no operation of 22A changes a supplier.
16. The correction of a batch by an entity that did not register it goes through the trigger `batch_correction` running as the migrator, with four policies `TO app_seed` on `batch` and `batch_key` (V0004): 22A section 3's "writes by the registering module only" cannot hold beside section 6's "caller owns a lot of the batch or F". The database checks that the corrected batch is a REGISTERED batch of the same SKU; who may correct is the handler's guard.
17. RegisterBatch carries `@CommandHandler(permission = CommandHandler.INTERNAL)`, a kernel addition raised as `docs/change-requests/CR-19A-6.md`: it has no operation (22A section 5 lists none) and its permission is the caller's (doc 22 section 4.1).
18. CorrectBatchMrp and CorrectBatchExpiry (22A section 6) are one command and one operation, `correctBatch` (22A section 5 names one), taking either value or both.
19. RegisterBatch refuses a DRAFT SKU as well as an INACTIVE one: 22A section 6 writes "sku not INACTIVE", doc 22 section 4.1 "SKU active"; a draft cannot be received.
20. The snapshot tables are `sku` and `tax_category` where 22A section 7.3 lists nine (`sku_uom_conversion`, `sku_barcode`, `sku_image`, `tag`, `sku_tag`, `tax_rate`, `batch` as well): the change log names a row by a UUID, and conversions, barcodes, tag assignments and rates have none of their own, so they travel inside the row of their SKU or category, as 22A section 7.2's "so the till receives the whole item" intends. `tag` (keyed by code) is not served: a SKU row carries its tag codes.
