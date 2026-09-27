-- A shop writes only at its own location (PLAN_TO_M2 task 6.12; decided 27 September 2026 on the
-- architect's delegation): the part of V0064 for a table of the sync lane, which V0080 creates
-- after V0064 on a fresh database. A quarantined fact is written in the device's scope, at the
-- device's shop (DeviceAuth, EventApplier), so nothing that works today changes.

ALTER POLICY own_write ON kernel.sync_quarantine
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
