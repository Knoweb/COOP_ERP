-- ============================================================
-- COOP ERP
-- Sprint 0 Database Baseline
-- ============================================================

-- Extensions
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ICU Collations
CREATE COLLATION IF NOT EXISTS en_icu
    (provider = icu, locale = 'en-LK');

CREATE COLLATION IF NOT EXISTS si_icu
    (provider = icu, locale = 'si-LK');

CREATE COLLATION IF NOT EXISTS ta_icu
    (provider = icu, locale = 'ta-LK');

-- Kernel / template
CREATE SCHEMA IF NOT EXISTS kernel;
CREATE SCHEMA IF NOT EXISTS hello;

-- M1
CREATE SCHEMA IF NOT EXISTS party;
CREATE SCHEMA IF NOT EXISTS security;

-- M2-M9
CREATE SCHEMA IF NOT EXISTS catalogue;
CREATE SCHEMA IF NOT EXISTS pricing;
CREATE SCHEMA IF NOT EXISTS trading;
CREATE SCHEMA IF NOT EXISTS inventory;
CREATE SCHEMA IF NOT EXISTS pos;
CREATE SCHEMA IF NOT EXISTS customers;
CREATE SCHEMA IF NOT EXISTS reporting;
CREATE SCHEMA IF NOT EXISTS integration;

-- Application group roles (doc 18 P4, P10): the application never holds DDL,
-- UPDATE or DELETE on ledgers. Roles are cluster-wide, so a second database in
-- the same cluster (a test database, Testcontainers reuse) must not fail here:
-- each role is created only when absent.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rw') THEN
        CREATE ROLE app_rw NOLOGIN;      -- module code
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_relay') THEN
        CREATE ROLE app_relay NOLOGIN;   -- outbox relay: UPDATE published_at only
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_report') THEN
        CREATE ROLE app_report NOLOGIN;  -- reporting projections and the analyst role
    END IF;
END
$$;

GRANT USAGE ON SCHEMA
    kernel,
    hello,
    party,
    security,
    catalogue,
    pricing,
    trading,
    inventory,
    pos,
    customers,
    reporting,
    integration
TO app_rw;

-- RLS scope helpers
CREATE OR REPLACE FUNCTION kernel.scope_entity()
RETURNS uuid
LANGUAGE sql
STABLE
AS $$
    SELECT nullif(
        current_setting('app.scope_entity_id', true),
        ''
    )::uuid
$$;

CREATE OR REPLACE FUNCTION kernel.scope_location()
RETURNS uuid
LANGUAGE sql
STABLE
AS $$
    SELECT nullif(
        current_setting('app.scope_location_id', true),
        ''
    )::uuid
$$;

CREATE OR REPLACE FUNCTION kernel.scope_class()
RETURNS text
LANGUAGE sql
STABLE
AS $$
    SELECT coalesce(
        nullif(current_setting('app.scope_class', true), ''),
        'NONE'
    )
$$;
