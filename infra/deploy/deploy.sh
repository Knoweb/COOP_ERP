#!/usr/bin/env bash
# Updates the demo server to the version of the clone it is run from. Run as root, FROM THE CLONE
# (it refuses anywhere else, and is not copied into /opt/coop-erp):
#
#   cd ~/COOP_ERP && git pull --ff-only && sudo ./infra/deploy/deploy.sh
#
# The demo runs explicit commit SHAs of main, chosen by the operator (wave 2, D-1): the commit the
# clone is at is the version of the files (compose, Caddyfile, realm, seeds) AND of the images.
# In order, it:
#   1. refuses a clone with local changes under infra/ or the seeds (--allow-dirty: laptop test);
#   2. checks that CI has published every image of the clone's commit to GHCR (else: "CI has not
#      published this commit yet") and writes IMAGE_TAG=<sha> into .env, the tag it replaces as
#      PREVIOUS_IMAGE_TAG;
#   3. backs up both databases and the uploaded files (backup.sh; --no-backup to skip). A
#      migration cannot be undone: the backup is the way back (SETUP section 7, "Going back");
#   4. refreshes the files in /opt/coop-erp (never .env), pulls, recreates what changed, waits
#      until every service is healthy, applies the realm's sign-in policy (brute force, no
#      self-service) to the running Keycloak, and checks the server from outside through Caddy;
#   5. removes the registry images of any other tag than IMAGE_TAG and PREVIOUS_IMAGE_TAG.
# The data stays: the backend migrates the database at start. The realm file is imported only
# into an empty Keycloak, so a new demo user in realm-dev.json reaches the server with
# demo-data.sh --reset.
#
# --no-pull uses the images already on the machine and keeps .env's IMAGE_TAG (a laptop test).
set -euo pipefail

# shellcheck source=lib.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

PULL=1 DIRTY_OK=0 BACKUP=1
while [ $# -gt 0 ]; do
  case "$1" in
    --no-pull) PULL=0 ;;
    --allow-dirty) DIRTY_OK=1 ;;
    --no-backup) BACKUP=0 ;;
    *) fail "usage: deploy.sh [--no-pull] [--allow-dirty] [--no-backup]" ;;
  esac
  shift
done

require_clone
[ "$DIRTY_OK" -eq 1 ] || require_clean_clone
[ -f "$APP_DIR/.env" ] || fail "$APP_DIR/.env is missing: run bootstrap.sh first"
# Keys added after this server was bootstrapped (wave 2: the notification recipient hash key;
# wave 3: the NIC pepper, without which the backend refuses to start on a public issuer).
env_ensure_key COOP_ERP_NOTIFICATION_RECIPIENT_KEY
env_ensure_key COOP_ERP_CUSTOMERS_NIC_PEPPER
ensure_env_additions

pin_image_tag "$PULL"

if [ "$BACKUP" -eq 1 ]; then
  if service_running postgres; then
    say "Backing up first (the way back if this version must be undone)"
    "$REPO_DIR/infra/deploy/backup.sh"
  else
    say "The database is not running: no backup taken before this deploy"
  fi
fi

sync_files
render_realm

if [ "$PULL" -eq 1 ]; then
  say "Pulling the images ($(env_get REGISTRY), tag $(env_get IMAGE_TAG))"
  compose pull --quiet postgres pgbouncer rabbitmq minio minio-init keycloak mailpit backend caddy
  compose --profile tools pull --quiet demo-tools
fi

say "Starting what changed and waiting until healthy"
start_stack
apply_realm_policy

# The Caddyfile is mounted, so a changed one does not recreate the container: reload it.
compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile

say "Checking from outside, through Caddy"
smoke

prune_images
echo
echo "Updated: $(env_get PUBLIC_URL)"
echo "  running   IMAGE_TAG=$(env_get IMAGE_TAG)"
echo "  previous  PREVIOUS_IMAGE_TAG=$(env_get PREVIOUS_IMAGE_TAG)"
echo "  to go back: SETUP section 7, \"Going back\" (restore.sh <stamp>, then deploy.sh at the previous commit)"
compose ps --format 'table {{.Service}}\t{{.Status}}'
