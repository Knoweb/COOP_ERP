-- Wave 4 of the code review (K4-01, K4-03), 13 October 2026.

-- ---- 1. the version-floor ALERT is raised once per device and floor (K4-01) ---------------------

-- Doc 31 section 6: a till below the floor after its grace "appears in the helpdesk queue
-- automatically", once. The floor the device was last alerted on is kept on its cursor (the one
-- sync row updated in place, doc 32 section 2); the ALERT is written only by the statement that
-- moves this column to a new floor, so batches, retries and heartbeats after it write nothing,
-- and a later raise of the floor alerts again.
ALTER TABLE kernel.device_sync_cursor ADD COLUMN floor_alerted varchar(20);

GRANT UPDATE (floor_alerted) ON kernel.device_sync_cursor TO app_rw;

-- ---- 2. a configuration value is written at the caller's location (K4-03) -----------------------

-- The template's own_write (RLS_POLICY_TEMPLATE.md; V0064): own writes stay at the location, so a
-- shop-scoped session writes only its own location's values, never an entity-wide one nor a
-- sibling shop's. A federation-wide row (scope_entity_id NULL) is the Federation's alone: written
-- in the system entity's OWN scope (kernel.system_entity(), V0061), as ConfigRegistry.set checks.
DROP POLICY IF EXISTS own_write ON kernel.config_value;

CREATE POLICY own_write ON kernel.config_value FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND (kernel.scope_location() IS NULL OR scope_location_id = kernel.scope_location())
                AND (scope_entity_id = kernel.scope_entity()
                     OR (scope_entity_id IS NULL
                         AND kernel.scope_entity() = (SELECT kernel.system_entity()))));
