# COOP ERP developer commands (17A section 13). Run `make` or `make help` for the list.
#
# Needs: Docker with the compose plugin (2.24 or newer), JDK 21, GNU make, a POSIX shell.
# On Windows run make from Git Bash, so the recipes below find `sh`.
# The backend image is built on the host by Jib (`make image`); the web client runs in a
# container. `build`, `test` and `gen-clients` also need Node with pnpm on the host.

.DEFAULT_GOAL := help

COMPOSE_DIR := infra/compose
COMPOSE     := docker compose --project-directory $(COMPOSE_DIR) -f $(COMPOSE_DIR)/compose.yml
COMPOSE_TWO := $(COMPOSE) -f $(COMPOSE_DIR)/compose.two.yml
SEED_DIR    := backend/app/src/main/resources/seed
JIB_BASE    := $(shell sed -n "s/^jibBaseImage=//p" backend/gradle.properties)

.PHONY: help image up up-2 down reset migrate seed urls build test test-int gen-clients new-module

help:
	@echo "make up           start the local stack, migrate, seed, print URLs and dev logins"
	@echo "make up-2         the same with two backend instances behind nginx"
	@echo "make image        build the backend container image with Jib (make up does this first)"
	@echo "make down         stop the stack, keep the data"
	@echo "make reset        stop the stack and delete its data volumes"
	@echo "make migrate      rebuild and restart the backend, which runs the Flyway migrations"
	@echo "make seed         load the development seed rows (safe to repeat)"
	@echo "make build        build backend and web on the host"
	@echo "make test         unit and architecture tests, schema-ownership and i18n checks"
	@echo "make test-int     integration tests against PostgreSQL in Docker (Testcontainers)"
	@echo "make gen-clients  regenerate web/src/generated from every OpenAPI slice"
	@echo "make new-module NAME=m2catalogue SCHEMA=catalogue"

# Jib builds coop-erp/backend:dev straight into the local Docker daemon. It is fast when
# nothing changed: only the layer that holds the project classes is rebuilt.
# Docker pulls the base image first and Jib reads it from the daemon; the reason is in
# backend/app/build.gradle.kts.
image:
	docker pull --quiet $(JIB_BASE)
	cd backend && ./gradlew :app:jibDockerBuild -PjibFromDaemon=true --console=plain

# --wait blocks until every service reports healthy, so when this returns the database is
# migrated (the backend runs Flyway at start) and the dev realm is imported.
# --remove-orphans clears the second backend and nginx left behind by an earlier `make up-2`.
up: image
	$(COMPOSE) up --detach --wait --remove-orphans
	@$(MAKE) --no-print-directory seed
	@$(MAKE) --no-print-directory urls

up-2: image
	$(COMPOSE_TWO) up --detach --wait --remove-orphans
	@$(MAKE) --no-print-directory seed
	@$(MAKE) --no-print-directory urls
	@echo "  two backend instances: nginx on :8080 alternates between them"

# Both use the two-instance file list so they also remove what `make up-2` started.
down:
	$(COMPOSE_TWO) down --remove-orphans

reset:
	$(COMPOSE_TWO) down --volumes --remove-orphans

# There is no separate migration step: FlywayConfig migrates when the backend starts.
# So "migrate" means: rebuild the image with the new migration files and start it again.
migrate: image
	$(COMPOSE) up --detach --wait --force-recreate --no-deps backend

# Seeds run as the PostgreSQL superuser inside the postgres container: they write rows for
# several entities at once, which row-level security forbids to the application user.
# The user and database names come from the container's own environment.
seed:
	@for f in $(SEED_DIR)/*/*.dev.sql; do \
		[ -f "$$f" ] || continue; \
		echo "seeding $$f"; \
		$(COMPOSE) exec -T postgres sh -c 'psql -q -v ON_ERROR_STOP=1 -U "$$POSTGRES_USER" -d "$$POSTGRES_DB"' < "$$f" || exit 1; \
	done

urls:
	@echo ""
	@echo "COOP ERP local stack is up"
	@echo "  web back office   http://localhost:5173"
	@echo "  backend API       http://localhost:8080   (health: /actuator/health)"
	@echo "  Keycloak          http://localhost:8085   (admin console: admin / admin)"
	@echo "  RabbitMQ console  http://localhost:15672  (coop / coop)"
	@echo "  MinIO console     http://localhost:9001   (coopminio / coopminio-dev)"
	@echo "  Mailpit inbox     http://localhost:8025"
	@echo "  PostgreSQL        localhost:5434, through PgBouncer localhost:6432, database coop_erp"
	@echo ""
	@echo "  dev logins (password: dev)"
	@echo "    fed-admin    Federation administrator, English"
	@echo "    mpcs-admin   MPCS administrator, Sinhala"
	@echo "    cashier      shop cashier, Tamil"
	@echo ""

build:
	cd backend && ./gradlew build -x test
	cd web && pnpm install --frozen-lockfile && pnpm build

test:
	cd backend && ./gradlew test
	node tools/check-schema-ownership.mjs
	node tools/check-i18n.mjs

# The tests tagged "integration": the whole application against a real PostgreSQL 16 that
# Testcontainers starts in Docker. Needs a running Docker, not a running `make up` stack.
test-int:
	cd backend && ./gradlew :app:integrationTest

gen-clients:
	sh tools/gen-clients.sh

new-module:
	@if [ -z "$(NAME)" ] || [ -z "$(SCHEMA)" ]; then \
		echo "Usage: make new-module NAME=m2catalogue SCHEMA=catalogue"; \
	else \
		python tools/new_module.py --name $(NAME) --schema $(SCHEMA); \
	fi
