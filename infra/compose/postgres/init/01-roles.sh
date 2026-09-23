#!/bin/sh
# Runs once, when the postgres_data volume is first created (`make reset` recreates it).
#
# Why this exists: a PostgreSQL superuser bypasses row-level security, even FORCE ROW LEVEL
# SECURITY. If the application connected as `postgres`, every tenant-isolation test would
# pass without testing anything. So the stack has three database users:
#
#   postgres       superuser; used by this script and by `make seed` only
#   coop_migrator  owns the database and its schemas; Flyway connects as this user (DDL), and
#                  so does each module's seed loader. It is a member of app_seed, the group a
#                  policy admits to write reference data (kernel V0005); the application user
#                  is not, and nothing in application code can make it one.
#   coop_app       the application; its only rights are those granted to the group app_rw
#
# Three of the groups are the ones 17A section 6.1 defines; app_seed was added on 23 September
# 2026 (kernel V0005). The baseline and V0005 create them
# only when absent, so creating them here first is safe.
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<EOSQL
CREATE ROLE app_rw     NOLOGIN;
CREATE ROLE app_relay  NOLOGIN;
CREATE ROLE app_report NOLOGIN;
CREATE ROLE app_seed   NOLOGIN;

CREATE ROLE "$MIGRATION_DB_USER" LOGIN PASSWORD '$MIGRATION_DB_PASSWORD' CREATEROLE IN ROLE app_seed;
CREATE ROLE "$APP_DB_USER"       LOGIN PASSWORD '$APP_DB_PASSWORD' IN ROLE app_rw;

ALTER DATABASE "$POSTGRES_DB" OWNER TO "$MIGRATION_DB_USER";
EOSQL
