#!/usr/bin/env bash
# Restores the demo server from one backup of backup.sh. Run as root:
#
#   sudo /opt/coop-erp/restore.sh <stamp>        e.g. restore.sh 20261006-020000
#   ls /opt/coop-erp/backups                     the stamps there are (coop_erp-<stamp>.dump)
#
# What it does, in order (wave 2, D-2; DEPLOY-03):
#   1. checks that both dumps of the stamp are there, and the image tag recorded with them;
#   2. asks you to type `restore`;
#   3. backs up what is there now (backup.sh): the undo of this restore;
#   4. stops the backend, Keycloak and PgBouncer;
#   5. drops both databases and creates them again, empty, with their owners, and restores each
#      dump into its fresh database in one transaction (--single-transaction --exit-on-error):
#      a table a later migration made is gone with the old database, so Flyway sees exactly the
#      schema of the dump; and a restore that fails half way leaves an empty database and an
#      error, never a database part old and part restored;
#   6. copies the uploaded files of the backup back into the object store;
#   7. starts the version the dump was taken with (the recorded tag becomes IMAGE_TAG in .env,
#      the one it replaces PREVIOUS_IMAGE_TAG): never an image older than the dump, on which
#      Flyway would start old code on a newer schema without a word; then checks the server.
#
# After a restore, run deploy.sh from the clone at the version you want to run (SETUP section 7):
# the previous commit to go back after a bad update, or main's to bring the restored data forward
# (the backend migrates it at start).
#
# --use-current-image  a backup older than the tag file (no <stamp>.tag): start IMAGE_TAG from .env.
#                      Only when you know that image is the dump's version or newer.
set -euo pipefail

# shellcheck source=lib.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

STAMP="" USE_CURRENT=0
while [ $# -gt 0 ]; do
  case "$1" in
    --use-current-image) USE_CURRENT=1 ;;
    -*) fail "usage: restore.sh <stamp> [--use-current-image]" ;;
    *) STAMP="$1" ;;
  esac
  shift
done
[ -n "$STAMP" ] || fail "usage: restore.sh <stamp> [--use-current-image]   (the stamps: ls $APP_DIR/backups)"
[ -f "$APP_DIR/.env" ] || fail "$APP_DIR/.env is missing: a restore needs the .env the backup was made with"

B="$APP_DIR/backups"
for db in coop_erp keycloak; do
  [ -s "$B/$db-$STAMP.dump" ] || fail "$B/$db-$STAMP.dump is missing or empty"
done
TAG="$(cat "$B/$STAMP.tag" 2>/dev/null | tr -d '\r' || true)"
if [ -z "$TAG" ]; then
  [ "$USE_CURRENT" -eq 1 ] || fail "$B/$STAMP.tag is missing: which version made this dump is unknown.
Starting an image older than the dump would run old code on a newer schema. If you know that
IMAGE_TAG in .env ($(env_get IMAGE_TAG)) is that version or newer, add --use-current-image."
  TAG="$(env_get IMAGE_TAG)"
fi
[ -d "$B/objects" ] || echo "WARNING: $B/objects is missing: the uploaded files are not restored" >&2

echo "This replaces BOTH databases (the application's and Keycloak's) and puts back the uploaded"
echo "files with the backup of $STAMP. Everything done on the demo since then is lost (a backup"
echo "of the present state is taken first). The demo then runs image tag $TAG."
printf 'Type restore to go on: '
read -r answer
[ "$answer" = restore ] || fail "nothing was changed"

if service_running postgres; then
  say "Backing up the present state first (the undo of this restore)"
  "$SCRIPT_DIR/backup.sh"
else
  compose up -d --wait --wait-timeout 300 postgres
fi

say "Stopping the backend, Keycloak and PgBouncer"
compose stop backend keycloak pgbouncer

# The owners: the application's database belongs to Flyway's user (01-roles.sh), Keycloak's to
# keycloak (02-keycloak.sh). template1 carries the ICU locale initdb was given.
for pair in coop_erp:coop_migrator keycloak:keycloak; do
  db="${pair%%:*}"; owner="${pair##*:}"
  say "Restoring $db from $db-$STAMP.dump"
  compose exec -T postgres dropdb -U postgres --if-exists --force "$db"
  compose exec -T postgres createdb -U postgres -O "$owner" "$db"
  compose exec -T postgres pg_restore -U postgres -d "$db" --single-transaction --exit-on-error \
    < "$B/$db-$STAMP.dump"
done

if [ -d "$B/objects" ]; then
  say "Copying the uploaded files back into the object store"
  compose up -d --wait --wait-timeout 300 minio
  compose run --rm --no-deps -T --user 0 -v "$B/objects:/backup:ro" -v "$SCRIPT_DIR/objects.sh:/objects.sh:ro" \
    --entrypoint sh minio-init /objects.sh restore
fi

if [ "$TAG" != "$(env_get IMAGE_TAG)" ]; then
  env_set PREVIOUS_IMAGE_TAG "$(env_get IMAGE_TAG)"
  env_set IMAGE_TAG "$TAG"
fi

say "Starting the demo at image tag $TAG and waiting until healthy"
start_stack
apply_realm_policy

say "Checking from outside, through Caddy"
smoke

echo
echo "Restored $STAMP; running IMAGE_TAG=$TAG. Next: deploy.sh from the clone at the version to run"
echo "(SETUP section 7)."
