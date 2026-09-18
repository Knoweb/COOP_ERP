# COOP ERP developer commands (17A section 13). Run `make` or `make help` for the list.
#
# Needs: Docker with the compose plugin (2.24 or newer), GNU make, a POSIX shell.
# On Windows run make from Git Bash, so the recipes below find `sh`.
# `make up`, `up-2`, `down`, `reset`, `migrate` and `seed` need nothing else: the backend
# and the web client are built inside containers. `build`, `test` and `gen-clients` run on
# the host and need JDK 21 and Node with pnpm.

.DEFAULT_GOAL := help

COMPOSE_DIR := infra/compose
COMPOSE     := docker compose --project-directory $(COMPOSE_DIR) -f $(COMPOSE_DIR)/compose.yml
COMPOSE_TWO := $(COMPOSE) -f $(COMPOSE_DIR)/compose.two.yml
SEED_DIR    := backend/app/src/main/resources/seed

.PHONY: help up up-2 down reset migrate seed urls build test test-int gen-clients new-module

help:
	@echo "make up           start the local stack, migrate, seed, print URLs and dev logins"
	@echo "make up-2         the same with two backend instances behind nginx"
	@echo "make down         stop the stack, keep the data"
	@echo "make reset        stop the stack and delete its data volumes"
	@echo "make migrate      rebuild and restart the backend, which runs the Flyway migrations"
	@echo "make seed         load the development seed rows (safe to repeat)"
	@echo "make build        build backend and web on the host"
	@echo "make test         unit and architecture tests, schema-ownership and i18n checks"
	@echo "make test-int     integration tests (not available yet, see the target)"
	@echo "make gen-clients  regenerate web/src/generated from every OpenAPI slice"
	@echo "make new-module NAME=m2catalogue SCHEMA=catalogue"

# --wait blocks until every service reports healthy, so when this returns the database is
# migrated (the backend runs Flyway at start) and the dev realm is imported.
# --remove-orphans clears the second backend and nginx left behind by an earlier `make up-2`.
up:
	$(COMPOSE) up --detach --build --wait --remove-orphans
	@$(MAKE) --no-print-directory seed
	@$(MAKE) --no-print-directory urls

up-2:
	$(COMPOSE_TWO) up --detach --build --wait --remove-orphans
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
migrate:
	$(COMPOSE) up --detach --build --wait --force-recreate --no-deps backend

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

# Deliberately fails instead of printing a friendly message and exiting 0: a target that
# pretends to pass hides the gap from CI. Testcontainers and the first integration tests
# arrive with the hello module (S0-12); replace this recipe then.
test-int:
	@echo "No integration tests exist yet: they arrive with the hello module (S0-12)." >&2
	@exit 1

gen-clients:
	sh tools/gen-clients.sh

new-module:
	@if [ -z "$(NAME)" ] || [ -z "$(SCHEMA)" ]; then \
		echo "Usage: make new-module NAME=m2catalogue SCHEMA=catalogue"; \
	else \
		python tools/new_module.py --name $(NAME) --schema $(SCHEMA); \
	fi
