CREATE SCHEMA IF NOT EXISTS hello;

CREATE TABLE hello.greeting (
    id UUID PRIMARY KEY,
    owner_entity_id UUID NOT NULL,
    text_en TEXT NOT NULL,
    text_si TEXT,
    text_ta TEXT,
    status TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT timezone('UTC', now()) NOT NULL
);

ALTER TABLE hello.greeting ENABLE ROW LEVEL SECURITY;
ALTER TABLE hello.greeting FORCE ROW LEVEL SECURITY;

CREATE POLICY greeting_own_policy ON hello.greeting
    FOR ALL
    TO app_rw
    USING (owner_entity_id = nullif(current_setting('knoweb.scope.entity_id', true), '')::uuid)
    WITH CHECK (owner_entity_id = nullif(current_setting('knoweb.scope.entity_id', true), '')::uuid);

CREATE POLICY greeting_fed_view_policy ON hello.greeting
    FOR SELECT
    TO app_rw
    USING (nullif(current_setting('knoweb.scope.entity_id', true), '') = '00000000-0000-0000-0000-000000000000');

GRANT SELECT, INSERT ON hello.greeting TO app_rw;
