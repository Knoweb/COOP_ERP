-- CR-19A-7 revised (27 Sep): the upload ledger of the objects a module owns without a document.
-- Lane C (19A section 9, attachments), the next free number after V0059.
--
-- kernel.api.ObjectStorage (M2-06) presigned a PUT for any key and kept no state, so the rules
-- K-09 gave a document's attachment (settle only after the presign window, a settled row never
-- changes, no new URL once settled) were each module's to keep, and M2's alone kept them. This
-- table holds one row per object a module asked an upload for, owned by the entity named in the
-- key: presignPut inserts it PENDING (or renews the window of a PENDING row), verify settles it
-- VERIFIED or FAILED after the window, and write, read and presignGet ask it first. The module
-- keeps its own business row (catalogue.sku_image) as before; this is the kernel's half.
--
-- The trigger below is V0058's for document_attachment: only a PENDING row may change, and only
-- forward (renewed, or settled once). The key, the module, the owner, the type and the creation
-- time never change. No DELETE for app_rw (17A section 6.2).

CREATE TABLE kernel.object_upload (
    object_key text PRIMARY KEY,
    owner_module text NOT NULL CHECK (owner_module ~ '^[a-z][a-z0-9]*$'),
    owner_entity_id uuid NOT NULL,
    content_type text NOT NULL,
    content_length bigint CHECK (content_length > 0),
    content_hash text CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'VERIFIED', 'FAILED')),
    failure text CHECK (failure IN ('MISSING', 'TOO_LARGE', 'HASH_MISMATCH')),
    upload_expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    settled_at timestamptz,
    CONSTRAINT object_upload_settled CHECK ((status = 'PENDING') = (settled_at IS NULL)),
    CONSTRAINT object_upload_failure CHECK ((status = 'FAILED') = (failure IS NOT NULL))
);

CREATE OR REPLACE FUNCTION kernel.object_upload_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'object.immutable' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.object_key IS DISTINCT FROM OLD.object_key
       OR NEW.owner_module IS DISTINCT FROM OLD.owner_module
       OR NEW.owner_entity_id IS DISTINCT FROM OLD.owner_entity_id
       OR NEW.content_type IS DISTINCT FROM OLD.content_type
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'object.identity_immutable' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_object_upload_transition
    BEFORE UPDATE ON kernel.object_upload
    FOR EACH ROW EXECUTE FUNCTION kernel.object_upload_transition();

-- The template (RLS_POLICY_TEMPLATE.md) without a location column: the object is the entity's.
ALTER TABLE kernel.object_upload ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.object_upload FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON kernel.object_upload FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON kernel.object_upload FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_update ON kernel.object_upload FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON kernel.object_upload FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON kernel.object_upload FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
           AND owner_entity_id = ANY (kernel.granted_entities()));

-- Only what a renewal (the window, the announced hash and length) and a settlement (the status,
-- the failure, the stored hash and size, when) change.
REVOKE ALL PRIVILEGES ON kernel.object_upload FROM PUBLIC;
GRANT SELECT, INSERT ON kernel.object_upload TO app_rw;
GRANT UPDATE (upload_expires_at, content_hash, content_length, status, failure, settled_at)
    ON kernel.object_upload TO app_rw;
