-- The catalogue schema findings of the review of 26 September 2026 (M2-01, V0001). A merged
-- migration is never edited, so every change is made here, on top of V0001 and V0002:
--
--   1. The child rows of a SKU (conversions, barcodes, tag assignments) follow the SKU's owner:
--      own_write asks catalogue.sku who owns the parent, and shared_read shows everyone only
--      the rows of a SHARED SKU that its owner wrote, plus the factory barcodes.
--   2. One identity per physical batch: catalogue.batch_key, a plain table with a unique key
--      on (sku, supplier, batch_no), filled by a trigger on every batch row.
--   3. A default partition of catalogue.batch as a safety net, and ensure_batch_partitions
--      made safe to run from two instances at once; the kernel's partition job now calls it
--      (BatchPartitionMaintainer).
--   4. An index on batch (batch_id), so a look-up by id does not scan every partition.
--   5. UPDATE narrowed to the columns the handler specifications change (22A section 6).
--
-- The row-level security rules stay those of db/migration/RLS_POLICY_TEMPLATE.md; the M2 README
-- lists every difference between this schema and 22A section 3.

-- ---------------------------------------------------------------------------------------------
-- 1. Child rows follow the SKU's owner.
--
-- V0001's own_write on the three child tables checked the row's own owner_entity_id only, so an
-- entity could attach a conversion or a tag to the Federation's SHARED SKU, or to another
-- entity's LOCAL SKU. The EXISTS runs under the caller's own policies on catalogue.sku: in an
-- OWN scope the caller sees its own SKUs and the SHARED ones, which is exactly what the rule
-- needs. What each table admits, from the guards of 22A section 6:
--   sku_uom_conversion  DefineConversion: "owner (F for SHARED)": the SKU's owner only.
--   sku_barcode         RegisterBarcode: "INTERNAL only for own SKUs": the SKU's owner, or any
--                       entity registering a factory code (EAN, UPC, GS1) on a SHARED SKU.
--   sku_tag             TagSku: "governed tags on SHARED only by F", and doc 22 section 3.5,
--                       "local tags belong to an entity for its own merchandising": the SKU's
--                       owner, or an entity putting one of its own local tags on a SHARED SKU.
DROP POLICY own_write ON catalogue.sku_uom_conversion;
CREATE POLICY own_write ON catalogue.sku_uom_conversion FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND EXISTS (SELECT 1 FROM catalogue.sku s
                             WHERE s.sku_id = sku_uom_conversion.sku_id
                               AND s.owner_entity_id = kernel.scope_entity()));

DROP POLICY own_write ON catalogue.sku_barcode;
CREATE POLICY own_write ON catalogue.sku_barcode FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND EXISTS (SELECT 1 FROM catalogue.sku s
                             WHERE s.sku_id = sku_barcode.sku_id
                               AND (s.owner_entity_id = kernel.scope_entity()
                                    OR (s.status = 'SHARED' AND sku_barcode.symbology <> 'INTERNAL'))));

DROP POLICY own_write ON catalogue.sku_tag;
CREATE POLICY own_write ON catalogue.sku_tag FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND EXISTS (SELECT 1 FROM catalogue.sku s
                             WHERE s.sku_id = sku_tag.sku_id
                               AND (s.owner_entity_id = kernel.scope_entity()
                                    OR (s.status = 'SHARED'
                                        AND EXISTS (SELECT 1 FROM catalogue.tag t
                                                     WHERE t.tag_code = sku_tag.tag_code
                                                       AND t.owner_entity_id = kernel.scope_entity())))));

-- shared_read admitted every row of a SHARED SKU whatever the row's owner, so an INTERNAL
-- barcode, unique only per owner (B-I2), reached every entity and every till: two societies'
-- code 0001 on two SHARED items resolved a scan to two items. Now everyone reads the rows the
-- SKU's owner wrote (the Federation's conversions, barcodes and governed tags), and every
-- factory barcode, which is unique federation-wide. An entity's own rows on a SHARED SKU (its
-- INTERNAL codes, its local tags) stay its own, through own_read.
DROP POLICY shared_read ON catalogue.sku_uom_conversion;
CREATE POLICY shared_read ON catalogue.sku_uom_conversion FOR SELECT TO app_rw
    USING (kernel.scope_class() <> 'NONE'
           AND EXISTS (SELECT 1 FROM catalogue.sku s
                        WHERE s.sku_id = sku_uom_conversion.sku_id
                          AND s.status = 'SHARED'
                          AND s.owner_entity_id = sku_uom_conversion.owner_entity_id));

