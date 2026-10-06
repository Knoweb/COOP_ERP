# The society's credit book

**Story.** As the society's office, I want to know who owes the society what for goods taken on credit at our shops, record a member's repayment at the counter, and see that nobody runs far past the limit we agreed with them, so that the credit book the shop kept on paper is kept by the system instead.

The back office part only (M7). The till's own account sales and repayments come with the till (CR-30-1); the demo's eight weeks of sales on account were posted through the same path a till's receipts will take.

**Who signs in.** `m101-office` (Shanthi Wijeratne, Kuliyapitiya MPCS office, entity-wide), password `demo`.

## Steps

1. **Members.** The register lists 36 members, names in Sinhala, Tamil and English. Search by name or by phone: `0700000100` is K. Perera.
2. **A member's card.** K. Perera's card shows account A00001: limit 15,000, balance 14,200, the warning at 94 % of the limit, and the ageing buckets (how much is 0–30, 31–60 days old and older).
3. **Statement of the account.** Eight weeks of sales on account (`DEMO-KHATA-…`, the till's charges) and a repayment with its receipt number (CPR). Point out the opening, running and closing balance.
4. **Record a repayment.** 2,000 in cash. The receipt number `M101-CPR-…` appears and the balance falls; the statement shows the payment settling the oldest charges first.
5. **Register a member.** Name, phone, and the consent to hold a credit account. Type a name like "Perera" to see the likely duplicates (shown, not refused). Use a phone number another member gave up recently to see the confirmation step: the officer confirms it is a different person.
6. **Open a credit account** on the new member's card, with a limit and the NIC. Only the last four characters of the NIC are ever shown.

## Account controls and privacy

As `m101-office` unless said.

- **A suspended account.** Open Sita Kumari's card: the account shows *Suspended*, and *Changes to the account* has "Repayments overdue: suspended until the member calls at the office". Reinstate it with a reason (Suspend, reinstate or close → Reinstate), then suspend it again. The tills refuse account sales on a suspended account; a sale an offline till took anyway is still posted, with a review record.
- **Limits.** On K. Perera's card (95 % of the limit), lower the limit or tick the hard block with a reason: saved at once. Raise it: the form says a higher limit needs the second factor, and the server asks for it (401) unless you signed in with it in the last ten minutes.
- **Reverse a repayment.** Open a statement, press *Reverse* on a repayment, give a reason ("Deposit bounced"): a reversing CPR is issued (`M101-CPR-…`), the repayment is marked reversed, the balance goes back up and the charges it settled are open again. Needs the second factor.
- **Adjustments.** Ask for an adjustment on the card (amount and reason); it waits for approval. Sign in as `m101-manager` and approve it: it is posted. The clerk who asked cannot approve their own.
- **Privacy requests.** Members → *Privacy requests*: Rizwan Hameed's erasure is fulfilled by Ruwan Dissanayake (the society's responsible officer); the member shows as "Customer", no phone. To show a new one: on a member's card record an *Access to their data* request; as `m101-manager` open Privacy requests, *Fulfil*, then *Download the export* (every row the society holds about the member). An erasure of a member who owes money is refused until the balance is settled.

## What to point out

- **A sale at the till is never refused for credit.** A charge over the limit is posted and flagged for review; the society decides what to do, not a rule that fires while the customer waits.
- **The ledger is only added to.** Nothing is edited or deleted: a balance is the sum of its postings, and what a repayment settled is written as its own rows.
- **One phone number, one person at a time.** A number held by a member now is refused for another; a number released recently needs a confirmation.
- **The NIC is not stored in the clear**: a hash to find duplicates, and the last four characters to show.

## What can go wrong

- **No "Members" in the navigation**: sign in as `m101-office`; other demo users do not hold the society office's permissions. A stack loaded before the credit book was added needs `make demo-data` again for the members and the user.
- **Not here yet**: account sales and repayments at a real till (the till app is in its desktop trial), statements by SMS, printed statements.
