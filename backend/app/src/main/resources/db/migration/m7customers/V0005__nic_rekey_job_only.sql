-- Review wave 3, M7M8M9-04: the two re-key functions of V0003 answered any FEDERATION_VIEW
-- session, and that is also the class of every human Federation viewer's request. So a viewer
-- session that reached SQL could read the reversible plain SHA-256 of every legacy member NIC
-- (legacy_nic_rows) and overwrite a legacy row's hash (rekey_nic), although CR-18-2 says that class
-- reads no personal data of natural persons.
--
-- Now they answer the platform's job scope only: FEDERATION_VIEW with no user and the entity that
-- is nobody's (the all-zero id of JobExecution.federationView and SystemScope.federationView). A
-- person's session always carries its user id (ScopeConnectionCustomizer sets app.user_id) and an
-- entity of the register, so no user can hold this scope. The functions are replaced in place:
-- same signatures, so their REVOKE from PUBLIC and GRANT to app_rw stand. They are dropped in a
-- later migration once NicRekeyJob reports no legacy row left (the suggested fix's second half).

CREATE OR REPLACE FUNCTION customers.legacy_nic_rows(p_limit integer)
RETURNS TABLE (customer_id uuid, nic_hash char(64))
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, customers, pg_temp
AS $$
    SELECT c.customer_id, c.nic_hash
      FROM customers.customer c
     WHERE kernel.scope_class() = 'FEDERATION_VIEW'
       AND nullif(current_setting('app.user_id', true), '') IS NULL
       AND kernel.scope_entity() = '00000000-0000-0000-0000-000000000000'::uuid
       AND c.nic_hash IS NOT NULL
       AND c.nic_key_id IS NULL
     ORDER BY c.customer_id
     LIMIT greatest(1, least(coalesce(p_limit, 500), 5000))
$$;

CREATE OR REPLACE FUNCTION customers.rekey_nic(p_customer uuid, p_old char(64), p_new char(64), p_key text)
RETURNS boolean
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog, customers, pg_temp
AS $$
DECLARE
    changed integer;
BEGIN
    IF kernel.scope_class() IS DISTINCT FROM 'FEDERATION_VIEW'
       OR nullif(current_setting('app.user_id', true), '') IS NOT NULL
       OR kernel.scope_entity() IS DISTINCT FROM '00000000-0000-0000-0000-000000000000'::uuid THEN
        RETURN false;
    END IF;
    IF p_customer IS NULL OR p_old IS NULL OR p_new IS NULL OR p_key IS NULL OR btrim(p_key) = '' THEN
        RAISE EXCEPTION 'rekey_nic needs the customer, the old and the new hash and the key id';
    END IF;
    UPDATE customers.customer
       SET nic_hash = p_new, nic_key_id = p_key
     WHERE customer_id = p_customer AND nic_hash = p_old AND nic_key_id IS NULL;
    GET DIAGNOSTICS changed = ROW_COUNT;
    RETURN changed > 0;
END;
$$;
