#!/usr/bin/env bash
# Shared by bootstrap.sh, deploy.sh, restore.sh, demo-data.sh and backup.sh; never run on its own.
#
# Where things are:
#   APP_DIR    the server's copy, /opt/coop-erp: compose files, Caddyfile, .env, the rendered
#              realm, the seeds, backups. Set COOP_ERP_DIR to use another folder (a local test).
#   REPO_DIR   the clone of the repository the script was started from, when it was started from
#              one (infra/deploy/<script>). The files in APP_DIR are refreshed from it.
#
# bootstrap.sh and deploy.sh run from the clone ONLY (wave 2, D-1): the clone's commit is the
# version the server runs, files and images alike. demo-data.sh, backup.sh and restore.sh also
# run from /opt/coop-erp, where sync_files copies them.
#
# The compose project is named coop-erp-demo (COOP_ERP_PROJECT to change it); the size file is
# the one named in .env (COOP_ERP_SIZE). COOP_ERP_COMPOSE_EXTRA names one more compose file in
# APP_DIR (compose.local-test.yml for a test on a laptop; never on a server).

APP_DIR="${COOP_ERP_DIR:-/opt/coop-erp}"
PROJECT="${COOP_ERP_PROJECT:-coop-erp-demo}"
# pwd -W: on a Windows laptop test (Git Bash) the path git and Docker understand (D:/...).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && { pwd -W 2>/dev/null || pwd; })"
REPO_DIR=""
if [ -f "$SCRIPT_DIR/../compose/realm-dev.json" ] && [ -d "$SCRIPT_DIR/../../backend" ]; then
  REPO_DIR="$(cd "$SCRIPT_DIR/../.." && { pwd -W 2>/dev/null || pwd; })"
fi

# The images CI publishes for every green commit of main, and which the server runs.
APP_IMAGES=(backend-worker web demo-tools)

say() { printf '\n==> %s\n' "$*"; }
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# deploy.sh and bootstrap.sh refuse to run from anywhere but the clone (DEPLOY-01): a copy in
# /opt/coop-erp would deploy new images against the files of an older commit.
require_clone() {
  [ -n "$REPO_DIR" ] || fail "run $(basename "$0") from the clone of the repository, not from $APP_DIR:
  cd ~/COOP_ERP && git pull --ff-only && sudo ./infra/deploy/$(basename "$0")"
}

# A clone with local edits under infra/ or the seeds would run them against an image that does
# not have them. --allow-dirty is for a test on a laptop.
require_clean_clone() {
  local dirty
  dirty="$(git -C "$REPO_DIR" status --porcelain -- infra backend/app/src/main/resources/seed)"
  [ -z "$dirty" ] || fail "the clone has local changes in infra/ or the seeds; the server would run them
against an image built without them. Commit or discard them first (git status), or pass
--allow-dirty on a laptop test:
$dirty"
}

# The value of KEY in .env (empty when absent). .env is KEY=value per line, no quotes.
env_get() {
  [ -f "$APP_DIR/.env" ] || return 0
  sed -n "s/^$1=//p" "$APP_DIR/.env" | tail -n 1
}

# Sets KEY=value in .env, replacing an existing line. Used for the address keys (bootstrap.sh
# told a new address), the size and the image tags (IMAGE_TAG, PREVIOUS_IMAGE_TAG); a secret
# is never rewritten. Writes through `cat >`, so the file keeps its mode (0600).
env_set() {
  local key="$1" value="$2" tmp
  tmp="$(mktemp)"
  grep -v "^$key=" "$APP_DIR/.env" > "$tmp" || true
  printf '%s=%s\n' "$key" "$value" >> "$tmp"
  cat "$tmp" > "$APP_DIR/.env"
  rm -f "$tmp"
}

# Appends KEY=value to .env only when KEY is not there yet: a value a later version of the kit
# needs reaches a server bootstrapped before it, once, and is never regenerated.
env_add_once() {
  grep -q "^$1=" "$APP_DIR/.env" || printf '%s=%s\n' "$1" "$2" >> "$APP_DIR/.env"
}

