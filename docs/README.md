# docs/ — how the documentation is organised and maintained

Operating instructions for everything under `docs/`, for people and for every AI tool. The working rules for the code are in [`AGENTS.md`](../AGENTS.md); this file only covers the documents. Read it once, then use it as a reference.

## 1. What is where

```
docs/
  DECISIONS_PENDING.md     doc 10 Open Items Register in Markdown: the assumption in force for every open decision
  PROGRESS.md              done / next / deviations, updated after every ticket; the resume point for any person or tool
  README.md                this file
  requirements/            the requirements baseline: the register 00 (Word, live), 01–13 (Word), 15–16 (PDF), plus txt/ extracts
  design/                  the system design: 14, 17–32, the nA guides, 24B, 00_Start_Here (Word), plus txt/ extracts
  sources/                 Markdown sources of documents revised or created from this repository onward
  tools/                   extract-text.sh (extracts) and the render script (Markdown → PDF)
  adr/                     architecture decision records, one file each, mirroring doc 09 (TEMPLATE.md)
  change-requests/         change requests against documents, one file each (TEMPLATE.md)
```

## 2. Numbering and file-name conventions

Every document has a number. The number is an identity, never a reading order.

| File name pattern | What it is |
|---|---|
| `00_Knoweb_Document_Register.docx` | The register, a live Word document kept in `docs/requirements/`: which version of every document is current. It wins every version dispute. |
| `00_Start_Here.docx` | The entry point to the design folder: reading paths by role and the developer start guide. |
| `01`–`13` | Requirements baseline (Word): vision, domain model, state machines, process flows, SRS, NFR, matrices, traceability, decisions log (09), open items (10, live), business primer, glossary (12, live), worked scenarios. |
| `15`, `16` | Clarification questionnaire and its answers (PDF). |
| `14`, `17`–`32` | System design: 14 Technical Foundations, 17 Solution Architecture, 18 Core Data Model, 19 Kernel Services, 20 Stage 2 plan, 21–29 the nine module designs (M1–M9), 30 Frontend, 31 Updates, 32 Sync Contract. |
| `<n>A_…` | Implementation guide for design document n (17A, 19A, 21A–29A). Self-contained: a developer starts from it alone. |
| `<n>B_…` | Addendum to design document n; closes a specific point without changing the design (24B numbering). |
| `33` | API Specification: a build artefact produced by the bundle assembler in 29A §7. Never written by hand; not a file in this folder. |
| `34`–`40` | Operational design and build readiness: not yet written. |

Versions live inside the document (cover block and page footer) and in the register, not in the file name. `v0.x` is a draft for design review; `v1.0` is decided. A re-issued document keeps its file name; the previous version stays in git history and is moved to trash on Drive.

## 3. Issued documents and their published copies

- The PDF and Word files in `docs/design/` and `docs/requirements/` are the **issued documents**.
- The Google Drive folders are their **published copies** for people outside the repository: requirements baseline `1DKsOlf4k8vlOJCjp2oVaEQlg-XCs1fgh`, system design `14pTgorZIGEZ9FrJGlmhaLy6Eh1o5FdFj`.
- If a folder copy and a repository copy differ, the register decides which version is current; the other one is replaced. If the register names a version that is in neither place, record it in `docs/PROGRESS.md` under deviations and raise it; do not guess.

## 4. Text extracts (`txt/`): what tools read

`docs/requirements/txt/` and `docs/design/txt/` hold one `.txt` per document, same base name. They exist so that people can search the documents and AI tools can read them without parsing PDF or Word files.

- They are **read-only reference**. Nobody edits them. A correction goes into the document (section 6), never into the extract.
- They are **generated**, by one script: `docs/tools/extract-text.sh`. PDF → `pdftotext -layout -enc UTF-8 -eol unix` (Xpdf 4.x or Poppler); Word → `docx2txt`. Output is UTF-8 with LF line endings on every platform (`.gitattributes` enforces LF).
- They are regenerated **only when the document changes**, and committed in the same commit as the new PDF or Word file. Run the script with the changed file as argument; running it with no arguments regenerates everything, which should produce no diff.
- Layout mode keeps tables readable but repeats page headers and footers; quote the section number, not the page.
- A document that has a Markdown source in `docs/sources/` has **no extract**: the source is what tools read (section 6).

## 5. Sources (`docs/sources/`) and rendering

The PDFs of documents 14–32 and the guides were rendered with reportlab, and the Word files 10 and 00_Start_Here with docx-js, by generator scripts that were lost before the handover; those files are content-only. From now on:

