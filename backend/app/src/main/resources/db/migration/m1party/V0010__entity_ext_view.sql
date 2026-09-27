-- The template's ext_view on party.entity (db/migration/RLS_POLICY_TEMPLATE.md; 17A section
-- 6.3; doc 18 section 3.7: EXTERNAL_TIMEBOXED reads its granted scope, read only). Every other
-- table of party has it (V0005 location and till_position, V0007 entity_relationship, V0009
-- device); party.entity alone was left without one when m1security V0009 left the party schema
-- to an m1party migration, and the review of the RLS matrix (#119) listed it as a departure to
-- fix here. Decided 27 September 2026 on the architect's delegation.
--
-- A regulator or auditor holding a grant for an entity reads that entity's registration row:
-- code, type, legal names, registration and VAT numbers, district, status. The row holds no
-- personal data (the responsible officer is a user id; the person is in security.app_user,
-- which keeps no ext_view on purpose). Nothing is written through it.
CREATE POLICY ext_view ON party.entity FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
           AND owner_entity_id = ANY (kernel.granted_entities()));
