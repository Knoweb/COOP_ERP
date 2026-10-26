
-- PRC-04: Federation advisory pricing rules are readable
-- by authenticated scopes for advisory adoption.
-- Ordinary rules remain protected by their existing RLS policies.

CREATE POLICY advisory_read
ON pricing.discount_rule
FOR SELECT
TO app_rw
USING (
    kernel.scope_class() <> 'NONE'
    AND advisory = true
    AND status = 'ACTIVE'
    AND owner_entity_id = (SELECT kernel.system_entity())
);
