# PROGRESS.md — where the work stands

Progress lives in `docs/progress/`, one file per entry, so two branches never edit the same file. This page explains the folder and is not edited per ticket.

- **Done:** `docs/progress/done/<yyyy-mm-dd>-<branch-slug>.md`, one bullet per ticket, in the style of its neighbours (what was built, the guide sections it follows, the branch). The branch slug is the branch name with `/` as `-` (`feat/m4-02-orders` is `2026-09-27-feat-m4-02-orders.md`).
- **Deviations:** `docs/progress/deviations/<yyyy-mm-dd>-<slug>.md`, one bullet per departure from a guide or document, with the reason and the ADR or change request that records it. A second deviation of the same branch adds `-2`, `-3`.
- **Next:** `docs/progress/NEXT.md`, the only hand-edited shared page, changed when the plan changes, not per ticket.

Entries from before the split whose text carries no date are named `0000-legacy-<nn>-<slug>.md`, in their old order. Every entry's text was kept as it was.

To read everything in one page: `make progress` (or `node tools/progress-index.mjs`) writes `build/PROGRESS.md`, the three headings with the entries in date order. It is a build output, never committed; the pipeline runs the same script, which fails on a badly named entry.

A person or tool arriving cold reads `AGENTS.md`, then `docs/README.md`, then `docs/progress/NEXT.md` and the newest entries, and can continue.
