-- A fourth group role beside app_rw, app_relay and app_report: the one a module's seed loader
-- writes reference data with (decided by the architect, 23 September 2026).
--
-- Why a role: FORCE ROW LEVEL SECURITY binds every writer, the table owner included, and
-- reference data (a permission catalogue, a unit list) belongs to no tenant, so no ordinary
-- policy admits it. A policy that trusts a session variable would let any application code
-- write the catalogue by setting the variable. A policy TO app_seed admits members of the role
-- only, and membership is given by the superuser when the database users are created
-- (infra/compose/postgres/init/01-roles.sh makes the migrator a member); application code
-- cannot join. Each module's seed migration adds the policy on its own reference tables.
--
-- Below V0010 on purpose: the kernel README keeps that range for what the baseline needs.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_seed') THEN
        CREATE ROLE app_seed NOLOGIN;    -- reference data: a module's seed loader, as the migrator
    END IF;
END
$$;
