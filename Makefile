# COOP ERP developer commands (17A section 13). Run `make` or `make help` for the list.
#
# Needs: Docker with the compose plugin (2.24 or newer), JDK 21, GNU make, a POSIX shell.
# On Windows the POSIX shell is the one that comes with Git for Windows; make finds it by
# itself (next block), so PowerShell, cmd and Git Bash all work.
# The backend image is built on the host by Jib (`make image`); the web client runs in a
# container. `build`, `test` and `gen-clients` also need Node with pnpm on the host.

.DEFAULT_GOAL := help

# ---- Windows: find a POSIX shell ----------------------------------------------------------
# The recipes below are written for sh (for loops, [ -n ... ], shell scripts). Started from
# Git Bash, make finds sh on the PATH and SHELL is its full path. Started from PowerShell or
# cmd there is no sh on the PATH, make leaves SHELL as the bare name "sh.exe" and would run
# every recipe with cmd.exe, which fails with "CreateProcess ... sh ... failed".
# Git is installed wherever this repository is used, and Git for Windows ships sh, sed and
# the rest next to git.exe. So: ask cmd where git.exe is (the Git/cmd folder) and use the
# sh.exe in Git/bin beside it.
# The PATH line is needed as well, because make starts a simple command such as
# `sh tools/gen-clients.sh` directly, without going through SHELL.
ifeq ($(OS),Windows_NT)
ifeq ($(SHELL),sh.exe)
GIT_CMD_DIR := $(shell for %%i in (git.exe) do @echo %%~dp$$PATH:i)
ifeq ($(GIT_CMD_DIR),)
$(error make needs a POSIX shell and found neither sh nor git on the PATH. Install Git for Windows, or run make from Git Bash)
endif
SHELL := $(GIT_CMD_DIR)..\bin\sh.exe
export PATH := $(PATH);$(GIT_CMD_DIR)..\usr\bin
endif
endif

COMPOSE_DIR := infra/compose
COMPOSE     := docker compose --project-directory $(COMPOSE_DIR) -f $(COMPOSE_DIR)/compose.yml
COMPOSE_TWO := $(COMPOSE) -f $(COMPOSE_DIR)/compose.two.yml
SEED_DIR    := backend/app/src/main/resources/seed
JIB_BASE    := $(shell sed -n "s/^jibBaseImage=//p" backend/gradle.properties)

.PHONY: help image up up-2 down reset migrate seed urls build test test-int format coverage hooks lint-ci gen-clients check-generated new-module test-scaffold smoke e2e

help:
	@echo "make up           start the local stack, migrate, seed, print URLs and dev logins"
	@echo "make up-2         the same with two backend instances behind nginx"
	@echo "make image        build the backend container image with Jib (make up does this first)"
	@echo "make down         stop the stack, keep the data"
	@echo "make reset        stop the stack and delete its data volumes"
	@echo "make migrate      rebuild and restart the backend, which runs the Flyway migrations"
	@echo "make seed         load the development seed rows (safe to repeat)"
	@echo "make build        build backend and web on the host"
	@echo "make test         backend unit and architecture tests, the script checks, web lint and web tests"
	@echo "make test-int     integration tests against PostgreSQL in Docker (Testcontainers)"
	@echo "make format       format the Java files you changed (the pipeline checks this)"
	@echo "make coverage     unit and integration tests, then the coverage report"
	@echo "make hooks        optional: run the quick checks before every git push"
	@echo "make lint-ci      check the workflow files before pushing a change to them (needs Docker)"
	@echo "make smoke        smoke test of the running stack (TWO=1 after make up-2)"
	@echo "make e2e          Playwright tests in a browser against the running stack (make up first)"
	@echo "make gen-clients  regenerate web/src/generated from every OpenAPI slice"
	@echo "make check-generated  fail when the committed clients or module diagrams are stale"
	@echo "make new-module NAME=m2catalogue SCHEMA=catalogue ENTITY=sku   (DRY_RUN=1 to preview)"
	@echo "make test-scaffold scaffold a throwaway module and prove everything still passes (clean tree only)"

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
# The web container mounts a volume at web/node_modules. When that folder does not exist yet
# (a fresh clone), Docker creates it, and on Linux it then belongs to root: the next
# `pnpm install` on the host fails with "Permission denied". That is what broke the nightly
# fresh clone. Creating it first keeps it the caller's.
up: image
	@mkdir -p web/node_modules
	$(COMPOSE) up --detach --wait --remove-orphans
	@$(MAKE) --no-print-directory seed
	@$(MAKE) --no-print-directory urls

up-2: image
	@mkdir -p web/node_modules
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
	cd backend && ./gradlew spotlessCheck test
	node --test tools/new-module.test.mjs tools/checks.test.mjs
	node tools/check-schema-ownership.mjs
	node tools/check-i18n.mjs
	node tools/check-permissions.mjs
	cd web && pnpm install --frozen-lockfile && pnpm lint && pnpm test

# The tests tagged "integration": the whole application against a real PostgreSQL 16 that
# Testcontainers starts in Docker. Needs a running Docker, not a running `make up` stack.
test-int:
	cd backend && ./gradlew :app:integrationTest

# Rewrites the Java files that differ from origin/main into the one code style of the project.
# `make test` and the pipeline fail on a file that is not formatted; this is the cure.
format:
	cd backend && ./gradlew spotlessApply

# One report over the unit and the integration tests. A report, not a gate: coverage floors
# are set per module by its implementation guide.
coverage:
	cd backend && ./gradlew :app:test :app:integrationTest :app:jacocoTestReport
	@echo "coverage report: backend/app/build/reports/jacoco/test/html/index.html"

