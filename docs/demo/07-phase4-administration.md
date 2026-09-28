# Phase 4: administration — dashboard, reports and the society register

**Story.** As the Federation's accounts officer, I want one dashboard and a handful of reports that read every transaction the demo has just created, so that head office sees the whole cooperative's trade and stock without asking any single warehouse for a number.

**Who signs in.** `fed-accounts` (Fathima Rizwan), screen in English, entity-wide.

## Steps

1. Sign in, go to **Reports** in the navigation — lands on the **Dashboard**. Point out the tiles: "Orders awaiting acceptance", "Deliveries in transit", "Goods received today", "Stock value at cost" — each is a live count from what was just clicked through in phases 1–3. Point out the freshness line ("Data up to …") — this is a read side fed by events, not a live query of the transaction tables, so a very fresh action can take a moment to appear here.
2. From a tile, click **Open the report**, or go to **Reports** to list all three: **Stock position**, **Trade by distributor**, **Invoices issued**.
3. Open **Stock position**. Choose a **Location** (or leave "All locations"), a date range, click **Show**. Point out the columns: Organisation, Location, item code and name, Quantity, Value at cost.
4. Click **Download CSV** — the same rows, as a file, for someone to open in a spreadsheet.
5. Click **Print (A4 PDF)** — point out the same waiting/ready pattern as the invoice's Print in phase 1 ("Printing … this takes a few seconds", then "The PDF is ready: open it").
6. Open **Trade by distributor**: who sold to whom and for how much — this is the demo's own phase 1 and phase 2 trades showing up as one table, seller and buyer both named.
7. Open **Invoices issued**: what was invoiced, to whom, and its tax — point out the invoices issued in phases 1 and 2 both appear here, with the VAT split from phase 2 visible in the totals.
8. Switch to the **society register**: sign in as `fed-steward` or another Federation-scoped user with the register permission, go to the society register screen (`/party/societies`, linked as "Society register" in the navigation for a user who holds `gov.entity.register` or the register-read permission). Point out: the register lists every society, filterable by code, name, status and district; **Register a society** opens a single form (code, type, names in three languages, registration and VAT numbers, district, default language, financial year start month); **Upload a CSV file** registers many societies at once, all-or-nothing — a bad row anywhere means nothing is registered and the report says what to fix.

## What to point out

- **Everything reported, not just recorded**: every document from phases 1–3 (the orders, deliveries, GRNs, invoices, transfers, the till sale) is what these reports are built from — nothing here is separately entered.
- **CSV and PDF for two different audiences**: CSV for someone who wants to work the numbers further; the printed PDF for a paper record or a meeting.
- **Permissions per role**: `fed-accounts` sees the reports but not the society register's register-a-society form unless also holding `gov.entity.register` — point this out rather than assuming everyone with a Federation login can do everything.

**Not in this demo yet: user and role management screens.** `docs/DEMO.md`'s cast of demo users and their narrow "Demo: ..." roles are seeded directly (`seed/m1security/demo-users.demo.sql`), not created or assigned from a screen. As of 28 September 2026, `web/src/modules/m1party` has only the society register (list, register one, bulk-register by CSV, and a society's own card) — no screen to create a user or assign a role. If M1's user/role screens have since been merged to `main`, read that module's pages and messages file and replace this paragraph with the click path.

## What can go wrong

- **A report shows nothing for a phase you just walked through**: the read side updates from events a short time after the write; wait a few seconds and click **Show** again before assuming the phase failed.
- **CSV download does nothing visible**: check the browser's own download location — it is a file save, not a screen change.
- **Print never becomes ready**: same background-worker wait as the invoice's Print (phase 1) — give it a few seconds.
- **The society register screen is missing "Register a society"**: the signed-in user does not hold `gov.entity.register` — the screen still shows the register (read), just not the create form; this is by design, not a bug, and is itself worth pointing out as a permissions example (see file 08).
