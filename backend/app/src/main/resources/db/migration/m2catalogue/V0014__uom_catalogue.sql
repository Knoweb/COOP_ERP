-- PGL-M2-04: UOM Catalogue lifecycle management
--
-- Extends catalogue.uom with status, description, and created_at timestamps.
-- Grants app_rw insert and column-level update privileges (uom_code is immutable)
-- and adds governed write and update policies restricted to the Federation in OWN scope.

ALTER TABLE catalogue.uom
    ADD COLUMN description text,
    ADD COLUMN status varchar(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT clock_timestamp();

CREATE INDEX uom_status_idx ON catalogue.uom (status);

-- Governed write and update: only Federation in OWN scope manages units of measure
CREATE POLICY governed_write ON catalogue.uom FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND kernel.scope_entity() = (SELECT kernel.system_entity()));

CREATE POLICY governed_update ON catalogue.uom FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND kernel.scope_entity() = (SELECT kernel.system_entity()))
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND kernel.scope_entity() = (SELECT kernel.system_entity()));

GRANT INSERT ON catalogue.uom TO app_rw;
GRANT UPDATE (name_en, name_si, name_ta, description, is_weight, status) ON catalogue.uom TO app_rw;
