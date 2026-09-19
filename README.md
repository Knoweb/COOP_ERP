# COOP ERP

Cooperative Retail and Distribution System.

## Technology

- Java 21
- Spring Boot 3.3.x
- Spring Modulith 1.2.x
- PostgreSQL 16
- Flyway 10.x
- React 18 + TypeScript + Vite
- Kotlin / Android / Jetpack Compose
- RabbitMQ
- MinIO
- Keycloak

## Repository

- `backend/app` — Spring Boot modular monolith
- `backend/shared-engine` — shared Kotlin engine
- `web` — React back office
- `till` — native Android POS
- `infra/compose` — local development stack
- `infra/deploy` — deployment definitions
- `tools` — development/build scripts
- `docs` — architecture/design references

## Development: the fifteen-minute start

You need Docker (Docker Desktop on Windows and macOS) with the compose plugin 2.24 or newer, JDK 21, GNU make and a POSIX shell. On Windows the shell is the one that ships with Git for Windows and make finds it by itself, so PowerShell, cmd and Git Bash all work. The backend image is built on your machine by Jib, through the Gradle wrapper; the web client is built and run inside a container, so Node is needed only for `make build`, `make test` and `make gen-clients`. Give Docker at least 6 GB of memory.

```bash
git clone https://github.com/Knoweb/COOP_ERP.git
cd COOP_ERP
make up
```

The first `make up` takes about ten minutes: it pulls the images, builds the backend image with Jib, starts PostgreSQL, PgBouncer, RabbitMQ, MinIO, Keycloak, Mailpit, the backend and the web client, waits until every one reports healthy, loads the development seed rows and prints the addresses. The database is migrated by the backend when it starts.

Then open http://localhost:5173 and sign in as `fed-admin`, `mpcs-admin` or `cashier`, password `dev`. The three users read English, Sinhala and Tamil in that order.

| Command | What it does |
|---|---|
| `make up` | Start the stack, migrate, seed, print the addresses and the dev logins |
| `make up-2` | The same with two backend instances behind nginx, to catch bugs that depend on one instance answering every request |
| `make image` | Build the backend container image with Jib; `make up`, `make up-2` and `make migrate` do this first |
| `make down` | Stop the stack and keep its data |
| `make reset` | Stop the stack and delete its data volumes; the next `make up` starts from an empty database |
| `make migrate` | Rebuild and restart the backend, which runs the new Flyway migrations |
| `make seed` | Load the development seed rows again (safe to repeat) |
| `make test` | Unit and architecture tests, schema-ownership and i18n checks (needs JDK 21 and Node on the host) |
| `make gen-clients` | Regenerate `web/src/generated` after changing an OpenAPI slice |
| `make new-module NAME=m3pricing SCHEMA=pricing ENTITY=price_list` | Copy the hello module as the start of a real module: backend package, migration, slice, seed, integration test, web module, message ids and route. `ENTITY` is lowercase with underscores and is spelled as each place needs (`PriceList`, `priceList`, `price_list`, `/price-lists`). Refuses to overwrite; `DRY_RUN=1` previews. The copy has placeholder permissions that `make test` refuses: replace them first, then follow the README written into the new package |
| `make test-scaffold` | Scaffold a throwaway module, prove everything still builds and passes with it, remove it again (needs a clean working tree) |

Ports, users and passwords are development defaults in `infra/compose/compose.yml`. To change one, copy `infra/compose/.env.example` to `infra/compose/.env` and edit it; after changing a database user run `make reset`.

To run the backend from the IDE instead of the container, stop that one container with `docker compose -p coop-erp stop backend` and start `CoopErpApplication`: its defaults point at the stack's PgBouncer and PostgreSQL ports.
