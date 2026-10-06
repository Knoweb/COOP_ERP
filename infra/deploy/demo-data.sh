#!/usr/bin/env bash
# Loads the demo for cooperative staff (docs/DEMO.md) onto the demo server, as `make demo-data`
# does on a laptop. Run as root after bootstrap.sh:
#
#   sudo /opt/coop-erp/demo-data.sh            load (safe to repeat: a second run changes nothing)
#   sudo /opt/coop-erp/demo-data.sh --reset    wipe every record of the demo, then load afresh
#   sudo /opt/coop-erp/demo-data.sh --repair-users
#                                              every demo user back to DEMO_PASSWORD; no data touched
#
# --repair-users (wave 2, D-4): the demo users are shared by a class of testers, and the
# application itself may reset a Federation user's credential (phase 4 of the demo). When a
# tester has changed one, this puts every user of the realm file (the storyline's characters)
# back to DEMO_PASSWORD, clears their required actions, OTP and other non-password credentials
# and every brute-force lockout, and switches the password grant off. About a minute; it touches
# no record of the demo (where --reset wipes the class's work). Users made in the back office
# are left as they are.
#
# Three steps, as in the Makefile:
#   1. the seed rows (seed/*/*.dev.sql, then the demo's parties and users, *.demo.sql), as the
#      PostgreSQL superuser inside the postgres container, because they write rows for several
#      entities at once, which row-level security forbids to the application user;
#   2. the demo loader: a one-off backend container (role web, no port of its own) that loads
#      the rest through the command handlers and exits (COOP_ERP_DEMO_LOAD);
#   3. eight weeks of till sales at the four demo shops through the sync contract, by the till
#      simulator in the demo-tools image (DemoTillHistory; no JDK or Gradle on the server). It
#      signs in as the shop managers with the password grant, which the demo realm keeps off:
#      this script switches it on for the run and off again afterwards, whatever happens.
#
# --reset asks first, then deletes the data volumes (database, broker, object store, mail; the
# certificates are kept) and starts again: Keycloak imports the realm afresh, so the demo users
# and their password are as in .env and a user made in the back office is gone.
set -euo pipefail

# shellcheck source=lib.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

RESET=0 REPAIR=0
case "${1:-}" in
  --reset) RESET=1 ;;
  --repair-users) REPAIR=1 ;;
  "") ;;
  *) fail "usage: demo-data.sh [--reset | --repair-users]" ;;
esac

[ -f "$APP_DIR/.env" ] || fail "$APP_DIR/.env is missing: run bootstrap.sh first"

if [ "$REPAIR" -eq 1 ]; then
  say "Putting every demo user back to DEMO_PASSWORD (no data is touched)"
  # The password and the user names go in on stdin, so neither is on a command line of the host.
  # shellcheck disable=SC2016  # expanded inside the container, on purpose
  {
    env_get DEMO_PASSWORD
    sed -n 's/.*"username": "\([^"]*\)".*/\1/p' "$APP_DIR/keycloak/realm-dev.json" | grep -v '^service-account-'
  } | compose exec -T keycloak bash -c '
    set -e
    K=/opt/keycloak/bin/kcadm.sh
    $K config credentials --server http://localhost:8080/auth --realm master \
      --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null 2>&1
    IFS= read -r pw
    while IFS= read -r u; do
      [ -n "$u" ] || continue
      id="$($K get users -r coop -q username="$u" -q exact=true --fields id --format csv --noquotes | tr -d "\r" | head -n 1)"
      if [ -z "$id" ]; then echo "  $u: not in the realm, skipped"; continue; fi
      $K set-password -r coop --userid "$id" --new-password "$pw"
      $K update "users/$id" -r coop -s "requiredActions=[]"
      for c in $($K get "users/$id/credentials" -r coop --fields id,type --format csv --noquotes | tr -d "\r" | grep -v ",password\$" | cut -d, -f1); do
        $K delete "users/$id/credentials/$c" -r coop
      done
      echo "  $u: repaired"
    done
    $K delete attack-detection/brute-force/users -r coop
    echo "  every brute-force lockout cleared"'
  set_password_grant false
  echo "  the password grant of coop-erp-web is off"
  exit 0
fi

if [ "$RESET" -eq 1 ]; then
  echo "This deletes EVERYTHING on the demo server: every record, user, mail and file testers made."
  printf 'Type reset to go on: '
  read -r answer
  [ "$answer" = reset ] || fail "nothing was deleted"
  say "Stopping the demo and deleting its data volumes"
  compose --profile tools down --remove-orphans
  for v in postgres_data rabbitmq_data minio_data mailpit_data; do
    docker volume rm -f "${PROJECT}_$v" >/dev/null
  done
  render_realm
  say "Starting afresh (Keycloak imports the realm, the backend migrates the empty database)"
  start_stack
  apply_realm_policy
fi

# The stack must be running and healthy before anything is loaded.
compose up -d --wait --wait-timeout 900

seed_file() {
  echo "  seeding ${1#"$APP_DIR"/}"
  # shellcheck disable=SC2016  # expanded inside the container, on purpose
  compose exec -T postgres sh -c 'psql -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < "$1"
}

say "1/3 Seed rows"
for f in "$APP_DIR"/seed/*/*.dev.sql "$APP_DIR"/seed/m1party/*.demo.sql "$APP_DIR"/seed/m1security/*.demo.sql; do
  [ -f "$f" ] || continue
  seed_file "$f"
done

say "2/3 The demo loader (a few minutes; it prints what it loads)"
compose run --rm --no-deps \
  -e COOP_ERP_DEMO_LOAD=true -e COOP_ERP_ROLE=web -e SPRING_PROFILES_ACTIVE=web -e SERVER_PORT=8099 \
  backend

say "3/3 The till history at the four demo shops"
password_grant_off() { set_password_grant false || echo "WARNING: could not switch the password grant off again; run demo-data.sh once more" >&2; }
set_password_grant true
trap password_grant_off EXIT
compose --profile tools run --rm --no-deps demo-tools
password_grant_off
trap - EXIT

cat <<EOF

The demo is loaded: $(env_get PUBLIC_URL)
The users and the storyline are in docs/DEMO.md; their password is DEMO_PASSWORD in $APP_DIR/.env.
EOF
