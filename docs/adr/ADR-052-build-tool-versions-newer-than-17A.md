# ADR-052 — Keep the build tools on their current supported lines where 17A names older ones

- **Status:** accepted (architect, 2026-09-20)
- **Date:** 2026-09-20
- **Mirrors:** new; to be entered in doc 09 at its next revision
- **Raised while:** `S0-01` close-out (repository, Makefile, version catalogue); open since `docs/PROGRESS.md` of 18 September ("Gradle 9.7.1 wrapper where 17A says 8.10+")

## Context

17A section 3 was written before the repository existed and names the tool versions of that time: Gradle "8.10+", Node "20 LTS", pnpm "9". The repository was started with what was current and installed: Gradle 9.7.1, Node 24 and pnpm 12.4.2. Three facts since then. Node 20 left long-term support in April 2026, so GitHub's runners and the official container images have moved on; the web container, the pipeline and the build machines all run Node 24, the current LTS line. Gradle 9.7.1 satisfies the letter of "8.10+" and runs the whole build, the Jib image and the pipeline; its only deprecation warnings come from the Kotlin 2.0 plugin. The one thing that speaks for Gradle 8 is that the Spring Boot 3.3 plugin does not list Gradle 9 as supported, and `CR-17A-2` asks to leave Spring Boot 3.3 for the 3.5 line, whose plugin does.

## Decision

The backend build stays on the Gradle 9.7.1 wrapper, and the web client, the pipeline and the local stack stay on Node 24 with pnpm 12.4.2. "8.10+", "20 LTS" and "9" in 17A section 3 are read as minimums, not as pins. The pins are in the repository: the Gradle wrapper properties, `NODE_VERSION` and `PNPM_VERSION` in `.github/workflows/ci.yml`, the image tags in `infra/compose/compose.yml`, and `backend/gradle/libs.versions.toml` for everything the Gradle build names. Node moves from one LTS line to the next, by hand and everywhere at once; Dependabot is told not to propose Node majors. The till keeps its own wrapper (Gradle 8.9 today, below 17A's 8.10: for the till's owner to raise or to record, S0-08), because the Android Gradle plugin decides which Gradle it supports.

## Consequences

Nobody downgrades a working toolchain to match a document, and a new developer installs current LTS tools instead of hunting for retired ones. Until Spring Boot moves to 3.5, the backend runs a plugin and Gradle combination its vendor has not tested; the pipeline has, on every push, and that is the evidence we rely on. If a Gradle 9 incompatibility appears before then, the way back is one line in `backend/gradle/wrapper/gradle-wrapper.properties`. 17A section 3 should say "or newer supported lines" at its next revision; no `docs/DECISIONS_PENDING.md` item is closed by this.
