#!/usr/bin/env bash
# Prepares a fresh Ubuntu 24.04 server for the COOP ERP demo and starts it
# (infra/deploy/SETUP-2vCPU-4GB.md, SETUP-4vCPU-8GB.md). Run as root, from the clone:
#
#   sudo ./infra/deploy/bootstrap.sh --size small  --email admin@example.com
#   sudo ./infra/deploy/bootstrap.sh --size medium --email admin@example.com
#
# The address testers open, one of:
#   (default)               https://<ip-with-dashes>.sslip.io, a name that resolves to the
#                           server's IP without any DNS work, with a real Let's Encrypt
#                           certificate. The IP is detected (DigitalOcean's metadata service if
#                           there is one, else a public lookup); --ip names it.
#   --ip 203.0.113.5        the same, for that IP
#   --ip 203.0.113.5 --tls internal
#                           https://203.0.113.5 with a certificate from Caddy's own authority,
#                           for when sslip.io or Let's Encrypt cannot be reached; browsers warn
#                           once. No --email needed.
#   --domain demo.example.com
#                           your own name (an A record pointing at the server), Let's Encrypt.
# Plain http://<ip> is not offered: the sign-in (PKCE) needs a secure page, and Keycloak refuses
# a sign-in over plain HTTP from outside.
#
# What it does, each step safe to repeat:
#   installs Docker Engine and the compose plugin (Docker's apt repository); makes a swap file
#   (2 GB small, 1 GB medium) with swappiness 10; opens 22, 80 and 443 in ufw; copies the
#   deployment files to /opt/coop-erp; writes /opt/coop-erp/.env with generated passwords and a
#   new till signing key pair, ONLY when there is no .env yet; renders the Keycloak realm; pulls
#   the images and starts everything; waits until healthy; prints the addresses.
#
# Run again with another --ip/--domain/--tls to move the demo to a new address: only the address
# lines of .env change (every password stays), and the realm Keycloak already has is updated.
#
# For a test on a machine that already has Docker (never on a server): --no-system skips the
# Docker install, swap and firewall and the root check; --no-pull uses images already present.
set -euo pipefail

# shellcheck source=lib.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

usage() {
  sed -n '2,33p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

SIZE="" IP="" DOMAIN="" EMAIL="" TLS="" SYSTEM=1 PULL=1
while [ $# -gt 0 ]; do
  case "$1" in
    --size) SIZE="${2:-}"; shift 2 ;;
    --ip) IP="${2:-}"; shift 2 ;;
    --domain) DOMAIN="${2:-}"; shift 2 ;;
    --email) EMAIL="${2:-}"; shift 2 ;;
    --tls) TLS="${2:-}"; shift 2 ;;
    --no-system) SYSTEM=0; shift ;;
    --no-pull) PULL=0; shift ;;
    -h|--help) usage 0 ;;
    *) echo "unknown option: $1" >&2; usage 2 ;;
  esac
done

EXISTING_SIZE="$(env_get COOP_ERP_SIZE)"
SIZE="${SIZE:-$EXISTING_SIZE}"
case "$SIZE" in small|medium) ;; *) fail "--size small or --size medium is required" ;; esac
case "$TLS" in ""|internal|acme) ;; *) fail "--tls takes internal (or acme, the default)" ;; esac
[ -z "$IP" ] || [ -z "$DOMAIN" ] || fail "give --ip or --domain, not both"

if [ "$SYSTEM" -eq 1 ] && [ "$(id -u)" -ne 0 ]; then
  fail "run as root (sudo $0 ...)"
fi

# ---- the system ---------------------------------------------------------------------------

install_docker() {
  if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
    say "Docker is installed: $(docker --version)"
    return 0
  fi
  say "Installing Docker Engine and the compose plugin from Docker's apt repository"
  apt-get update -q
  apt-get install -y -q ca-certificates curl gnupg
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  # shellcheck disable=SC1091
  . /etc/os-release
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -q
  apt-get install -y -q docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  systemctl enable --now docker
}

