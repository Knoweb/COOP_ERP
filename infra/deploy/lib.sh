#!/usr/bin/env bash
# Shared by bootstrap.sh, deploy.sh, demo-data.sh and backup.sh; never run on its own.
#
# Where things are:
#   APP_DIR    the server's copy, /opt/coop-erp: compose files, Caddyfile, .env, the rendered
#              realm, the seeds, backups. Set COOP_ERP_DIR to use another folder (a local test).
#   REPO_DIR   the clone of the repository the script was started from, when it was started from
#              one (infra/deploy/<script>). The files in APP_DIR are refreshed from it.
#
# The compose project is named coop-erp-demo (COOP_ERP_PROJECT to change it); the size file is
# the one named in .env (COOP_ERP_SIZE). COOP_ERP_COMPOSE_EXTRA names one more compose file in
# APP_DIR (compose.local-test.yml for a test on a laptop; never on a server).

APP_DIR="${COOP_ERP_DIR:-/opt/coop-erp}"
PROJECT="${COOP_ERP_PROJECT:-coop-erp-demo}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR=""
if [ -f "$SCRIPT_DIR/../compose/realm-dev.json" ] && [ -d "$SCRIPT_DIR/../../backend" ]; then
  REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
fi

say() { printf '\n==> %s\n' "$*"; }
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# The value of KEY in .env (empty when absent). .env is KEY=value per line, no quotes.
env_get() {
  [ -f "$APP_DIR/.env" ] || return 0
  sed -n "s/^$1=//p" "$APP_DIR/.env" | tail -n 1
}

# Sets KEY=value in .env, replacing an existing line. Used for the address keys only, when
# bootstrap.sh is told a new address; a secret is never rewritten.
env_set() {
  local key="$1" value="$2" tmp
  tmp="$(mktemp)"
  grep -v "^$key=" "$APP_DIR/.env" > "$tmp" || true
  printf '%s=%s\n' "$key" "$value" >> "$tmp"
  cat "$tmp" > "$APP_DIR/.env"
  rm -f "$tmp"
}

compose() {
  local size extra
  size="$(env_get COOP_ERP_SIZE)"
  [ -n "$size" ] || fail "COOP_ERP_SIZE is not set in $APP_DIR/.env; run bootstrap.sh first"
  local files=(-f "$APP_DIR/compose.yml" -f "$APP_DIR/resources-$size.yml")
  extra="${COOP_ERP_COMPOSE_EXTRA:-}"
  if [ -n "$extra" ]; then files+=(-f "$APP_DIR/$extra"); fi
  docker compose --project-directory "$APP_DIR" --env-file "$APP_DIR/.env" -p "$PROJECT" "${files[@]}" "$@"
}

