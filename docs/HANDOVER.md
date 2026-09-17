# HANDOVER.md — from the design conversation to this repository

Date: 17 September 2026. Read once at the start; `AGENTS.md` holds the standing rules; `docs/DECISIONS_PENDING.md` the assumptions.

## 1. Where things stand

- Requirements baseline: 16 documents (00–13, 15, 16) at v0.2, plus change requests of 3 September 2026 not yet folded in. Drive folder `1DKsOlf4k8vlOJCjp2oVaEQlg-XCs1fgh`.
- Design phase: complete on paper for Stage 1 (14, 17, 18, 19, 20, 30, 31, 32), Sprint 0 guides (17A, 19A), and all nine module pairs (21–29 with 21A–29A) plus 24B. Drive folder `14pTgorZIGEZ9FrJGlmhaLy6Eh1o5FdFj`. Entry document: `00_Start_Here` (folder guide plus developer start guide).
- Not yet written: 34 Security Architecture and Threat Model, 35 Infrastructure and Deployment, 36 Observability/Backup/DR, 37 Data Migration and Onboarding Tooling, 38 Operations and Support Runbook, 39 Test Strategy, 40 Traceability and Build Readiness. 33 (API Specification) is a **build artefact** produced by the bundle assembler in 29A §7 — it is never written by hand.
- Code: repository `https://github.com/Knoweb/COOP_ERP` (main branch) exists and is essentially empty. Nothing has been generated or compiled yet. The decision on 10 September was to start the codebase from 17A now.

## 2. Corrections the Drive folder needs before you rely on it

The folder is behind the register. Treat these as stale until replaced:

| File | Problem | Fix |
|---|---|---|
| `18_Core_Data_Model.pdf` | Folder copy is v0.2 (7 Sep). v0.3 (10 Sep) is the model of record: applies CR-21…CR-29, adds Part F schema register, `has_printed_mrp`, lot `condition`, `supplier`, `location_assortment`, CPR as an M7 document | Upload v0.3 |
| `14_Technical_Foundations.pdf` | Never uploaded | Upload v0.2 |
| `22A_M2_Implementation_Guide.pdf` | Never uploaded | Upload v0.1 |
| `00_Knoweb_Document_Register.pdf` | Folder has none; v0.4 exists | Upload v0.4, then re-issue as v0.5 listing `00_Start_Here` |
| `10_Open_Items_Register.docx` | Never uploaded (v0.3, Word) | Upload |
| `00_Start_Here` | Folder copy predates section 12 (non-developer reading paths) | Upload v1.1 |

Until the uploads happen, the versions in `docs/design/` in this repository (to be committed with this handover) are the current ones.

## 3. Decisions taken in conversation that are not yet in any document

Record these in `docs/adr/` (mirroring doc 09) when you first touch the area.

1. **Codebase starts from 17A now.** Sprint 0 skeleton is generated as files and verified by the platform pair on first build. Repository: `Knoweb/COOP_ERP` on GitHub (CI is GitHub Actions).
2. **Document sources live in this repository from now on** under `docs/sources/` (Markdown or generator script); PDFs are rendered into `docs/design/` and uploaded to Drive; the register is bumped on every change. Reason: the PDF generators used for docs 14–32 were lost in a tooling reset and no source survives — every current PDF is content-only. Re-issuing any of them means re-authoring from the PDF.
3. **Doc 18 is issued as one document**; addenda (nB) close specific points only (24B). 18B was withdrawn.
4. **Live registers are Word (10, 12); everything else PDF.**
5. **`00_Start_Here` is the single folder entry point**; the earlier README was deleted and merged into it.
6. **Numbering (24B)** was decided by the programme without waiting for the tax advisor: no financial-year reset, GRN non-fiscal, multi-series per entity, voids keep numbers, discrepancy/claim non-fiscal, indefinite retention. The advisor's review, if it arrives, is a change request.
7. **CR-27-2**: `payer_kind = CUSTOMER` was removed from M4's `trading.doc_payment_receipt` (24A §3); customer repayments are M7 `CPR` documents.
8. **ADR-37 to ADR-50** are listed in doc 18 v0.3 §11 and have not yet been entered into doc 09.
9. **Team**: fresh graduates and trainees, roughly ten, composition tentative. The platform pair and the first M1 developer are not yet named (doc 10 P-03). Modules are handed over one at a time; Claude Code and other coding assistants are expected to do much of the typing.

