# Phase 2: a distributor sells to a society

**Story.** As a society's buyer, I want to order from my own distributor the same way it orders from the Federation, so that the same trading chain repeats down every level of the cooperative without a different screen or a different rule at each level.

The same nine-step shape as phase 1, one level down: D101 (Wayamba distributor) sells to M101 (Kuliyapitiya MPCS), on D101's own price list to its societies. Walked live on 28 September 2026, including the invoice's VAT split by tax category.

**Who signs in, in order.** `m101-buyer` (Sinhala) → `d101-buyer` (Sinhala, acting as seller this time) → `d101-stores` (Sinhala, Kurunegala warehouse only) → `m101-buyer` again (receiving) → `d101-accounts` (Sinhala).

## Steps

1. **Order.** `m101-buyer`: **Trading**, **New order**. *Order from*: D101 Wayamba Cooperative Distributors. *Deliver to*: the society's own stores (M101 W01). Add a couple of items at a quantity that crosses D101's own tier boundary (D101's list has tiers from 0 and from 20 — order at least 20 of one item to show the lower price). **Submit order**.
2. **Accept.** `d101-buyer`, now acting as the seller: **Trading**, **Order desk: orders received**, open the M101 order, check the **Available** column, choose a **Delivery date**, **Accept**.
3. **Delivery note.** `d101-buyer`, on the accepted order: **Prepare the delivery note** *From warehouse*: Kurunegala warehouse (W01), **Save the delivery note**, **Issue**.
4. **Dispatch.** `d101-stores` (scoped to W01 only): **Trading**, **Delivery notes sent**, open the note, type **Vehicle** and **Driver**, **Dispatch**.
5. **GRN.** `m101-buyer`: **Trading**, **Deliveries on their way to us**, open the note, **Receive the goods**. This time receive the full quantity (no short line needed — it was already shown in phase 1; showing it twice adds nothing). **Save the count**, **Confirm receipt**. **See the stock position** at the society's stores.
6. **Invoice.** `d101-accounts`: **Trading**, **Goods received by our buyers**, open the GRN, **Issue the invoice**. Point out the invoice lines: **VAT %** and **VAT** are shown per line, split by the item's own **Tax category** (a STD item carries VAT, an EXEMPT item shows none) — the invoice totals **Net amount**, **VAT**, **Total** are the sum of lines taxed differently, not one flat rate applied to everything. Click **Print**.

## What to point out

- **The same chain, one level down**: nothing about the screens changed between phase 1 and phase 2 — the same order desk, delivery note, GRN and invoice screens, because a distributor selling to a society is structurally the same trade as the Federation selling to a distributor.
- **VAT by tax category, not a flat rate**: an EXEMPT item (most raw foodstuffs in this catalogue) and a STD item on the same invoice are taxed differently, correctly, on the same document (fixed and proved 28 Sep — `docs/progress/done/2026-09-28-fix-invoice-vat-by-tax-category.md` if that entry name matches what is on `main`).
- **Permissions per role, again**: `d101-buyer` both orders (from the Federation, phase 1) and sells (to M101, here) — the same person can hold two jobs, but each action is checked against what that command actually needs, not against "is this person important".

## Credit limit and exposure

Every relationship has a credit limit, set by the seller when it opens (M1). The buyer's exposure is what it owes on open invoices plus what the seller has accepted but not yet invoiced, less anything paid on account. `d102-buyer` (D102's commercial user): **Trading**, *Our buyers' accounts*: Point Pedro MPCS (M103) stands past 80 % of its Rs 27,000 limit, marked with a warning. Open its submitted order on the order desk: *Credit and exposure* shows the limit, the parts of the exposure, and what accepting takes it to, "over the credit limit" when it would. The order can still be accepted: the limit warns, it never blocks (ADR-12), and the acceptance tells the seller's accounts (`exposure.warning.v1`). The buyer sees the same account under *Our accounts with suppliers*. (A database loaded before this change keeps M103's old Rs 5,000,000 limit until the demo is reloaded from empty.)

## What can go wrong

Same failure modes as phase 1 (see that file's "What can go wrong") — a missing relationship, an unaccepted order, an unfilled GRN line. Specific to this phase:

- **The society's order screen offers no seller**: check the relationship D101 → M101 is ACTIVE, and that M101's own price list from D101 (not the Federation's) is what is being read.
- **The VAT split does not show, or shows one rate for everything**: confirm the catalogue items used have different tax categories (mix a STD and an EXEMPT item) — a demo with only EXEMPT items on the invoice will correctly show no VAT at all, which can look like a bug if the audience is not told which items are tax-exempt.
