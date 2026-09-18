# ADR-051 — The project and its identifiers are named coop-erp; Java packages keep the root lk.coopfed.knoweb

- **Status:** accepted
- **Date:** 2026-09-18
- **Mirrors:** new; to be entered in doc 09 at its next revision
- **Raised while:** Sprint 0 review of 18 September 2026, before the Makefile, compose stack and CI names are written

## Context

17A §2 and §13 call the repository and application "knoweb" and every implementation guide cites the Java package root `lk.coopfed.knoweb`. The repository was created as `Knoweb/COOP_ERP`, the Gradle projects as `coop-erp-backend`, `coop-erp-web` and `coop-erp-till`, and the README as "COOP ERP". Two names were in use, and the Makefile, compose services and CI jobs about to be written would have baked in whichever came first. Renaming the package root would also require a change request against 17A, 19A and all nine module guides.

## Decision

The product, project and every configurable identifier are named **coop-erp**: Gradle project names, `spring.application.name`, the configuration prefix `coop-erp:` and the environment variable `COOP_ERP_ROLE`, the application class `CoopErpApplication`, and the names of the Makefile targets, compose services, images and CI jobs when they are written. The word knoweb does not appear in new code or configuration.

The Java and Kotlin package root stays `lk.coopfed.knoweb`, and with it the Maven group id of the shared engine and the Android namespace and application id `lk.coopfed.knoweb.till`, because those are package identifiers fixed by the design documents and cited in every guide.

## Consequences

Developers see coop-erp everywhere they type a name and knoweb only inside package declarations and imports, which the guides already show. Where 17A says `knoweb` for a project, application or Makefile name, coop-erp is used; PROGRESS.md records that deviation. Reversing the decision later means a package move across every module, which is why the package root is settled now.