- Any document that is revised or created has a Markdown source `docs/sources/<same base name>.md`. The source is the maintained text; the PDF is rendered from it and is never edited by hand.
- One script in `docs/tools/` renders a source to `docs/design/<same base name>.pdf` (or `docs/requirements/` for baseline documents). The render script does not exist yet; it is added with the first document revised from this repository, and the choice of renderer is recorded in `docs/adr/`. Until it exists, no document is re-issued from here.
- Sources keep the document's cover block (number, title, version, date, status, inputs) and its section numbering, so that references such as "24A §3.2" stay valid.
- One source lives outside `docs/sources/`: doc 10's is `docs/DECISIONS_PENDING.md`, kept at the docs root so that code comments can cite its item ids by a short path. It must carry the doc 10 version the register names; the Word file is its published form.

## 6. Procedure: revising or creating a document

1. **Create the source.** For an existing document, copy `txt/<name>.txt` to `docs/sources/<name>.md` and turn it into clean Markdown: headings, tables, code blocks; remove the repeated page headers and footers. For a new document, start from the cover block of a neighbouring document of the same kind.
2. **Edit** the source. Bump the version in the cover block and footer text and add a line to the document's own change log if it has one.
3. **Render** with the script in `docs/tools/` to the document's folder, replacing the old PDF (same file name).
4. **Bump the register.** Update `00_Knoweb_Document_Register` (its own source, rendered the same way) with the new version, date and status.
5. **Upload** the new PDF to the Drive folder, moving the superseded copy to trash.
6. **Delete the extract** `txt/<name>.txt`: the source is now what tools read. Run `docs/tools/extract-text.sh` for the file to confirm it is skipped.
7. **Commit** source, PDF, register and the deleted extract together: `docs(<n>): <what changed> (v<x.y>)`.

## 7. Change requests (`docs/change-requests/`)

A change request is how a problem in a document is raised. Write one when a document contradicts another, when a rule cannot be built as written, or when you believe the design is wrong. Do not fix the problem locally in code; the same problem exists in the documents and in other modules.

- One file per request, named `CR-<doc>-<n>.md` (for example `CR-24-3.md` for the third request against document 24), from `TEMPLATE.md`.
- Status moves `raised → accepted | rejected → applied`. Only the architect accepts. When applied, the affected documents are re-issued by the procedure in section 6 and the request records their new versions.
- Requests accepted before this repository existed (CR-21-1 to CR-29-x) are already applied in doc 18 v0.3.1; they are not repeated here.

## 8. Architecture decision records (`docs/adr/`)

Decisions already taken are numbered ADR-01 onward in doc 09 (requirements baseline) and are cited by number in the design documents; ADR-37 to ADR-50 are listed in doc 18 v0.3.1 §11 and not yet entered in doc 09.

- One file per decision, `ADR-<nnn>-<short-title>.md` (three digits), from `TEMPLATE.md`.
- Mirror an existing doc 09 decision here the first time code touches its area, so the reason is beside the code. Record a **new** decision here first, with the next free number, and enter it in doc 09 at its next revision.
- Small choices made during the build, such as a library or a version that 17A leaves unspecified, are one-paragraph ADRs too. A decision that closes an item in `docs/DECISIONS_PENDING.md` names the item id and updates that file in the same commit.

## 9. Progress (`docs/PROGRESS.md`)

Three headings, always current: **Done** (tickets completed, with commit references), **Next** (the next ticket and anything blocking it), **Deviations** (every departure from a guide or document, with the reason and the ADR or change request that records it). Update it after every ticket, in the same commit. A person or tool arriving cold reads `AGENTS.md`, then this file, then `PROGRESS.md`, and can continue.

## 10. When documents disagree

The register decides which version of a document is current. Between documents of the current versions, the design document's *reasoning* and the implementation guide's *procedure* both hold; a guide restates material from 17, 18, 19, 30 and 32 and marks it "restated from doc X §Y", and if a restatement and its source disagree, the source has probably moved. In every case: stop, write a change request (section 7), and raise it. Never pick one silently.

## 11. Reading paths

- **New developer taking a module:** `00_Start_Here` → `AGENTS.md` → your module's nA guide, end to end → the module's design document when you need the reason for a rule.
- **Platform pair (Sprint 0):** `17A` then `19A`, with `19`, `18` (Parts C–F) and `32` beside them.
- **Anyone checking a decision:** `DECISIONS_PENDING.md` for open ones; doc 09 and `docs/adr/` for taken ones.
