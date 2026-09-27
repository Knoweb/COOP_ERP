-- fix/demo-display-polish (28 September 2026): a trading counterparty's name showed as
-- "null Cooperative Federation" in the web client of every OWN- or PARTY-scoped reader (a
-- distributor's buyer, the Federation's stores). The party directory (V0003, V0013), which is
-- what such a reader gets for a counterparty (PartyQueriesImpl.getEntity), carried the legal
-- names and the VAT number but not the entity code, so the answer's entityCode was null.
--
-- The code is the party's public short name: it is printed on every document the party issues
-- (D101-ORD-0000001) and is no more private than the legal name beside it. This gives the
-- directory the code, kept by the same trigger, so a counterparty reads "FED Cooperative
-- Federation" exactly as the party's own users do. Row-level security (V0013) is unchanged.

ALTER TABLE party.entity_party_directory
    ADD COLUMN entity_code varchar(12);

CREATE OR REPLACE FUNCTION party.sync_entity_party_directory()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, party
AS $$
BEGIN
    INSERT INTO party.entity_party_directory (
        entity_id,
        entity_code,
        legal_name_en,
        legal_name_si,
        legal_name_ta,
        vat_registration_no
    )
    VALUES (
        NEW.entity_id,
        NEW.entity_code,
        NEW.legal_name_en,
        NEW.legal_name_si,
        NEW.legal_name_ta,
        NEW.vat_registration_no
    )
    ON CONFLICT (entity_id)
    DO UPDATE
    SET
        entity_code = EXCLUDED.entity_code,
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
    entity_code,
    legal_name_en,
    legal_name_si,
    legal_name_ta,
    vat_registration_no
ON party.entity
FOR EACH ROW
EXECUTE FUNCTION party.sync_entity_party_directory();

-- Backfill rows already present when this migration is installed.
UPDATE party.entity_party_directory d
   SET entity_code = e.entity_code
  FROM party.entity e
 WHERE e.entity_id = d.entity_id
   AND e.entity_code IS DISTINCT FROM d.entity_code;
