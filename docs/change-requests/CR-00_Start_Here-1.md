# CR-00_Start_Here-1 — File list names the wrong format and folder for the two live registers

- **Status:** accepted (architect, 2026-09-20); the documents are not re-issued yet
- **Date raised:** 2026-09-18
- **Raised by:** Claude Code session, while checking the 18 September document update against the register
- **Documents affected:** `00_Start_Here.docx` v1.1 §3 (the file list) and the "which is which" table
- **Code affected:** none

## What is wrong or missing

Section 3 of 00_Start_Here v1.1 lists "every file in the folder" and includes:

> `00_Knoweb_Document_Register.pdf` — Document register: the authoritative list of documents and current versions
> `10_Open_Items_Register.docx` — Live register of open decisions by decider, each with the assumption in force

The register (00 v0.5, 18 September 2026) says of itself: "Design and implementation documents are issued as PDF; the live registers (00, 10) and the folder entry point (00_Start_Here) are Word documents", and lists both `00_Knoweb_Document_Register.docx` and `10_Open_Items_Register.docx` under section 1, the requirements baseline folder. In the repository they live in `docs/requirements/`; on Drive they live in the baseline folder. So 00_Start_Here names the register with the wrong extension and places both registers in the wrong folder.

## Why it matters

A new developer following 00_Start_Here looks for the register and the open-items register in the design folder and does not find them. The register is the document that decides versions, so its location has to be stated correctly in the entry point.

## Proposed change

In 00_Start_Here §3, change the two rows to `00_Knoweb_Document_Register.docx` and `10_Open_Items_Register.docx` and add "(requirements baseline folder; in the repository `docs/requirements/`)" to both. Keep the rest of the list unchanged.

## Impact if accepted

00_Start_Here re-issued as v1.2 from a Markdown source under `docs/sources/`, register row for 00_Start_Here bumped to v1.2, Drive copy replaced. No code or extract change beyond the regenerated extract being deleted in favour of the source.
