# m2catalogue — M2 Catalogue & Batch

The living guide of M2 (AGENTS.md): once code exists, this file, the seed files and the tests supersede `docs/design/22A_M2_Implementation_Guide` for day-to-day work. Every deviation from the guide is recorded at the end, with the reason. Read `hello/README.md` first: its six rules apply here unchanged. The reasoning behind the module is `docs/design/22_M2_Catalogue_Batch`.

M2 defines what can be counted and sold: SKU identity in one global namespace, units and conversions, barcodes, tags, tax categories and rates, images, and the batch as physical identity carrying expiry and printed MRP. It owns identity, not holding: stock and cost are M5's, prices are M3's.

## What is where (after M2-01)

| Path | What it holds |
|---|---|
| `api/` | The published contract. Empty until M2-02 adds the SKU commands and events. |
| `internal/seed/M2SeedLoader` | Loads the reference data of `seed/m2catalogue` on start, as the migrator (the same arrangement as `M1SeedLoader`). |
| `resources/db/migration/m2catalogue/V0001__catalogue.sql` | The tables of M2-01, their row-level security, grants, and the batch partitions. |
| `resources/seed/m2catalogue/` | `uom.yaml`, `tax.yaml`, `tags.yaml` (loaded by `M2SeedLoader`), `audit-event-types.yaml` (loaded by the kernel's `AuditEventTypeSeedLoader`). |
| `resources/seed/m1party/permissions.yaml` | The eleven `cat.*` permissions of M2, at the end of M1's file (see "Permissions" below). |
| `resources/openapi/m2catalogue.yaml` | The slice: info only, no operation yet. |
| `web/src/modules/m2catalogue/` | The module registration only: no route, no navigation entry, until M2-10. |
| `src/test/.../m2catalogue/` | `CatalogueSchemaIntegrationTest` (tables, forced RLS, policies, grants, partitions), `CatalogueRlsIntegrationTest` (rows of the RLS matrix of 22A section 9), `internal/seed/M2SeedLoaderTest`. |

The scaffold of `make new-module NAME=m2catalogue SCHEMA=catalogue ENTITY=sku` was run and its greeting-shaped copy removed: the Sku entity, handler, queries and controller, the greeting migration, the development seed, the copied integration test, the web page and the greeting message ids. None of it was needed to prove the scaffold: hello keeps its own tests, and the M2 tests above prove the schema.

## The schema

V0001 creates `uom`, `tax_category`, `tax_rate`, `sku`, `sku_uom_conversion`, `sku_barcode`, `tag`, `sku_tag` and `batch`, as 22A section 3 writes them. The other tables of section 3 arrive with the ticket that first writes them, each in a new migration of this lane: `supplier` (M2-05), `sku_image` (M2-06), `sku_alias` (M2-07), `duplicate_suspect` (M2-08), `location_assortment` (M2-09).

Who reads and writes what:

| Table | Reads | Writes (app_rw) |
|---|---|---|
| `uom` | every scope except NONE | nothing; seeded only |
| `tax_category`, `tax_rate` | every scope except NONE | the owner (the Federation) in OWN: INSERT, UPDATE |
| `sku` | the owner in OWN; everyone when SHARED; Federation view; external grant | the owner in OWN: INSERT, UPDATE |
| `sku_uom_conversion`, `sku_barcode` | as the SKU: the owner, and everyone when the SKU is SHARED | the owner in OWN: INSERT, UPDATE |
| `tag` | governed tags by everyone; a local tag by its owner | the owner in OWN (local tags): INSERT, UPDATE |
| `sku_tag` | as the SKU | the owner in OWN: INSERT only |
| `batch` | every scope except NONE (global identity) | the registering entity in OWN: INSERT, and UPDATE of `status` only |

Every table carries the template of `db/migration/RLS_POLICY_TEMPLATE.md` (own_read, own_write, fed_view, ext_view, with own_* testing the class OWN) plus `own_update` where the table is updated, and the reference reads named `authenticated_read`, `shared_read`, `governed_read`. The reference tables the seed loader fills (`uom`, `tax_category`, `tax_rate`, `tag`) have `seed_reference TO app_seed`. No table here has a counterparty, so none has `party_read`.

### Batch partitions

`catalogue.batch` is partitioned by the month of `created_at` (doc 22 section 9.1). `catalogue.ensure_batch_partitions(months_ahead)` creates the missing monthly partitions, each with RLS forced and the parent's policies copied, and V0001 calls it for this month and the three after. **Nothing calls it daily yet.** The kernel's `PartitionJob` (K-04/K-05) calls the kernel's own two functions and takes no list of tables, so it cannot be extended from here without changing the kernel. Before the first batch is registered (M2-05), one of the two must happen: the platform pair gives `PartitionJob` a way for modules to register a partition function, or M2-05 adds a `@ScheduledJob` in this module that runs `select catalogue.ensure_batch_partitions(3)` daily. Without either, inserts into `batch` fail from the fourth month after the last migration.

## Seeds

| File | Table | Rule |
|---|---|---|
| `uom.yaml` | `catalogue.uom` | inserted when the code is absent; never changed afterwards |
| `tags.yaml` | `catalogue.tag` | governed (owner null); inserted when the code is absent |
| `tax.yaml` | `catalogue.tax_category`, `catalogue.tax_rate` | owner = the Federation (`coop-erp.system.entity-id`); a category when its code is absent, a rate when no rate of the category overlaps it. When the property is not set, the tax rows are skipped with a warning (the test context does not set it; the local stack does) |
| `audit-event-types.yaml` | `kernel.audit_event_type` | the audit codes of 22A section 6, all INFO |

A new tax rate is published through PublishTaxRate (M2-03), never by editing `tax.yaml`. The transliteration table of 22A section 3.1 (`transliteration-v1.csv`) belongs to M2-08.

### Permissions

22A section 3.1 says the permission catalogue of M2 is "appended to the M1-owned catalogue file". `M1SeedLoader` reads one file, `seed/m1party/permissions.yaml`, and `security.permission` is M1's table, so the eleven `cat.*` codes are at the end of that file under a comment, with `module: m2catalogue`. `M1SeedLoaderTest` counts them.

## What the next tickets build on

- **M2-02 SKU aggregate**: `catalogue.sku` with its indexes and policies; the units and tax categories a SKU cites are seeded; `SKU_*` audit codes are in place; permissions `cat.sku.create`, `cat.sku.create_local`, `cat.sku.deactivate`. Adds the first operations to the slice and the first `api` records.
- **M2-03 units, tags, tax**: `sku_uom_conversion` and `tax_rate` with their exclusion constraints; `tag` and `sku_tag`. Open: a governed tag written by the Federation (no owner) needs a policy the template does not give; TagSku "replace set" needs a way to remove an assignment, and app_rw may not DELETE.
- **M2-04 barcodes**: `sku_barcode` with `barcode_factory_unique` (B-I2).
- **M2-05 batch**: `batch` and its partitions; adds `supplier`; must settle the daily partition call (above), and whether a correction by a lot holder that is not the registering entity needs more than `own_update`.
- **M2-06 to M2-09**: add `sku_image`, `sku_alias` (with the promotion policy on `sku`: the Federation takes over a LOCAL row, which `own_update` does not admit), `duplicate_suspect`, `location_assortment`.

## Deviations from the implementation guide

1. The collations and the trigram operator class are qualified with `kernel.` (22A writes `en_icu`, `gin_trgm_ops`): the kernel baseline created them in the kernel schema and this lane migrates with `search_path = catalogue`.
2. The (sku, supplier, batch_no, created_at) key of `batch` is a unique index, not a table constraint: a constraint cannot hold the `coalesce` expression. As 22A writes it, it includes `created_at`, which PostgreSQL requires of a unique index on a partitioned table; it therefore does not by itself stop a second registration of the same batch number, and RegisterBatch looks up first (existing-or-create).
3. `own_update` policies on the tables that are updated: the template has none, and under FORCE ROW LEVEL SECURITY an UPDATE with no UPDATE policy changes no row.
4. `shared_read` tests `kernel.scope_class() <> 'NONE'` besides what 22A writes (`status = 'SHARED'`, `true` for batch): a transaction with no scope reads nothing (RLS template). The conversions, barcodes and tags of a SHARED SKU are readable by everyone as well, through the same policy name; 22A says only "SHARED visible to all", and a till cannot sell a shared item without its barcodes.
5. `tax_rate.effective_from` is the `apply_from` of a publication (22A section 7.3: "applyFrom = effective_from"); the column keeps the name of 22A section 3.
6. Units are read-only for the application (`GRANT SELECT`), and `sku_tag` is insert-only; 22A section 3 grants SELECT, INSERT, UPDATE on every table, but no command changes a unit or a tag assignment.
