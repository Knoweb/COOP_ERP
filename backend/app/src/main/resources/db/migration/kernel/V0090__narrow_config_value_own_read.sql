-- FIX-11: Modify config_value.own_read to narrow its access so a database session with no scope cannot read federation-wide rows.

DROP POLICY IF EXISTS own_read ON kernel.config_value;

CREATE POLICY own_read ON kernel.config_value FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND (scope_entity_id IS NULL
                OR (scope_entity_id = kernel.scope_entity()
                    AND (kernel.scope_location() IS NULL
                         OR scope_location_id IS NULL
                         OR scope_location_id = kernel.scope_location()))));
