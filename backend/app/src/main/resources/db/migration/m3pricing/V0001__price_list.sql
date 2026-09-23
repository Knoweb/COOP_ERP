-- pricing.price_list: the table of the template module (17A section 12).
-- Copy this file's shape for every operational table: columns, constraints, row-level
-- security from the template of 17A section 6.3, then the narrowest grants that work.
-- The pricing schema itself is created by the kernel baseline (one schema per module).

CREATE TABLE pricing.price_list (
    id              uuid        PRIMARY KEY,                -- UUIDv7 from kernel Ids.next(), never a database default
    owner_entity_id uuid        NOT NULL,                   -- the legal entity that owns the row; every table has it
    text_en         text        NOT NULL CHECK (btrim(text_en) <> ''),
    text_si         text,                                   -- null means "not translated yet"; the screen then shows
    text_ta         text,                                   -- the English text with the EN fallback tag
    status          text        NOT NULL CHECK (status IN ('REGISTERED')),
    -- now() returns timestamptz, an absolute instant. Never wrap it in timezone('UTC', ...):
    -- that yields a zone-less value which PostgreSQL reads back in the session's zone, and
    -- the stored instant ends up shifted by the Colombo offset.
    created_at      timestamptz NOT NULL DEFAULT now(),

    -- The natural key (17A section 4.4: natural keys are unique constraints, never primary
    -- keys). It also makes the handler's duplicate guard safe when two requests race.
    CONSTRAINT price_list_owner_text_uq UNIQUE (owner_entity_id, text_en)
);

-- Row-level security. ENABLE switches it on; FORCE makes it apply to the table owner too.
ALTER TABLE pricing.price_list ENABLE ROW LEVEL SECURITY;
ALTER TABLE pricing.price_list FORCE ROW LEVEL SECURITY;

-- The policies read the caller's scope through the kernel helpers, never through
-- current_setting() directly: the helpers own the setting names (app.scope_entity_id, ...).
-- A price list has no location, so the location clause of the template is left out.
CREATE POLICY own_read ON pricing.price_list FOR SELECT TO app_rw
    USING (owner_entity_id = kernel.scope_entity());

CREATE POLICY own_write ON pricing.price_list FOR INSERT TO app_rw
    WITH CHECK (owner_entity_id = kernel.scope_entity());

CREATE POLICY fed_view ON pricing.price_list FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');

-- The fourth policy of the template, ext_view (regulators and auditors), needs
-- kernel.granted_entities(), which 19A ticket K-01 provides. Add it in a new migration then.

-- A price list is never changed or removed once registered, so: SELECT and INSERT, nothing else.
GRANT SELECT, INSERT ON pricing.price_list TO app_rw;
