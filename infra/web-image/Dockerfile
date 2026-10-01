# The web image of a deployed server (infra/deploy): Caddy with the built back office in /srv.
# One container is the TLS front door, the static file server and the reverse proxy to the
# backend, Keycloak and Mailpit; its Caddyfile is not baked in but mounted from the server
# (infra/deploy/Caddyfile), so a change of address needs no new image. The bundle names no
# address either: Caddy answers /config.js from its environment (web/src/shell/runtimeConfig.ts),
# so one image serves any server.
#
# Build context: web, after `pnpm build` (which fills dist).
#   docker build -f infra/web-image/Dockerfile -t coop-erp/web:dev web
FROM caddy:2.10-alpine
COPY dist /srv
