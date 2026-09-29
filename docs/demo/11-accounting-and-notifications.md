# Accounting export and notifications

**Story.** As the Federation's accounts, I want every invoice, credit note and payment to reach our accounting package as balanced journal entries, once and only once, and I want the people on the other side told by e-mail or SMS when something needs them, in their own language.

**Who signs in.** `fed-accounts` for the journal export; `fed-steward` for the notification rules. Password `demo`. Mailpit, the local mail catcher, is at http://localhost:8025 (no sign-in).

## Steps

1. **The mails the demo already sent.** Open Mailpit. After `make demo-data` it holds, each in the reader's language:
   - invoice and payment mails to the buyer's accounts desk: D101 (Sinhala), D102 (Tamil), and the societies M101, M102 and M103;
   - the bounced-cheque mail to D101's accounts desk (the buyer whose cheque bounced);
   - the exposure warning to D102's accounts desk (Tamil), because D102 is the seller whose buyer, Point Pedro MPCS, has passed 80 % of its credit limit;
   - the write-off waiting for approval to M101's manager.

   SMS goes to a log-only provider in the development stack: the backend log shows the lines, no message leaves the machine.
2. **Journal exports.** `fed-accounts`: **Journal exports**. Keep the period (the first of the month two months back, to today) and click **Generate the export**. The history shows the export with its lines, and Debit equals Credit.
3. **Download and reconcile.** **Download CSV** saves the double-entry journal file for the accounting package. **Reconciliation** shows *Balanced* and the totals by account role (receivables, revenue, VAT output, bank or cash).
4. **Once only.** Generate the same period again: nothing is left to export. Each posting leaves in exactly one export; a later export over the same period carries only what arrived since.
5. **Notifications.** **Notifications** shows the rules, the templates in the reader's language and the delivery log (recipients shown as hashes, not addresses). `fed-steward` can retire a rule and activate it again.

## What to point out

- **Balanced by construction.** Every export is checked debit against credit, and its file is fingerprinted: a file changed after it left is detected on reconciliation.
- **Nothing is exported twice**, so a re-run of the month does not double the books.
- **Contacts stay private.** An entity reads only its own notification contacts; the notifier resolves the one recipient it needs for one message and nothing more.
- **Three languages** for every template: each recipient reads the mail in the language on their contact.

## What can go wrong

- **Mailpit is empty**: the worker sends the mails a moment after `make demo-data`; refresh. A stack started before M9 was added needs `make up` again for the mail settings.
- **"Nothing to export"** on the first try: the period chosen holds no postings; widen it.
- **Not here yet**: stock movements (M5) and the credit book (M7) do not publish postings yet, so they are not in the export; e-invoicing and external API clients.
