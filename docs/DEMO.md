# DEMO.md: the demo for cooperative staff

What the demo shows, how to set it up, who signs in with what, and the click path of phase 1. Built by DEMO-01 (27 September 2026). The data is Sri Lankan: two distributors in Wayamba and the North, three multi-purpose cooperative societies (MPCS), four shops, forty everyday goods with names in English, Sinhala and Tamil.

## Set it up

```sh
make up            # the local stack (once; it also loads the development seed)
make demo-data     # the demo: safe to repeat, a second run changes nothing
```

A clean demo, from nothing: `make reset && make up && make demo-data`. `make reset` also resets the identity server, which re-imports `infra/compose/realm-dev.json` with the demo users; a stack started before DEMO-01 needs it once, or the demo users cannot sign in.

What `make demo-data` does:

1. `make seed`, then the demo's parties and users as rows (`seed/m1party/demo-parties.demo.sql`, `seed/m1security/demo-users.demo.sql`). These are rows and not commands because the identity server's token names the user's home entity (the `ent` attribute of the realm), so the entities need ids fixed in advance, as the development seed already does.
2. A one-off backend container (`COOP_ERP_DEMO_LOAD=true`) runs `lk.coopfed.knoweb.demo.DemoDataLoader` and exits. The loader issues every other part of the demo through the modules' own command handlers, each as the demo user whose job it is, with the permission check on. So every row has its audit record, its event, its document number and its stock movements, exactly as if a person had done it on the screens. It asks each module first and skips what is already there.
3. Last, the till history (DEMO-02b, below): `./gradlew :app:demoTillHistory` on the host, the till simulator selling at the four shops through the sync contract. It needs Java 21 on the host, as `make demo-till-sale` does.

What is loaded:

| Part | Through | Who issues it |
|---|---|---|
| Till positions (5) and the primary till of each of the 4 shops, with their numbering series | M1 `RegisterTillPosition`, `SetPrimaryTill` | each society's manager |
| 40 shared SKUs (rice, dhal, sugar, flour, oil, milk powder, tea, soap, spices...), 37 pack conversions (bag, case, dozen), 37 EAN-13 barcodes, tax categories STD and EXEMPT | M2 `CreateSku`, `ActivateSharedSku`, `DefineConversion`, `RegisterBarcode` | `fed-steward` |
| The Federation's trade list to distributors, two price tiers per SKU (from 0 and from 100) | M3 `CreatePriceList`, `SetLines`, `PublishPriceList` | `fed-pricing` |
| Each distributor's trade list to its societies (tiers from 0 and from 20) | M3, the same commands | `d101-buyer`, `d102-buyer` |
| Relationships Federation to D101 and D102; D101 to M101 and M102; D102 to M103; all ACTIVE on the lists above | M1 `OpenTradingRelationship`, `ActivateRelationship` | the seller: `fed-pricing`, `d101-buyer`, `d102-buyer` |
| Opening stock of every SKU at the Federation warehouse and at both distributors' warehouses, batches with expiry and printed MRP | M5 `PrepareOpeningBalance`, `SignOpeningBalance` (stores), `CountersignOpeningBalance` (a second person, accounts) | `fed-stores` then `fed-accounts`; `d101-stores` then `d101-accounts`; `d102-stores` then `d102-accounts` |
| Phase 3: Kuliyapitiya MPCS's opening stock at its stores (M101 W01), half a distributor's quantities at the Wayamba price | M5, the same three commands | `m101-buyer` then `m101-manager` |
| Phase 3: the town shop's first stock (M101 S01), 23 everyday packed items, 26 to 48 of each, sent from the stores and received there | M5 `IssueTransfer` (at the stores), `ReceiveTransfer` (at the shop, by the shop's own session) | `m101-manager`, then `m101-shop` |
| Phase 3: the Hettipola shop's first stock (M101 S02), 16 items, 12 to 30 of each, sent from the stores a month later and received there | M5 `IssueTransfer`, `ReceiveTransfer` (the shop has no staff user of its own, so the manager, entity-wide, receives) | `m101-manager` |
| Phase 3: the Pannala (M102) and Point Pedro (M103) shops' opening stock, 20 items each, 12 to 48 of each, counted at the shop (these societies have no stores: their distributor delivers to the shop) | M5 `PrepareOpeningBalance`, `SignOpeningBalance` (the shop's staff), `CountersignOpeningBalance` (the manager) | `m102-shop` then `m102-manager`; `m103-shop` then `m103-manager` |