make_swap() {
  local gb=2
  [ "$SIZE" = medium ] && gb=1
  if swapon --show=NAME --noheadings | grep -q .; then
    say "Swap is already on: $(swapon --show=NAME,SIZE --noheadings | tr '\n' ' ')"
  else
    say "Creating a ${gb} GB swap file"
    fallocate -l "${gb}G" /swapfile
    chmod 600 /swapfile
    mkswap /swapfile >/dev/null
    swapon /swapfile
    grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
  fi
  # Swap is the safety net for a rare peak, not working memory: use it only when RAM is short.
  echo 'vm.swappiness=10' > /etc/sysctl.d/99-coop-erp.conf
  sysctl -q -p /etc/sysctl.d/99-coop-erp.conf
}

open_firewall() {
  say "Firewall (ufw): allowing SSH (22), HTTP (80, for the certificate) and HTTPS (443)"
  command -v ufw >/dev/null 2>&1 || apt-get install -y -q ufw
  ufw allow 22/tcp >/dev/null
  ufw allow 80/tcp >/dev/null
  ufw allow 443/tcp >/dev/null
  ufw --force enable >/dev/null
  ufw status | sed 's/^/  /'
}

if [ "$SYSTEM" -eq 1 ]; then
  install_docker
  make_swap
  open_firewall
fi

# ---- the address ---------------------------------------------------------------------------

# The public IPv4: DigitalOcean's metadata service when the server is a Droplet (it answers in
# milliseconds there and not at all elsewhere, hence the short timeout), else a public lookup.
detect_ip() {
  local ip=""
  ip="$(curl -fs --max-time 2 http://169.254.169.254/metadata/v1/interfaces/public/0/ipv4/address 2>/dev/null || true)"
  [ -n "$ip" ] || ip="$(curl -4fs --max-time 10 https://ifconfig.me 2>/dev/null || true)"
  [ -n "$ip" ] || ip="$(curl -4fs --max-time 10 https://api.ipify.org 2>/dev/null || true)"
  printf '%s' "$ip"
}

ADDRESS_GIVEN=0
if [ -n "$IP" ] || [ -n "$DOMAIN" ] || [ -n "$TLS" ] || [ -z "$(env_get PUBLIC_URL)" ]; then
  ADDRESS_GIVEN=1
  if [ -n "$DOMAIN" ]; then
    HOST="$DOMAIN"

  else
    [ -n "$IP" ] || IP="$(detect_ip)"
    [ -n "$IP" ] || fail "could not find the server's public IP; give it with --ip"
    echo "$IP" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$' || fail "--ip needs an IPv4 address, not '$IP'"
    if [ "$TLS" = internal ]; then
      HOST="$IP"
    else
      HOST="$(echo "$IP" | tr . -).sslip.io"
    fi
  fi
  if [ "$TLS" = internal ]; then
    CADDY_TLS=internal
  else
    [ -n "$EMAIL" ] || EMAIL="$(env_get ACME_EMAIL)"
    [ -n "$EMAIL" ] || fail "--email is needed for a Let's Encrypt certificate (or use --tls internal)"
    CADDY_TLS="$EMAIL"
  fi
  PUBLIC_URL="https://$HOST"
fi

# ---- the files and .env --------------------------------------------------------------------

mkdir -p "$APP_DIR"
sync_files

rand() { openssl rand -hex "${1:-24}"; }

