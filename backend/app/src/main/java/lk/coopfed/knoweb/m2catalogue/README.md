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
| `resources/seed/m2catalogue/` | `uom.yaml`, `tax.yaml`, `tags.yaml` (loaded by `M2SeedLoader`), `audit-event-types.yaml` (loaded by the kernel's `AuditEventTypeSeedLoader`). |
| `resources/seed/m1party/permissions.yaml` | The eleven `cat.*` permissions of M2, at the end of M1's file (see "Permissions" below). |
| `resources/openapi/m2catalogue.yaml` | The slice: info only, no operation yet. |
| `web/src/modules/m2catalogue/` | The module registration only: no route, no navigation entry, until M2-10. |
| `src/test/.../m2catalogue/` | `CatalogueSchemaIntegrationTest` (tables, forced RLS, policies, grants, partitions, the default partition and the concurrent roll-over), `CatalogueRlsIntegrationTest` (rows of the RLS matrix of 22A section 9, the child rows of a SKU, one identity per batch), `internal/seed/M2SeedLoaderTest`, `internal/batch/BatchPartitionMaintainerPostgresIntegrationTest`. |

The scaffold of `make new-module NAME=m2catalogue SCHEMA=catalogue ENTITY=sku` was run and its greeting-shaped copy removed: the Sku entity, handler, queries and controller, the greeting migration, the development seed, the copied integration test, the web page and the greeting message ids. None of it was needed to prove the scaffold: hello keeps its own tests, and the M2 tests above prove the schema.

## The schema

V0001 creates `uom`, `tax_category`, `tax_rate`, `sku`, `sku_uom_conversion`, `sku_barcode`, `tag`, `sku_tag` and `batch`, as 22A section 3 writes them; V0003 (the review of 26 September) adds `batch_key` and changes the policies, grants and partitions listed under "Deviations". The other tables of section 3 arrive with the ticket that first writes them, each in a new migration of this lane: `supplier` (M2-05), `sku_image` (M2-06), `sku_alias` (M2-07), `duplicate_suspect` (M2-08), `location_assortment` (M2-09).

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
| `batch_key` | every scope except NONE (the identity of a batch, read like the batch) | filled by the trigger `batch_one_identity` on every batch row, as the registering entity: INSERT, and UPDATE of `batch_id` only (a correction re-points the identity) |

Every table carries the template of `db/migration/RLS_POLICY_TEMPLATE.md` (own_read, own_write, fed_view, ext_view, with own_* testing the class OWN) plus `own_update` where the table is updated, and the reference reads named `authenticated_read`, `shared_read`, `governed_read`. The reference tables the seed loader fills (`uom`, `tax_category`, `tax_rate`, `tag`) have `seed_reference TO app_seed`. No table here has a counterparty, so none has `party_read`. The `own_write` of the three child tables of a SKU asks `catalogue.sku` who owns the parent (an EXISTS under the caller's own policies), so `RlsMatrixIntegrationTest` lists them as a departure: the matrix's made-up rows have no parent SKU and their insert is refused.

### One identity per batch

Doc 22 section 3.7: "(sku_id, supplier_id, batch_no) unique". The unique index of `batch` must carry `created_at`, the partition key, so it never held that promise, and RegisterBatch's "return existing or insert" (22A section 6) would race under two GRNs confirmed together. `catalogue.batch_key` is a plain table with a plain unique key on `(sku_id, coalesce(supplier_id), batch_no)`, filled by a `BEFORE INSERT` trigger on `batch`: a first registration inserts the identity row, a second meets the key (`batch_key_identity_uq`), a correction (`corrects_batch_id` set) re-points the identity at the replacement row. The trigger runs as the caller under the caller's policies, so a correction by an entity that did not register the batch changes no identity row and is refused; a correction by a lot holder that is not the registering entity (22A section 6, CorrectBatch: "caller owns a lot of the batch or F") needs more than `own_update` on `batch` and `batch_key`, and M2-05 settles it (below). RegisterBatch (M2-05) can look the identity up in `batch_key` by key, which no longer needs a partition scan; `batch (batch_id)` is indexed for the look-up by id.

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

## What the next tickets build on

- **M2-02 SKU aggregate**: `catalogue.sku` with its indexes and policies; the units and tax categories a SKU cites are seeded; `SKU_*` audit codes are in place; permissions `cat.sku.create`, `cat.sku.create_local`, `cat.sku.deactivate`. Adds the first operations to the slice and the first `api` records.
- **M2-03 units, tags, tax**: `sku_uom_conversion` and `tax_rate` with their exclusion constraints; `tag` and `sku_tag`. Open: a governed tag written by the Federation (no owner) needs a policy the template does not give; TagSku "replace set" needs a way to remove an assignment, and app_rw may not DELETE.
- **M2-04 barcodes**: `sku_barcode` with `barcode_factory_unique` (B-I2).
- **M2-05 batch**: `batch`, `batch_key` and the partitions (the daily call is settled, above); adds `supplier`. Open: CorrectBatch by a lot holder that is not the registering entity (22A section 6: "caller owns a lot of the batch (M5 query) or F"). `own_update` on `batch` and on `batch_key` admits the registering entity only, so such a correction silently changes no row today (`CatalogueRlsIntegrationTest.aBatchIsReadByEveryScopeAndChangedByItsRegisteringEntityOnly` pins that) and the trigger refuses it; M2-05 adds either a SECURITY DEFINER path guarded by the handler (the lot query and the Federation check) or a policy, with a test that asserts the old row is SUPERSEDED and the identity re-pointed.
- **Dependencies of 22A section 4** still to declare in `package-info.java` when the packages exist: `m1party::api` (no `NamedInterface` on M1's api package yet), `m3pricing::query` and `m5inventory::query` (not built).
- **M2-06 to M2-09**: add `sku_image`, `sku_alias` (with the promotion policy on `sku`: the Federation takes over a LOCAL row, which `own_update` does not admit), `duplicate_suspect`, `location_assortment`.

## Deviations from the implementation guide

Every difference between the schema as migrated (V0001 to V0003) and 22A section 3. The header of V0001 says "three changes"; it is a merged migration and stays as written, so this list is the one to trust.

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
