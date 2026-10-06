-- Wave 2 of the code review (M7CR-01, -02, -09, -10, -11, -13, -15; RLS-01, -02, -03, -13), decided
-- 6 October 2026 on the architect's delegation:
--   docs/progress/deviations/2026-10-06-wave2-keyed-hashes.md (3), (4)
--   docs/progress/deviations/2026-10-06-wave2-member-identity-visibility.md (1)   CR-18-2
--   docs/progress/deviations/2026-10-06-wave2-cross-tenant-functions.md (1), (2)
--   docs/progress/deviations/2026-10-06-wave2-m7-erasure-and-retention.md (1), (2)   CR-27A-1
-- The identity side of the module: a keyed NIC hash with its key id and the re-key door, the
-- NIC duplicate check across societies, the two existing cross-tenant functions brought under
-- the pattern, the locked erasure guard, the redaction grant, and member identity out of the
-- view-all classes. The credit book (V0004) is a separate migration on purpose.

-- ---------------------------------------------------------------------------------------------
-- 1. The NIC hash is keyed. nic_hash is now HMAC-SHA-256(pepper, SHA-256("nic:" + canonical NIC))
--    under the key nic_key_id names (KeyedHash.keyId(), 16 hex characters). A row with a NULL key
--    id still holds the plain SHA-256 of the form that was typed (the legacy scheme); NicRekeyJob
--    turns those into HMAC(pepper, stored_sha256) through rekey_nic below without knowing a NIC,
--    and until it reports none left the lookup tries the legacy candidate set as well.
ALTER TABLE customers.customer ADD COLUMN nic_key_id text;
GRANT UPDATE (nic_key_id) ON customers.customer TO app_rw;

-- The definer functions below run as the migrator, a member of app_seed, whom FORCE subjects to
-- the policies too: customer_directory lets that role, and nobody else, read every customer row,
-- as phone_directory (V0001) and balance_directory (V0002) do for phones and accounts.
CREATE POLICY customer_directory ON customers.customer FOR SELECT TO app_seed USING (true);
-- rekey_nic and the erasure lock change rows of every society: an UPDATE policy for the migrator
-- alone (a FOR UPDATE lock is refused under RLS without one).
CREATE POLICY customer_rekey ON customers.customer FOR UPDATE TO app_seed USING (true) WITH CHECK (true);
CREATE POLICY erasure_lock ON customers.customer_account FOR UPDATE TO app_seed USING (true) WITH CHECK (true);
CREATE POLICY history_directory ON customers.account_history FOR SELECT TO app_seed USING (true);

-- The legacy rows the re-key job works through, oldest first, a page at a time. The job runs as
-- the platform's FEDERATION_VIEW reader (the class a job names explicitly, as
-- kernel.change_log_purge does); an OWN session or any other class gets nothing. What leaves is
-- the stored hash, which the job must have to compute the keyed one; it never leaves the
-- application.
CREATE FUNCTION customers.legacy_nic_rows(p_limit integer)
RETURNS TABLE (customer_id uuid, nic_hash char(64))
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, customers, pg_temp
AS $$
    SELECT c.customer_id, c.nic_hash
      FROM customers.customer c
     WHERE kernel.scope_class() = 'FEDERATION_VIEW'
       AND c.nic_hash IS NOT NULL
       AND c.nic_key_id IS NULL
     ORDER BY c.customer_id
     LIMIT greatest(1, least(coalesce(p_limit, 500), 5000))
$$;

