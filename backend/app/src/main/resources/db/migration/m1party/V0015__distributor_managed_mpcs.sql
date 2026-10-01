ALTER TABLE party.entity
    ADD COLUMN managing_distributor_id uuid REFERENCES party.entity (entity_id);

ALTER POLICY own_read ON party.entity
    USING (
        kernel.scope_class() = 'OWN'
        AND (
            owner_entity_id = kernel.scope_entity()
            OR managing_distributor_id = kernel.scope_entity()
        )
    );

ALTER POLICY own_write ON party.entity
    WITH CHECK (
        kernel.scope_class() = 'OWN'
        AND (
            owner_entity_id = kernel.scope_entity()
            OR managing_distributor_id = kernel.scope_entity()
        )
    );

ALTER POLICY own_update ON party.entity
    USING (
        kernel.scope_class() = 'OWN'
        AND (
            owner_entity_id = kernel.scope_entity()
            OR managing_distributor_id = kernel.scope_entity()
        )
    )
    WITH CHECK (
        kernel.scope_class() = 'OWN'
        AND (
            owner_entity_id = kernel.scope_entity()
            OR managing_distributor_id = kernel.scope_entity()
        )
    );