## 4. Things I know about the documents that you should know too

- Every nA guide is self-contained by design: material from 17, 18, 19, 30, 32 is restated inside it and marked "restated from doc X §Y". If a restatement and its source disagree, the source has probably moved; check the register and raise it.
- Each module design §5 (contract) freezes when a dependant designs against it; none has formally frozen yet because no code exists. The freeze happens at the ticket named `<module>-last` in each guide ("contract freeze").
- Stubs are named in each guide's §11 for dependencies on modules not yet built (M3's price-list query for M2, M5's lot queries for M2/M3/M4, etc.). Build to the stub; replace when the real module lands (25A tickets M5-02…M5-04 replace most of them).
- Cost of goods sold in M8's `sales_daily` needs `unit_cost_at_movement` to travel in the receipt bundle from the till (noted in 28A §11 for the M5/M6 developers).
- The doc 07 configuration register still contains a "discount cap" item and a supervisor price override that were rescinded by ADR-14; they must not be implemented.
- The seven prototype spikes (doc 14 §8) have no documents; they close as ADRs or module changes. Spike 1 (power-loss issuance on the till) and spike 5 (sync load) gate the sync tunables in doc 10 E-06.
- Doc 20 v0.3's schedule (to 6 November 2026) was a planning estimate before any code; it was to be re-planned after 21/21A were measured. Treat dates as unmeasured.

## 5. Environment notes

- The design conversation could not compile Java (no Maven Central access). This environment can; the first `make up` and `make test` on the generated skeleton will surface dependency-version and compile errors — expect about a day of fixing, which is inside the 17A estimate.
- The docx files (10, 00_Start_Here) were produced with `docx-js`; the PDFs with reportlab (sources lost). Going forward use Markdown sources rendered by a single script kept in `docs/tools/`.

## 6. Immediate tasks, in order

1. Commit `CLAUDE.md`, `docs/HANDOVER.md`, `docs/DECISIONS_PENDING.md`, and the current design PDFs into `docs/design/` (including the six files the Drive folder lacks).
2. Generate the Sprint 0 skeleton exactly per 17A §2–§13: repository tree, Gradle version catalogue, Spring Boot app with `web`/`worker`/`ingest` profiles, kernel package with interfaces and stubs, Modulith descriptors, `ArchitectureTests.java`, baseline Flyway migration (schemas, roles, ICU collations, RLS helpers), hello module end to end, shared-engine skeleton, web shell scaffold, till scaffold, docker compose with dev realm, Makefile, CI, `make new-module`. Definition of done: the fifteen-minute start in 17A §13 works on a fresh clone.
3. 19A kernel services K-01…K-13, replacing stubs; the hello module's integration tests stay green throughout; the doc 32 conformance suite (§11) passes against the till simulator.
4. Only then M1 (21A) — its contract freeze at ticket M1-13 lets M2 start.
5. In parallel, when asked: docs 34–40 as Markdown sources under `docs/sources/`, rendered to PDF; register updates.

## 7. Open questions for the architect (not for you to decide)

Everything in `docs/DECISIONS_PENDING.md`. The ones Sprint 0 and M1 will hit first: E-01 identity provider, E-02 broker, E-03 component library, E-05 PIN/MFA/numerals, E-07 A4 rendering engine, F-03/F-04/F-05 M1 defaults, L-07 grant duration, P-03 team allocation.
