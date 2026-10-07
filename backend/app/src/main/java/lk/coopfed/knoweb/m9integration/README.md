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
| `internal/journal/` | `JournalPostingsConsumer` (`m9.journal`, the owner's side), `BuyerJournalPostingsConsumer` (`m9.journal.buyer`, the counterparty's side by the kernel's counterparty delivery), `RecordJournalPostingsHandler`, `RequestJournalExportHandler` (the JournalBuilder), `JournalFileV1` (frozen), `JournalFileV2`, `JournalFiles` (the writers by version, SHA-256) |
| `internal/notify/` | `NotifyConfiguration`: `RuleStore` (the kernel's `NotificationRuleQueries`), `ContactAudience` (ROLE_AT_OWNER, ROLE_AT_COUNTERPARTY), `SmtpEmailChannel`, `SmsChannel` over `SmsGateway` (`LogSmsGateway`); `SetNotificationRuleStatusHandler` |
| `internal/queries/` | `IntegrationQueriesImpl` |
| `web/IntegrationController` | The slice `openapi/m9integration.yaml` |
| `db/migration/m9integration/` | `V0001__integration.sql` (tables, RLS, grants), `V0002__notification_templates_and_rules.sql` (the seeded templates and rules), `V0003__contacts_own_rls_and_resolver.sql` (contacts under own-entity RLS, the recipient resolver), `V0004`, `V0005` (template wording), `V0006__journal_export_file_and_contact_door.sql` (the owner in the posting key, the stored file, the resolver that knows the caller, no Federation view of contacts) |
| `seed/m9integration/` | `audit-event-types.yaml`; `notification-contacts.dev.sql` (dev and demo contacts, `.test` addresses and placeholder numbers). The posting map the events carry is M4's, `seed/m4trading/posting-map.yaml` |
| `web/src/modules/m9integration/` | `/integration/journal` (exports), `/integration/notifications` (rules, templates, log) |

## The accounting export

1. M4 publishes `journal.postings_ready.v1` with every invoice, credit note, payment receipt and
   reversal, and (wave 2) every GRN the receiver confirms (its posting map names the roles and
   the amount). An invoice's and a credit note's event carries **both sides** and the buyer as
   `counterpartyEntityId` (CR-24A-3 item 5): the seller's receivable, revenue and VAT output, and
   the buyer's GRN accrual, VAT input and payable (`INV GOODS BUYER`, `CN GOODS BUYER`). A PRC
   carries the seller's side only (the buyer's cash book is its own record, doc 10 A-01) and a
   GRN the receiver's; neither names a counterparty.
2. Two consumers file the two sides, each in its own party's scope, dated by the document's
   business date as issued (the payload's `businessDate`, CR-29-1 item 4):
   - `JournalPostingsConsumer` (`m9.journal`, a consumer of every type) runs in the owner's scope
     and records the lines of the owner's side, the issuer role of the document type in the
     kernel's registry (SELLER for INV, CN, PRC; BUYER for GRN). An event published before wave 2
     has no `businessDate` and falls back to the event's day in Asia/Colombo.
   - `BuyerJournalPostingsConsumer` (`m9.journal.buyer`, `party = COUNTERPARTY`; CR-19A-13) is run
     by the kernel's dispatcher in the OWN, entity-wide scope of the payload's counterparty, after
     the kernel has checked that the event is central (never a till's) and that the counterparty
     is `kernel.document.counterparty_entity_id` of the document the event names. It records the
     lines of the other side (BUYER for INV and CN) under the ordinary `own_write` policy, with
     its own inbox row in the buyer's scope. The payable exists when the invoice is issued; no
     click by the buyer. An event with no counterparty is passed over.
   Both hand the lines to `RecordJournalPostings`, which holds them in `journal_posting`; a
   redelivery finds the document held by this entity (`owner_entity_id` and `document_id`, the
   unique key since V0006: the seller's and the buyer's postings of one invoice share the id and
   both start at seq 1) and adds nothing.
3. `RequestJournalExport(periodFrom, periodTo, provisional)` (`int.journal.export`, a user of the
   whole entity) refuses a period whose end is today or later in `coop-erp.business-timezone`
   unless the request says `provisional` (`m9.journal.period_open`; CR-29-1 item 1), then takes,
   under a per-entity advisory lock, every posting of the period that no export took, writes the
   file once with the current format version and stores it (`journal_export_file`: version,
   bytes, SHA-256; CR-29-1 item 2), writes `journal_export` (GENERATED, CSV, `provisional`,
   totals, the hash) and `journal_line` (one per posting). `journal_line.posting_id` is unique: a
   posting is exported once, and a later export over the same period is the supplement; a
   provisional export is a first instalment of its period, never superseded. The journal balances
   by construction (each line debits one role and credits another with one amount); it is checked
   role by role.
4. The file (`GET .../journal-exports/{id}/file`) is the double-entry journal for the accounting
   package, the stored bytes, byte for byte: format version 2 is
   `entry,date,doc_type,doc_number,line_kind,side,account_role,debit,credit,document_id,export`,
   two entries per line, `export` being `FINAL` or `PROVISIONAL` on every row. The file name
   carries `_PROVISIONAL` too. An export made before V0006 has no stored file and is rebuilt by
   `JournalFileV1`, which is frozen and never changes. Account roles, never account numbers (doc
   10 J-02).
5. The reconciliation recomputes debits and credits in total and by role from the lines and
   compares them with what was recorded; the file check hashes the stored bytes against the
   recorded hash and regenerates them with the writer of the recorded `format_version`, so the
   lines and the file agree (for a pre-V0006 export, the version-1 writer's file is hashed).
6. The supplement due (`GET .../journal-postings/supplement-due`): the postings no export took
   that are dated on or before the latest `period_to` the entity exported, with count, amount,
   dates and that period end. The exports page shows it as a banner; the next export over that
   period takes them. It catches a posting that arrived after midnight through consumer lag, a
   dead-letter replay, and a provisional export of an open period alike.

M5 and M7 publish no `journal.postings_ready.v1` yet (a write-off's and a customer account's
posting maps are theirs to seed); when they do, the owner's consumer takes them unchanged (a
HOLDER document keeps every line). When location-dated postings reach M9, "closed" extends to
every location of the entity having `BusinessDate.current(location) > periodTo` (a TODO in
`RequestJournalExportHandler` cites `2026-10-06-wave2-journal-export.md` (2)); whether one shop
offline for two days should block the entity's final export is open for the architect.

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
  which sends nothing and logs the id and length). A failure is thrown without the relay's or the
  gateway's text (both throw the kernel's `NotificationChannel.SendFailed` with a category:
  `AUTH`, `TIMEOUT`, `REJECTED` or `UNKNOWN`; wave 2, M9-10); the kernel retries (1, 5, 15
  minutes) and keeps the class and the category only. The mail's `Message-ID` is
  `<notificationId@domain of smtp.from>`, set before the send, so a retry is the same mail to the
  receiving side (M9-09). The relay is reached as `coop-erp.integration.smtp.security` says:
  `NONE` (Mailpit, a relay on the deployment's own network), `STARTTLS` (required, the server's
  identity checked) or `SSL`, with `username` and `password` (`COOP_ERP_SMTP_PASSWORD`) when the
  relay wants them; `NONE` with a relay beyond this machine or network refuses the start unless
  the OIDC issuer is a development one (M9-11; the variables are in `infra/deploy/.env.example`).
- **Rule toggle**: `SetNotificationRuleStatus` (`int.notify.manage`, the Federation only)
  activates or retires a federation-wide rule and publishes `notification_rule.changed.v1`,
  which empties the dispatcher's cache.
- **Delivery log**: the kernel's `kernel.notification_log`, read select-only under its policies.
  The screen names the recipient's entity (this entity, or another by the end of its id: M9
  reads no party names), the role, and eight characters of the keyed hash, never the hash
  itself (wave 2, M9-07); a QUEUED row with no attempt and a future `nextAttemptAt` reads
  "deferred (quiet hours) until" (CR-19A-12). `ContactAudience` gives the kernel the entity and
  the role, so the recipient's entity's quiet hours hold.

