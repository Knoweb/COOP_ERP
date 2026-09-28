# Stock: the stock position, and an opening balance with sign and countersign

**Story.** As a warehouse's own stores officer, I want to load and record the opening stock of my warehouse with two people's sign-off, so that every quantity we hold from day one is provable and no single person can invent stock.

**Who signs in.** `fed-stores` (Kamal Jayasinghe), screen in **Sinhala** — narrate the buttons in English from this script, but say out loud that the screen itself is Sinhala. Then `d101-stores` (Lasantha Herath, Sinhala) to show a second warehouse. For the live "prepare an opening balance" part, sign in as an MPCS user with stores permission (for example `m101-buyer`, Sinhala) at a warehouse that has none yet, since the Federation and both distributors' opening stock is already loaded.

## Steps

1. As `fed-stores`, go to **Stock** ("තොගය"). Choose **Location**: Federation central warehouse (FW01). Point out the table: item, **Batch** (DEMO-2026-*n*), **Expiry**, **Printed MRP**, **Condition** (Good/Damaged), **On hand**, **Available**, and **Pick order** — the FEFO order stock will be picked in.
2. Sign out, sign in as `d101-stores`, choose the Kurunegala warehouse (W01) — the same screen, this warehouse's own stock, loaded and signed off the same way.
3. Live: sign in as `m101-buyer` (or whichever user has not yet prepared an opening balance for their location), go to **Stock** → **Load opening stock**. Choose the **Location**, **Find an item by code or name**, add two or three items, type a **Quantity** and a **Unit cost** for each, click **Prepare**.
4. The balance lands in state "Prepared". Point out the two buttons this state offers: **Sign** (the person who counted it) and, once signed, **Countersign and post** — and that the screen requires "another person than the signer" to countersign.
5. If the development identity realm accepts a fresh password sign-in as the second factor (it does, in this demo environment only — see `docs/DEMO_SETUP.md`), click **Sign**, then sign out and back in as a second person from the same entity, open the same balance, click **Countersign and post**. State becomes "Posted" and the stock now shows in the location's stock position.

## What to point out

- **Nobody edits another's record, and no single person posts stock alone**: the balance needs a signer and a different countersigner before a single unit is on the books — this is the same idea as the GRN later (ownership only passes on a confirmed act, never a silent edit).
- **A shop is a location, not a legal entity**: the same stock-position screen works for a Federation warehouse, a distributor's warehouse, a society's stores, or (in phase 3) a shop — one screen, permission-filtered by location.
- **Everything audited**: prepare, sign and countersign are each their own command, each with its own audit record.

## What can go wrong

- **"This location already has an opening balance"**: a location takes exactly one opening balance ever; if you need a fresh one for a demo, pick a location that has not been loaded yet, or reset the stack (`make reset && make up && make demo-data`).
- **Countersign is refused as the same user who signed**: the check is deliberate — pick a genuinely different user of the same entity.
- **"Your account cannot list locations"**: the signed-in user has no location-scoped permission at all; sign in as one of the users named in `docs/DEMO.md`'s table instead.
- **Sign or countersign asks for a second factor you cannot supply**: outside this development/demo environment a fresh password sign-in does not count as the second factor (see `docs/DEMO_SETUP.md`, section 4) — this step then needs a real one-time code, which the demo realm does not issue.
