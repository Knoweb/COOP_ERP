-- INV-07 Phase 3: activate the insert-only cancellation fact. A dispatched transfer may restore
-- stock only after a source-authorised user attests that the goods physically returned to the
-- original source. The check keeps that rule independently true in persisted facts.

ALTER TABLE inventory.transfer_cancellation
    ADD COLUMN cancelled_from_status text NOT NULL DEFAULT 'ISSUED'
        CHECK (cancelled_from_status IN ('ISSUED', 'IN_TRANSIT')),
    ADD COLUMN physical_return_verified_by uuid,
    ADD COLUMN physical_return_verified_at timestamptz,
    ADD CONSTRAINT transfer_cancellation_return_verification CHECK (
        (cancelled_from_status = 'ISSUED'
            AND physical_return_verified_by IS NULL
            AND physical_return_verified_at IS NULL)
        OR
        (cancelled_from_status = 'IN_TRANSIT'
            AND physical_return_verified_by IS NOT NULL
            AND physical_return_verified_at IS NOT NULL)
    );