Each shop's range and quantities are in `lk.coopfed.knoweb.demo.DemoShopStock`: enough for the eight weeks of the till history below, with stock left on the shelves at the end.

### The trading history (DEMO-02)

Last, the loader leaves a history of trading so that the Trading, Reports and dashboard screens open with documents in them (`lk.coopfed.knoweb.demo.DemoTradingHistory`). Every document is issued through M4's handlers as the user whose job it is: the buyer orders and submits; the seller's sales user accepts and drafts and issues the delivery note from the seller's stores; the seller's stores user dispatches; the buyer's receiver counts and confirms the GRN (every third order has its last line a tenth short, which raises a discrepancy); the seller's accounts user invoices.

| Relationship | Orders | Buyer / receiver | Seller: sales, stores, accounts |
|---|---|---|---|
| Federation to D101 (to Kurunegala W01) | 12 | `d101-buyer` / `d101-stores` | `fed-sales`, `fed-stores`, `fed-accounts` |
| Federation to D102 (to Jaffna W01) | 8 | `d102-buyer` / `d102-stores` | `fed-sales`, `fed-stores`, `fed-accounts` |
| D101 to M101 (to Kuliyapitiya stores W01) | 8 | `m101-buyer` | `d101-buyer`, `d101-stores`, `d101-accounts` |
| D101 to M102 (to Pannala shop) | 8 | `m102-manager` | `d101-buyer`, `d101-stores`, `d101-accounts` |
| D102 to M103 (to Point Pedro shop) | 8 | `m103-manager` | `d102-buyer`, `d102-stores`, `d102-accounts` |

**Left open for the live walk.** The last four orders of each relationship stop part-way: one **submitted** (to accept at the order desk), one **accepted** (to put on a delivery note), one **in transit** (to receive with a GRN), one **received** (to invoice). The others, 24 in all, are invoiced. Each history order's notes read `Demo history <relationship> week n no. k`; that is how a second run finds them and moves each on from where it stands.

**Dates.** The demo opens for business 60 days before `make demo-data` runs (catalogue, price lists, relationships, opening stock and the town shop's transfer carry that date; the Hettipola transfer is 30 days ago), and the history spreads over the eight weeks since: the first order of each relationship was placed 55 days ago, the last (the submitted one) today, the others evenly between, all five relationships interleaved in date order. Each step of an order has its own later day: ordered and submitted at 09:00, accepted the next day, on a delivery note and dispatched the day after, received (GRN) two days later, invoiced the day after that. So Reports by period and the dashboard show a trend, and every numbering series numbers its documents in date order. A step that would fall later today than the load runs at the time of the load.

How: the loader runs each step through the ordinary handlers inside `kernel.api.HistoricalTime`, which moves the application's clock, and each location's business date, back on the loader's own thread only. It is refused unless `coop-erp.demo.historical-time` is true, which only the one-off `make demo-data` container sets (`COOP_ERP_DEMO_LOAD`); no controller or job can reach it, and an architecture rule allows only the demo package to use it. Nothing is written to the database behind the handlers: numbering, audit, events and projections are what they would have been on those days. Event listeners on other threads (M5's stock ledger from `grn.confirmed.v1`) record their own rows at the real time.

### The till history (DEMO-02b)

After the loader, `make demo-data` sells at the four shops over the same eight weeks (`lk.coopfed.knoweb.demo.DemoTillHistory`, test sources, run on the host), so that the receipts screen, the till sessions and the shops' stock have sales in them. Receipts are the till's to write, never central's (AGENTS.md, idea 3), so the loader cannot write them and `HistoricalTime` is not the tool: the history goes the way a real till's sales do, through the sync contract, with the till simulator of `make demo-till-sale`.