new_env() {
  local key_dir priv pub
  command -v openssl >/dev/null 2>&1 || apt-get install -y -q openssl
  say "Writing $APP_DIR/.env with generated passwords and a new till signing key pair"
  key_dir="$(mktemp -d)"
  # Ed25519, PKCS#8 and X.509 in base64, the form TillSigner reads (application.yml, sync.signing).
  openssl genpkey -algorithm ed25519 -out "$key_dir/k.pem" 2>/dev/null
  priv="$(openssl pkey -in "$key_dir/k.pem" -outform DER | base64 | tr -d '\n')"
  pub="$(openssl pkey -in "$key_dir/k.pem" -pubout -outform DER | base64 | tr -d '\n')"
  rm -rf "$key_dir"
  umask 077
  cat > "$APP_DIR/.env" <<EOF
# Generated by bootstrap.sh on $(date -u +%Y-%m-%dT%H:%MZ). Every variable is explained in
# .env.example. Keep a copy somewhere safe: a backup cannot be restored without these values.
COOP_ERP_SIZE=$SIZE
PUBLIC_URL=$PUBLIC_URL
CADDY_SITE=$PUBLIC_URL
CADDY_TLS=$CADDY_TLS
ACME_EMAIL=${EMAIL}
REGISTRY=${COOP_ERP_REGISTRY:-ghcr.io/knoweb/coop_erp}
IMAGE_TAG=${COOP_ERP_IMAGE_TAG:-main}
DEMO_PASSWORD=Demo-$(rand 4)
KEYCLOAK_ADMIN=admin
KEYCLOAK_ADMIN_PASSWORD=$(rand)
KEYCLOAK_BACKEND_CLIENT_SECRET=$(rand)
MAIL_USER=mail
MAIL_PASSWORD=$(rand 8)
MAIL_FROM=notifications@coop-erp.test
POSTGRES_PASSWORD=$(rand)
MIGRATION_DB_PASSWORD=$(rand)
APP_DB_PASSWORD=$(rand)
RELAY_DB_PASSWORD=$(rand)
KEYCLOAK_DB_PASSWORD=$(rand)
RABBITMQ_PASSWORD=$(rand)
MINIO_ROOT_PASSWORD=$(rand)
COOP_ERP_IDEMPOTENCY_SECRET=$(rand 32)
COOP_ERP_NOTIFICATION_KEY=$(openssl rand -base64 32)
SYNC_SIGNING_PRIVATE_KEY=$priv
SYNC_SIGNING_PUBLIC_KEY=$pub
BACKUP_KEEP=14
BACKUP_S3_BUCKET=
BACKUP_S3_ENDPOINT=
BACKUP_S3_ACCESS_KEY=
BACKUP_S3_SECRET_KEY=
EOF
  chmod 600 "$APP_DIR/.env"
}

ADDRESS_CHANGED=0
if [ -f "$APP_DIR/.env" ]; then
  say "$APP_DIR/.env exists: kept (no password is regenerated)"
  if [ "$SIZE" != "$EXISTING_SIZE" ]; then env_set COOP_ERP_SIZE "$SIZE"; echo "  size set to $SIZE"; fi
  if [ "$ADDRESS_GIVEN" -eq 1 ] && [ "$PUBLIC_URL" != "$(env_get PUBLIC_URL)" ]; then
    env_set PUBLIC_URL "$PUBLIC_URL"
    env_set CADDY_SITE "$PUBLIC_URL"
    env_set CADDY_TLS "$CADDY_TLS"
    [ -z "$EMAIL" ] || env_set ACME_EMAIL "$EMAIL"
    ADDRESS_CHANGED=1
    echo "  address set to $PUBLIC_URL"
  elif [ "$ADDRESS_GIVEN" -eq 1 ] && [ "$CADDY_TLS" != "$(env_get CADDY_TLS)" ]; then
    env_set CADDY_TLS "$CADDY_TLS"
  fi
else
  new_env
fi

render_realm

# ---- start ---------------------------------------------------------------------------------

if [ "$PULL" -eq 1 ]; then
  say "Pulling the images ($(env_get REGISTRY), tag $(env_get IMAGE_TAG))"
  compose pull --quiet postgres pgbouncer rabbitmq minio minio-init keycloak mailpit backend caddy
  compose --profile tools pull --quiet demo-tools
fi

say "Starting the demo (the first start imports the realm and migrates the database: a few minutes)"
start_stack

if [ "$ADDRESS_CHANGED" -eq 1 ]; then
  say "Writing the new address into the running Keycloak realm"
  set_web_client_urls
fi

say "Checking from outside, through Caddy"
smoke || true

URL="$(env_get PUBLIC_URL)"
cat <<EOF

COOP ERP demo is up ($SIZE).

  Back office          $URL
  Keycloak admin       $URL/auth/admin/      user: $(env_get KEYCLOAK_ADMIN)
  Mail catcher         $URL/mail/            user: $(env_get MAIL_USER)

  The demo users' password, the admin and the mail passwords are in $APP_DIR/.env
  (DEMO_PASSWORD, KEYCLOAK_ADMIN_PASSWORD, MAIL_PASSWORD):  sudo grep PASSWORD $APP_DIR/.env

  Next: load the demo data       sudo $APP_DIR/demo-data.sh
EOF
if [ "$(env_get CADDY_TLS)" = internal ]; then
  cat <<EOF

  The certificate is Caddy's own (--tls internal): each browser warns once ("Advanced", then
  "Proceed"). To avoid the warning, install Caddy's root certificate on the testers' machines:
    sudo docker cp $PROJECT-caddy-1:/data/caddy/pki/authorities/local/root.crt ./coop-erp-demo-root.crt
EOF
fi
