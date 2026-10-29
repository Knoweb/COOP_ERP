-- M1-03: support COMMERCIAL price lists (institutional / commercial selling prices).
ALTER TABLE pricing.price_list DROP CONSTRAINT price_list_kind_check;
ALTER TABLE pricing.price_list ADD CONSTRAINT price_list_kind_check
    CHECK (kind IN ('TRADE', 'RETAIL', 'ADVISORY', 'COMMERCIAL'));

CREATE UNIQUE INDEX one_published_commercial ON pricing.price_list (owner_entity_id)
    WHERE kind = 'COMMERCIAL' AND status = 'PUBLISHED';
CREATE UNIQUE INDEX one_draft_commercial ON pricing.price_list (owner_entity_id)
    WHERE kind = 'COMMERCIAL' AND status = 'DRAFT';