| Shop | Demo till | Enrolled by | Sale days |
|---|---|---|---|
| Kuliyapitiya town shop (M101 S01) | `DEMO-TILL-S01` (the one `make demo-till-sale` uses) | `m101-manager` | 24, from 55 days ago (about 35 receipts) |
| Hettipola shop (M101 S02) | `DEMO-TILL-M101-S02` | `m101-manager` | 12, from its transfer a month ago (about 18 receipts) |
| Pannala shop (M102) | `DEMO-TILL-M102-S01` | `m102-manager` | 24, from 55 days ago (about 35 receipts) |
| Point Pedro shop (M103) | `DEMO-TILL-M103-S01` | `m103-manager` | 24, from 55 days ago (about 35 receipts) |

Each shop's manager registers the shop's demo till on till position 1 when it is not there and issues it a one-time code; the till enrols and takes its snapshot. Then, three days a week (never today, which is `make demo-till-sale`'s), the till opens a session at 08:30 with a float of 2,000, makes one or two cash sales of one to five items each (one to three of each, only items the shop holds ten or more of, spread over the days so no lot goes negative), closes at 18:00 with the count equal to what it expects, and uploads. The till's own clock is set to that day, so sessions and receipts carry the day's business date and times and number from the till position's receipt series in date order. M5's stock movements for these sales are recorded at the real time by M5's own listener, as for the trading history.

Safe to repeat: a sale day on which the shop's demo till already has a receipt is not sold again, so a second run finds every day done and enrols nothing. It waits for central to apply the sales before it ends, and fails if the relay has not applied them within a minute.

A database loaded before this (with a history dated all on one day) keeps it: the loader finds every order by its notes and issues nothing. To see the eight weeks, `make reset && make up && make demo-data`.

The catalogue, its prices and the opening quantities are in `backend/app/src/main/resources/demo/catalogue.psv`, one line per SKU; change it there, not in code.

## Who is who

Every demo user signs in at http://localhost:5173 with the password **demo**. The earlier development users (`fed-admin`, `fed-officer`, `mpcs-admin`, `cashier`, password `dev`) are still there and are not part of the story.

| User | Name | Entity | Scope | Language | Job in the story |
|---|---|---|---|---|---|
| `fed-steward` | Nirmala Perera | Federation | entity-wide | en | Catalogue steward: creates and shares SKUs, units, barcodes |
| `fed-pricing` | Suresh Fernando | Federation | entity-wide | en | Trade price list to distributors; opens and activates the distributors' relationships |
| `fed-stores` | Kamal Jayasinghe | Federation | **Federation central warehouse (FW01) only** | si | Stores and dispatch: opening stock (prepare, sign), picking, dispatch of the delivery note |
| `fed-sales` | Tharindu Silva | Federation | entity-wide | en | Accepts the distributor's order, drafts and issues the delivery note |
| `fed-accounts` | Fathima Rizwan | Federation | entity-wide | en | Invoices; countersigns the opening stock |
| `d101-buyer` | Chaminda Rathnayake | D101 Wayamba Cooperative Distributors | entity-wide | si | Orders from the Federation; prices and sells to its societies |
| `d101-stores` | Lasantha Herath | D101 | **Kurunegala warehouse (W01) only** | si | Receives the goods (GRN); opening stock |
| `d101-accounts` | Dilani Wijesekara | D101 | entity-wide | si | Invoices, disputes; countersigns the opening stock |
| `d102-buyer` | Kumaran Sivalingam | D102 Northern Cooperative Distributors | entity-wide | ta | As `d101-buyer`, in Tamil |
| `d102-stores` | Arun Thevarajah | D102 | **Jaffna warehouse (W01) only** | ta | As `d101-stores` |
| `d102-accounts` | Priya Nadarajah | D102 | entity-wide | ta | As `d101-accounts` |
| `m101-buyer` | Sandya Kumari | M101 Kuliyapitiya MPCS | entity-wide | si | The society buyer: orders from D101, confirms receipt; prepares and signs the stores' opening stock (phase 3) |
| `m101-manager` | Ruwan Dissanayake | M101 | entity-wide | si | The second society user: holds the countersign permission; the shops and their tills; sends stock to the shop, enrols the demo till (phase 3) |
| `m101-shop` | Malani Gunawardena | M101 | **Kuliyapitiya town shop (S01) only** | si | Shop staff; receives the transfer at the shop (phase 3); shows that a shop session cannot write at another location |
| `m101-office` | Shanthi Wijeratne | M101 | entity-wide | si | The society office: members and the credit book (`docs/demo/09`); the in-person witness of the stores' write-off, which the manager approves (`docs/demo/10`) |
| `m102-manager` | Nimal Bandara | M102 Pannala MPCS | entity-wide | si | The society's manager |
| `m103-manager` | Selvaraj Yogarajah | M103 Point Pedro MPCS | entity-wide | ta | The society's manager, in Tamil |
| `m102-shop` | Dilrukshi Senanayake | M102 | **Pannala shop (S01) only** | si | Shop staff; prepares and signs the shop's opening stock, which `m102-manager` countersigns |
| `m103-shop` | Tharshini Kanagaratnam | M103 | **Point Pedro shop (S01) only** | ta | As `m102-shop`, at Point Pedro |

The roles are narrow (`seed/m1security/demo-users.demo.sql`, "Demo: ..." roles): with the permission check on, a user sees and does only the job above. A location-scoped user (FW01, W01, S01) is refused by row-level security anywhere else (PR #148).

Places: Federation central warehouse, Peliyagoda (FW01); Kurunegala warehouse (D101 W01); Jaffna warehouse (D102 W01); Kuliyapitiya stores (M101 W01); shops Kuliyapitiya town (M101 S01, two tills), Hettipola (M101 S02), Pannala (M102 S01), Point Pedro (M103 S01).

## The storyline of phase 1: the Federation sells to a distributor

Each step names who signs in and where they click. Steps 1 to 3 are already done by `make demo-data`; the demo shows them, then does steps 4 to 11 live.

1. **Catalogue.** `fed-steward`: Catalogue, browse the SKUs; open "Samba rice 5 kg": names in three scripts, unit EA, pack BAG of 5, barcode, tax EXEMPT, status SHARED. (Optional: create a new SKU and share it.)
2. **Price list.** `fed-pricing`: Pricing, "Federation trade list for distributors", PUBLISHED; the tiers (a lower price from 100). Relationships: D101 and D102 ACTIVE on this list. (Optional: draft a new version, change a price, publish.)
3. **Stock.** `fed-stores`: Inventory, stock position of the Federation central warehouse: every SKU, batch DEMO-2026-n, expiry, MRP. The opening balance was prepared and signed by `fed-stores` and countersigned by `fed-accounts` (OPB document). Sign in as `d101-stores` to see the Kurunegala warehouse's stock the same way.
4. **Order.** `d101-buyer` (Sinhala): **Trading**, "New order". *Order from*: the Federation; *Deliver to*: Kurunegala warehouse (W01). Find "Samba rice 5 kg", add it, quantity 120; find "Red dhal 1 kg", add it, quantity 40. Each line shows the Federation's tier price for its quantity (the rice at the price from 100) and the line amount. **Submit order**: the order takes D101's number (`D101-ORD-…`) and shows *Submitted*. The buyer does not see the Federation's stock: availability is the seller's own.
5. **Accept.** `fed-sales`: **Trading**, *Order desk*, open the D101 order. The *Available* column is the Federation's stock of each item; choose the delivery date and **Accept**. The order shows *Accepted*, with the allocated quantity and the tier price per line. (**Reject** asks for a reason.)
6. **Delivery note.** `fed-sales`, on the accepted order: **Prepare the delivery note**. *From warehouse*: Federation central warehouse (FW01); each line starts at the open quantity and the first batch in FEFO order (DEMO-2026-n); the drop goes to the delivery location the buyer named. **Save the delivery note**, then **Issue**: the note takes the Federation's number and the stock is reserved at FW01.
7. **Dispatch.** `fed-stores` (Sinhala, FW01 only): **Trading**, *Delivery notes sent*, open the note; type the vehicle and the driver, **Dispatch**: *In transit*.
8. **GRN.** `d101-stores` (Sinhala, W01 only): **Trading**, *Deliveries on their way to us*, open the note, **Receive the goods**. The count starts at what was sent, the batch, expiry and MRP from the Federation's batch; type 38 for the dhal to show a short line (marked *Short*). **Save the count**, then **Confirm receipt**: the GRN takes its number, ownership passes here (AGENTS.md, idea 2), the short line raises a discrepancy with the Federation, and the card lists the stock received. **See the stock position** (Inventory, Kurunegala warehouse) shows the new lots.
9. **Invoice.** `fed-accounts`: **Trading**, *Goods received by our buyers*, open the GRN, **Issue the invoice**: the tax invoice at the received quantities and the tier prices, with its links to the GRN and the delivery note. **Print** opens the A4 PDF once the worker has printed it (a moment after the issue). `d101-accounts` (Sinhala): **Trading**, *Invoices received*, reads the same invoice, and its **Print** opens the same A4 PDF (stored under the Federation, reached through the invoice the buyer reads).
10. **Settle the short delivery.** `fed-accounts`: open the GRN of step 8 (**Trading**, *Goods received by our buyers*). Its *Discrepancy* fact **Raised with the seller** opens the discrepancy: the dhal line sent 40, received 38, difference -2, status *Open* (also listed under *Discrepancies raised with us* on the desk). **Accept the count and settle** (reason prefilled "Count accepted"): the discrepancy reads *Settled*, with when and the reason, and **no credit note is issued**. The invoice of step 9 billed the 38 received (ownership passes at the GRN), so the 2 short bags were never charged. Only damaged quantity the invoice charged is credited, by a credit note at the invoice price (the demo GRN has none). `d101-accounts` (Sinhala): **Trading**, *Discrepancies we raised*, reads the same discrepancy *Settled* (read only), and the invoice with nothing credited. (Optional: `d101-accounts` disputes the invoice with a reason, and closes the dispute.)
11. **The buyer pays.** `fed-accounts`: open the invoice of step 9 (**Trading**, *Invoices issued*). It reads *Payment: Open* and the amount due. Under *Record a payment*, keep *Bank transfer* and the amount (it starts at the amount due), type a reference such as `TT-1001`, and **Record the payment**. The invoice reads *Settled*, *Paid* shows the amount, *Amount due* 0.00, and *Payments* lists the receipt (`FED-PRC-...`). Open it: the receipt shows what it settled. (For a cheque, choose *Cheque* and give the bank, number and date; the receipt then offers *The cheque cleared* / *The cheque bounced*. A bounce reverses the receipt and the invoice reads *Open* again.) `d101-accounts` (Sinhala): **Trading**, *Invoices received*: the invoice reads *Settled*, with the same receipt, read only; *Payments we made* lists it. The history already holds paid invoices: of the Federation's first four to D101, two settled, one part-paid in cash, and one whose cheque bounced (its receipt *Reversed*, the invoice open again).

The whole path, as these users, is `web/e2e/trading.spec.ts` (run by the pipeline's stack smoke after `make demo-data`).

**Credit limit and exposure.** Every relationship has a credit limit, set by the seller when it opens (M1); opening or activating a relationship with a limit needs `bil.creditlimit.change` and a fresh second factor, as changing the limit does, and the demo's openers (`fed-pricing`, `d101-buyer`, `d102-buyer`) hold it. The buyer's exposure is what it owes on open invoices plus what the seller has accepted but not yet invoiced, less anything paid on account and any credit note not yet applied. `d102-buyer` (D102's commercial user): **Trading**, *Our buyers' accounts*: Point Pedro MPCS (M103) stands past 80 % of its Rs 27,000 limit, marked with a warning. Open its submitted order on the order desk: *Credit and exposure* shows the limit, the parts of the exposure, and what accepting takes it to, "over the credit limit" when it would. The order can still be accepted: the limit warns, it never blocks (ADR-12), and the acceptance tells the seller's accounts (`exposure.warning.v1`). The buyer sees the same account under *Our accounts with suppliers*. (A database loaded before this change keeps M103's old Rs 5,000,000 limit until the demo is reloaded from empty.)

The same chain one level down: `d101-buyer` sells to Kuliyapitiya MPCS on the Wayamba list, and `m101-buyer` orders and receives.

## The storyline of phase 3: the shop

A society moves stock from its stores to one of its shops; the shop sells at the till; after sync the shop's stock goes down and the sale shows centrally. `make demo-data` has already moved the first stock to the town shop (the table above); the demo shows that transfer, can make another one live, and then makes a sale.

1. **The stores' stock.** `m101-manager`: Inventory, location "W01 Kuliyapitiya stores": every item, batch DEMO-2026-n, opening balance prepared and signed by `m101-buyer` and countersigned by `m101-manager`.
2. **The transfer.** `m101-manager`: Inventory, Transfers (`/inventory/transfers`), location W01: the transfer to S01 is RECEIVED. Live: to "S01 Kuliyapitiya town shop", enter a quantity against a few lots, Send: the stores' stock drops at once and the transfer is IN_TRANSIT (TRANSFER_OUT at the stores).
3. **The shop receives.** `m101-shop` (held to the town shop only): Inventory, Transfers: the transfer in transit to the shop, Receive: the shop's stock rises (TRANSFER_IN at the shop). The shop's session writes only the shop's rows; it cannot see or change the stores' (PR #148).
4. **The sale.** Without an Android device: `make demo-till-sale` in a terminal. The till simulator registers the demo till `DEMO-TILL-S01` on till position 1 of the town shop (as `m101-manager`), enrols it with a one-time code, takes its snapshot, opens a session with a float of 2,000, sells three items by barcode (1, 2 and 3 of the first three packed items), closes the session and uploads through the sync contract. It prints each item's stock at the shop before and after, and the receipt with its number from the till position's own receipt series.
5. **Central sees it.** `m101-shop` or `m101-manager`: Inventory, location S01: the three items are down by what was sold. Shop receipts (`/pos`), shop "S01 Kuliyapitiya town shop": one business day at a time (today by default; pick an earlier **Business date** for the eight weeks of the till history), 50 a page with **Show more**, and **Flagged receipts only** with each flag's reason in words; today's receipt at the top; each row shows the number from the till position's series, the time, the till, how it was paid and the total. Open it: its lines, net, tax and total, the tender and the session it was sold in. Till sessions (`/pos/sessions`), by day the same way: each session's float, and at the close the counted and expected cash and the variance. Selling more than the shop holds is not refused: the lot goes negative and is flagged for review, because a sale that happened at a till is a fact (AGENTS.md).

`make demo-till-sale` needs the stack (`make up`) and the demo (`make demo-data`); each run is one more sale. It uses the demo users' passwords, so it runs against the local stack only (`COOP_ERP_API`, `COOP_ERP_TOKEN_URL` to point it elsewhere).

## Still to come (TODO)

- The till history is cash only, with no voids, refunds, khata (customer account) sales or variances at the close; those wait for M6's and M7's deferred work.
- The demo users sign in with a password only; a step that asks for a second factor (publishing a price list, signing a balance) is accepted in the development realm because a fresh password sign-in counts (`COOP_ERP_MFA_PASSWORD_REAUTH_COUNTS`).
- The till is not part of phase 1: the shops have positions and a primary till. The shops' demo tills are enrolled by the till history of `make demo-data` (and `make demo-till-sale`); the Android till itself is the till track's.
- Phase 3: counts, write-offs, repack, weigh-and-price, park and resume, and returns are deferred.
