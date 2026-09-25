-- M2-01: the catalogue schema, first migration of the m2catalogue lane (22A section 3).
--
-- What is here: the tables ticket M2-01 names, which the aggregates of M2-02 to M2-05 write:
--   uom, sku, sku_uom_conversion, sku_barcode, tag, sku_tag, tax_category, tax_rate, batch.
-- What is not here yet, and which ticket adds it in a new migration of this lane:
--   supplier (M2-05), sku_image (M2-06), sku_alias (M2-07), duplicate_suspect (M2-08),
--   location_assortment (M2-09). batch.supplier_id carries the identifier only until then.
--
-- The DDL is 22A section 3 with three changes a runnable migration needs, each commented where
-- it is made: the collations and the trigram operator class live in the kernel schema (the
-- kernel baseline created them there), and the (sku, supplier, batch_no) key of batch is a
-- unique index because a table constraint cannot hold an expression.
--
-- Row-level security follows db/migration/RLS_POLICY_TEMPLATE.md on every table: ENABLE and
-- FORCE; own_read and own_write gated on the class OWN; own_update where the table is updated
-- (the template has no UPDATE policy, and under FORCE an UPDATE with no policy changes no row);
-- fed_view; ext_view. No table of this migration has a counterparty column, so none has
-- party_read. The catalogue is also reference data for everyone (22A section 3, doc 22
-- sections 3.1 and 3.7), which the template does not cover; those reads are extra policies,
-- each named for what it admits:
--   authenticated_read  every scope class except NONE (uom, tax_category, tax_rate)
--   governed_read       governed tags, for every scope class except NONE
--   shared_read         a SHARED sku, and the conversions, barcodes and tags of a SHARED sku;
--                       and every batch (a batch is global identity, not owned)
-- and seed_reference TO app_seed on the tables the M2 seed loader fills (kernel V0005: the
-- migrator is a member of app_seed; the application role is not).
--
-- No table of this migration has a location_id, so every own_read leaves the location line of
-- the template out.

-- ---------------------------------------------------------------------------------------------
-- Units of measure: Federation-governed vocabulary; an entity cannot add units (doc 22 3.2).
-- Reference data with no owner: seeded from seed/m2catalogue/uom.yaml, read by everyone.
CREATE TABLE catalogue.uom (
    uom_code  varchar(10) PRIMARY KEY,
    name_en   text        NOT NULL,
    name_si   text,
    name_ta   text,
    is_weight boolean     NOT NULL DEFAULT false
);

ALTER TABLE catalogue.uom ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalogue.uom FORCE ROW LEVEL SECURITY;

CREATE POLICY authenticated_read ON catalogue.uom FOR SELECT TO app_rw
    USING (kernel.scope_class() <> 'NONE');

CREATE POLICY seed_reference ON catalogue.uom FOR ALL TO app_seed
    USING (true) WITH CHECK (true);

-- No command writes a unit: the application reads them only.
GRANT SELECT ON catalogue.uom TO app_rw;

-- ---------------------------------------------------------------------------------------------
-- Tax categories and their effective-dated rates (doc 22 3.6). Owned by the Federation, which
-- defines categories and publishes rates in its own OWN scope (cat.tax.publish), so own_write
-- admits exactly that; every scope reads them (22A section 3). The seed loader writes the
-- three seeded categories with the Federation as owner (coop-erp.system.entity-id).
CREATE TABLE catalogue.tax_category (
    tax_category_id uuid        PRIMARY KEY,
    code            varchar(12) NOT NULL UNIQUE,
    name_en         text        NOT NULL,
    name_si         text,
    name_ta         text,
    owner_entity_id uuid        NOT NULL
);

-- effective_from is the apply_from of the publication: tax_rate.published.v1 and the till
-- snapshot carry it as applyFrom (22A section 7.3: "applyFrom = effective_from").
CREATE TABLE catalogue.tax_rate (
    tax_category_id uuid         NOT NULL REFERENCES catalogue.tax_category,
    rate_percent    numeric(5,2) NOT NULL,
    effective_from  date         NOT NULL,
    effective_to    date,
    owner_entity_id uuid         NOT NULL,
    CHECK (effective_to IS NULL OR effective_to >= effective_from),
    -- One rate in force per category on any day.
    EXCLUDE USING gist (
        tax_category_id WITH =,
        daterange(effective_from, coalesce(effective_to, 'infinity'::date), '[]') WITH &&
    )
);

