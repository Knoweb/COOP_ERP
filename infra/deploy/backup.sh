#!/usr/bin/env bash
# Backs up the demo server to /opt/coop-erp/backups. Run as root:
#
#   sudo /opt/coop-erp/backup.sh
#
# One run, one stamp (UTC, 20261006-020000):
#   coop_erp-<stamp>.dump, keycloak-<stamp>.dump   both databases, pg_dump's custom format, as the
#                                                  superuser (row-level security cannot shorten them)
#   <stamp>.tag                                    the image tag the backend ran: restore.sh starts
#                                                  that version again, never an older one
#   objects/attachments, objects/images, objects/packages
#                                                  the uploaded files (GRN and claim photos, SKU
#                                                  images), copied AFTER the dumps, so every object
#                                                  a dumped row names is in the copy (objects.sh:
#                                                  one file per object and an INDEX of the keys).
#                                                  One current copy, nothing removed from it:
#                                                  objects are written once under unique keys.
#   LAST_OK / LAST_FAILED                          the stamp of the last good run, or of a failed one;
#                                                  deploy.sh and the smoke print them
# Keeps the newest BACKUP_KEEP dumps of each database (.env, default 14). When BACKUP_S3_BUCKET is
# set in .env it also uploads the dumps, the tag and the object copy to that S3-compatible
# bucket (DigitalOcean Spaces, Contabo Object Storage, AWS S3 ...) with BACKUP_S3_ENDPOINT,
# BACKUP_S3_ACCESS_KEY and BACKUP_S3_SECRET_KEY; the keys reach the upload container through a
# 0600 file, never its command line.
#
# Not backed up: the bucket audit-anchors (demo anchors can be made again; before real data it
# must go to an object-locked bucket off the server, it is evidence), the mail catcher, and .env
# itself: without its passwords and its till signing key a dump restores into a server the tills
# and users no longer match, and without its NIC pepper (COOP_ERP_CUSTOMERS_NIC_PEPPER) no
# captured NIC matches again until each member presents the card. Keep a copy of .env apart from
# the server, once, and again whenever deploy.sh reports a key it generated. The dumps are not
# encrypted: demo data only (before real data: age, with the private key kept off the server).
#
# Every night at 02:00, for example:  echo '0 2 * * * root /opt/coop-erp/backup.sh' > /etc/cron.d/coop-erp-backup
# A failed night is visible in LAST_FAILED, which every deploy.sh prints.
#
# To restore: restore.sh <stamp> (SETUP section 9).
set -euo pipefail

# shellcheck source=lib.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

[ -f "$APP_DIR/.env" ] || fail "$APP_DIR/.env is missing: run bootstrap.sh first"
B="$APP_DIR/backups"
mkdir -p "$B/objects"
chmod 700 "$B"
rm -f "$B"/*.part

STAMP="$(date -u +%Y%m%d-%H%M%S)"
ENV_FILE=""
finish() {
  local rc=$?
  [ -z "$ENV_FILE" ] || rm -f "$ENV_FILE"
  if [ "$rc" -ne 0 ]; then
    rm -f "$B"/*.part
    echo "$STAMP" > "$B/LAST_FAILED"
    echo "backup $STAMP FAILED (exit $rc); LAST_FAILED written" >&2
  fi
}
trap finish EXIT

FILES=()
for db in coop_erp keycloak; do
  out="$B/$db-$STAMP.dump"
  say "Dumping $db to $out"
  compose exec -T postgres pg_dump -U postgres -d "$db" -Fc > "$out.part"
  mv "$out.part" "$out"
  FILES+=("$out")
done

# The version the dumps belong to: the image of the running backend (the tag after the last
# colon), or IMAGE_TAG from .env when the backend is not running.
TAG=""
CID="$(compose ps -q backend 2>/dev/null | tr -d '\r' | head -n 1 || true)"
if [ -n "$CID" ]; then
  TAG="$(docker inspect --format '{{.Config.Image}}' "$CID" | tr -d '\r')"
  TAG="${TAG##*:}"
fi
[ -n "$TAG" ] || TAG="$(env_get IMAGE_TAG)"
echo "$TAG" > "$B/$STAMP.tag"
FILES+=("$B/$STAMP.tag")

say "Copying the uploaded files to $B/objects"
compose run --rm --no-deps -T --user 0 -v "$B/objects:/backup" -v "$SCRIPT_DIR/objects.sh:/objects.sh:ro" \
  --entrypoint sh minio-init /objects.sh backup

KEEP="$(env_get BACKUP_KEEP)"; KEEP="${KEEP:-14}"
for db in coop_erp keycloak; do
  # shellcheck disable=SC2012
  ls -1t "$B/$db-"*.dump 2>/dev/null | tail -n +"$((KEEP + 1))" | xargs -r rm -f
done
# shellcheck disable=SC2012
ls -1t "$B/"*.tag 2>/dev/null | tail -n +"$((KEEP + 1))" | xargs -r rm -f

BUCKET="$(env_get BACKUP_S3_BUCKET)"
if [ -n "$BUCKET" ]; then
  say "Uploading to s3://$BUCKET"
  ENV_FILE="$(mktemp)"
  chmod 600 "$ENV_FILE"
  {
    echo "AWS_ACCESS_KEY_ID=$(env_get BACKUP_S3_ACCESS_KEY)"
    echo "AWS_SECRET_ACCESS_KEY=$(env_get BACKUP_S3_SECRET_KEY)"
    echo "AWS_DEFAULT_REGION=us-east-1"
  } > "$ENV_FILE"
  # Pinned by digest (WCD-14): it is handed the bucket's keys. Bump tag and digest together.
  aws() {
    docker run --rm --env-file "$ENV_FILE" -v "$B:/backups:ro" \
      amazon/aws-cli:2.27.0@sha256:e3e329e1d2894b7b4bbb0aacacd0a155262159b2e7a3b4275eb1f24046d3e06c \
      --endpoint-url "$(env_get BACKUP_S3_ENDPOINT)" --only-show-errors "$@"
  }
  for f in "${FILES[@]}"; do
    aws s3 cp "/backups/$(basename "$f")" "s3://$BUCKET/coop-erp/$(basename "$f")"
  done
  aws s3 sync /backups/objects "s3://$BUCKET/coop-erp/objects"
fi

echo "$STAMP" > "$B/LAST_OK"
rm -f "$B/LAST_FAILED"
echo
ls -lh "${FILES[@]}"
echo "backup $STAMP done (restore with: restore.sh $STAMP)"
