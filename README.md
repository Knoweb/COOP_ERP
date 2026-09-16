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

## Development

```bash
make up
make test