ALTER TABLE catalogue.tax_category ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalogue.tax_category FORCE ROW LEVEL SECURITY;
ALTER TABLE catalogue.tax_rate ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalogue.tax_rate FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON catalogue.tax_category FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON catalogue.tax_category FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_update ON catalogue.tax_category FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON catalogue.tax_category FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON catalogue.tax_category FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));
CREATE POLICY authenticated_read ON catalogue.tax_category FOR SELECT TO app_rw
    USING (kernel.scope_class() <> 'NONE');
CREATE POLICY seed_reference ON catalogue.tax_category FOR ALL TO app_seed
    USING (true) WITH CHECK (true);

CREATE POLICY own_read ON catalogue.tax_rate FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON catalogue.tax_rate FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
-- PublishTaxRate closes the rate in force at apply_from - 1 (22A section 6): an UPDATE of
-- effective_to.
CREATE POLICY own_update ON catalogue.tax_rate FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON catalogue.tax_rate FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON catalogue.tax_rate FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));
CREATE POLICY authenticated_read ON catalogue.tax_rate FOR SELECT TO app_rw
    USING (kernel.scope_class() <> 'NONE');
CREATE POLICY seed_reference ON catalogue.tax_rate FOR ALL TO app_seed
    USING (true) WITH CHECK (true);

GRANT SELECT, INSERT, UPDATE ON catalogue.tax_category TO app_rw;
GRANT SELECT, INSERT, UPDATE ON catalogue.tax_rate TO app_rw;

-- ---------------------------------------------------------------------------------------------
-- The SKU (doc 22 3.1). DRAFT and LOCAL rows are the creating entity's (OWN, and Federation
-- view); SHARED rows are owned by the Federation and readable by every scope (shared_read).
CREATE TABLE catalogue.sku (
    sku_id              uuid        PRIMARY KEY,
    sku_code            varchar(12) NOT NULL UNIQUE,
    owner_entity_id     uuid        NOT NULL,
    status              text        NOT NULL DEFAULT 'DRAFT'
                                    CHECK (status IN ('DRAFT', 'LOCAL', 'SHARED', 'INACTIVE')),
    prior_status        text        CHECK (prior_status IN ('LOCAL', 'SHARED')),   -- for reactivation
    -- 22A writes COLLATE en_icu; the collations were created in the kernel schema by the
    -- kernel baseline, and this migration runs with search_path = catalogue.
    short_name_en       varchar(80) NOT NULL COLLATE kernel.en_icu,
    short_name_si       varchar(80) COLLATE kernel.si_icu,
    short_name_ta       varchar(80) COLLATE kernel.ta_icu,
    description_en      text,
    description_si      text,
    description_ta      text,
    base_uom_code       varchar(10) NOT NULL REFERENCES catalogue.uom,
    sold_by_weight      boolean     NOT NULL DEFAULT false,
    batch_tracked       boolean     NOT NULL DEFAULT false,
    expiry_tracked      boolean     NOT NULL DEFAULT false,
    has_printed_mrp     boolean     NOT NULL DEFAULT true,
    expiry_warning_days smallint,
    -- A plain identifier as 22A has it; M2-03 decides whether the category becomes a foreign key.
    tax_category_id     uuid        NOT NULL,
    multi_mrp_policy    text        NOT NULL DEFAULT 'AUTO_LOWEST'
                                    CHECK (multi_mrp_policy IN ('AUTO_LOWEST', 'BARCODE_RESOLVED', 'PICKER')),
    origin_kind         text        NOT NULL DEFAULT 'PURCHASED'
                                    CHECK (origin_kind IN ('PURCHASED', 'REPACK_OUTPUT', 'LOCAL_SOURCED')),
    attributes          jsonb       NOT NULL DEFAULT '{}' CHECK (pg_column_size(attributes) <= 4096),
    CHECK (status <> 'SHARED' OR (short_name_si IS NOT NULL AND short_name_ta IS NOT NULL)),   -- B-I1
    CHECK (NOT sold_by_weight OR base_uom_code = 'KG'),
    CHECK (NOT expiry_tracked OR batch_tracked)
);

-- gin_trgm_ops belongs to pg_trgm, which the kernel baseline installed in the kernel schema.
CREATE INDEX sku_name_en_trgm ON catalogue.sku USING gin (short_name_en kernel.gin_trgm_ops);
CREATE INDEX sku_name_si_trgm ON catalogue.sku USING gin (short_name_si kernel.gin_trgm_ops);
CREATE INDEX sku_name_ta_trgm ON catalogue.sku USING gin (short_name_ta kernel.gin_trgm_ops);
CREATE INDEX sku_owner_status ON catalogue.sku (owner_entity_id, status);

