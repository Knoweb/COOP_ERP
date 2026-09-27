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

What is loaded:

| Part | Through | Who issues it |
|---|---|---|
| Till positions (5) and the primary till of each of the 4 shops, with their numbering series | M1 `RegisterTillPosition`, `SetPrimaryTill` | each society's manager |
| 40 shared SKUs (rice, dhal, sugar, flour, oil, milk powder, tea, soap, spices...), 37 pack conversions (bag, case, dozen), 37 EAN-13 barcodes, tax categories STD and EXEMPT | M2 `CreateSku`, `ActivateSharedSku`, `DefineConversion`, `RegisterBarcode` | `fed-steward` |
| The Federation's trade list to distributors, two price tiers per SKU (from 0 and from 100) | M3 `CreatePriceList`, `SetLines`, `PublishPriceList` | `fed-pricing` |
| Each distributor's trade list to its societies (tiers from 0 and from 20) | M3, the same commands | `d101-buyer`, `d102-buyer` |
| Relationships Federation to D101 and D102; D101 to M101 and M102; D102 to M103; all ACTIVE on the lists above | M1 `OpenTradingRelationship`, `ActivateRelationship` | the seller: `fed-pricing`, `d101-buyer`, `d102-buyer` |
| Opening stock of every SKU at the Federation warehouse and at both distributors' warehouses, batches with expiry and printed MRP | M5 `PrepareOpeningBalance`, `SignOpeningBalance` (stores), `CountersignOpeningBalance` (a second person, accounts) | `fed-stores` then `fed-accounts`; `d101-stores` then `d101-accounts`; `d102-stores` then `d102-accounts` |
| Phase 3: Kuliyapitiya MPCS's opening stock at its stores (M101 W01), a tenth of a distributor's quantities at the Wayamba price | M5, the same three commands | `m101-buyer` then `m101-manager` |
| Phase 3: half of every lot at the stores sent to Kuliyapitiya town shop (M101 S01) and received there | M5 `IssueTransfer` (at the stores), `ReceiveTransfer` (at the shop, by the shop's own session) | `m101-manager`, then `m101-shop` |

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
| `m102-manager` | Nimal Bandara | M102 Pannala MPCS | entity-wide | si | The society's manager |
| `m103-manager` | Selvaraj Yogarajah | M103 Point Pedro MPCS | entity-wide | ta | The society's manager, in Tamil |

The roles are narrow (`seed/m1security/demo-users.demo.sql`, "Demo: ..." roles): with the permission check on, a user sees and does only the job above. A location-scoped user (FW01, W01, S01) is refused by row-level security anywhere else (PR #148).

Places: Federation central warehouse, Peliyagoda (FW01); Kurunegala warehouse (D101 W01); Jaffna warehouse (D102 W01); Kuliyapitiya stores (M101 W01); shops Kuliyapitiya town (M101 S01, two tills), Hettipola (M101 S02), Pannala (M102 S01), Point Pedro (M103 S01).

## The storyline of phase 1: the Federation sells to a distributor

Each step names who signs in and where they click. Steps 1 to 3 are already done by `make demo-data`; the demo shows them, then does steps 4 to 9 live.

1. **Catalogue.** `fed-steward`: Catalogue, browse the SKUs; open "Samba rice 5 kg": names in three scripts, unit EA, pack BAG of 5, barcode, tax EXEMPT, status SHARED. (Optional: create a new SKU and share it.)
2. **Price list.** `fed-pricing`: Pricing, "Federation trade list for distributors", PUBLISHED; the tiers (a lower price from 100). Relationships: D101 and D102 ACTIVE on this list. (Optional: draft a new version, change a price, publish.)
3. **Stock.** `fed-stores`: Inventory, stock position of the Federation central warehouse: every SKU, batch DEMO-2026-n, expiry, MRP. The opening balance was prepared and signed by `fed-stores` and countersigned by `fed-accounts` (OPB document). Sign in as `d101-stores` to see the Kurunegala warehouse's stock the same way.
4. **Order.** `d101-buyer`: Trading, new order to the Federation, add Samba rice 5 kg (120) and Red dhal 1 kg (40): the prices come from the Federation's list, the tier price from 100. Submit: the order takes D101's number.
5. **Accept.** `fed-sales`: the submitted order, accept.
6. **Delivery note.** `fed-sales`: draft the delivery note from the accepted order, issue it (the stock is reserved at FW01).
7. **Dispatch.** `fed-stores`: pick and dispatch the delivery note from the central warehouse.
8. **GRN.** `d101-stores`: goods received note against the delivery note, confirm: ownership passes here (AGENTS.md, idea 2); the batches become D101's lots at Kurunegala.
9. **Invoice.** `fed-accounts`: issue the invoice from the confirmed GRN; `d101-accounts` sees it.

The same chain one level down: `d101-buyer` sells to Kuliyapitiya MPCS on the Wayamba list, and `m101-buyer` orders and receives.

## The storyline of phase 3: the shop

A society moves stock from its stores to one of its shops; the shop sells at the till; after sync the shop's stock goes down and the sale shows centrally. `make demo-data` has already moved the first stock to the town shop (the table above); the demo shows that transfer, can make another one live, and then makes a sale.

1. **The stores' stock.** `m101-manager`: Inventory, location "W01 Kuliyapitiya stores": every item, batch DEMO-2026-n, opening balance prepared and signed by `m101-buyer` and countersigned by `m101-manager`.
2. **The transfer.** `m101-manager`: Inventory, Transfers (`/inventory/transfers`), location W01: the transfer to S01 is RECEIVED. Live: to "S01 Kuliyapitiya town shop", enter a quantity against a few lots, Send: the stores' stock drops at once and the transfer is IN_TRANSIT (TRANSFER_OUT at the stores).
3. **The shop receives.** `m101-shop` (held to the town shop only): Inventory, Transfers: the transfer in transit to the shop, Receive: the shop's stock rises (TRANSFER_IN at the shop). The shop's session writes only the shop's rows; it cannot see or change the stores' (PR #148).
4. **The sale.** Without an Android device: `make demo-till-sale` in a terminal. The till simulator registers the demo till `DEMO-TILL-S01` on till position 1 of the town shop (as `m101-manager`), enrols it with a one-time code, takes its snapshot, opens a session with a float of 2,000, sells three items by barcode (1, 2 and 3 of the first three packed items), closes the session and uploads through the sync contract. It prints each item's stock at the shop before and after, and the receipt with its number from the till position's own receipt series.
5. **Central sees it.** `m101-shop` or `m101-manager`: Inventory, location S01: the three items are down by what was sold. The receipt and the session are at `GET /v1/pos/receipts?locationId=...` and `/v1/pos/sessions` (no screen yet). Selling more than the shop holds is not refused: the lot goes negative and is flagged for review, because a sale that happened at a till is a fact (AGENTS.md).

`make demo-till-sale` needs the stack (`make up`) and the demo (`make demo-data`); each run is one more sale. It uses the demo users' passwords, so it runs against the local stack only (`COOP_ERP_API`, `COOP_ERP_TOKEN_URL` to point it elsewhere).

## Still to come (TODO)

- **M4 demo data.** Steps 4 to 9 need M4 (orders, acceptance, delivery notes, GRN, invoices), which was being merged when DEMO-01 was built (pull requests #156, #157, #159, #163, #164). The loader does not create trading documents; the storyline does them live. When M4 is on main: check the steps above against its screens, and if the demo should start with documents already in the history (a completed order-to-invoice for D101, an open order for D102), add them to `DemoDataLoader` through M4's handlers, as the users named above, and check whether M4's numbering series need anything for the demo entities (the order series is the buyer's).
- The demo users sign in with a password only; a step that asks for a second factor (publishing a price list, signing a balance) is accepted in the development realm because a fresh password sign-in counts (`COOP_ERP_MFA_PASSWORD_REAUTH_COUNTS`).
- The till is not part of phase 1: the shops have positions and a primary till. Phase 3's demo till is enrolled by `make demo-till-sale`, not by `make demo-data`; the Android till itself is the till track's.
- Phase 3 has no receipts screen yet (the reads are in the API); counts, write-offs, repack, weigh-and-price, park and resume, and returns are deferred.