# Copies what the server needs from the clone into APP_DIR. .env and backups are never touched.
sync_files() {
  if [ -z "$REPO_DIR" ]; then
    say "Not started from a clone: using the files already in $APP_DIR"
    return 0
  fi
  say "Copying the deployment files from $REPO_DIR to $APP_DIR"
  mkdir -p "$APP_DIR/postgres/init" "$APP_DIR/keycloak" "$APP_DIR/seed" "$APP_DIR/backups"
  local f
  for f in compose.yml resources-small.yml resources-medium.yml compose.local-test.yml Caddyfile \
           lib.sh bootstrap.sh deploy.sh demo-data.sh backup.sh .env.example README.md \
           SETUP-2vCPU-4GB.md SETUP-4vCPU-8GB.md; do
    if [ -f "$REPO_DIR/infra/deploy/$f" ]; then cp "$REPO_DIR/infra/deploy/$f" "$APP_DIR/$f"; fi
  done
  chmod +x "$APP_DIR"/*.sh
  cp "$REPO_DIR/infra/compose/postgres/init/01-roles.sh" "$APP_DIR/postgres/init/01-roles.sh"
  cp "$REPO_DIR/infra/deploy/postgres/02-keycloak.sh" "$APP_DIR/postgres/init/02-keycloak.sh"
  cp "$REPO_DIR/infra/compose/realm-dev.json" "$APP_DIR/keycloak/realm-dev.json"
  rm -rf "$APP_DIR/keycloak/theme"
  mkdir -p "$APP_DIR/keycloak/theme"
  cp -R "$REPO_DIR/infra/keycloak/theme/coop" "$APP_DIR/keycloak/theme/coop"
  rm -rf "$APP_DIR/seed"
  cp -R "$REPO_DIR/backend/app/src/main/resources/seed" "$APP_DIR/seed"
  # The Keycloak and postgres containers read these as other users: readable by all, and the
  # folders enterable. Nothing secret is in them (the realm with the password is rendered below).
  chmod -R a+rX "$APP_DIR/postgres" "$APP_DIR/keycloak" "$APP_DIR/seed"
}

# keycloak/realm-coop.json, the realm the demo server imports on its first start, rendered from
# the development realm (infra/compose/realm-dev.json) so that the two never drift apart:
#   - the web client's addresses become the server's (PUBLIC_URL) instead of localhost:5173
#   - every user's password ("dev" and "demo" in the file) becomes DEMO_PASSWORD from .env
#   - the backend client's secret becomes KEYCLOAK_BACKEND_CLIENT_SECRET from .env
#   - HTTPS is required from outside the server's own network (sslRequired external)
#   - the password grant of the web client is off (a development shortcut); demo-data.sh
#     switches it on for the few minutes the till history needs it
# The result is checked: no development address or password may be left.
render_realm() {
  local url pw secret src out
  url="$(env_get PUBLIC_URL)"; pw="$(env_get DEMO_PASSWORD)"; secret="$(env_get KEYCLOAK_BACKEND_CLIENT_SECRET)"
  [ -n "$url" ] && [ -n "$pw" ] && [ -n "$secret" ] || fail "PUBLIC_URL, DEMO_PASSWORD or KEYCLOAK_BACKEND_CLIENT_SECRET missing in .env"
  src="$APP_DIR/keycloak/realm-dev.json"; out="$APP_DIR/keycloak/realm-coop.json"
  [ -f "$src" ] || fail "$src is missing; run the script from a clone of the repository"
  sed -e "s|http://localhost:5173|$url|g" \
      -e "s|\"value\": \"dev\"|\"value\": \"$pw\"|g" \
      -e "s|\"value\": \"demo\"|\"value\": \"$pw\"|g" \
      -e "s|\"secret\": \"coop-erp-backend-dev\"|\"secret\": \"$secret\"|" \
      -e "s|\"sslRequired\": \"none\"|\"sslRequired\": \"external\"|" \
      -e "s|\"directAccessGrantsEnabled\": true|\"directAccessGrantsEnabled\": false|g" \
      -e "s|COOP ERP (development)|COOP ERP (demo)|" \
      -e "s|(development)\"|(demo)\"|g" \
      "$src" > "$out.tmp"
  if grep -q 'localhost:5173' "$out.tmp" || grep -Eq '"value": "(dev|demo)"' "$out.tmp" \
     || grep -q 'coop-erp-backend-dev' "$out.tmp" || grep -q '"directAccessGrantsEnabled": true' "$out.tmp"; then
    rm -f "$out.tmp"
    fail "the rendered realm still holds a development value; realm-dev.json changed shape, update render_realm in lib.sh"
  fi
  mv "$out.tmp" "$out"
  # Read by Keycloak's user inside its container; it holds the demo password, which is no
  # secret from the testers, but nobody else on the server needs it.
  chmod 644 "$out"
}

# The database users and groups, applied to the running database (as `make roles` on a laptop),
# so that a user added later reaches an existing database. Both scripts are idempotent.
apply_db_roles() {
  compose exec -T postgres sh /docker-entrypoint-initdb.d/01-roles.sh
  compose exec -T postgres sh /docker-entrypoint-initdb.d/02-keycloak.sh
}

# Starts everything and waits until every service reports healthy (Keycloak's first start, with
# its realm import, takes a few minutes on a small server).
start_stack() {
  compose up -d --wait --wait-timeout 300 postgres
  apply_db_roles
  compose up -d --wait --wait-timeout 900 --remove-orphans
}

# Keycloak's admin command line inside its container, signed in as the bootstrap administrator
# from the container's own environment (the password never appears on this host's command line).
kcadm() {
  # shellcheck disable=SC2016  # expanded inside the container, on purpose
  compose exec -T keycloak bash -c '
    /opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080/auth --realm master \
      --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null 2>&1 &&
    /opt/keycloak/bin/kcadm.sh "$@"' kcadm "$@"
}

web_client_id() {
  kcadm get clients -r coop -q clientId=coop-erp-web --fields id --format csv --noquotes | tr -d '\r' | head -n 1
}

# The web client's addresses in the RUNNING realm: the realm file is imported only on the first
# start, so a changed address must also be written into the realm Keycloak already has.
set_web_client_urls() {
  local url id
  url="$(env_get PUBLIC_URL)"
  id="$(web_client_id)"
  [ -n "$id" ] || fail "the web client coop-erp-web was not found in the realm coop"
  kcadm update "clients/$id" -r coop \
    -s "rootUrl=$url" \
    -s "redirectUris=[\"$url/*\"]" \
    -s "webOrigins=[\"$url\"]" \
    -s "attributes.\"post.logout.redirect.uris\"=$url/*"
}

# DEMO ONLY: the till history signs in as the demo users with their password (the password
# grant), which the demo realm otherwise refuses. On for the run, off again after it.
set_password_grant() {
  local id
  id="$(web_client_id)"
  [ -n "$id" ] || fail "the web client coop-erp-web was not found in the realm coop"
  kcadm update "clients/$id" -r coop -s "directAccessGrantsEnabled=$1"
}

# A check from outside, through Caddy, as a tester's browser would reach the server.
# --resolve sends the request to this machine whatever the name resolves to (the same answer
# Caddy would give from the internet); -k because a `tls internal` certificate is not trusted.
smoke() {
  local url host port fails=0 path code
  url="$(env_get PUBLIC_URL)"
  host="${url#https://}"; host="${host%%/*}"
  port=443
  case "$host" in *:*) port="${host##*:}"; host="${host%%:*}" ;; esac
  for path in / /config.js /api/actuator/health /auth/realms/coop/.well-known/openid-configuration /mail/; do
    code="$(curl -sk -o /dev/null -w '%{http_code}' --max-time 20 --resolve "$host:$port:127.0.0.1" "$url$path" || true)"
    case "$path:$code" in
      /mail/:401|*:200) printf '  ok    %s %s\n' "$code" "$url$path" ;;
      *) printf '  FAIL  %s %s\n' "$code" "$url$path"; fails=$((fails + 1)) ;;
    esac
  done
  [ "$fails" -eq 0 ] || fail "$fails smoke check(s) failed; see 'Troubleshooting' in the SETUP document"
}

# Adds a generated key (base64 of 32 random bytes) to .env when the key is missing: a server
# bootstrapped before the key existed gets one at its next deploy, and a key that is there is
# never rewritten (a new one would change what it protects). The value is never printed.
env_ensure_key() {
  local key="$1"
  [ -n "$(env_get "$key")" ] && return 0
  env_set "$key" "$(openssl rand -base64 32)"
  echo "  $key generated (it was missing from $APP_DIR/.env)"
}
