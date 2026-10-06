#!/bin/sh
# Keycloak's own database on the demo server (infra/deploy/compose.yml): a login user `keycloak`
# owning a database `keycloak`, apart from the application's. Runs when the postgres volume is
# first created (docker-entrypoint-initdb.d, beside 01-roles.sh, which bootstrap.sh copies from
# infra/compose) and again on every bootstrap.sh and deploy.sh, so every statement is idempotent.
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<EOSQL
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'keycloak') THEN
        CREATE ROLE keycloak LOGIN;
    END IF;
END
\$\$;
ALTER ROLE keycloak PASSWORD '$KEYCLOAK_DB_PASSWORD';
SELECT 'CREATE DATABASE keycloak OWNER keycloak'
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'keycloak')
\gexec
EOSQL
