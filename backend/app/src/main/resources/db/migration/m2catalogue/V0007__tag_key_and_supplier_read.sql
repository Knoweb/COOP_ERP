-- Catalogue decisions of 27 September 2026, taken on the architect's delegation
-- (docs/change-requests/CR-22A-1.md; the module README, "Deviations").
--
--   1. The key of a tag is its code within its owner (CR-22A-1, accepted as revised). V0001
--      keyed catalogue.tag on tag_code alone, federation-wide: two entities could not both have
--      a local tag "promo", the second learnt from the duplicate key that a tag it cannot see
--      exists, and the Federation could not introduce a governed code an entity had taken.
--      Now:
--        tag       tag_id is the primary key; (tag_code, owner or none) is unique, so a governed
--                  code is unique among governed tags and a local code within its entity.
--        sku_tag   names the tag by tag_id (the rows already there are carried over by their
--                  code, which V0001's key made unambiguous); the key is (sku_id, tag_id).
--      No reserved prefix: the governed codes stay as doc 22 and doc 18 name them (core-range).
--      A local DefineTag refuses a code a governed tag already has (governed tags are read by
--      everyone, so the refusal reveals nothing); a governed code introduced later may equal
--      an entity's older local code, and the two stay apart by tag_id and the governed flag.
--   2. A governed tag is written by the Federation (22A section 6, DefineTag: "governed tags F
--      only"). V0001's own_write needs an owner, and a governed tag has none, so no application
--      path could write one; governed_write and governed_update admit the Federation
--      (kernel.system_entity(), kernel V0061) in its OWN scope, for governed rows only.
--   3. sku_tag's own_write also checks the tag: governed, or the caller's own local tag. A
--      foreign key is checked without row-level security, so without this line an entity could
--      put another entity's local tag, which it cannot read, on its own item.
--   4. A supplier is read by its owner (the template) and, beyond it, by everyone once a batch
--      cites it (a society receiving the Federation's batch reads that batch's supplier).
--      V0004 let every scope read every supplier: an entity's list of whom it buys from was
--      the whole federation's to read before it had supplied anything.

-- ---------------------------------------------------------------------------------------------
-- 1. The key of a tag, and the assignments that name it

-- The policy that names sku_tag.tag_code goes first; it is rewritten below on tag_id.
DROP POLICY own_write ON catalogue.sku_tag;

ALTER TABLE catalogue.tag ADD COLUMN tag_id uuid NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE catalogue.tag ALTER COLUMN tag_id DROP DEFAULT;
ALTER TABLE catalogue.tag ADD CONSTRAINT tag_id_unique UNIQUE (tag_id);

-- The rows already assigned keep their tag: V0001's key made each code name one tag. The
-- migrator owns the table and has no policy on sku_tag, so FORCE is lifted for this one
-- statement (the owner then passes the policies) and put back straight after, in the same
-- transaction. The tag rows are read through seed_reference (the migrator is in app_seed).
ALTER TABLE catalogue.sku_tag ADD COLUMN tag_id uuid;
ALTER TABLE catalogue.sku_tag NO FORCE ROW LEVEL SECURITY;
UPDATE catalogue.sku_tag st
   SET tag_id = t.tag_id
  FROM catalogue.tag t
 WHERE t.tag_code = st.tag_code;
ALTER TABLE catalogue.sku_tag FORCE ROW LEVEL SECURITY;

ALTER TABLE catalogue.sku_tag ALTER COLUMN tag_id SET NOT NULL;
ALTER TABLE catalogue.sku_tag DROP CONSTRAINT sku_tag_pkey;
ALTER TABLE catalogue.sku_tag DROP COLUMN tag_code;
ALTER TABLE catalogue.sku_tag ADD PRIMARY KEY (sku_id, tag_id);

ALTER TABLE catalogue.tag DROP CONSTRAINT tag_pkey;
ALTER TABLE catalogue.tag ADD PRIMARY KEY (tag_id);
ALTER TABLE catalogue.tag DROP CONSTRAINT tag_id_unique;
ALTER TABLE catalogue.sku_tag
    ADD CONSTRAINT sku_tag_tag_id_fkey FOREIGN KEY (tag_id) REFERENCES catalogue.tag (tag_id);

-- A unique index, not a constraint: a constraint cannot hold the coalesce (the batch key does
-- the same). The all-zero owner stands for "governed", which no entity id ever is.
CREATE UNIQUE INDEX tag_code_per_owner ON catalogue.tag (
    tag_code, coalesce(owner_entity_id, '00000000-0000-0000-0000-000000000000'::uuid));

-- ---------------------------------------------------------------------------------------------
-- 2. Governed tags, by the Federation

CREATE POLICY governed_write ON catalogue.tag FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND kernel.scope_entity() = (SELECT kernel.system_entity())
                AND governed
                AND owner_entity_id IS NULL);
CREATE POLICY governed_update ON catalogue.tag FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND kernel.scope_entity() = (SELECT kernel.system_entity())
           AND governed)
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND kernel.scope_entity() = (SELECT kernel.system_entity())
                AND governed
                AND owner_entity_id IS NULL);

-- ---------------------------------------------------------------------------------------------
-- 3. A tag assignment: the SKU's owner, or an entity putting its own local tag on a SHARED
-- item (V0003); and in both cases a tag the caller may use.
CREATE POLICY own_write ON catalogue.sku_tag FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND EXISTS (SELECT 1 FROM catalogue.tag t
                             WHERE t.tag_id = sku_tag.tag_id
                               AND (t.governed OR t.owner_entity_id = kernel.scope_entity()))
                AND EXISTS (SELECT 1 FROM catalogue.sku s
                             WHERE s.sku_id = sku_tag.sku_id
                               AND (s.owner_entity_id = kernel.scope_entity()
                                    OR (s.status = 'SHARED'
                                        AND EXISTS (SELECT 1 FROM catalogue.tag t
                                                     WHERE t.tag_id = sku_tag.tag_id
                                                       AND t.owner_entity_id = kernel.scope_entity())))));

-- ---------------------------------------------------------------------------------------------
-- 4. Suppliers: the owner's, and those a batch cites

DROP POLICY authenticated_read ON catalogue.supplier;
CREATE POLICY cited_read ON catalogue.supplier FOR SELECT TO app_rw
    USING (kernel.scope_class() <> 'NONE'
           AND EXISTS (SELECT 1 FROM catalogue.batch_key k WHERE k.supplier_id = supplier.supplier_id));

CREATE INDEX batch_key_supplier ON catalogue.batch_key (supplier_id) WHERE supplier_id IS NOT NULL;
