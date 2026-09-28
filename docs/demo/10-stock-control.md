# Stock control at the society's stores

**Story.** As the society's stores, I want to count what is on the shelf, write off what is spoilt with a witness, and repack loose goods into our own packs, so that the book matches the shelf and every loss has a reason and a second person behind it.

**Who signs in.** `m101-buyer` (Sandya Kumari, Sinhala) for counting and requesting; `m101-manager` (Ruwan Dissanayake, Sinhala) for approving and witnessing. Password `demo`.

## Steps

1. **The count of three weeks ago.** `m101-buyer`: **Stock** → **Stock counts**, choose *Kuliyapitiya stores*. The count reads *Closed, adjustment approved*. Open it: red dhal one short (posted at once, within tolerance), white sugar three short (needed approval; the manager approved it the same afternoon). Click an item for its stock card: the *Count adjustment* line sits in the ledger with the balance after it.
2. **Count something now.** Back on **Stock counts**, leave *Some items*, tick an item and **Start the count**. The sheet lists each lot with what the book says. Type what you count: within 2 units of the book it posts at once; more than that waits for the manager. **Submit count**.
3. **Write-offs.** **Stock** → **Write-offs**, *Kuliyapitiya stores*: two packs of wheat flour written off as *damaged in store* (spoilt by a roof leak), numbered from the stores' WOF series, witnessed and approved by the manager. A new one: pick a category and a quantity against a lot, **Save draft**; on the write-off, add a photograph (required for theft, unexplained shrinkage and at a one-person shop), **Submit**. Sign in as `m101-manager`: **Witness**, then **Approve** (approving asks for the second factor).
4. **Repack.** **Stock** → **Repacks**: the recipe *Loose samba rice into 5 kg packs* and the repack of six days ago: 10 kg of loose rice into two *Samba rice 5 kg, society pack*, each at the cost of the rice it holds. A repack can be **Reverse**d while none of its packs has been sold or moved.
5. **Lots below zero.** When tills sell the last bags while offline, a lot can go below zero. **Stock counts** lists such lots under *Lots below zero*, to **Acknowledge** with a note until the next count corrects them. (The demo data has none; a run of `make demo-till-sale` past a lot's stock makes one.)

## What to point out

- **Selling goes on during a count.** Each lot is measured against the book at the moment the count is submitted, so a till sale in the middle of counting is not a variance, and nothing is frozen.
- **Two people for every loss that matters.** A variance above tolerance and every write-off need a second person; the one who asked can never approve, and approval stays within the approver's value limit.
- **Every loss has a category and, where it matters, a photograph.** A witness at a one-person shop works remotely from the photographs.
- **A repack keeps the cost.** The packs carry the cost of the rice they hold; the yield variance is recorded.

## What can go wrong

- **No counts or write-offs listed**: choose the location *Kuliyapitiya stores*; a stack loaded before stock control was added needs `make demo-data` again.
- **Approve asks for a second factor and fails**: the development realm accepts a fresh password sign-in as the second factor; sign out and in again, then approve.
