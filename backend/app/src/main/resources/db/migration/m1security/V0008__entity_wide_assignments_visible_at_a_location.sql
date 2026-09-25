-- K-03b: an entity-wide role assignment applies at every location of the entity (doc 19
-- section 3.1: "an ENTITY grant is expanded to every location at check time"). The own_read
-- policy of V0001 hid a row with no location from a location-scoped session, so the resolver,
-- running in a shop user's scope, could not see the entity-wide assignments that apply there
-- and the user lost them at the till. The row stays the entity's; a location-scoped session
-- sees its own location's assignments and the entity-wide ones, as the resolver needs.
-- A merged migration is never edited: the policy is altered here.
ALTER POLICY own_read ON security.user_role
    USING (kernel.scope_class() = 'OWN'
           AND scope_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL
                OR scope_location_id IS NULL
                OR scope_location_id = kernel.scope_location()));
