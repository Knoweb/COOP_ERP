ALTER POLICY own_read ON security.app_user
    USING (
        kernel.scope_class() = 'OWN'
        AND (
            owner_entity_id = kernel.scope_entity()
            OR owner_entity_id IN (SELECT entity_id FROM party.entity WHERE managing_distributor_id = kernel.scope_entity())
        )
        AND (
            kernel.scope_location() IS NULL
            OR EXISTS (
                SELECT 1
                FROM security.user_role ur
                WHERE ur.user_id = app_user.user_id
                  AND ur.scope_entity_id = kernel.scope_entity()
                  AND (ur.scope_location_id IS NULL OR ur.scope_location_id = kernel.scope_location())
            )
            OR NOT security.user_has_any_assignment(app_user.user_id)
        )
    );

ALTER POLICY own_write ON security.app_user
    WITH CHECK (
        kernel.scope_class() = 'OWN'
        AND (
            owner_entity_id = kernel.scope_entity()
            OR owner_entity_id IN (SELECT entity_id FROM party.entity WHERE managing_distributor_id = kernel.scope_entity())
        )
    );

ALTER POLICY own_update ON security.app_user
    USING (
        kernel.scope_class() = 'OWN'
        AND (
            owner_entity_id = kernel.scope_entity()
            OR owner_entity_id IN (SELECT entity_id FROM party.entity WHERE managing_distributor_id = kernel.scope_entity())
        )
        AND (
            kernel.scope_location() IS NULL
            OR EXISTS (
                SELECT 1
                FROM security.user_role ur
                WHERE ur.user_id = app_user.user_id
                  AND ur.scope_entity_id = kernel.scope_entity()
                  AND (ur.scope_location_id IS NULL OR ur.scope_location_id = kernel.scope_location())
            )
            OR NOT security.user_has_any_assignment(app_user.user_id)
        )
    )
    WITH CHECK (
        kernel.scope_class() = 'OWN'
        AND (
            owner_entity_id = kernel.scope_entity()
            OR owner_entity_id IN (SELECT entity_id FROM party.entity WHERE managing_distributor_id = kernel.scope_entity())
        )
    );

ALTER POLICY own_read ON security.user_role
    USING (
        kernel.scope_class() = 'OWN'
        AND (
            scope_entity_id = kernel.scope_entity()
            OR scope_entity_id IN (SELECT entity_id FROM party.entity WHERE managing_distributor_id = kernel.scope_entity())
        )
        AND (
            kernel.scope_location() IS NULL
            OR scope_location_id IS NULL
            OR scope_location_id = kernel.scope_location()
        )
    );

ALTER POLICY own_write ON security.user_role
    WITH CHECK (
        kernel.scope_class() = 'OWN'
        AND (
            scope_entity_id = kernel.scope_entity()
            OR scope_entity_id IN (SELECT entity_id FROM party.entity WHERE managing_distributor_id = kernel.scope_entity())
        )
        AND (
            kernel.scope_location() IS NULL
            OR scope_location_id = kernel.scope_location()
        )
    );

ALTER POLICY own_read ON security.role
    USING (
        (
            kernel.scope_class() = 'OWN'
            AND (
                owner_entity_id = kernel.scope_entity()
                OR owner_entity_id IN (SELECT entity_id FROM party.entity WHERE managing_distributor_id = kernel.scope_entity())
            )
        )
        OR (
            owner_entity_id IS NULL
            AND kernel.scope_class() <> 'NONE'
        )
    );


