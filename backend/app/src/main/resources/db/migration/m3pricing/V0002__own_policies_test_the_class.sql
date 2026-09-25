-- CR-17A-3: own_read and own_write test the scope class as well as the entity, as the
-- template in db/migration/RLS_POLICY_TEMPLATE.md has it since 19A K-01 (hello's V0002 is the
-- same change). A merged migration is never edited: the policies are altered here.
ALTER POLICY own_read ON pricing.price_list
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());

ALTER POLICY own_write ON pricing.price_list
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
