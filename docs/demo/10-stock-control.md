# Stock control at the society's stores

**Story.** As the society's stores, I want to count what is on the shelf, write off what is spoilt with a witness, and repack loose goods into our own packs, so that the book matches the shelf and every loss has a reason and a second person behind it.

**Who signs in.** `m101-buyer` (Sandya Kumari, Sinhala) for counting and requesting; `m101-office` (Shanthi Wijeratne, Sinhala) for witnessing a write-off in person; `m101-manager` (Ruwan Dissanayake, Sinhala) for approving. Password `demo`.

## Steps

1. **The count of three weeks ago.** `m101-buyer`: **Stock** → **Stock counts**, choose *Kuliyapitiya stores*. The count reads *Closed, adjustment approved*. Open it: red dhal one short (posted at once, within tolerance), white sugar three short (needed approval; the manager approved it the same afternoon). Click an item for its stock card: the *Count adjustment* line sits in the ledger with the balance after it.
2. **Count something now.** Back on **Stock counts**, leave *Some items*, tick an item and **Start the count**. The sheet lists each lot with what the book says. Type what you count: a variance posts at once only when it is within the quantity tolerance (2 units of the book, or 1 % of it) **and** worth no more than Rs 1,000 (`inventory.count_tolerance_value`); anything else waits for the manager. So one bag of red dhal short posts at once, but one 5 kg bag of samba rice short (about Rs 1,210) waits for the manager. A count whose variances together pass Rs 10,000 waits as a whole. **Submit count**.
3. **Write-offs.** **Stock** → **Write-offs**, *Kuliyapitiya stores*: two packs of wheat flour written off as *damaged in store* (spoilt by a roof leak), numbered from the stores' WOF series, witnessed in person by the society office (`m101-office`) and approved by the manager. A new one: pick a category and a quantity against a lot, **Save draft**; on the write-off, add a photograph (required for theft, unexplained shrinkage and at a one-person shop), **Submit**. The witness is a second person who saw the loss: sign in as `m101-office`, **Witness**. Then sign in as `m101-manager`: **Approve** (approving asks for the second factor). The approver may not also be the in-person witness; only at a one-person shop may the approver witness remotely from the photographs.
4. **Repack.** **Stock** → **Repacks**: the recipe *Loose samba rice into 5 kg packs* and the repack of six days ago: 10 kg of loose rice into two *Samba rice 5 kg, society pack*, each at the cost of the rice it holds. A repack can be **Reverse**d while none of its packs has been sold or moved.
5. **Lots below zero.** When tills sell the last bags while offline, a lot can go below zero. **Stock counts** lists such lots under *Lots below zero*, to **Acknowledge** with a note until the next count corrects them. (The demo data has none; a run of `make demo-till-sale` past a lot's stock makes one.)

## What to point out

- **Selling goes on during a count.** Each line is measured against the book at the moment it was counted (the sheet stamps the time when you type the quantity), so a till sale in the middle of counting is not a variance, and nothing is frozen.
- **Two people for every loss that matters, three for a write-off.** A variance above tolerance needs a second person; a write-off needs a witness and an approver, and the one who asked can never approve. Approval stays within the approver's value limit: the society managers approve up to Rs 250,000; a role with no limit set approves only up to Rs 25,000, and a user without the permission approves nothing.
- **Expired stock is never picked.** A lot past its expiry date shows *Expired* on the stock screen and is never offered for a delivery note, a transfer or a price; a lot that expires today still sells today.
- **Every loss has a category and, where it matters, a photograph.** A witness at a one-person shop works remotely from the photographs.
- **A repack keeps the cost.** The packs carry the cost of the rice they hold; the yield variance is recorded.

## What can go wrong

- **No counts or write-offs listed**: choose the location *Kuliyapitiya stores*; a stack loaded before stock control was added needs `make demo-data` again.
- **Approve asks for a second factor and fails**: the development realm accepts a fresh password sign-in as the second factor; sign out and in again, then approve.
- **"The person who witnessed the write-off in person cannot approve it"**: the manager witnessed this write-off; have `m101-office` witness it instead. **`m101-office` has no Stock entry in the navigation**: as of 7 October 2026 the society office's demo role holds the witness permission but not `inv.stock.view`, which the write-off list and page need, so a live write-off stops at *Waiting for a witness*. Show the loaded one (step 3) instead.