# Optional and per clone: git runs .githooks/pre-push before every push (schema ownership,
# message catalogue, permissions: a few seconds). Undo with: git config --unset core.hooksPath
hooks:
	git config core.hooksPath .githooks
	@echo "installed: .githooks/pre-push runs before every git push (skip once with --no-verify)"

# A workflow file with a syntax error does not fail in the pipeline: the pipeline does not
# start at all, and the pull request shows no checks. So check before pushing: actionlint reads
# every file in .github/workflows, shellcheck included.
# MSYS_NO_PATHCONV: started from Git Bash on Windows, MSYS would rewrite the container path
# /repo into C:/Program Files/Git/repo. The variable does nothing anywhere else.
lint-ci:
	MSYS_NO_PATHCONV=1 docker run --rm -v "$(CURDIR):/repo" -w /repo rhysd/actionlint:latest -color

gen-clients:
	sh tools/gen-clients.sh

# Generated files are committed: the web clients (from the OpenAPI slices) and the module
# diagrams in docs/modules (from ArchitectureTests). This target regenerates both and fails
# when git then sees a difference, which means somebody changed a slice or a module
# dependency and did not commit what it generates. The pipeline runs it on every push.
check-generated:
	sh tools/gen-clients.sh
	cd backend && ./gradlew :app:test --tests "*ArchitectureTests*"
	@# Compared by content against what is staged or committed: a changed file, or a new file
	@# git does not know yet. (`git status` would also report line-ending noise on Windows.)
	@if ! git diff --quiet -- web/src/generated docs/modules \
	    || [ -n "$$(git ls-files --others --exclude-standard -- web/src/generated docs/modules)" ]; then \
		echo "" >&2; \
		echo "Generated files are stale. The regenerated files are in your working tree: review and commit them." >&2; \
		git --no-pager diff --stat -- web/src/generated docs/modules >&2; \
		git ls-files --others --exclude-standard -- web/src/generated docs/modules >&2; \
		exit 1; \
	fi
	@echo "generated files are up to date"

# Copies the hello module as the start of a real module, then generates its web client.
# The tool checks the three names and refuses to overwrite anything; add DRY_RUN=1 to see
# what it would do. ENTITY is the first aggregate of the module, lowercase with underscores:
# sku, price_list, tax_category. The copy's permissions are placeholders (todo....) that
# `make test` refuses until you replace them with the codes of the module's guide.
new-module:
	node tools/new-module.mjs --name "$(NAME)" --schema "$(SCHEMA)" --entity "$(ENTITY)" $(if $(PLURAL),--plural "$(PLURAL)") $(if $(DRY_RUN),--dry-run)
	$(if $(DRY_RUN),,sh tools/gen-clients.sh)

# The done criterion of S0-13 as a command: scaffold a throwaway module, then prove that
# everything still builds and passes with it in place, including the sixteen integration
# tests it inherits from hello. It removes the module again with git, so it insists on a
# clean working tree first and never touches uncommitted work. If a step fails, the
# scaffolded module is left in place for you to look at; `git reset --hard && git clean -fd`
# removes it.
test-scaffold:
	@if [ -n "$$(git status --porcelain)" ]; then \
		echo "make test-scaffold needs a clean working tree (commit or stash first)." >&2; exit 1; \
	fi
	node tools/new-module.mjs --name m3pricing --schema pricing --entity price_list
	sh tools/gen-clients.sh
	@echo "--- a fresh copy carries placeholder permissions, and the check must refuse them"
	@if node tools/check-permissions.mjs > /dev/null 2>&1; then \
		echo "check-permissions accepted the scaffold placeholders" >&2; exit 1; \
	fi
	@echo "--- replace them, as the developer does in step 1 of the module README"
	grep -rl "todo\.pricing\.price_list\." backend/app/src web/src | xargs sed -i "s/todo\.pricing\.price_list\./prc.price_list./g"
	node tools/check-permissions.mjs
	cd backend && ./gradlew :app:test :app:integrationTest
	node tools/check-schema-ownership.mjs
	node tools/check-i18n.mjs
	cd web && pnpm install --frozen-lockfile && pnpm build && pnpm test
	git reset --hard --quiet
	git clean -fdq
	@echo "scaffolder proof passed; the throwaway module has been removed"

# Smoke test of the running stack, from outside, through the published ports. Start the
# stack first: `make up` then `make smoke`, or `make up-2` then `make smoke TWO=1`.
smoke:
	node tools/smoke.mjs $(if $(TWO),--two)

# The end-to-end tests: a real browser signs in as each development user on the identity
# server's own page and reads the back office (17A section 7). Like `make smoke` it needs the
# stack to be running already: `make up` then `make e2e`, or `make up-2` then `make e2e`.
# TWO=1 is accepted and changes nothing, because the addresses are the same either way: with
# two instances nginx fronts both backends on the same port 8080 (infra/compose/compose.two.yml),
# so the browser and the tests cannot tell the difference, which is the point.
#
# On Linux, run `pnpm install` in web/ once BEFORE the first `make up`: the web container keeps
# its node_modules in a named volume, and to mount that volume inside the bind mount Docker
# creates web/node_modules on the host as root, after which pnpm cannot write there. Docker
# Desktop on Windows and macOS does not have the problem.
#
# The Chromium build Playwright uses (about 150 MB) is downloaded once; the install is a
# no-op afterwards. It lands in the user's home folder, which on a machine with a full C:
# drive is the wrong place: export PLAYWRIGHT_BROWSERS_PATH=D:/tmp/ms-playwright (or any
# other folder with room) before running, and the download and the run both follow it.
e2e:
	cd web && pnpm install --frozen-lockfile && pnpm exec playwright install chromium && pnpm exec playwright test