ALTER TABLE catalogue.sku ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalogue.sku FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON catalogue.sku FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON catalogue.sku FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
-- Activation, update, deactivation and reactivation change the owner's own row. Promotion
-- (the Federation takes over a LOCAL row, M2-07) needs more than this; M2-07 adds it.
CREATE POLICY own_update ON catalogue.sku FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON catalogue.sku FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON catalogue.sku FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));
-- 22A section 3: "CREATE POLICY shared_read ON catalogue.sku FOR SELECT TO app_rw USING
-- (status = 'SHARED')". The class test keeps a transaction with no scope (NONE) out, as the
-- template requires of every read.
CREATE POLICY shared_read ON catalogue.sku FOR SELECT TO app_rw
    USING (kernel.scope_class() <> 'NONE' AND status = 'SHARED');

GRANT SELECT, INSERT, UPDATE ON catalogue.sku TO app_rw;

-- ---------------------------------------------------------------------------------------------
-- Conversions (doc 22 3.2): CASE = 24 x EA; a change of case size is a new effective-dated row.
CREATE TABLE catalogue.sku_uom_conversion (
    sku_id          uuid          NOT NULL REFERENCES catalogue.sku,
    uom_code        varchar(10)   NOT NULL REFERENCES catalogue.uom,
    factor_to_base  numeric(14,6) NOT NULL CHECK (factor_to_base > 0),
    effective_from  date          NOT NULL,
    effective_to    date,
    owner_entity_id uuid          NOT NULL,
    CHECK (effective_to IS NULL OR effective_to >= effective_from),
    EXCLUDE USING gist (
        sku_id WITH =,
        uom_code WITH =,
        daterange(effective_from, coalesce(effective_to, 'infinity'::date), '[]') WITH &&
    )
);

-- ---------------------------------------------------------------------------------------------
-- The barcode registry (doc 22 3.3).
CREATE TABLE catalogue.sku_barcode (
    barcode         varchar(48) NOT NULL,
    symbology       text        NOT NULL
                                CHECK (symbology IN ('EAN13', 'EAN8', 'UPCA', 'GS1_128', 'GS1_DATAMATRIX', 'GS1_QR', 'INTERNAL')),
    sku_id          uuid        NOT NULL REFERENCES catalogue.sku,
    uom_code        varchar(10) NOT NULL REFERENCES catalogue.uom,
    -- The batch a code identifies. No foreign key: batch is partitioned and its key is
    -- (created_at, batch_id); the handler checks that the batch belongs to the sku (M2-04).
    batch_id        uuid,
    owner_entity_id uuid        NOT NULL,
    status          text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'RETIRED')),
    PRIMARY KEY (barcode, symbology, owner_entity_id)
);

-- B-I2: factory symbologies unique federation-wide; INTERNAL unique per owner (the primary key).
CREATE UNIQUE INDEX barcode_factory_unique ON catalogue.sku_barcode (barcode, symbology)
    WHERE symbology <> 'INTERNAL' AND status = 'ACTIVE';

-- ---------------------------------------------------------------------------------------------
-- Tags (doc 22 3.5): governed tags are the Federation's vocabulary (owner null) and readable by
-- everyone; local tags belong to one entity.
CREATE TABLE catalogue.tag (
    tag_code        varchar(40) PRIMARY KEY,
    name_en         text        NOT NULL,
    name_si         text,
    name_ta         text,
    governed        boolean     NOT NULL DEFAULT false,
    owner_entity_id uuid,
    CHECK (governed = (owner_entity_id IS NULL))
);

CREATE TABLE catalogue.sku_tag (
    sku_id          uuid        NOT NULL REFERENCES catalogue.sku,
    tag_code        varchar(40) NOT NULL REFERENCES catalogue.tag,
    owner_entity_id uuid        NOT NULL,
    PRIMARY KEY (sku_id, tag_code)
);

