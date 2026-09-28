# M9 Integration: the living guide

29A (M9 Integration implementation guide) and doc 29 say what the module is. This file says what
is built, how it differs from 29A, and where to start. It supersedes 29A for day-to-day work on
what it covers.

Built so far (29 September 2026, branch `feat/m9-accounting-export-notifications`): the
**accounting export** (29A M9-03 in its demo form) and the **notification** side of the kernel's
delivery (M9-04 and M9-05 in their demo form). Not built: API clients and the public API subset
(M9-02), providers and the health job, e-invoicing (M9-06), the bundle assembler (M9-07), the
screens of those, the Playwright flows.

## Layout

| Path | What it holds |
|---|---|
| `api/` | Commands `RecordJournalPostings`, `RequestJournalExport`, `SetNotificationRuleStatus`; events `journal.postings_recorded.v1`, `journal.generated.v1`, `notification_rule.changed.v1` |
| `query/IntegrationQueries` | Exports, lines, the journal file, reconciliation, pending postings, templates, rules, the delivery log |
| `internal/journal/` | `JournalPostingsConsumer` (`m9.journal`), `RecordJournalPostingsHandler`, `RequestJournalExportHandler` (the JournalBuilder), `JournalFile` (CSV and SHA-256) |
| `internal/notify/` | `NotifyConfiguration`: `RuleStore` (the kernel's `NotificationRuleQueries`), `ContactAudience` (ROLE_AT_OWNER, ROLE_AT_COUNTERPARTY), `SmtpEmailChannel`, `SmsChannel` over `SmsGateway` (`LogSmsGateway`); `SetNotificationRuleStatusHandler` |
| `internal/queries/` | `IntegrationQueriesImpl` |
| `web/IntegrationController` | The slice `openapi/m9integration.yaml` |
| `db/migration/m9integration/` | `V0001__integration.sql` (tables, RLS, grants), `V0002__notification_templates_and_rules.sql` (the seeded templates and rules) |
| `seed/m9integration/` | `audit-event-types.yaml`; `notification-contacts.dev.sql` (dev and demo contacts, `.test` addresses and placeholder numbers) |
| `web/src/modules/m9integration/` | `/integration/journal` (exports), `/integration/notifications` (rules, templates, log) |

## The accounting export

1. M4 publishes `journal.postings_ready.v1` with every invoice, credit note, payment receipt and
   reversal (its posting map names the roles and the amount). `JournalPostingsConsumer` (a
   consumer of every type, for the envelope's time) hands the postings to
   `RecordJournalPostings`, which holds them in `journal_posting` in the owner's scope, dated by
   the event's day in Asia/Colombo. A redelivery finds the document and adds nothing.
2. `RequestJournalExport(periodFrom, periodTo)` (`int.journal.export`, a user of the whole entity)
   takes, under a per-entity advisory lock, every posting of the period that no export took,
   writes `journal_export` (GENERATED, CSV, totals, SHA-256 of the file) and `journal_line` (one
   per posting). `journal_line.posting_id` is unique: a posting is exported once, and a later
   export over the same period is the supplement. The journal balances by construction (each
   line debits one role and credits another with one amount); it is checked role by role.
3. The file (`GET .../journal-exports/{id}/file`) is the double-entry journal for the accounting
   package: `entry,date,doc_type,doc_number,line_kind,side,account_role,debit,credit,document_id`,
   two entries per line. Account roles, never account numbers (doc 10 J-02).
4. The reconciliation recomputes debits and credits in total and by role from the lines and
   compares them, and the file's hash, with what was recorded at generation.

M5 and M7 publish no `journal.postings_ready.v1` yet (a write-off's and a customer account's
posting maps are theirs to seed); when they do, the consumer takes them unchanged.

## Notifications

The kernel (K-10) dispatches, renders, suppresses, retries and logs. M9 gives it:

- **Templates and rules** (`V0002`): six templates in English, Sinhala and Tamil (ICU
  MessageFormat over the payload's scalar fields) and six federation-wide ACTIVE rules for the
  events that exist: `invoice.issued.v1`, `payment_receipt.recorded.v1` and `cheque.bounced.v1`
  (e-mail, and SMS for the cheque) to the buyer's ACCOUNTS; `exposure.warning.v1` to the
  seller's ACCOUNTS; `writeoff.submitted.v1` to the society's MANAGER.
- **Audience**: `ContactAudience` resolves a role code to the entity's `notification_contact`
  rows on the rule's channels, in the contact's language. The counterparty of M4's events is the
  party of seller and buyer that is not the owner (the kernel's dispatcher, 29 September 2026).
- **Adapters**: `SmtpEmailChannel` (JavaMail to `coop-erp.integration.smtp.*`; Mailpit in
  compose) and `SmsChannel` over the provider `coop-erp.integration.sms.provider` (only `log`,
  which sends nothing and logs the id and length). A failure is thrown without the relay's text;
  the kernel retries (1, 5, 15 minutes).
- **Rule toggle**: `SetNotificationRuleStatus` (`int.notify.manage`, the Federation only)
  activates or retires a federation-wide rule and publishes `notification_rule.changed.v1`,
  which empties the dispatcher's cache.
- **Delivery log**: the kernel's `kernel.notification_log`, read select-only under its policies.

`coop-erp.integration.notify.enabled=false` removes all of M9's notification beans (the kernel's
own delivery test stands in its test channels).

## Deviations from 29A (decided on the architect's delegation, 29 September 2026)

1. `journal_posting` and `notification_contact` are M9 tables 29A does not have: the postings
   arrive as events (M9 reads no module's tables), and M1 keeps no e-mail or telephone of its
   users (doc 21 section 9.3), so a role is reached at the entity's contacts for it.
2. The export is generated in the request, not by the worker, and its file is built from the
   lines on download rather than stored in object storage; the hash proves it unchanged.
   29A's overlap guard is replaced by "each posting once"; the day-close guard and the
   provisional flag wait for M8's freshness query; acknowledge, regenerate and the
   unacknowledged-review job are not built.
3. `posting_map_registry` is not built: the roles travel on the event. The posting-map viewer
   waits for it.
4. Templates and rules are rows of a migration, not seed YAML read at start; DefineTemplate,
   DefineRule, the TemplateValidator and entity-owned rules come with their ticket. The read code
   `int.notify.view` is added (29A names none).
5. Templates, rules and contacts are readable by every session (`USING (true)`): the dispatcher
   reads them in the event owner's scope and the renderer after the commit with none, and a
   counterparty's contacts are read in the owner's scope. No operation serves a contact.
6. No IN_APP adapter (the shell's bell does not exist); no rule uses IN_APP. No provider_config
   table: the SMS provider is a configuration property.
