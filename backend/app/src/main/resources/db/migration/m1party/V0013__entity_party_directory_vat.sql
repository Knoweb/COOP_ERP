-- CR-21A-6, decided 27 September 2026 on the architect's delegation: an invoice (M4-08, PR #164)
-- records the buyer's VAT number empty, because the seller cannot read it. The seller's session
-- is OWN scope and party.entity's own_read policy (V0001) shows it only its own row; the
-- counterparty projection that could answer this (V0003, V0004: legal names, read by a PARTY-
-- scoped caller and, since V0004, narrowed to an active trading relationship) carries no VAT
-- number and is not consulted for an OWN-scoped reader at all.
--
-- This gives the directory the VAT number and lets an OWN-scoped caller read a counterparty's
-- row of it too, the same way a PARTY-scoped caller already does: its own entity, or the other
-- side of an active trading relationship (PartyQueriesImpl.getEntity falls back to the directory
-- once its own-scope read of party.entity finds nothing, which is exactly the case of a
-- counterparty). A trading counterparty is entitled to see this on a tax invoice; the masking of
-- doc 18 section 3.7 is of the columns a counterparty may not see, not of this one.

ALTER TABLE party.entity_party_directory
    ADD COLUMN vat_registration_no text;

CREATE OR REPLACE FUNCTION party.sync_entity_party_directory()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, party
AS $$
BEGIN
    INSERT INTO party.entity_party_directory (
        entity_id,
        legal_name_en,
        legal_name_si,
        legal_name_ta,
        vat_registration_no
    )
    VALUES (
        NEW.entity_id,
        NEW.legal_name_en,
        NEW.legal_name_si,
        NEW.legal_name_ta,
        NEW.vat_registration_no
    )
    ON CONFLICT (entity_id)
    DO UPDATE
    SET
        legal_name_en = EXCLUDED.legal_name_en,
        legal_name_si = EXCLUDED.legal_name_si,
        legal_name_ta = EXCLUDED.legal_name_ta,
        vat_registration_no = EXCLUDED.vat_registration_no;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_sync_entity_party_directory
    ON party.entity;

CREATE TRIGGER trg_sync_entity_party_directory
AFTER INSERT OR UPDATE OF
    legal_name_en,
    legal_name_si,
    legal_name_ta,
    vat_registration_no
ON party.entity
FOR EACH ROW
EXECUTE FUNCTION party.sync_entity_party_directory();

-- Backfill rows already present when this migration is installed.
UPDATE party.entity_party_directory d
   SET vat_registration_no = e.vat_registration_no
  FROM party.entity e
 WHERE e.entity_id = d.entity_id
   AND e.vat_registration_no IS DISTINCT FROM d.vat_registration_no;

-- An OWN-scoped caller reads its counterparty's directory row on the same terms a PARTY-scoped
-- caller does: its own entity, or the other side of an active trading relationship.
DROP POLICY party_names_read ON party.entity_party_directory;

CREATE POLICY party_names_read
    ON party.entity_party_directory
    FOR SELECT
    TO app_rw
    USING (
        kernel.scope_class() IN ('PARTY', 'OWN')
        AND (
            entity_id = kernel.scope_entity()
            OR entity_id IN (
                SELECT r.seller_entity_id FROM party.entity_relationship r
                 WHERE r.buyer_entity_id = kernel.scope_entity() AND r.status = 'ACTIVE'
                UNION
                SELECT r.buyer_entity_id FROM party.entity_relationship r
                 WHERE r.seller_entity_id = kernel.scope_entity() AND r.status = 'ACTIVE'
            )
        )
    );
