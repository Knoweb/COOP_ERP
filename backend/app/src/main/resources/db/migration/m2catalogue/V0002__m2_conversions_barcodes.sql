CREATE TABLE catalogue.conversion (
    id uuid PRIMARY KEY,
    sku_id uuid NOT NULL REFERENCES catalogue.sku(id),
    from_uom text NOT NULL,
    to_uom text NOT NULL,
    factor numeric(14,6) NOT NULL,
    valid_from date NOT NULL,
    valid_to date NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,

    CONSTRAINT cv_active_overlap EXCLUDE USING gist (
        sku_id WITH =,
        from_uom WITH =,
        to_uom WITH =,
        daterange(valid_from, valid_to, '[]') WITH &&
    )
);

ENABLE ROW LEVEL SECURITY ON catalogue.conversion;
FORCE ROW LEVEL SECURITY ON catalogue.conversion;
CREATE POLICY conversion_policy ON catalogue.conversion
    FOR ALL
    TO app_rw
    USING (true)
    WITH CHECK (true);

CREATE TABLE catalogue.barcode (
    id uuid PRIMARY KEY,
    barcode text NOT NULL,
    symbology text NOT NULL,
    sku_id uuid NOT NULL REFERENCES catalogue.sku(id),
    uom text NOT NULL,
    batch_id uuid,
    owner_entity_id uuid,
    status text NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL
);

ENABLE ROW LEVEL SECURITY ON catalogue.barcode;
FORCE ROW LEVEL SECURITY ON catalogue.barcode;
CREATE POLICY barcode_policy ON catalogue.barcode
    FOR ALL
    TO app_rw
    USING (owner_entity_id IS NULL OR owner_entity_id = (current_setting('app.entity_id'))::uuid)
    WITH CHECK (owner_entity_id IS NULL OR owner_entity_id = (current_setting('app.entity_id'))::uuid);
