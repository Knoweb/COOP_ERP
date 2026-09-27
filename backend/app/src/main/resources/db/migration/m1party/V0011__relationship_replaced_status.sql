-- CR-21A-2 item 2, accepted on 27 September 2026: a relationship row whose first day has not
-- come yet can be corrected on that first day. An amendment dated exactly on the row's first
-- day, while that day is still in the future, marks the row REPLACED and a new ACTIVE row takes
-- its whole range with the corrected terms (AmendRelationshipTermsHandler). The replaced row is
-- kept, with its terms and range as they were, as the record of what was once agreed.
--
-- A REPLACED row is never in force: LookupRelationship reads ACTIVE rows only, and the A-I3
-- exclusion constraint of V0001 covers ACTIVE rows only, so the new row may take the same range.
-- Only the status list changes. V0001's CHECK has no name of its own, so PostgreSQL named it
-- entity_relationship_status_check; it is replaced by a named one.

ALTER TABLE party.entity_relationship
    DROP CONSTRAINT entity_relationship_status_check;

ALTER TABLE party.entity_relationship
    ADD CONSTRAINT entity_relationship_status_check
    CHECK (status IN ('DRAFT', 'ACTIVE', 'SUSPENDED', 'REPLACED'));