DROP POLICY shared_read ON catalogue.sku_barcode;
CREATE POLICY shared_read ON catalogue.sku_barcode FOR SELECT TO app_rw
    USING (kernel.scope_class() <> 'NONE'
           AND EXISTS (SELECT 1 FROM catalogue.sku s
                        WHERE s.sku_id = sku_barcode.sku_id
                          AND s.status = 'SHARED'
                          AND (s.owner_entity_id = sku_barcode.owner_entity_id
                               OR sku_barcode.symbology <> 'INTERNAL')));

DROP POLICY shared_read ON catalogue.sku_tag;
CREATE POLICY shared_read ON catalogue.sku_tag FOR SELECT TO app_rw
    USING (kernel.scope_class() <> 'NONE'
           AND EXISTS (SELECT 1 FROM catalogue.sku s
                        WHERE s.sku_id = sku_tag.sku_id
                          AND s.status = 'SHARED'
                          AND s.owner_entity_id = sku_tag.owner_entity_id));

-- ---------------------------------------------------------------------------------------------
-- 5. UPDATE only where a handler specification changes a column (22A section 6), as V0001
-- already did for batch (status). A whole-row grant let a published rate be rewritten in place.
--   tax_rate            PublishTaxRate closes the rate in force: effective_to.
--   sku_uom_conversion  DefineConversion closes the row before the new one: effective_to.
--   sku_barcode         RetireBarcode sets the status, LinkBarcodeToBatch the batch_id.
REVOKE UPDATE ON catalogue.tax_rate FROM app_rw;
GRANT UPDATE (effective_to) ON catalogue.tax_rate TO app_rw;
REVOKE UPDATE ON catalogue.sku_uom_conversion FROM app_rw;
GRANT UPDATE (effective_to) ON catalogue.sku_uom_conversion TO app_rw;
REVOKE UPDATE ON catalogue.sku_barcode FROM app_rw;
GRANT UPDATE (status, batch_id) ON catalogue.sku_barcode TO app_rw;

-- ---------------------------------------------------------------------------------------------
-- 4. A look-up by batch_id. The primary key of batch starts with created_at (the partition
-- key), so a query by id alone read every partition. A partitioned index: every partition
-- gets one, the ones created later too.
CREATE INDEX batch_by_id ON catalogue.batch (batch_id);

-- ---------------------------------------------------------------------------------------------
-- 2. One identity per physical batch (doc 22 section 3.7: "(sku_id, supplier_id, batch_no)
-- unique"). The unique index of catalogue.batch must carry created_at, the partition key, so it
-- never stopped the same batch number being registered twice, and two GRNs confirmed at the
-- same moment would both look the batch up, find nothing and both insert (22A section 6,
-- RegisterBatch: "return existing or insert"). A plain table with a plain unique key holds the
-- identity: the first registration inserts its row, the second meets the key, whatever the
-- timing. A correction (CorrectBatch: a new batch row with corrects_batch_id, the old one
-- SUPERSEDED, B-I8) keeps the identity and re-points it at the replacement row.
--
-- Owned and read like batch: the registering entity writes, every scope but NONE reads.
CREATE TABLE catalogue.batch_key (
    sku_id          uuid        NOT NULL REFERENCES catalogue.sku,
    supplier_id     uuid,       -- catalogue.supplier arrives with M2-05, as on batch
    batch_no        varchar(40) NOT NULL,
    batch_id        uuid        NOT NULL PRIMARY KEY,   -- the current row of catalogue.batch
    owner_entity_id uuid        NOT NULL                -- the registering entity, for RLS
);

CREATE UNIQUE INDEX batch_key_identity_uq ON catalogue.batch_key (
    sku_id, coalesce(supplier_id, '00000000-0000-0000-0000-000000000000'::uuid), batch_no);

ALTER TABLE catalogue.batch_key ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalogue.batch_key FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON catalogue.batch_key FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON catalogue.batch_key FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
-- A correction re-points the identity (batch_id only, see the grant).
CREATE POLICY own_update ON catalogue.batch_key FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON catalogue.batch_key FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON catalogue.batch_key FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));
CREATE POLICY shared_read ON catalogue.batch_key FOR SELECT TO app_rw
    USING (kernel.scope_class() <> 'NONE');

