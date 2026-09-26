-- The inbox of the event consumers (K-05, V0031) was open to every app_rw transaction
-- (USING (true)): any request could read, claim or overwrite the claims of any consumer.
-- A consumer runs in the system scope of the event it applies (EventConsumerDispatcher sets
-- the event's owner entity, class OWN), so a claim now carries that entity and app_rw sees and
-- writes only the claims of its scope entity. The policy follows own_read / own_write of
-- RLS_POLICY_TEMPLATE.md; the table has no location, so the location line is left out.
--
-- Rows written before this migration have no owner and are no longer visible to app_rw: a
-- dev stack needs `make reset` (the inbox of a fresh stack is empty).
--
-- Retention of the inbox (a partition or a purge job beside the outbox archive) is deferred:
-- it is decided with the outbox archive policy, not here.

ALTER TABLE kernel.event_inbox
    ADD COLUMN owner_entity_id uuid DEFAULT kernel.scope_entity();

DROP POLICY event_inbox_consumer
    ON kernel.event_inbox;

CREATE POLICY own_read
    ON kernel.event_inbox
    FOR SELECT
    TO app_rw
    USING (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
    );

CREATE POLICY own_write
    ON kernel.event_inbox
    FOR INSERT
    TO app_rw
    WITH CHECK (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
    );

CREATE POLICY own_update
    ON kernel.event_inbox
    FOR UPDATE
    TO app_rw
    USING (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
    )
    WITH CHECK (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
    );
