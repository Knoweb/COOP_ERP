-- PGL-M2-08: Department Category dimension for retail merchandise and reporting
--
-- Adds catalogue.department_category for central merchandising hierarchy, and
-- links catalogue.sku to its department category via an optional foreign key
-- (department_id), preserving full backward compatibility for existing items.

CREATE TABLE catalogue.department_category (
    department_id   uuid         PRIMARY KEY,
    code            varchar(32)  NOT NULL,
    name_en         text         NOT NULL,
    name_si         text,
    name_ta         text,
    description     text,
    display_order   smallint     NOT NULL DEFAULT 0,
    status          varchar(16)  NOT NULL DEFAULT 'ACTIVE'
                                 CHECK (status IN ('ACTIVE', 'INACTIVE')),
    owner_entity_id uuid,
    created_at      timestamptz  NOT NULL DEFAULT clock_timestamp()
);

-- Unique code within an owner (all-zero UUID stands for governed/shared Federation departments)
CREATE UNIQUE INDEX department_category_code_per_owner ON catalogue.department_category (
    code, coalesce(owner_entity_id, '00000000-0000-0000-0000-000000000000'::uuid)
);

CREATE INDEX department_category_status_idx ON catalogue.department_category (status, display_order);

ALTER TABLE catalogue.department_category ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalogue.department_category FORCE ROW LEVEL SECURITY;

-- 1. Reference read: any authenticated tenant scope reads department categories
CREATE POLICY authenticated_read ON catalogue.department_category FOR SELECT TO app_rw
    USING (kernel.scope_class() <> 'NONE');

-- 3. Governed write and update: only Federation in OWN scope manages governed departments
CREATE POLICY governed_write ON catalogue.department_category FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND kernel.scope_entity() = (SELECT kernel.system_entity())
                AND owner_entity_id IS NULL);

CREATE POLICY governed_update ON catalogue.department_category FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND kernel.scope_entity() = (SELECT kernel.system_entity())
           AND owner_entity_id IS NULL)
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND kernel.scope_entity() = (SELECT kernel.system_entity())
                AND owner_entity_id IS NULL);

GRANT SELECT, INSERT, UPDATE ON catalogue.department_category TO app_rw;

-- Department dimension on SKU: optional for legacy items, queryable by reporting
ALTER TABLE catalogue.sku
    ADD COLUMN department_id uuid REFERENCES catalogue.department_category (department_id);

CREATE INDEX sku_department_id_idx ON catalogue.sku (department_id);