-- ---------------------------------------------------------------------------------------------
-- Policies of the three child tables of a SKU. Their rows follow the SKU: the owner's in OWN,
-- everyone's when the SKU is SHARED (a till must see the barcodes and conversions of every
-- SHARED item it sells). The EXISTS runs under the caller's own policies on catalogue.sku.
ALTER TABLE catalogue.sku_uom_conversion ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalogue.sku_uom_conversion FORCE ROW LEVEL SECURITY;
ALTER TABLE catalogue.sku_barcode ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalogue.sku_barcode FORCE ROW LEVEL SECURITY;
ALTER TABLE catalogue.sku_tag ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalogue.sku_tag FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON catalogue.sku_uom_conversion FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON catalogue.sku_uom_conversion FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
-- A new effective row closes the one before it (effective_to).
CREATE POLICY own_update ON catalogue.sku_uom_conversion FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON catalogue.sku_uom_conversion FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON catalogue.sku_uom_conversion FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));
CREATE POLICY shared_read ON catalogue.sku_uom_conversion FOR SELECT TO app_rw
    USING (kernel.scope_class() <> 'NONE'
           AND EXISTS (SELECT 1 FROM catalogue.sku s WHERE s.sku_id = sku_uom_conversion.sku_id AND s.status = 'SHARED'));

CREATE POLICY own_read ON catalogue.sku_barcode FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON catalogue.sku_barcode FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
-- RetireBarcode sets the status, LinkBarcodeToBatch the batch_id.
CREATE POLICY own_update ON catalogue.sku_barcode FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON catalogue.sku_barcode FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON catalogue.sku_barcode FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));
CREATE POLICY shared_read ON catalogue.sku_barcode FOR SELECT TO app_rw
    USING (kernel.scope_class() <> 'NONE'
           AND EXISTS (SELECT 1 FROM catalogue.sku s WHERE s.sku_id = sku_barcode.sku_id AND s.status = 'SHARED'));

CREATE POLICY own_read ON catalogue.sku_tag FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON catalogue.sku_tag FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON catalogue.sku_tag FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON catalogue.sku_tag FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));
CREATE POLICY shared_read ON catalogue.sku_tag FOR SELECT TO app_rw
    USING (kernel.scope_class() <> 'NONE'
           AND EXISTS (SELECT 1 FROM catalogue.sku s WHERE s.sku_id = sku_tag.sku_id AND s.status = 'SHARED'));

GRANT SELECT, INSERT, UPDATE ON catalogue.sku_uom_conversion TO app_rw;
GRANT SELECT, INSERT, UPDATE ON catalogue.sku_barcode TO app_rw;
-- A tag assignment is a row that exists or does not; nothing in it is ever changed.
GRANT SELECT, INSERT ON catalogue.sku_tag TO app_rw;

-- Tags.
ALTER TABLE catalogue.tag ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalogue.tag FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON catalogue.tag FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
-- A local tag only: a governed tag has no owner, so no OWN caller satisfies this. Governed
-- tags are seeded (seed_reference); the Federation's DefineTag for a governed tag is M2-03's.
CREATE POLICY own_write ON catalogue.tag FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_update ON catalogue.tag FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON catalogue.tag FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON catalogue.tag FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));
CREATE POLICY governed_read ON catalogue.tag FOR SELECT TO app_rw
    USING (kernel.scope_class() <> 'NONE' AND governed);
CREATE POLICY seed_reference ON catalogue.tag FOR ALL TO app_seed
    USING (true) WITH CHECK (true);

GRANT SELECT, INSERT, UPDATE ON catalogue.tag TO app_rw;