`coop-erp.integration.notify.enabled=false` removes all of M9's notification beans (the kernel's
own delivery test stands in its test channels).

## Deviations from 29A (decided on the architect's delegation, 29 September 2026)

1. `journal_posting` and `notification_contact` are M9 tables 29A does not have: the postings
   arrive as events (M9 reads no module's tables), and M1 keeps no e-mail or telephone of its
   users (doc 21 section 9.3), so a role is reached at the entity's contacts for it.
2. The export is generated in the request, not by the worker. Its file is stored with the export
   as `bytea` in `journal_export_file` with the writer's format version (wave 2, CR-29-1 item 2;
   `2026-10-06-wave2-journal-export.md` (1)), not in 29A's object storage (not transactional, and
   the kernel has no server-side put); the download serves those bytes and the reconciliation
   checks them. Before V0006 the file was rebuilt from the lines on download; those exports still
   are, by the frozen `JournalFileV1`. 29A's overlap guard is replaced by "each posting once"; the
   open-period guard is `periodTo >= today` in the business time zone with the `provisional` flag
   as the escape (29A's "all locations' day-close" waits for location-dated postings); doc 29's
   SUPERSEDED and "supersedes" are withdrawn, a late posting is a supplement and the exports page
   says when one is due; acknowledge, regenerate and the unacknowledged-review job are not built.
3. `posting_map_registry` is not built: the roles travel on the event, both sides of an invoice
   and a credit note on one event (`2026-10-06-wave2-buyer-postings.md` (2)); the buyer's side is
   filed by `m9.journal.buyer` under the kernel's counterparty delivery (CR-19A-13), not by an
   acknowledgement command of the buyer's and not under a SECURITY DEFINER function. The
   posting-map viewer waits for the registry.
4. Templates and rules are rows of a migration, not seed YAML read at start; DefineTemplate,
   DefineRule, the TemplateValidator and entity-owned rules come with their ticket. The read code
   `int.notify.view` is added (29A names none).
5. Templates and rules are readable by every session (`USING (true)`; no personal data): the
   dispatcher reads them in the event owner's scope and the renderer after the commit with none.
   Contacts have own-entity RLS (V0003) and, since V0006, no Federation view either (an address
   is personal data; `2026-10-06-wave2-member-identity-visibility.md` (1)); the dispatcher
   reaches a counterparty's addresses only through `integration.notification_recipients(entity,
   role)`, a SECURITY DEFINER function owned by the migrator (`search_path` ending in `pg_temp`,
   EXECUTE granted to app_rw) that answers an OWN caller only, for its own entity or for one it
   trades with by M1's `party.caller_trades_with` (ACTIVE or SUSPENDED, either direction; wave 2,
   M9-06, `2026-10-06-wave2-cross-tenant-functions.md` (4)). FEDERATION_VIEW and EXTERNAL get
   nothing from it.
6. No IN_APP adapter (the shell's bell does not exist); no rule uses IN_APP. No provider_config
   table: the SMS provider is a configuration property.
