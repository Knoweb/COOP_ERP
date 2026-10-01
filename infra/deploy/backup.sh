#!/usr/bin/env bash
# Backs up the demo server's two databases (the application's and Keycloak's) to
# /opt/coop-erp/backups, one timestamped file each, in pg_dump's custom format. Run as root:
#
#   sudo /opt/coop-erp/backup.sh
#
# Keeps the newest BACKUP_KEEP of each (.env, default 14). When BACKUP_S3_BUCKET is set in .env
# it also uploads both files to that S3-compatible bucket (DigitalOcean Spaces, Contabo Object
# Storage, AWS S3 ...) with BACKUP_S3_ENDPOINT, BACKUP_S3_ACCESS_KEY and BACKUP_S3_SECRET_KEY.
#
# What is NOT in the dump: the object store (attachments, printed PDFs, which the system prints
# again) and the mail catcher. And .env itself: without its passwords and its till signing key a
# dump restores into a server the tills and users no longer match. Keep a copy of .env apart from
# the server, once.
#
# A whole-machine copy is the provider's: DigitalOcean's weekly Droplet backups (a paid option
# chosen when the Droplet is created, or later under Backups), or a snapshot on any provider.
#
# Every night at 02:00, for example:  echo '0 2 * * * root /opt/coop-erp/backup.sh' > /etc/cron.d/coop-erp-backup
#
# To restore (the SETUP documents walk through it):
#   docker compose ... exec -T postgres pg_restore -U postgres -d coop_erp --clean --if-exists < file.dump
set -euo pipefail

# shellcheck source=lib.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

[ -f "$APP_DIR/.env" ] || fail "$APP_DIR/.env is missing: run bootstrap.sh first"
mkdir -p "$APP_DIR/backups"
chmod 700 "$APP_DIR/backups"

STAMP="$(date -u +%Y%m%d-%H%M%S)"
FILES=()
for db in coop_erp keycloak; do
  out="$APP_DIR/backups/$db-$STAMP.dump"
  say "Dumping $db to $out"
  compose exec -T postgres pg_dump -U postgres -d "$db" -Fc > "$out.part"
  mv "$out.part" "$out"
  FILES+=("$out")
done

KEEP="$(env_get BACKUP_KEEP)"; KEEP="${KEEP:-14}"
for db in coop_erp keycloak; do
  # shellcheck disable=SC2012
  ls -1t "$APP_DIR/backups/$db-"*.dump 2>/dev/null | tail -n +"$((KEEP + 1))" | xargs -r rm -f
done

BUCKET="$(env_get BACKUP_S3_BUCKET)"
if [ -n "$BUCKET" ]; then
  say "Uploading to s3://$BUCKET"
  for f in "${FILES[@]}"; do
    docker run --rm -v "$APP_DIR/backups:/backups:ro" \
      -e AWS_ACCESS_KEY_ID="$(env_get BACKUP_S3_ACCESS_KEY)" \
      -e AWS_SECRET_ACCESS_KEY="$(env_get BACKUP_S3_SECRET_KEY)" \
      -e AWS_DEFAULT_REGION=us-east-1 \
      amazon/aws-cli:2.27.0 s3 cp "/backups/$(basename "$f")" "s3://$BUCKET/coop-erp/$(basename "$f")" \
      --endpoint-url "$(env_get BACKUP_S3_ENDPOINT)" --only-show-errors
  done
fi

echo
ls -lh "${FILES[@]}"