-- ---------------------------------------------------------------------------------------------
-- The batch (doc 22 3.7): global physical identity, partitioned by the month of created_at
-- (doc 22 9.1: millions per year). Readable by every scope; written by the registering entity.
-- owner_entity_id is the registering entity, for RLS; it is not the holder (the holder's lot
-- is M5's).
CREATE TABLE catalogue.batch (
    batch_id           uuid          NOT NULL,
    sku_id             uuid          NOT NULL REFERENCES catalogue.sku,
    batch_no           varchar(40)   NOT NULL,
    supplier_id        uuid,         -- catalogue.supplier arrives with M2-05
    manufacture_date   date,
    expiry_date        date,
    printed_mrp        numeric(14,2),
    origin_document_id uuid,
    is_synthetic       boolean       NOT NULL DEFAULT false,
    corrects_batch_id  uuid,
    status             text          NOT NULL DEFAULT 'REGISTERED' CHECK (status IN ('REGISTERED', 'SUPERSEDED')),
    created_at         timestamptz   NOT NULL DEFAULT now(),
    owner_entity_id    uuid          NOT NULL,
    PRIMARY KEY (created_at, batch_id)
) PARTITION BY RANGE (created_at);

-- 22A writes UNIQUE (sku_id, coalesce(supplier_id, '0...0'), batch_no, created_at) as a table
-- constraint; a constraint cannot hold an expression, a unique index can. The partition key
-- created_at is part of it because PostgreSQL requires that of a unique index on a partitioned
-- table, so it does not stop the same (sku, supplier, batch_no) being registered twice:
-- RegisterBatch looks the batch up first (existing-or-create, M2-05).
CREATE UNIQUE INDEX batch_sku_supplier_no_uq ON catalogue.batch (
    sku_id, coalesce(supplier_id, '00000000-0000-0000-0000-000000000000'::uuid), batch_no, created_at);
CREATE INDEX batch_lookup ON catalogue.batch (sku_id, batch_no);

ALTER TABLE catalogue.batch ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalogue.batch FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON catalogue.batch FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON catalogue.batch FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
-- A correction marks the old batch SUPERSEDED (status only, see the grant below).
CREATE POLICY own_update ON catalogue.batch FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON catalogue.batch FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON catalogue.batch FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));
-- 22A section 3: "readable by every authenticated scope (policy shared_read USING (true) for
-- SELECT)". The class test keeps a transaction with no scope out.
CREATE POLICY shared_read ON catalogue.batch FOR SELECT TO app_rw
    USING (kernel.scope_class() <> 'NONE');

-- 22A section 3: "batch: INSERT + UPDATE(status) only". A printed MRP or an expiry is never
-- edited; a correction is a new batch row (B-I8).
GRANT SELECT, INSERT ON catalogue.batch TO app_rw;
GRANT UPDATE (status) ON catalogue.batch TO app_rw;

-- The monthly partitions. A partition is read and written through catalogue.batch only (the
-- application has no privilege on a partition itself, and PostgreSQL applies the parent's
-- policies to a query on the parent); each partition still gets RLS enabled, forced and the
-- same policies, so that a partition read directly obeys the same rules and the schema test
-- (every table has row-level security and a policy) holds for partitions too. The kernel
-- does the same for its audit and outbox partitions (kernel V0030, V0031).
CREATE OR REPLACE FUNCTION catalogue.ensure_batch_partitions(months_ahead integer DEFAULT 3)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, catalogue
AS $$
DECLARE
    offset_month   integer;
    partition_from timestamptz;
    partition_to   timestamptz;
    partition_name text;
    policy         record;
BEGIN
    IF months_ahead < 0 OR months_ahead > 24 THEN
        RAISE EXCEPTION 'months_ahead must be between 0 and 24';
    END IF;

    FOR offset_month IN 0..months_ahead LOOP
        partition_from := (date_trunc('month', current_timestamp AT TIME ZONE 'UTC')
                           + make_interval(months => offset_month)) AT TIME ZONE 'UTC';
        partition_to := (date_trunc('month', current_timestamp AT TIME ZONE 'UTC')
                         + make_interval(months => offset_month + 1)) AT TIME ZONE 'UTC';
        partition_name := 'batch_' || to_char(partition_from AT TIME ZONE 'UTC', 'YYYY_MM');

        IF to_regclass('catalogue.' || partition_name) IS NULL THEN
            EXECUTE format(
                'CREATE TABLE catalogue.%I PARTITION OF catalogue.batch FOR VALUES FROM (%L) TO (%L)',
                partition_name, partition_from, partition_to);
        END IF;

        EXECUTE format('ALTER TABLE catalogue.%I ENABLE ROW LEVEL SECURITY', partition_name);
        EXECUTE format('ALTER TABLE catalogue.%I FORCE ROW LEVEL SECURITY', partition_name);
        EXECUTE format('REVOKE ALL PRIVILEGES ON catalogue.%I FROM PUBLIC, app_rw', partition_name);

        -- The parent's policies, copied by their text, so the two cannot drift apart.
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
    END LOOP;
END;
$$;

REVOKE ALL ON FUNCTION catalogue.ensure_batch_partitions(integer) FROM PUBLIC;
-- The kernel's partition functions are executable by the application role, which runs the
-- scheduled job; the same here, for the job that will call this one daily (see README.md).
GRANT EXECUTE ON FUNCTION catalogue.ensure_batch_partitions(integer) TO app_rw;

-- This month and the three after it.
SELECT catalogue.ensure_batch_partitions(3);
