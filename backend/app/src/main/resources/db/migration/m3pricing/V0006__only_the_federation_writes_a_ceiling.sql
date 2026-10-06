-- Wave 2 of the code review (RLS-04, RLS-05), decided 6 October 2026 on the architect's delegation:
-- docs/progress/deviations/2026-10-06-wave2-pricing-rules.md (1), (2); CR-23A-1.

-- ---- 1. only the Federation writes a ceiling, in SQL as well as in the handler (RLS-04) -----------

-- 23A section 3: a control price is "always the Federation's". V0005's own_write admitted any OWN
-- entity and everyone_reads showed every row as a ceiling, so the handler guard
-- (m3.control_price.federation_only) was the only thing between a society and a federation-wide
-- ceiling, and the exclusion constraint would then have refused the Federation's real entry. The
-- template's fed_admin form (RLS_POLICY_TEMPLATE.md; CR-21A-1 item 2): a policy that must know which
-- entity is the Federation asks (SELECT kernel.system_entity()). The class and entity tests stay, so
-- the Federation acting entity-wide is the only caller that passes all three; everyone_reads admits
-- the Federation's rows only, so a row that is somehow not the Federation's is a ceiling for nobody.
-- With no system entity configured nobody reads or writes a ceiling: fails closed (kernel V0061);
-- every selling environment sets coop-erp.system.entity-id. The handler guard stays.
ALTER POLICY own_write ON pricing.control_price
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND owner_entity_id = (SELECT kernel.system_entity()));
ALTER POLICY own_update ON pricing.control_price
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND owner_entity_id = (SELECT kernel.system_entity()))
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND owner_entity_id = (SELECT kernel.system_entity()));
ALTER POLICY everyone_reads ON pricing.control_price
    USING (kernel.scope_class() <> 'NONE'
           AND owner_entity_id = (SELECT kernel.system_entity()));

-- The same for the ADVISORY lists every scope reads (23A section 3: the Federation's advice): a
-- society's own list, whatever kind it says it is, is read by its owner through own_read only.
ALTER POLICY advisory_read ON pricing.price_list
    USING (kernel.scope_class() <> 'NONE'
           AND kind = 'ADVISORY'
           AND status IN ('PUBLISHED', 'SUPERSEDED')
           AND owner_entity_id = (SELECT kernel.system_entity()));
ALTER POLICY advisory_read ON pricing.price_list_line
    USING (kernel.scope_class() <> 'NONE'
           AND EXISTS (SELECT 1
                         FROM pricing.price_list l
                        WHERE l.price_list_id = price_list_line.price_list_id
                          AND l.kind = 'ADVISORY'
                          AND l.status IN ('PUBLISHED', 'SUPERSEDED')
                          AND l.owner_entity_id = (SELECT kernel.system_entity())));

-- pricing.mrp_policy is left as V0005 has it: 23A section 3 keys it by owner, a society's effective
-- policy falls back to the Federation's row, and the society's manager sets it.

-- ---- 2. a suspended buyer does not read the live trade list (RLS-05) -------------------------------

-- buyer_read admitted the buyer of any relationship row that names the list, whatever its status,
-- so a SUSPENDED or REPLACED buyer kept reading the seller's later versions. ACTIVE only, as
-- party_names_read (m1party V0013): a suspended buyer cannot order, and its invoices are the record
-- of the price it was charged. party.entity_relationship has no validity dates, only status.
-- price_list_line.buyer_read needs no change: its EXISTS on pricing.price_list runs under the
-- caller's policies and follows the list.
ALTER POLICY buyer_read ON pricing.price_list
    USING (kernel.scope_class() IN ('OWN', 'PARTY')
           AND kind = 'TRADE'
           AND status IN ('PUBLISHED', 'SUPERSEDED')
           AND EXISTS (SELECT 1
                         FROM party.entity_relationship r
                        WHERE r.price_list_id = price_list.root_price_list_id
                          AND r.seller_entity_id = price_list.owner_entity_id
                          AND r.buyer_entity_id = kernel.scope_entity()
                          AND r.status = 'ACTIVE'));
