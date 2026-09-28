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
8. Switch to the **society register**: sign in as `fed-steward` (who holds the register permission through "Demo: administration"), go to the society register screen (`/party/societies`, linked as "Society register" in the navigation for a user who holds `gov.entity.register` or the register-read permission). Point out: the register lists every society, filterable by code, name, status and district; **Register a society** opens a single form (code, type, names in three languages, registration and VAT numbers, district, default language, financial year start month); **Upload a CSV file** registers many societies at once, all-or-nothing — a bad row anywhere means nothing is registered and the report says what to fix.
9. **Users and roles**: sign in as `fed-steward` (Nirmala Perera). The navigation now shows **Users**, **Roles** and **External access** beside the **Society register**. Other users do not see these entries at all: an entry the user cannot open is hidden, not shown and inert.
   - **Users → New user**: enter the user name (for example `fed-clerk2`), the name shown, the language and the kind (Back office), then **Create user**. The user's card opens in **Pending**. Point out that a new user has no password and no role yet.
   - **Issue a temporary password**: the password is shown once, to be handed over in person. The status turns **Active**. At the first sign-in the user must choose a new password.
   - **Give a role**: choose **Demo: accounts** and, under **Where**, the Federation warehouse (or "The whole organisation"), then **Assign role**. The role appears under "Roles and where they apply" with **Revoke** beside it.
   - Sign in as the new user in a private window. The navigation shows Trading and Reports and nothing the role does not allow. Typing `/party/users` by hand shows "not allowed".
   - **Separation of duties**: giving a user a role whose duties clash with one the user already holds is refused by the server. The card shows "Separation of duties:" and the server's reason. Point out that this is a rule about the person, not an error.
   - **Roles**: each role with its permission codes. Templates are listed last, and they are read only in this version.
   - **External access**: the Federation's register of time-boxed read-only grants for auditors. Grant (an external user, the organisations, an end date, a reason) and **End now**.
   - **Deactivate** (on a user's card): asks for a reason, signs the user out everywhere, and cannot be undone.

## What to point out

- **Everything reported, not just recorded**: every document from phases 1–3 (the orders, deliveries, GRNs, invoices, transfers, the till sale) is what these reports are built from — nothing here is separately entered.
- **CSV and PDF for two different audiences**: CSV for someone who wants to work the numbers further; the printed PDF for a paper record or a meeting.
- **Permissions per role**: `fed-accounts` sees the reports but not the society register's register-a-society form unless also holding `gov.entity.register` — point this out rather than assuming everyone with a Federation login can do everything.


## What can go wrong

- **A report shows nothing for a phase you just walked through**: the read side updates from events a short time after the write; wait a few seconds and click **Show** again before assuming the phase failed.
- **CSV download does nothing visible**: check the browser's own download location — it is a file save, not a screen change.
- **Print never becomes ready**: same background-worker wait as the invoice's Print (phase 1) — give it a few seconds.
- **The society register screen is missing "Register a society"**: sign in as `fed-steward`, who holds `gov.entity.register` through "Demo: administration". `fed-accounts` does not have it, by design.
