# The web image of a deployed server (infra/deploy): Caddy with the built back office in /srv.
# One container is the TLS front door, the static file server and the reverse proxy to the
# backend, Keycloak and Mailpit; its Caddyfile is not baked in but mounted from the server
# (infra/deploy/Caddyfile), so a change of address needs no new image. The bundle names no
# address either: Caddy answers /config.js from its environment (web/src/shell/runtimeConfig.ts),
# so one image serves any server.
#
# Build context: web, after `pnpm build` (which fills dist).
#   docker build -f infra/web-image/Dockerfile -t coop-erp/web:dev web
#
# The base is pinned by digest (wave 2, DEPLOY-15): a tag can be moved under us. Dependabot (the
# docker entry for this folder in .github/dependabot.yml) proposes the next digest.
FROM caddy:2.10-alpine@sha256:4c6e91c6ed0e2fa03efd5b44747b625fec79bc9cd06ac5235a779726618e530d
COPY dist /srv
