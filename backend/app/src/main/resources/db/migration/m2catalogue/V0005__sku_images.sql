-- M2-06: SKU images (22A sections 3 and 6; doc 22 section 3.4), the table M2-01 deferred.
--
--   1. catalogue.sku_image as 22A section 3 writes it, with its one-active-image index, and four
--      additions the flow needs (module README, Deviations):
--        content_type       what was presigned; the thumbnail job decodes by it
--        upload_expires_at  the end of the pre-signed PUT; nothing settles before it (the K-09
--                           rule: until then the same URL could replace the verified bytes)
--        created_at         when it was attached; an upload that never came is FAILED after the
--                           register's m2.image.upload_window_hours
--        status FAILED      an upload that never came, came with another hash or is not an
--                           image the job can read; 22A has PENDING, ACTIVE and RETIRED only
--   2. Who writes: the row is always the caller's own (owner_entity_id = the scope entity), on a
--      SKU the caller owns, or on a SHARED SKU as a local override (doc 22 section 3.4, DR-5:
--      "an entity may also attach a local override for a SHARED SKU ... scoped to its own
--      locations"). Who reads: the row's owner, and everyone the rows the SKU's owner attached to
--      a SHARED SKU; another entity's override stays its own.
--   3. A row changes only forward: PENDING -> ACTIVE, FAILED or RETIRED; ACTIVE -> RETIRED. A
--      settled row (FAILED, RETIRED) never changes again, and the thumbnail key is written once,
--      on PENDING -> ACTIVE (the trigger below; the K-09 rule for settled attachments).

-- ---------------------------------------------------------------------------------------------
-- 1. The table
CREATE TABLE catalogue.sku_image (
    image_id          uuid        PRIMARY KEY,
    sku_id            uuid        NOT NULL REFERENCES catalogue.sku,
    barcode           varchar(48),
    owner_entity_id   uuid        NOT NULL,
    object_key_full   text        NOT NULL,
    object_key_thumb  text,
    content_hash      char(64)    NOT NULL,
    content_type      text        NOT NULL,
    status            text        NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ACTIVE', 'RETIRED', 'FAILED')),
    upload_expires_at timestamptz NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CHECK (status <> 'ACTIVE' OR object_key_thumb IS NOT NULL)
);

-- 22A section 3: one ACTIVE image per (sku, barcode-or-none, owner). The owner is in the key, so
-- an entity's local override stands beside the SKU owner's image instead of colliding with it.
CREATE UNIQUE INDEX one_active_image ON catalogue.sku_image (sku_id, coalesce(barcode, ''), owner_entity_id)
    WHERE status = 'ACTIVE';

-- The thumbnail job's scan: the PENDING rows whose upload window has ended, oldest first.
CREATE INDEX sku_image_pending ON catalogue.sku_image (upload_expires_at)
    WHERE status = 'PENDING';

-- The lookup's read: the ACTIVE images of a SKU.
CREATE INDEX sku_image_by_sku ON catalogue.sku_image (sku_id)
    WHERE status = 'ACTIVE';

-- ---------------------------------------------------------------------------------------------
-- 2. Row-level security: the template (own_read, own_write, own_update, fed_view, ext_view), with
-- own_write following the SKU as on the other child rows (V0003), and shared_read for the SKU
-- owner's images of a SHARED SKU.
ALTER TABLE catalogue.sku_image ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalogue.sku_image FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON catalogue.sku_image FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON catalogue.sku_image FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND EXISTS (SELECT 1 FROM catalogue.sku s
                             WHERE s.sku_id = sku_image.sku_id
                               AND (s.owner_entity_id = kernel.scope_entity() OR s.status = 'SHARED')));
-- SettleImage (the thumbnail job, in the owner's scope) and RetireImage set the status and the
-- thumbnail key of the caller's own row.
CREATE POLICY own_update ON catalogue.sku_image FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON catalogue.sku_image FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON catalogue.sku_image FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));
-- A till sells a SHARED item with the owner's picture; a society's local override is its own
-- (doc 22 section 3.4: "scoped to its own locations") and is not shown to anyone else.
CREATE POLICY shared_read ON catalogue.sku_image FOR SELECT TO app_rw
    USING (kernel.scope_class() <> 'NONE'
           AND EXISTS (SELECT 1 FROM catalogue.sku s
                        WHERE s.sku_id = sku_image.sku_id
                          AND s.status = 'SHARED'
                          AND s.owner_entity_id = sku_image.owner_entity_id));

REVOKE ALL PRIVILEGES ON catalogue.sku_image FROM PUBLIC;
GRANT SELECT, INSERT ON catalogue.sku_image TO app_rw;
GRANT UPDATE (status, object_key_thumb) ON catalogue.sku_image TO app_rw;

-- ---------------------------------------------------------------------------------------------
-- 3. Forward only
CREATE FUNCTION catalogue.sku_image_transition()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, catalogue
AS $$
BEGIN
    IF OLD.status IN ('RETIRED', 'FAILED') THEN
        RAISE EXCEPTION 'm2.image.immutable' USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status = 'ACTIVE' AND NEW.status <> 'RETIRED' THEN
        RAISE EXCEPTION 'm2.image.immutable' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.object_key_thumb IS DISTINCT FROM OLD.object_key_thumb
       AND NOT (OLD.status = 'PENDING' AND NEW.status = 'ACTIVE') THEN
        RAISE EXCEPTION 'm2.image.immutable' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sku_image_transition
    BEFORE UPDATE ON catalogue.sku_image
    FOR EACH ROW EXECUTE FUNCTION catalogue.sku_image_transition();