GRANT SELECT, INSERT ON catalogue.batch_key TO app_rw;
GRANT UPDATE (batch_id) ON catalogue.batch_key TO app_rw;

-- The trigger runs as the caller, under the caller's policies: the registering entity writes
-- the identity row of its own batch, and a correction by anyone else changes no identity row
-- and is refused here. A correction by a lot holder that is not the registering entity (22A
-- section 6, CorrectBatch: "caller owns a lot of the batch or F") needs more than own_update
-- on batch and on this table; M2-05 settles it (README, "What the next tickets build on").
CREATE FUNCTION catalogue.batch_keep_one_identity()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, catalogue
AS $$
BEGIN
    IF NEW.corrects_batch_id IS NULL THEN
        INSERT INTO catalogue.batch_key (sku_id, supplier_id, batch_no, batch_id, owner_entity_id)
        VALUES (NEW.sku_id, NEW.supplier_id, NEW.batch_no, NEW.batch_id, NEW.owner_entity_id);
    ELSE
        UPDATE catalogue.batch_key SET batch_id = NEW.batch_id WHERE batch_id = NEW.corrects_batch_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'batch % corrects batch %, whose identity row this scope cannot change',
                NEW.batch_id, NEW.corrects_batch_id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- On the parent: PostgreSQL copies a row trigger to every partition, the ones created later too.
CREATE TRIGGER batch_one_identity
    BEFORE INSERT ON catalogue.batch
    FOR EACH ROW EXECUTE FUNCTION catalogue.batch_keep_one_identity();

-- ---------------------------------------------------------------------------------------------
-- 3. The partitions of batch.
--
-- V0001 created this month's partition and the three after it and nothing called
-- ensure_batch_partitions again, so from the fourth month on every registration would have
-- failed with "no partition of relation batch found for row". Two safety nets now:
--   - the kernel's partition-maintenance job calls ensure_batch_partitions daily
--     (m2catalogue.internal.batch.BatchPartitionMaintainer, through kernel.api.PartitionMaintainer);
--   - a DEFAULT partition catches a row of a month that has no partition yet (the job did not
--     run, or an import carries a created_at before the first partition), so the registration
--     succeeds and the row waits there.
-- A row in the default partition blocks the creation of its month's partition ("updated
-- partition constraint for default partition would be violated by some row"), so the function
-- builds the month's table beside the parent, moves those rows into it and attaches it. That
-- move is a DELETE by the function's owner, the migrator: not a deletion of a batch, the same
-- row leaves one partition for another, and app_rw still cannot delete anything.
--
-- Under FORCE ROW LEVEL SECURITY the owner of the default partition is bound by its policies
-- too, and the parent's policies admit app_rw only; this one admits the migrator (a member of
-- app_seed, kernel V0005 and 01-roles.sh) for the move. It exists on the default partition only.
CREATE TABLE catalogue.batch_default PARTITION OF catalogue.batch DEFAULT;

CREATE POLICY partition_move ON catalogue.batch_default FOR ALL TO app_seed
    USING (true) WITH CHECK (true);

-- What V0001 did to each partition inside the loop, as a function of its own so the default
-- partition and the monthly ones are treated alike: RLS enabled and forced, no privilege of
-- its own for the application (it reads and writes through the parent), and the parent's
-- policies copied by their text, so the two cannot drift apart.
CREATE FUNCTION catalogue.secure_batch_partition(partition_name text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, catalogue
AS $$
DECLARE
    policy record;
BEGIN
    EXECUTE format('ALTER TABLE catalogue.%I ENABLE ROW LEVEL SECURITY', partition_name);
    EXECUTE format('ALTER TABLE catalogue.%I FORCE ROW LEVEL SECURITY', partition_name);
    EXECUTE format('REVOKE ALL PRIVILEGES ON catalogue.%I FROM PUBLIC, app_rw', partition_name);

    FOR policy IN
        SELECT p.polname,
               CASE p.polcmd WHEN 'r' THEN 'SELECT' WHEN 'a' THEN 'INSERT' WHEN 'w' THEN 'UPDATE'
                             WHEN 'd' THEN 'DELETE' ELSE 'ALL' END            AS command,
               pg_get_expr(p.polqual, p.polrelid)                             AS using_expr,
               pg_get_expr(p.polwithcheck, p.polrelid)                        AS check_expr,
               (SELECT string_agg(quote_ident(r.rolname), ', ')
                  FROM pg_roles r WHERE r.oid = ANY (p.polroles))             AS roles
          FROM pg_policy p
         WHERE p.polrelid = 'catalogue.batch'::regclass
    LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_policy q
                        WHERE q.polrelid = ('catalogue.' || partition_name)::regclass
                          AND q.polname = policy.polname) THEN
            EXECUTE format('CREATE POLICY %I ON catalogue.%I FOR %s TO %s', policy.polname, partition_name,
                           policy.command, policy.roles)
                    || coalesce(' USING (' || policy.using_expr || ')', '')
                    || coalesce(' WITH CHECK (' || policy.check_expr || ')', '');
        END IF;
    END LOOP;
