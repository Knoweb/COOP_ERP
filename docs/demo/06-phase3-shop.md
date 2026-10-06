# Phase 3: the shop

**Story.** As a shop's staff member, I want the shop's own stock to fall the moment a sale happens at the till, so that head office always knows what a shop actually has, even though the till itself works offline.

`make demo-data` has already moved the first stock to the town shop; this file shows that transfer, makes a fresh one live, and then makes a till sale from a terminal (there is no Android device in this demo).

**Who signs in.** `m101-manager` (Ruwan Dissanayake, Sinhala, entity-wide) for the stores side; `m101-shop` (Malani Gunawardena, Sinhala, held to Kuliyapitiya town shop S01 only) for the shop side.

## Steps

1. **The stores' stock.** `m101-manager`: **Stock**, location "W01 Kuliyapitiya stores": every item, its batch, opening balance prepared and signed by `m101-buyer`, countersigned by `m101-manager` (this is the same opening-balance idea as file 03, already loaded here).
2. **The already-loaded transfer.** `m101-manager`: **Stock** → **Transfers** (`/inventory/transfers`), location W01: point out the transfer to S01 shows state "Received".
3. **A fresh transfer, live.** Still `m101-manager`: **Send stock to another location**. **To**: "S01 Kuliyapitiya town shop". Type a **Quantity to send** against a few lots. Click **Send**. Point out: the stores' own stock drops immediately, and the transfer shows "In transit" (a TRANSFER_OUT movement at the stores) — before the shop has done anything.
4. **The shop receives.** Sign out, sign in as `m101-shop` (held to the town shop only): **Stock** → **Transfers**: the transfer in transit to the shop. Click **Receive** — the shop's own stock rises (a TRANSFER_IN movement at the shop). Point out: this session can only see and write the shop's own rows — it cannot see the stores' stock at all, even read-only.
5. **The sale, without a till device.** In a terminal (not the browser): `make demo-till-sale`. Narrate what it does while it runs: it registers the demo till `DEMO-TILL-S01` on till position 1 of the town shop (as `m101-manager`), enrols it with a one-time code, takes its snapshot, opens a session with a float of 2,000, sells three items by barcode, closes the session, and uploads through the sync contract — printing each item's stock at the shop before and after, and the receipt number.
6. **Central sees it.** Sign back in as `m101-shop` or `m101-manager`: **Stock**, location S01 — the three sold items are down by exactly what was sold.
7. **The receipts, on screen.** Still `m101-manager` (or `m101-shop`): **Shop receipts**, choose the shop "S01 Kuliyapitiya town shop". The list shows one business day, today by default, newest first, 50 at a time (**Show more** loads the next 50). The sale from step 5 is at the top: its number (from the till position's own series), time, till, how it was paid, total; a *Flagged* chip if central flagged it, with the reason in the reader's language (for example a total that does not match its lines, or a sale outside a till session). Tick **Flagged receipts only** to show just what central questioned. Open a receipt: the lines with item names, net, tax and total, the tenders, and a link to its **session** (float, opened and closed, counted and expected cash, variance). **Till sessions** is listed by day the same way; a close whose opening never reached central reads "Opening not received". For the eight weeks of earlier sales at every shop that `make demo-data` loads (three trading days a week), pick an earlier day under **Business date**.

## Asking the stores for stock

`m101-shop` asks, `m101-manager` approves. Sign in as **m101-shop** → Stock → *Transfer requests*: this morning's request "Weekend stock for the town shop", Approved; once the stores' transfer is issued it shows *In transit* with a link to Transfers, where the shop receives it. To ask live: pick the shop, type the quantities wanted for items it sells → *Send the request*. As **m101-manager** → Stock → *Transfer requests*: choose *Send from* (the society's warehouse is preselected when it is the only one) → *Approve*, or give a reason → *Reject*. The Exception queue (reporting) shows an open claim as *Open claim* until the seller decides it.

## What to point out

- **A shop is a location, not a legal entity**: the shop (S01) belongs to the society (M101), the same way the society's own warehouse (W01) does — the difference the system enforces is only which rows a location-scoped session may touch.
- **The till writes its own record; central never merges it**: `make demo-till-sale` uploads a fact (a completed sale) through the sync contract; nothing on the central side rewrites or reconciles it — if the till says three units were sold, three units were sold, even if that takes the shop's lot negative (see below).
- **Nobody edits another's record**: `m101-shop`'s session cannot see the stores' stock at all — not filtered display, but refused by row-level security.
- **A sale is a fact even if it oversells**: selling more than the shop's system-recorded stock is *not refused* — the lot goes negative and is flagged for review, because a till sale that already happened cannot be un-happened by a central validation rule.

## What can go wrong

- **`make demo-till-sale` fails to connect**: it needs the stack up (`make up`) and the demo loaded (`make demo-data`) first; it also only works against the local stack, since it signs in with the demo users' known passwords (`COOP_ERP_API`, `COOP_ERP_TOKEN_URL` would point it elsewhere, which you should not do on a shared server).
- **Running the sale twice in the same demo**: each run is one more sale — the numbers will not reset, so if you are demoing "the shop's stock going down" a second time, expect a smaller drop than the first time, or a lot already at zero or negative from an earlier run.
- **The transfer screen shows nothing for S01**: check you are signed in with a user scoped to (or above) the stores location W01 to send, and to S01 to receive — a user scoped only to one location cannot do both halves in the same session.
