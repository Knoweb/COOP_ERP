# Demo flows for presenting to cooperative staff

Step-by-step scripts for showing the system to people who will use it, not to developers. Each file in this folder is one story: who signs in, what to click, what to say, and what to do if something goes wrong. `docs/DEMO.md` is the technical reference behind these scripts (what `make demo-data` loads, the full users table, the storyline in prose); this folder is the presenter's script.

## Before you present

1. Reset and reload: `make reset && make up && make demo-data`. `make reset` also wipes the identity server, so it re-imports the demo users — do this the day of, not a week before, in case anyone has clicked into a document and changed its state (nothing here is undone by re-running `make demo-data`, but a cleaner history reads better).
2. If you pulled a change since your last reset and it added a web package (a new dependency in `web/package.json`), restart the web container so it picks it up: `docker compose -f infra/compose/compose.yml restart web`.
3. Open http://localhost:5173 once yourself and sign in as `fed-steward` / `demo` to check the stack answers before the audience is watching.
4. Have the users table open in a second tab: `docs/DEMO.md`, section "Who is who". Every demo user's password is **demo**.
5. Decide who is "driving" (typing and clicking) versus "narrating" (talking to the audience). Switching users means signing out and back in — do it in the same browser tab, or keep a private window per user if you want two logged in side by side (needed for "languages and permissions").

## Order of a full demo (about 45–60 minutes)

A run through everything that works today, in the order that builds the story:

| # | File | Minutes | What it shows |
|---|---|---|---|
| 1 | [01-catalogue.md](01-catalogue.md) | 5 | Browsing items in three languages, creating and sharing a new one |
| 2 | [02-pricing.md](02-pricing.md) | 5 | The Federation's trade price list, tiers, publishing |
| 3 | [03-stock.md](03-stock.md) | 5 | Stock position, opening balance with sign and countersign |
| 4 | [04-phase1-federation-to-distributor.md](04-phase1-federation-to-distributor.md) | 12 | The full order-to-invoice chain, Federation selling to a distributor |
| 5 | [05-phase2-distributor-to-society.md](05-phase2-distributor-to-society.md) | 8 | The same chain one level down, distributor selling to a society |
| 6 | [06-phase3-shop.md](06-phase3-shop.md) | 8 | Stock moving from the society's stores to a shop, and a till sale |
| 7 | [07-phase4-administration.md](07-phase4-administration.md) | 8 | The dashboard, the three reports, CSV and PDF, the society register |
| 8 | [08-languages-and-permissions.md](08-languages-and-permissions.md) | 5 | Switching languages, a user who cannot see a screen |
| 9 | [09-society-credit-book.md](09-society-credit-book.md) | 6 | The society's members and credit book: accounts, statements, repayments at the office |
| 10 | [10-stock-control.md](10-stock-control.md) | 8 | Counts with approval, witnessed write-offs, repacking loose rice into society packs |

Run them in this order for a full demo; each file also stands alone if you only need to show one part. Files 4 and 5 are the heart of the story — do not cut them short if time is tight; cut file 7 or 8 instead.

## The demo users

The full table (name, entity, scope, language, job in the story) lives in `docs/DEMO.md`, "Who is who" — do not duplicate it here, it would go stale. Every file below names which user signs in for each step and which language they see.

## What is marked "not in this demo yet"

A few things the design describes are not built, or not loaded by `make demo-data`, as of this writing (28 September 2026). Each demo file says so at the point it would otherwise come up, with a one-line reason, instead of describing a screen that does not exist. The current list is short: the till itself (the demo uses the till simulator), and the parts each file names under "Not here yet".
