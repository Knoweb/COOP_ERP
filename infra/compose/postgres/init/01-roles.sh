#!/bin/sh
# The database users and groups of the local stack. Runs twice: once by PostgreSQL when the
# postgres_data volume is first created (docker-entrypoint-initdb.d), and on every `make up`
# after that (`make roles`), so that a user or a membership added later reaches an existing
# database without `make reset`. Every statement is therefore idempotent.
#
# Why this exists: a PostgreSQL superuser bypasses row-level security, even FORCE ROW LEVEL
# SECURITY. If the application connected as `postgres`, every tenant-isolation test would
# pass without testing anything. So the stack has four database users:
#
#   postgres       superuser; used by this script and by `make seed` only
#   coop_migrator  owns the database and its schemas; Flyway connects as this user (DDL), and
#                  so does each module's seed loader. It is a member of app_seed, the group a
#                  policy admits to write reference data (kernel V0005); the application user
#                  is not, and nothing in application code can make it one.
#   coop_app       the application; its only rights are those granted to the group app_rw
#   coop_relay     the outbox relay (19A K-05, CR-19A-5): member of app_relay only, which may
#                  read the outbox and set published_at, nothing else
#
# Why users are not created by a migration: a login user carries a password, which is
# environment work and never enters git; and on PostgreSQL 16 the migrator may create a role
# but may not add it to a group it did not create (no ADMIN option), measured 24 September
# 2026. The groups themselves (17A section 6.1 plus app_seed) are also created by the kernel
# migrations when absent, so either order works.
set -e

: "${RELAY_DB_USER:=coop_relay}"
: "${RELAY_DB_PASSWORD:=coop_relay}"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<EOSQL
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_rw')     THEN CREATE ROLE app_rw     NOLOGIN; END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_relay')  THEN CREATE ROLE app_relay  NOLOGIN; END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_report') THEN CREATE ROLE app_report NOLOGIN; END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_seed')   THEN CREATE ROLE app_seed   NOLOGIN; END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$MIGRATION_DB_USER') THEN
        CREATE ROLE "$MIGRATION_DB_USER" LOGIN CREATEROLE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$APP_DB_USER') THEN
        CREATE ROLE "$APP_DB_USER" LOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$RELAY_DB_USER') THEN
        CREATE ROLE "$RELAY_DB_USER" LOGIN;
    END IF;
END
\$\$;

-- Passwords and memberships are set every time: they are what changes between environments.
ALTER ROLE "$MIGRATION_DB_USER" PASSWORD '$MIGRATION_DB_PASSWORD';
ALTER ROLE "$APP_DB_USER"       PASSWORD '$APP_DB_PASSWORD';
ALTER ROLE "$RELAY_DB_USER"     PASSWORD '$RELAY_DB_PASSWORD';
GRANT app_seed  TO "$MIGRATION_DB_USER";
GRANT app_rw    TO "$APP_DB_USER";
GRANT app_relay TO "$RELAY_DB_USER";

ALTER DATABASE "$POSTGRES_DB" OWNER TO "$MIGRATION_DB_USER";
EOSQL
