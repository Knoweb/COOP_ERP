-- CAT-12: inter-entity receivable opening balances (bulk onboarding).
-- The two-party signed opening receivable document links to kernel.document and trading.doc_invoice,
-- recording the authorization from both the seller (e.g. Distributor) and the buyer (e.g. MPCS).

CREATE TABLE trading.opening_receivable (
    opening_receivable_id uuid          PRIMARY KEY,
    document_id           uuid          NOT NULL UNIQUE,
    relationship_id       uuid          NOT NULL,
    seller_entity_id      uuid          NOT NULL,
    buyer_entity_id       uuid          NOT NULL,
    amount                numeric(14,2) NOT NULL CHECK (amount > 0),
    reference_no          varchar(60),
    seller_signed_by      uuid          NOT NULL,
    seller_signed_at      timestamptz   NOT NULL DEFAULT now(),
    buyer_signed_by       uuid          NOT NULL,
    buyer_signed_at       timestamptz   NOT NULL DEFAULT now(),
    notes                 text,
    created_at            timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX opening_receivable_parties ON trading.opening_receivable (seller_entity_id, buyer_entity_id);
CREATE INDEX opening_receivable_document ON trading.opening_receivable (document_id);

ALTER TABLE trading.opening_receivable ENABLE ROW LEVEL SECURITY;
ALTER TABLE trading.opening_receivable FORCE ROW LEVEL SECURITY;

CREATE POLICY document_read ON trading.opening_receivable FOR SELECT TO app_rw
    USING (kernel.document_visible(document_id));

CREATE POLICY document_write ON trading.opening_receivable FOR INSERT TO app_rw
    WITH CHECK (kernel.document_owned(document_id) AND kernel.document_open_for_write(document_id));

GRANT SELECT, INSERT ON trading.opening_receivable TO app_rw;

CREATE OR REPLACE FUNCTION trading.resolve_opening_entity(
    p_identifier text
)
RETURNS uuid
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, party, pg_temp
AS $$
DECLARE
    v_entity_id uuid;
    v_direct_id uuid;
BEGIN
    IF kernel.scope_class() IS DISTINCT FROM 'OWN'
       OR kernel.scope_entity() IS NULL
       OR p_identifier IS NULL
       OR trim(p_identifier) = '' THEN
        RETURN NULL;
    END IF;

    BEGIN
        v_direct_id := trim(p_identifier)::uuid;
    EXCEPTION WHEN invalid_text_representation THEN
        v_direct_id := NULL;
    END;

    IF v_direct_id IS NOT NULL THEN
        SELECT entity_id INTO v_entity_id
          FROM party.entity
         WHERE entity_id = v_direct_id
           AND status = 'ACTIVE'
           AND (
               entity_id = kernel.scope_entity()
               OR kernel.scope_entity() = kernel.system_entity()
               OR EXISTS (
                   SELECT 1 FROM party.entity_relationship r
                    WHERE (r.seller_entity_id = kernel.scope_entity() AND r.buyer_entity_id = v_direct_id)
                       OR (r.buyer_entity_id = kernel.scope_entity() AND r.seller_entity_id = v_direct_id)
               )
           )
         LIMIT 1;
        IF v_entity_id IS NOT NULL THEN
            RETURN v_entity_id;
        END IF;
    END IF;

    SELECT entity_id INTO v_entity_id
      FROM party.entity
     WHERE (entity_code = trim(p_identifier) OR legal_name_en = trim(p_identifier))
       AND status = 'ACTIVE'
       AND (
           entity_id = kernel.scope_entity()
           OR kernel.scope_entity() = kernel.system_entity()
           OR EXISTS (
               SELECT 1 FROM party.entity_relationship r
                WHERE (r.seller_entity_id = kernel.scope_entity() AND r.buyer_entity_id = entity_id)
                   OR (r.buyer_entity_id = kernel.scope_entity() AND r.seller_entity_id = entity_id)
           )
       )
     LIMIT 1;

    RETURN v_entity_id;
END;
$$;

CREATE OR REPLACE FUNCTION trading.resolve_opening_signer(
    p_entity_id uuid,
    p_identifier text
)
RETURNS uuid
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, security, party, pg_temp
AS $$
DECLARE
    v_user_id uuid;
    v_direct_id uuid;
BEGIN
    IF kernel.scope_class() IS DISTINCT FROM 'OWN'
       OR kernel.scope_entity() IS NULL
       OR p_entity_id IS NULL
       OR p_identifier IS NULL
       OR trim(p_identifier) = '' THEN
        RETURN NULL;
    END IF;

    IF p_entity_id IS DISTINCT FROM kernel.scope_entity()
       AND kernel.scope_entity() IS DISTINCT FROM kernel.system_entity()
       AND NOT EXISTS (
           SELECT 1 FROM party.entity_relationship r
            WHERE (r.seller_entity_id = kernel.scope_entity() AND r.buyer_entity_id = p_entity_id)
               OR (r.buyer_entity_id = kernel.scope_entity() AND r.seller_entity_id = p_entity_id)
       ) THEN
        RETURN NULL;
    END IF;

    BEGIN
        v_direct_id := trim(p_identifier)::uuid;
    EXCEPTION WHEN invalid_text_representation THEN
        v_direct_id := NULL;
    END;

    IF v_direct_id IS NOT NULL THEN
        SELECT user_id INTO v_user_id
          FROM security.app_user
         WHERE user_id = v_direct_id
           AND home_entity_id = p_entity_id
           AND status = 'ACTIVE'
         LIMIT 1;
        IF v_user_id IS NOT NULL THEN
            RETURN v_user_id;
        END IF;
    END IF;

    SELECT user_id INTO v_user_id
      FROM security.app_user
     WHERE home_entity_id = p_entity_id
       AND (username = trim(p_identifier) OR display_name = trim(p_identifier))
       AND status = 'ACTIVE'
     LIMIT 1;

    RETURN v_user_id;
END;
$$;

CREATE OR REPLACE FUNCTION trading.simulate_opening_signer(
    p_entity_id uuid
)
RETURNS uuid
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, security, party, pg_temp
AS $$
DECLARE
    v_user_id uuid;
BEGIN
    IF kernel.scope_class() IS DISTINCT FROM 'OWN'
       OR kernel.scope_entity() IS NULL
       OR p_entity_id IS NULL THEN
        RETURN NULL;
    END IF;

    IF p_entity_id IS DISTINCT FROM kernel.scope_entity()
       AND kernel.scope_entity() IS DISTINCT FROM kernel.system_entity()
       AND NOT EXISTS (
           SELECT 1 FROM party.entity_relationship r
            WHERE (r.seller_entity_id = kernel.scope_entity() AND r.buyer_entity_id = p_entity_id)
               OR (r.buyer_entity_id = kernel.scope_entity() AND r.seller_entity_id = p_entity_id)
       ) THEN
        RETURN NULL;
    END IF;

    SELECT user_id INTO v_user_id
      FROM security.app_user
     WHERE home_entity_id = p_entity_id
       AND status = 'ACTIVE'
     ORDER BY user_id
     LIMIT 1;

    RETURN v_user_id;
END;
$$;

REVOKE ALL ON FUNCTION trading.resolve_opening_entity(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION trading.resolve_opening_entity(text) TO app_rw;

REVOKE ALL ON FUNCTION trading.resolve_opening_signer(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION trading.resolve_opening_signer(uuid, text) TO app_rw;

REVOKE ALL ON FUNCTION trading.simulate_opening_signer(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION trading.simulate_opening_signer(uuid) TO app_rw;