# The values added to .env after a first bootstrap (wave 2): the object store's own user for the
# backend instead of MinIO's root (DEPLOY-12).
ensure_env_additions() {
  env_add_once MINIO_APP_USER coop-erp-app
  env_add_once MINIO_APP_PASSWORD "$(openssl rand -hex 24)"
  env_add_once PREVIOUS_IMAGE_TAG ""
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

# Is this compose service running now? (backup.sh and restore.sh need the database up.)
service_running() {
  compose ps --status running --services 2>/dev/null | tr -d '\r' | grep -qx "$1"
}

# Copies what the server needs from the clone into APP_DIR. .env and backups are never touched.
# bootstrap.sh and deploy.sh are NOT copied (they run from the clone only); an old copy of
# either is removed, so the wrong command is not there to be run.
sync_files() {
  [ -n "$REPO_DIR" ] || fail "not started from a clone of the repository"
  say "Copying the deployment files from $REPO_DIR to $APP_DIR"
  mkdir -p "$APP_DIR/postgres/init" "$APP_DIR/keycloak" "$APP_DIR/seed" "$APP_DIR/backups"
  rm -f "$APP_DIR/deploy.sh" "$APP_DIR/bootstrap.sh"
  local f
  for f in compose.yml resources-small.yml resources-medium.yml compose.local-test.yml Caddyfile \
           lib.sh demo-data.sh backup.sh restore.sh objects.sh .env.example README.md \
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
  # folders enterable. Nothing secret is in them. Not the whole keycloak/ folder (WCD-17): the
  # rendered realm-coop.json beside them holds the backend client's secret and the demo
  # password, and stays 0600 (render_realm), also while this runs and if a later render fails.
  chmod a+rx "$APP_DIR/keycloak"
  chmod -R a+rX "$APP_DIR/postgres" "$APP_DIR/seed" "$APP_DIR/keycloak/theme" "$APP_DIR/keycloak/realm-dev.json"
  # A realm rendered by an older kit, whose recursive chmod left it readable by all.
  if [ -f "$APP_DIR/keycloak/realm-coop.json" ]; then chmod 600 "$APP_DIR/keycloak/realm-coop.json"; fi
}

# IMAGE_TAG := the clone's commit (D-1, DEPLOY-02), once GHCR has every image of it; the tag it
# replaces is kept as PREVIOUS_IMAGE_TAG, so a failed update says what to go back to, and
# demo-data.sh runs the demo-tools of the same commit as the seeds it loads. $1 = 0 (--no-pull,
# a laptop test with images built locally) keeps the tag .env already has.
pin_image_tag() {
  local pull="$1" sha registry current img missing=()
  current="$(env_get IMAGE_TAG)"
  if [ "$pull" -eq 0 ]; then
    say "--no-pull: running the images tagged $current (IMAGE_TAG in .env), as they are on this machine"
    return 0
  fi
  sha="$(git -C "$REPO_DIR" rev-parse HEAD)"
  registry="$(env_get REGISTRY)"
  say "Checking that CI has published $sha to $registry"
  for img in "${APP_IMAGES[@]}"; do
    docker manifest inspect "$registry/$img:$sha" >/dev/null 2>&1 || missing+=("$img")
  done
  [ "${#missing[@]}" -eq 0 ] || fail "CI has not published this commit yet ($sha; missing: ${missing[*]}).
Either its pipeline is still running (wait for the Package (main) job), or main's tests failed on
it: git checkout an earlier commit of main and run this again. (Is the server signed in to GHCR?
SETUP section 3.)"
  if [ "$current" != "$sha" ]; then
    env_set PREVIOUS_IMAGE_TAG "$current"
    env_set IMAGE_TAG "$sha"
  fi
  echo "  IMAGE_TAG=$sha (previous: $(env_get PREVIOUS_IMAGE_TAG))"
}

# Removes the registry's images except the two tags .env names (pinned SHA tags are never
# dangling, so `docker image prune` alone would keep every version ever deployed).
prune_images() {
  local registry keep prev ref tag
  registry="$(env_get REGISTRY)"; keep="$(env_get IMAGE_TAG)"; prev="$(env_get PREVIOUS_IMAGE_TAG)"
  docker image ls --format '{{.Repository}}:{{.Tag}}' | tr -d '\r' | grep "^$registry/" | while read -r ref; do
    tag="${ref##*:}"
    [ "$tag" = "$keep" ] || [ "$tag" = "$prev" ] || [ "$tag" = "<none>" ] || docker image rm "$ref" >/dev/null 2>&1 || true
  done
  docker image prune -f >/dev/null
}

# keycloak/realm-coop.json, the realm the demo server imports on its first start, rendered from
# the development realm (infra/compose/realm-dev.json) so that the two never drift apart:
#   - the web client's addresses become the server's (PUBLIC_URL) instead of localhost:5173
#   - every user's password ("dev" and "demo" in the file) becomes DEMO_PASSWORD from .env
#   - the backend client's secret becomes KEYCLOAK_BACKEND_CLIENT_SECRET from .env
#   - HTTPS is required from outside the server's own network (sslRequired external), and
#     brute-force protection is on, with a temporary lockout only (D-3). Not in realm-dev.json:
#     on a laptop and in CI the end-to-end tests sign the same users in from four browsers at
#     once, and Keycloak's quick-login check locked fed-admin out (PR #267's first CI run)
#   - the password grant of the web client is off (a development shortcut); demo-data.sh
#     switches it on for the few minutes the till history needs it
# The default roles and the disabled OTP enrolment are set by apply_realm_policy, which also
# writes the brute-force settings into a realm Keycloak already has.
# The result is checked: no development address or password may be left.
render_realm() {
  local url pw secret src out
  url="$(env_get PUBLIC_URL)"; pw="$(env_get DEMO_PASSWORD)"; secret="$(env_get KEYCLOAK_BACKEND_CLIENT_SECRET)"
  [ -n "$url" ] && [ -n "$pw" ] && [ -n "$secret" ] || fail "PUBLIC_URL, DEMO_PASSWORD or KEYCLOAK_BACKEND_CLIENT_SECRET missing in .env"
  src="$APP_DIR/keycloak/realm-dev.json"; out="$APP_DIR/keycloak/realm-coop.json"
  [ -f "$src" ] || fail "$src is missing; run the script from a clone of the repository"
  # The temporary file holds the secret too: written 0600 from its first byte (WCD-17), in a
  # subshell so the umask does not reach the rest of the script. A leftover .tmp would keep its
  # old mode, so it goes first.
  rm -f "$out.tmp"
  ( umask 077
    sed -e "s|http://localhost:5173|$url|g" \
      -e "s|\"value\": \"dev\"|\"value\": \"$pw\"|g" \
      -e "s|\"value\": \"demo\"|\"value\": \"$pw\"|g" \
      -e "s|\"secret\": \"coop-erp-backend-dev\"|\"secret\": \"$secret\"|" \
      -e "s|\"sslRequired\": \"none\",|\"sslRequired\": \"external\", \"bruteForceProtected\": true, \"permanentLockout\": false, \"failureFactor\": 30, \"maxFailureWaitSeconds\": 900,|" \
      -e "s|\"directAccessGrantsEnabled\": true|\"directAccessGrantsEnabled\": false|g" \
      -e "s|COOP ERP (development)|COOP ERP (demo)|" \
      -e "s|(development)\"|(demo)\"|g" \
      "$src" > "$out.tmp" )
  if grep -q 'localhost:5173' "$out.tmp" || grep -Eq '"value": "(dev|demo)"' "$out.tmp" \
     || grep -q 'coop-erp-backend-dev' "$out.tmp" || grep -q '"directAccessGrantsEnabled": true' "$out.tmp" \
     || ! grep -q '"bruteForceProtected": true' "$out.tmp"; then
    rm -f "$out.tmp"
    fail "the rendered realm still holds a development value; realm-dev.json changed shape, update render_realm in lib.sh"
  fi
  mv "$out.tmp" "$out"
  # It holds the backend client's secret (DEPLOY-08): readable by Keycloak's user inside its
  # container (uid 1000, gid 0) and by nobody else on the server. On a laptop test (Docker
  # Desktop) chown on a shared folder does nothing, which is fine there.
  chown 1000:0 "$out" 2>/dev/null || true
  chmod 600 "$out"
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

# The demo's identity policy (D-3, D-4) written into the RUNNING realms, on every bootstrap,
# deploy and restore: the realm file reaches only an empty Keycloak. Safe to repeat.
#   - brute-force protection, temporary lockout only (30 failures, up to 15 minutes; a success
#     clears the count), on coop and on master: about four testers share each demo user, and a
#     permanent lockout would be an outage they cause themselves
#   - no self-service: manage-account and manage-account-links out of the default roles (Caddy
#     also answers 404 to the account console), and OTP enrolment disabled. UPDATE_PASSWORD stays:
#     phase 4 of the demo gives a new user a temporary password to change at the first sign-in.
#   - the master realm's own address is the console's (KC_HOSTNAME_ADMIN, through an SSH tunnel)
apply_realm_policy() {
  say "Applying the demo's sign-in policy to the running realms (brute force, no self-service)"
  local r
  for r in coop master; do
    kcadm update "realms/$r" -s bruteForceProtected=true -s permanentLockout=false \
      -s failureFactor=30 -s maxFailureWaitSeconds=900
  done
  # shellcheck disable=SC2016  # expanded inside the container, on purpose
  compose exec -T keycloak bash -c '
    K=/opt/keycloak/bin/kcadm.sh
    $K config credentials --server http://localhost:8080/auth --realm master \
      --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null 2>&1 || exit 1
    have="$($K get-roles -r coop --rname default-roles-coop --cclientid account 2>/dev/null | tr -d "\r")"
    for role in manage-account manage-account-links; do
      if printf "%s\n" "$have" | grep -Eq "\"name\" *: *\"$role\""; then
        $K remove-roles -r coop --rname default-roles-coop --cclientid account --rolename "$role"
      fi
    done
    $K update authentication/required-actions/CONFIGURE_TOTP -r coop -s enabled=false -s defaultAction=false
    # The master realm (the sign-in of the console) answers at the tunnel address, not at the
    # public one, where Caddy answers 404: else its sign-in form posts to a closed address.
    $K update realms/master -s "attributes.frontendUrl=$KC_HOSTNAME_ADMIN"'
}

# DEMO ONLY: the till history signs in as the demo users with their password (the password
# grant), which the demo realm otherwise refuses. On for the run, off again after it.
set_password_grant() {
  local id
  id="$(web_client_id)"
  [ -n "$id" ] || fail "the web client coop-erp-web was not found in the realm coop"
  kcadm update "clients/$id" -r coop -s "directAccessGrantsEnabled=$1"
}

# The last backup, from the markers backup.sh writes: printed by deploy.sh and the smoke, which
# is the alert a team without a mail server on the box will actually see (DEPLOY-13).
backup_status() {
  local ok failed age=""
  ok="$(cat "$APP_DIR/backups/LAST_OK" 2>/dev/null || true)"
  failed="$(cat "$APP_DIR/backups/LAST_FAILED" 2>/dev/null || true)"
  if [ -n "$ok" ]; then
    local then_s now_s
    then_s="$(date -u -d "${ok:0:4}-${ok:4:2}-${ok:6:2} ${ok:9:2}:${ok:11:2}:${ok:13:2}" +%s 2>/dev/null || echo "")"
    now_s="$(date -u +%s)"
    [ -z "$then_s" ] || age=" ($(( (now_s - then_s) / 86400 )) day(s) ago)"
    printf '  info  LAST_OK backup: %s%s\n' "$ok" "$age"
  else
    printf '  WARN  no backup has succeeded yet: run backup.sh, and add its cron line (SETUP section 9)\n'
  fi
  if [ -n "$failed" ]; then
    printf '  WARN  LAST_FAILED backup: %s (run backup.sh by hand to see why)\n' "$failed"
  fi
}

# A check from outside, through Caddy, as a tester's browser would reach the server.
# --resolve sends the request to this machine whatever the name resolves to (the same answer
# Caddy would give from the internet); -k because a `tls internal` certificate is not trusted.
# Besides the pages that must answer, the paths that must NOT (Keycloak's admin and master
# realm, the account console, an application-initiated action, the actuator index) are checked
# to answer 404, and the web client's password grant must be off (DEPLOY-09).
smoke() {
  local url host port fails=0 path want code id grant
  url="$(env_get PUBLIC_URL)"
  host="${url#https://}"; host="${host%%/*}"
  port=443
  case "$host" in *:*) port="${host##*:}"; host="${host%%:*}" ;; esac
  while read -r want path; do
    [ -n "$path" ] || continue
    code="$(curl -sk -o /dev/null -w '%{http_code}' --max-time 20 --resolve "$host:$port:127.0.0.1" "$url$path" || true)"
    if [ "$code" = "$want" ]; then
      printf '  ok    %s %s\n' "$code" "$url$path"
    else
      printf '  FAIL  %s %s (expected %s)\n' "$code" "$url$path" "$want"; fails=$((fails + 1))
    fi
  done <<'EOF'
200 /
200 /config.js
200 /api/actuator/health
200 /auth/realms/coop/.well-known/openid-configuration
401 /mail/
404 /api/actuator
404 /api/actuator/prometheus
404 /auth/
404 /auth/admin/
404 /auth/realms/master/.well-known/openid-configuration
404 /auth/realms/coop/account/
404 /auth/realms/coop/clients-registrations/default
404 /auth/realms/coop/protocol/openid-connect/auth?client_id=coop-erp-web&kc_action=UPDATE_PASSWORD
EOF
  id="$(web_client_id || true)"
  grant="$(kcadm get "clients/$id" -r coop --fields directAccessGrantsEnabled --format csv --noquotes 2>/dev/null | tr -d '\r' | head -n 1 || true)"
  if [ "$grant" = "false" ]; then
    printf '  ok    the password grant of coop-erp-web is off\n'
  else
    printf '  FAIL  the password grant of coop-erp-web is "%s" (on outside a demo-data.sh run: run\n        demo-data.sh --repair-users, which switches it off)\n' "$grant"
    fails=$((fails + 1))
  fi
  backup_status
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