REVOKE ALL ON FUNCTION customers.legacy_nic_rows(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION customers.legacy_nic_rows(integer) TO app_rw;

-- One legacy row re-keyed: only when it still holds p_old with no key id, so a run that stops
-- half way is simply run again (idempotent, restartable), and a row an officer re-captured in
-- between is left as the officer wrote it. The pepper never reaches SQL text: the caller computed
-- p_new in Java. Answers whether a row was changed.
CREATE FUNCTION customers.rekey_nic(p_customer uuid, p_old char(64), p_new char(64), p_key text)
RETURNS boolean
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog, customers, pg_temp
AS $$
DECLARE
    changed integer;
BEGIN
    IF kernel.scope_class() IS DISTINCT FROM 'FEDERATION_VIEW' THEN
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

REVOKE ALL ON FUNCTION customers.rekey_nic(uuid, char, char, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION customers.rekey_nic(uuid, char, char, text) TO app_rw;

-- 2. The NIC duplicate check sees every society (M7CR-15), under the pattern of
--    RLS_POLICY_TEMPLATE.md ("a function that answers across tenants"): an OWN caller only; the
--    caller is kernel.scope_entity(); the parameter names the hashes asked about (the keyed
--    canonical hash and, while legacy rows exist, their SHA-256 candidates); the answer is the
--    caller's own customer id when the holder is the caller's, else only that one is held
--    elsewhere. Another society's id never leaves it (one person, one identity: CR-27A-1 item 5).
CREATE FUNCTION customers.nic_holders(p_hashes char(64)[])
RETURNS TABLE (own_customer_id uuid, held_elsewhere boolean)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, customers, pg_temp
AS $$
    SELECT CASE WHEN c.owner_entity_id = kernel.scope_entity() THEN c.customer_id END,
           c.owner_entity_id IS DISTINCT FROM kernel.scope_entity()
      FROM customers.customer c
     WHERE kernel.scope_class() = 'OWN'
       AND kernel.scope_entity() IS NOT NULL
       AND c.nic_hash IS NOT NULL
       AND c.nic_hash = ANY (p_hashes)
$$;

REVOKE ALL ON FUNCTION customers.nic_holders(char[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION customers.nic_holders(char[]) TO app_rw;

-- 3. phone_holders under the same pattern (RLS-03): the own id when the holder is the caller's
--    customer, whether the caller holds it, and when the holder let the number go (ReuseDetector
--    keeps NEEDS_CONFIRMATION by valid_to). The return type changes, so the function is replaced.
DROP FUNCTION customers.phone_holders(text);

CREATE FUNCTION customers.phone_holders(p_phone text)
RETURNS TABLE (own_customer_id uuid, held_by_caller boolean, valid_to timestamptz)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, customers, pg_temp
AS $$
    SELECT CASE WHEN ph.owner_entity_id = kernel.scope_entity() THEN ph.customer_id END,
           ph.owner_entity_id = kernel.scope_entity(),
           ph.valid_to
      FROM customers.customer_phone ph
     WHERE kernel.scope_class() = 'OWN'
       AND kernel.scope_entity() IS NOT NULL
       AND ph.phone = p_phone
       AND ph.is_primary
$$;

REVOKE ALL ON FUNCTION customers.phone_holders(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION customers.phone_holders(text) TO app_rw;

-- 4. accounts_with_balance under the pattern: an OWN caller that registered the customer, else
--    zero. Kept for the deactivation and account screens; the erasure guard now locks instead.
CREATE OR REPLACE FUNCTION customers.accounts_with_balance(p_customer uuid)
RETURNS integer
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, customers, pg_temp
AS $$
    SELECT CASE
             WHEN kernel.scope_class() = 'OWN'
              AND EXISTS (SELECT 1 FROM customers.customer c
                           WHERE c.customer_id = p_customer AND c.owner_entity_id = kernel.scope_entity())
             THEN (SELECT count(*)::integer FROM customers.customer_account a
                    WHERE a.customer_id = p_customer AND a.balance <> 0)
             ELSE 0
           END
$$;

-- 5. The erasure guard (M7CR-09, CR-27A-1 item 3): every account of the customer at every
--    society, locked for the transaction, counted three ways: not CLOSED, with a balance (a till
--    fact may land on a CLOSED account), and CLOSED after p_closed_since (the offline window has
--    not passed). An OWN caller that registered the customer only; anyone else gets nothing and
--    locks nothing. VOLATILE: it takes row locks.
CREATE FUNCTION customers.lock_accounts_for_erasure(p_customer uuid, p_closed_since timestamptz)
RETURNS TABLE (not_closed integer, with_balance integer, recently_closed integer)
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog, customers, pg_temp
AS $$
BEGIN
    IF kernel.scope_class() IS DISTINCT FROM 'OWN'
       OR NOT EXISTS (SELECT 1 FROM customers.customer c
                       WHERE c.customer_id = p_customer AND c.owner_entity_id = kernel.scope_entity()) THEN
        RETURN;
    END IF;
    RETURN QUERY
        WITH locked AS (
            SELECT a.account_id, a.status, a.balance
              FROM customers.customer_account a
             WHERE a.customer_id = p_customer
             ORDER BY a.account_id
               FOR UPDATE
        )
        SELECT count(*) FILTER (WHERE l.status <> 'CLOSED')::integer,
               count(*) FILTER (WHERE l.balance <> 0)::integer,
               count(*) FILTER (WHERE l.status = 'CLOSED'
                                  AND EXISTS (SELECT 1 FROM customers.account_history h
                                               WHERE h.account_id = l.account_id
                                                 AND h.action = 'CLOSED'
                                                 AND h.changed_at > p_closed_since))::integer
          FROM locked l;
END;
$$;

REVOKE ALL ON FUNCTION customers.lock_accounts_for_erasure(uuid, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION customers.lock_accounts_for_erasure(uuid, timestamptz) TO app_rw;

-- 6. What erasure redacts (M7CR-10): the module's own free text about the person. notes joins
--    outcome in the UPDATE grant; nothing else gains one (history, adjustment reasons and the CPR
--    reference are the society's accounting records, retained under doc 10 L-05).
GRANT UPDATE (notes) ON customers.data_subject_request TO app_rw;

-- 7. Member identity is the society's (CR-18-2): FEDERATION_VIEW and EXTERNAL_TIMEBOXED read no
--    personal data of a natural person by policy. The credit book keeps both policies (money, no
--    name). data_subject_request loses both too: its notes and outcome are free text about the
--    person, and the redaction above is in this same migration.
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['customer', 'customer_phone', 'customer_consent', 'data_subject_request']
    LOOP
        EXECUTE format('DROP POLICY fed_view ON customers.%I', t);
        EXECUTE format('DROP POLICY ext_view ON customers.%I', t);
    END LOOP;
END;
$$;

-- 8. Definer hygiene (RLS-13): pg_temp last on every SECURITY DEFINER function of the schema
--    (kernel V0086 says why); bodies unchanged.
ALTER FUNCTION customers.secure_posting_partition(text)
    SET search_path = pg_catalog, customers, pg_temp;
ALTER FUNCTION customers.ensure_posting_partitions(integer)
    SET search_path = pg_catalog, customers, pg_temp;
