-- PGL-M2-05: Durable legacy-code mapping for supplier master data migration
--
-- Adds optional supplier_code to catalogue.supplier, uniquely indexed per owner entity
-- when present, preserving legacy supplier identity across repeated imports.

ALTER TABLE catalogue.supplier ADD COLUMN supplier_code text;

CREATE UNIQUE INDEX supplier_code_per_owner ON catalogue.supplier (
    owner_entity_id, lower(supplier_code)
) WHERE supplier_code IS NOT NULL;

