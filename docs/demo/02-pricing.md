# Pricing: the trade price list and its tiers

**Story.** As the Federation's pricing officer, I want to publish one trade price list with a lower price for larger quantities, so that every distributor buys the same items at the same, published prices and a bigger order is rewarded automatically.

**Who signs in.** `fed-pricing` (Suresh Fernando), password `demo`, screen in English.

**Not in this demo yet.** Retail prices (what a shop charges a customer) and control prices (the gazetted legal ceiling) are part of the design (`AGENTS.md` vocabulary: "Control price") but no screen exists for them yet — only the trade price list between the Federation, distributors and societies is built. Say so if asked "what does the shop charge?": that price list is not built. If it has been merged to `main` by the time you read this, check `web/src/modules/m3pricing` for a retail or control price screen and update this note.

## Steps

1. Sign in, go to **Price lists**.
2. Open "Federation trade list for distributors". Point out: **State** is "Published", the **Version** number, **Applies from** date, and the lines: one item can have two rows, a price **From quantity** 0 and a lower price **From quantity** 100 — the tier.
3. Point out the **relationships**: D101 and D102 are ACTIVE on this list — this is what lets `d101-buyer` and `d102-buyer` order from the Federation later in the demo.
4. Optional live addition: click **Draft the next version**. **Find an item by code or name**, find one, **Add**, fill in a **Unit price (without VAT)** and a **From quantity**, **Save lines**. Point out the checks: a negative price, a duplicate quantity for the same item, or a missing line from quantity 0 are all refused with a plain reason before you can publish.
5. Click **Publish** — the new version becomes "Published" and the old one moves to "Superseded" automatically; nothing is overwritten.

## What to point out

- **Tiers reward volume without a special case in the order screen**: the buyer's order screen (phase 1, next file) just reads whichever tier line matches the quantity ordered.
- **Nothing is ever edited once published**: a correction is a new draft version, published in turn; the old version stays exactly as it was, because a superseded price is still what an old order was actually priced at.
- **Permissions per role**: only `fed-pricing` can publish the Federation's own list; a distributor publishes its own list to its own societies (see phase 2) but cannot touch the Federation's.

## What can go wrong

- **Publish is refused with "above the lowest printed MRP"**: a typed price is higher than the printed maximum retail price of that item — correct the price, save, publish again; this is a legal ceiling check, not a bug.
- **A line will not save ("the quantities of an item must rise")**: the tier quantities for one item must be entered in ascending order (e.g. 0, then 100, not 100 then 0).
- **No "Publish" button visible**: check the list is in "Draft" state — you cannot publish an already-published list; draft the next version first.
