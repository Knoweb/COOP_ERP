-- M9 Integration: Online Payments, COD, and LankaQR Orchestration (Wave 7, PGL-M3-07).
-- Provider-neutral payment intent tracking, LankaQR payload persistence, COD lifecycle,
-- idempotent settlement, and audit logs.

CREATE TABLE integration.payment_intent (
    payment_id         uuid         PRIMARY KEY,
    owner_entity_id    uuid         NOT NULL,
    order_reference    text         NOT NULL CHECK (btrim(order_reference) <> ''),
    customer_ref       text         NOT NULL CHECK (btrim(customer_ref) <> ''),
    amount             numeric(14,2) NOT NULL CHECK (amount > 0),
    currency           text         NOT NULL DEFAULT 'LKR' CHECK (currency = 'LKR'),
    payment_method     text         NOT NULL CHECK (payment_method IN ('ONLINE', 'LANKA_QR', 'COD')),
    status             text         NOT NULL DEFAULT 'INITIATED' CHECK (status IN ('INITIATED', 'PENDING', 'PAID', 'FAILED', 'COD_PENDING', 'CANCELLED')),
    provider           text         NOT NULL,
    provider_ref       text,
    qr_payload         text,
    description        text,
    failure_reason     text,
    idempotency_key    text         NOT NULL,
    metadata           jsonb        NOT NULL DEFAULT '{}'::jsonb,
    initiated_at       timestamptz  NOT NULL DEFAULT now(),
    settled_at         timestamptz,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT payment_intent_owner_dedup_uq UNIQUE (owner_entity_id, idempotency_key)
);

CREATE INDEX payment_intent_owner_status ON integration.payment_intent (owner_entity_id, status);
CREATE INDEX payment_intent_order_ref ON integration.payment_intent (owner_entity_id, order_reference);
CREATE INDEX payment_intent_provider_ref ON integration.payment_intent (provider, provider_ref);

ALTER TABLE integration.payment_intent ENABLE ROW LEVEL SECURITY;
ALTER TABLE integration.payment_intent FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON integration.payment_intent FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON integration.payment_intent FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_update ON integration.payment_intent FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON integration.payment_intent FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON integration.payment_intent FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT, UPDATE ON integration.payment_intent TO app_rw;

-- Audit / event log for provider callbacks, settlements, and webhooks
CREATE TABLE integration.payment_event_log (
    event_id           uuid         PRIMARY KEY,
    payment_id         uuid         NOT NULL REFERENCES integration.payment_intent(payment_id),
    owner_entity_id    uuid         NOT NULL,
    event_type         text         NOT NULL,
    provider           text         NOT NULL,
    provider_ref       text,
    status             text         NOT NULL,
    payload            jsonb        NOT NULL DEFAULT '{}'::jsonb,
    created_at         timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX payment_event_log_payment ON integration.payment_event_log (payment_id);

ALTER TABLE integration.payment_event_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE integration.payment_event_log FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON integration.payment_event_log FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON integration.payment_event_log FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON integration.payment_event_log FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON integration.payment_event_log FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON integration.payment_event_log TO app_rw;

