# PROGRESS.md — where the work stands

Updated after every ticket, in the same commit. Three headings: Done, Next, Deviations. A person or tool arriving cold reads `AGENTS.md`, then `docs/README.md`, then this file, and can continue.

Last updated: 18 September 2026, branch `docs/step0-instruction-files`.

## Done

- **Step 0 (17 Sep).** `AGENTS.md` is the tool-neutral instruction file; `CLAUDE.md` and `GEMINI.md` point to it. `docs/README.md` is the documentation operating guide. `docs/tools/extract-text.sh` regenerates the read-only extracts; all extracts regenerated as UTF-8 with LF endings. ADR and change-request templates added; LF enforced for `.md`, `.txt`, `.sh`. Commit `cf0d836`.
- **Step 1 (17 Sep).** Orientation reading done: `AGENTS.md`, `docs/HANDOVER.md` (since retired), `docs/DECISIONS_PENDING.md`, then 17A and 19A end to end, then 19, 18 and 32. Checkpoint 1 reported to the architect; its open decisions are under Next.
- **Document update (18 Sep).** Register v0.5 (Word, live, `docs/requirements/`), 18 v0.3.1, 14 and 22A added, 00_Start_Here v1.1, doc 10 v0.4 with `docs/DECISIONS_PENDING.md` as its Markdown source; extracts regenerated; CR-00_Start_Here-1 raised. Commits `ee32343` to `500102a`. Both Drive folders match the repository file for file.
- **HANDOVER.md retired (18 Sep).** Everything actionable in the briefing now lives in the register, doc 10, `docs/README.md` and this file; its one unresolved point became CR-28A-1 (cost of goods sold from `stock.moved.v1`, not from the receipt bundle). The original stays in git history, commit `ed20626`.
- **Sprint 0 work done before the handover** by the platform pair (16 Sep, PRs #1 to #6): parts of S0-02 (app, three profiles, health endpoint), S0-03 (kernel `api` interfaces and in-memory stubs), S0-04 (per-module Flyway runner, baseline migration) and S0-05 (architecture tests, pipeline scripts). Each is partial; see Deviations.

## Next

- **Architect decisions from Checkpoint 1:** (1) keep and complete the existing skeleton, or regenerate it from 17A; (2) merge this branch; (3) toolchain on the build machine (JDK 21, Docker Desktop, Node 20 with pnpm, GNU make) or verification by the platform pair; (4) project and application name: knoweb (17A) or coop-erp (repository).
- **Step 2:** Sprint 0 per 17A §14, tickets S0-01 to S0-13, one conventional commit per ticket, ending with the fifteen-minute start of 17A §13 on a fresh clone. Not started.
- **Step 3:** 19A tickets K-01 to K-13, hello module green throughout, doc 32 §11 conformance suite passing against the till simulator. Not started.
- No module (M1 onward) starts before the architect says so.

## Deviations

- **Extracts** are produced with `pdftotext -layout -enc UTF-8 -eol unix`, not bare `-layout`: on Windows the bare form wrote Latin-1 with every dash as an invalid UTF-8 byte.
- **Existing skeleton versus 17A**, to resolve in Step 2: Gradle 9.7.1 wrapper where 17A says 8.10+; no `gradle/libs.versions.toml`; Gradle root project and `spring.application.name` are `coop-erp-backend` where 17A says `knoweb`; Spring Boot 3.3.5 and Modulith 1.2.5 pinned inline; no JPA, Security, Testcontainers, OpenAPI generator or Jib; Flyway runs from a `@PostConstruct` in `FlywayConfig`; `ArchitectureTests` lacks 17A's layer rule and own-schema repository rule and has an extra kernel-internals rule; the three `i18n` files are empty; no Makefile, hello module, shared engine, web or till scaffold, compose, CI workflow or `make new-module`.
- **AGENTS.md** lists nine module packages; 17A §2 and the repository also carry `m10procurement` as a reserved package.
- **Build machine** used for this session has no JDK, Docker, Node or make on PATH, so the Checkpoint 2 proof cannot run on it as it stands.
- **Document set:** the register lists two supporting documents (Document_Numbering_RFC, Numbering_Scheme_Tax_Advisor_Briefing) that are in neither the Drive baseline folder nor the repository. Docs 34 to 40 are unwritten; ADR-37 to ADR-50 are not yet in doc 09; glossary 12 lacks the design terms; the Markdown-to-PDF render script has not been chosen.