END;
$$;

REVOKE ALL ON FUNCTION catalogue.secure_batch_partition(text) FROM PUBLIC;

-- The return type changes (the number of partitions created, for the job's run history), which
-- CREATE OR REPLACE cannot do.
DROP FUNCTION catalogue.ensure_batch_partitions(integer);

CREATE FUNCTION catalogue.ensure_batch_partitions(months_ahead integer DEFAULT 3)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, catalogue
AS $$
DECLARE
    offset_month   integer;
    partition_from timestamptz;
    partition_to   timestamptz;
    partition_name text;
    created        integer := 0;
BEGIN
    IF months_ahead < 0 OR months_ahead > 24 THEN
        RAISE EXCEPTION 'months_ahead must be between 0 and 24';
    END IF;

    -- One creator at a time. Two instances rolling over together both found the month missing
    -- and the second failed on "already exists"; the lock is released with the transaction.
    PERFORM pg_advisory_xact_lock(hashtext('catalogue.batch partitions'));

    FOR offset_month IN 0..months_ahead LOOP
        partition_from := (date_trunc('month', current_timestamp AT TIME ZONE 'UTC')
                           + make_interval(months => offset_month)) AT TIME ZONE 'UTC';
        partition_to := (date_trunc('month', current_timestamp AT TIME ZONE 'UTC')
                         + make_interval(months => offset_month + 1)) AT TIME ZONE 'UTC';
        partition_name := 'batch_' || to_char(partition_from AT TIME ZONE 'UTC', 'YYYY_MM');

        IF NOT EXISTS (SELECT 1 FROM pg_inherits
                        WHERE inhparent = 'catalogue.batch'::regclass
                          AND inhrelid = to_regclass('catalogue.' || partition_name)) THEN
            -- Built beside the parent (the constraints and defaults of batch; the indexes and
            -- the trigger come with ATTACH), filled with the rows the default partition caught
            -- for this month, then attached. The foreign key to sku is added while the table is
            -- still empty: ATTACH then adopts it instead of validating the moved rows itself,
            -- a validation that runs as this function's owner, whom FORCE ROW LEVEL SECURITY
            -- on sku shows no row (the row-by-row check of an INSERT is a referential-integrity
            -- check and is exempt, so the move itself passes).
            EXECUTE format('CREATE TABLE IF NOT EXISTS catalogue.%I (LIKE catalogue.batch INCLUDING DEFAULTS INCLUDING CONSTRAINTS)',
                           partition_name);
            EXECUTE format('ALTER TABLE catalogue.%I ADD FOREIGN KEY (sku_id) REFERENCES catalogue.sku', partition_name);
            EXECUTE format('WITH moved AS (DELETE FROM catalogue.batch_default WHERE created_at >= %L AND created_at < %L RETURNING *)'
                           || ' INSERT INTO catalogue.%I SELECT * FROM moved',
                           partition_from, partition_to, partition_name);
            EXECUTE format('ALTER TABLE catalogue.batch ATTACH PARTITION catalogue.%I FOR VALUES FROM (%L) TO (%L)',
                           partition_name, partition_from, partition_to);
            created := created + 1;
        END IF;

        PERFORM catalogue.secure_batch_partition(partition_name);
    END LOOP;

    PERFORM catalogue.secure_batch_partition('batch_default');

    RETURN created;
END;
$$;

REVOKE ALL ON FUNCTION catalogue.ensure_batch_partitions(integer) FROM PUBLIC;
-- The application role runs the partition job (as for the kernel's own partition functions).
GRANT EXECUTE ON FUNCTION catalogue.ensure_batch_partitions(integer) TO app_rw;

-- Secures the default partition and confirms this month and the three after it.
SELECT catalogue.ensure_batch_partitions(3);
