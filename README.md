# COOP ERP

Cooperative Retail and Distribution System.

## Read this first

| If you want to | Read |
|---|---|
| work in this repository, as a person or with an AI coding tool | [AGENTS.md](AGENTS.md): what the system is, the rules the build enforces, how we work |
| know where the work stands, and every deviation from the guides with its reason | [docs/PROGRESS.md](docs/PROGRESS.md) |
| find a design document, or learn how the documentation is organised | [docs/README.md](docs/README.md) |
| build a module | the hello module's [README](backend/app/src/main/java/lk/coopfed/knoweb/hello/README.md): the template every module copies, and the table of what the build checks for you |
| see the plan up to the first two modules | [docs/PLAN_TO_M2.md](docs/PLAN_TO_M2.md) |

Branches are short-lived and merged by squash; the **title of the pull request** becomes the commit on `main`, so write it as a conventional commit (`feat(m1): register an entity`). Run `make format` and `make test` before you push. A design you think is wrong is a change request in `docs/change-requests/`, not a local fix.

## Technology

Versions are pinned in `backend/gradle/libs.versions.toml`, `web/package.json` and `infra/compose/compose.yml`; `docs/adr/ADR-052-*.md` says where they are newer than 17A names.

- Java 21
- Spring Boot 3.5.x
- Spring Modulith 1.4.x
- PostgreSQL 16
- Flyway 11.x
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
| `make build` | Build the backend and the web client on the host, without tests |
| `make up-2` | The same with two backend instances behind nginx, to catch bugs that depend on one instance answering every request |
| `make image` | Build the backend container image with Jib; `make up`, `make up-2` and `make migrate` do this first |
| `make down` | Stop the stack and keep its data |
| `make reset` | Stop the stack and delete its data volumes; the next `make up` starts from an empty database |
| `make migrate` | Rebuild and restart the backend, which runs the new Flyway migrations |
| `make seed` | Load the development seed rows again (safe to repeat) |
| `make test` | Backend unit and architecture tests, the format check, the script checks (schema ownership, message catalogue, permissions), and the web client's lint and tests (needs JDK 21 and Node with pnpm on the host) |
| `make test-int` | Integration tests against a real PostgreSQL that Testcontainers starts in Docker (needs Docker, not the running stack) |
| `make format` | Format the Java files you changed; `make test` and the pipeline fail on an unformatted file |
| `make coverage` | Unit and integration tests, then one coverage report (`backend/app/build/reports/jacoco/test/html/index.html`); a report, not a gate |
| `make hooks` | Optional, per clone: run the quick checks (schema ownership, message catalogue, permissions) before every `git push` |
| `make lint-ci` | Check the workflow files with actionlint before pushing a change to them: a broken workflow file does not fail, it silently does not run (needs Docker) |
| `make smoke` | Smoke test of the running stack from outside, through the published ports; `make smoke TWO=1` after `make up-2` |
| `make e2e` | The end-to-end tests: a real browser signs in as each development user on the identity server's page and reads the back office (scope banner, navigation and the hello screen in English, Sinhala and Tamil; what each role may do). Needs the stack running, like `make smoke`; `TWO=1` is accepted and changes nothing, because with two instances nginx fronts both backends on the same port. The Chromium build is downloaded once; set `PLAYWRIGHT_BROWSERS_PATH` first if your home drive has no room |
| `make check-generated` | Regenerate the web clients and the module diagrams in `docs/modules` and fail when the committed ones are stale |
| `make gen-clients` | Regenerate `web/src/generated` after changing an OpenAPI slice |
| `make new-module NAME=m3pricing SCHEMA=pricing ENTITY=price_list` | Copy the hello module as the start of a real module: backend package, migration, slice, seed, integration test, web module, message ids and route. `ENTITY` is lowercase with underscores and is spelled as each place needs (`PriceList`, `priceList`, `price_list`, `/price-lists`). Refuses to overwrite; `DRY_RUN=1` previews. The copy has placeholder permissions that `make test` refuses: replace them first, then follow the README written into the new package |
| `make test-scaffold` | Scaffold a throwaway module, prove everything still builds and passes with it, remove it again (needs a clean working tree) |

## What is automated

| What | Where | When |
|---|---|---|
| Build, unit and architecture tests, the format check, the four script checks, integration tests against a real PostgreSQL 16 (a throwaway container per run), web lint, tests and build, coverage report | `ci.yml`, job "Build, verify, test" | every pull request, every push to `main` |
| A module scaffolded from hello still builds and passes | `ci.yml`, job "Scaffolder proof" | same |
| The whole stack with two backend instances behind nginx, smoke-tested from outside, then the back office in a real browser: signing in as each development user, the scope banner, the navigation and the hello screen in three languages, what each role may do, a deep link that survives a login, sign-out (Playwright, Chromium; report and traces uploaded when it fails) | `ci.yml`, job "Stack smoke" | same |
| Backend image, web bundle, shared engine, bill of materials | `ci.yml`, job "Package" | same |
| No secret in any commit (gitleaks) | `ci.yml`, job "Secret scan" | same |
| The pull request title is a conventional commit | `pr-title.yml` | when a pull request is opened or its title is edited |
| The fifteen-minute start from a fresh clone | `ci.yml`, job "Nightly fresh clone" | every night, 01:30 in Colombo |
| **The team is told** when `main` or the nightly run fails: an issue labelled `ci-failure` mentions the maintainers, and closes by itself on the next green run. With the five `NOTIFY_*` repository secrets set (named in `ci.yml`), a mail goes out as well, on red and again on green | `ci.yml`, job "Notify the team" | on failure |
| Vulnerable dependencies: one issue labelled `security`, with the version to move to | `security-scan.yml` | Monday 06:00 in Colombo, and by hand |
| Dependency updates, grouped per area; security fixes at once | `dependabot.yml` | Monday 06:00 in Colombo |

Watch the repository (the eye at the top of its GitHub page, "All activity" or at least "Issues") to get these by mail. Not automated, because the GitHub plan of the organisation does not allow it for a private repository: a red pipeline does not block the merge button, and nobody is asked for review automatically. Until that changes it is a rule, not a lock: **do not merge on red.**

Ports, users and passwords are development defaults in `infra/compose/compose.yml`. To change one, copy `infra/compose/.env.example` to `infra/compose/.env` and edit it; after changing a database user run `make reset`.

After pulling a change to `web/package.json`, restart the web container so that it installs the new packages: `docker compose -p coop-erp restart web` (`make up` leaves a running container alone; the sign is "Failed to resolve import" in the browser).

To run the backend from the IDE instead of the container, stop that one container with `docker compose -p coop-erp stop backend` and start `CoopErpApplication`: its defaults point at the stack's PgBouncer and PostgreSQL ports.
