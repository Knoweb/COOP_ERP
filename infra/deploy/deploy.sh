#!/usr/bin/env bash
# Updates the demo server to the newest images (the tag in .env, `main` by default: whatever CI
# last published from main). Run as root, from the clone after a `git pull`, so that the
# compose files, the seeds and the realm template are as new as the images:
#
#   cd ~/COOP_ERP && git pull && sudo ./infra/deploy/deploy.sh
#
# It refreshes the files in /opt/coop-erp (never .env), pulls, recreates what changed, waits
# until every service is healthy and checks the server from outside through Caddy. The data
# stays: the backend migrates the database at start. The realm file is imported only into an
# empty Keycloak, so a new demo user in realm-dev.json reaches the server with demo-data.sh --reset.
#
# --no-pull uses the images already on the machine (a test on a laptop).
set -euo pipefail

# shellcheck source=lib.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

PULL=1
case "${1:-}" in
  --no-pull) PULL=0 ;;
  "") ;;
  *) fail "usage: deploy.sh [--no-pull]" ;;
esac

[ -f "$APP_DIR/.env" ] || fail "$APP_DIR/.env is missing: run bootstrap.sh first"

sync_files
render_realm

if [ "$PULL" -eq 1 ]; then
  say "Pulling the images ($(env_get REGISTRY), tag $(env_get IMAGE_TAG))"
  compose pull --quiet postgres pgbouncer rabbitmq minio minio-init keycloak mailpit backend caddy
  compose --profile tools pull --quiet demo-tools
fi

say "Starting what changed and waiting until healthy"
start_stack

# The Caddyfile is mounted, so a changed one does not recreate the container: reload it.
compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile

say "Checking from outside, through Caddy"
smoke

docker image prune -f >/dev/null
echo
echo "Updated: $(env_get PUBLIC_URL)"
compose ps --format 'table {{.Service}}\t{{.Status}}'
