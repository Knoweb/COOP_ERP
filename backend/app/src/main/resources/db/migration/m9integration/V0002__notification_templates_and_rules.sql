-- The phase-1 templates and rules of the demo scope (29A section 3.1, seed/m9/templates.yaml and
-- rules.yaml), for the events the modules publish today. Federation-owned (owner null), ACTIVE.
-- Rows in a migration, not a seed file read at start: the texts are reference data that change by
-- a new migration, and nothing else writes them until DefineTemplate and DefineRule are built
-- (decided on the architect's delegation, 29 September 2026; module README).
--
-- Placeholders are the scalar fields of the event's payload (the kernel's dispatcher passes them),
-- formatted with ICU MessageFormat in the recipient's language. No apostrophe in a text: ICU reads
-- it as a quote.
--
-- The audience's role code (ACCOUNTS, MANAGER) names the contacts of integration.notification_contact
-- at the entity: ROLE_AT_COUNTERPARTY the other party of the document (the buyer on the seller's
-- invoice), ROLE_AT_OWNER the entity whose event it is.

CREATE POLICY seed_reference ON integration.notification_template TO app_seed USING (true) WITH CHECK (true);
CREATE POLICY seed_reference ON integration.notification_rule TO app_seed USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON integration.notification_template TO app_seed;
GRANT SELECT, INSERT, UPDATE ON integration.notification_rule TO app_seed;

INSERT INTO integration.notification_template (
    template_id, owner_entity_id, channel, subject_en, subject_si, subject_ta, body_en, body_si, body_ta,
    placeholders_schema, status
)
VALUES
    ('invoice-issued-email', NULL, 'EMAIL',
     'Invoice {docNumberDisplay} issued',
     'ඉන්වොයිසිය {docNumberDisplay} නිකුත් කෙරිණි',
     'விலைப்பட்டியல் {docNumberDisplay} வழங்கப்பட்டது',
     'Invoice {docNumberDisplay} has been issued to you for Rs {grossAmount, number,#,##0.00}, tax point {taxPointDate}, due {dueDate}.',
     'ඔබට ඉන්වොයිසිය {docNumberDisplay} රු. {grossAmount, number,#,##0.00} ක් සඳහා නිකුත් කර ඇත. බදු දිනය {taxPointDate}, ගෙවිය යුතු දිනය {dueDate}.',
     'உங்களுக்கு விலைப்பட்டியல் {docNumberDisplay} ரூ. {grossAmount, number,#,##0.00} க்கு வழங்கப்பட்டுள்ளது. வரி நாள் {taxPointDate}, செலுத்த வேண்டிய நாள் {dueDate}.',
     '["docNumberDisplay", "grossAmount", "taxPointDate", "dueDate"]', 'ACTIVE'),
    ('payment-received-email', NULL, 'EMAIL',
     'Payment {docNumberDisplay} received',
     'ගෙවීම {docNumberDisplay} ලැබිණි',
     'கொடுப்பனவு {docNumberDisplay} பெறப்பட்டது',
     'Your payment {docNumberDisplay} of Rs {amount, number,#,##0.00} ({method}) was recorded on {receivedOn}. Held on account: Rs {unappliedAmount, number,#,##0.00}.',
     'ඔබේ ගෙවීම {docNumberDisplay}, රු. {amount, number,#,##0.00} ({method}) {receivedOn} දින වාර්තා කෙරිණි. ගිණුමේ රඳවා ඇති මුදල: රු. {unappliedAmount, number,#,##0.00}.',
     'உங்கள் கொடுப்பனவு {docNumberDisplay}, ரூ. {amount, number,#,##0.00} ({method}) {receivedOn} அன்று பதிவு செய்யப்பட்டது. கணக்கில் வைக்கப்பட்ட தொகை: ரூ. {unappliedAmount, number,#,##0.00}.',
     '["docNumberDisplay", "amount", "method", "receivedOn", "unappliedAmount"]', 'ACTIVE'),
    ('cheque-bounced-email', NULL, 'EMAIL',
     'Cheque returned by the bank',
     'චෙක්පත බැංකුව විසින් ආපසු හරවා ඇත',
     'காசோலை வங்கியால் திருப்பி அனுப்பப்பட்டது',
     'A cheque of Rs {amount, number,#,##0.00} was returned by the bank: {reason}. The invoices it paid are open again.',
     'රු. {amount, number,#,##0.00} ක චෙක්පතක් බැංකුව විසින් ආපසු හරවා ඇත: {reason}. එමගින් ගෙවූ ඉන්වොයිසි නැවත විවෘත වී ඇත.',
     'ரூ. {amount, number,#,##0.00} காசோலை வங்கியால் திருப்பி அனுப்பப்பட்டது: {reason}. அதனால் செலுத்தப்பட்ட விலைப்பட்டியல்கள் மீண்டும் திறக்கப்பட்டுள்ளன.',
     '["amount", "reason"]', 'ACTIVE'),
    ('cheque-bounced-sms', NULL, 'SMS',
     NULL, NULL, NULL,
     'Cheque of Rs {amount, number,#,##0.00} returned by the bank. Invoices reopened.',
     'රු. {amount, number,#,##0.00} චෙක්පත බැංකුවෙන් ආපසු හරවා ඇත.',
     'ரூ. {amount, number,#,##0.00} காசோலை வங்கியால் திருப்பி அனுப்பப்பட்டது.',
     '["amount"]', 'ACTIVE'),
    ('exposure-warning-email', NULL, 'EMAIL',
     'Credit exposure at {thresholdPercent}% of the limit',
     'ණය නිරාවරණය සීමාවෙන් {thresholdPercent}% යි',
     'கடன் வெளிப்பாடு வரம்பின் {thresholdPercent}%',
     'An accepted order took the exposure of a buyer to Rs {amount, number,#,##0.00} against a credit limit of Rs {creditLimit, number,#,##0.00} ({thresholdPercent}%).',
     'පිළිගත් ඇණවුමක් නිසා ගැනුම්කරුගේ නිරාවරණය රු. {amount, number,#,##0.00} දක්වා ඉහළ ගොස් ඇත; ණය සීමාව රු. {creditLimit, number,#,##0.00} ({thresholdPercent}%).',
     'ஏற்கப்பட்ட ஆர்டர் ஒன்றால் வாங்குபவரின் வெளிப்பாடு ரூ. {amount, number,#,##0.00} ஆக உயர்ந்துள்ளது; கடன் வரம்பு ரூ. {creditLimit, number,#,##0.00} ({thresholdPercent}%).',
     '["amount", "creditLimit", "thresholdPercent"]', 'ACTIVE'),
    ('writeoff-approval-email', NULL, 'EMAIL',
     'Write-off {documentNo} awaits approval',
     'අපහරණය {documentNo} අනුමැතිය බලාපොරොත්තුවෙන්',
     'தள்ளுபடி {documentNo} ஒப்புதலுக்காக காத்திருக்கிறது',
     'Write-off {documentNo} of Rs {value, number,#,##0.00} (band {band}) was submitted and awaits your approval.',
     'රු. {value, number,#,##0.00} ක අපහරණය {documentNo} (කාණ්ඩය {band}) ඉදිරිපත් කර ඇති අතර ඔබේ අනුමැතිය බලාපොරොත්තු වේ.',
     'ரூ. {value, number,#,##0.00} மதிப்புள்ள தள்ளுபடி {documentNo} (பிரிவு {band}) சமர்ப்பிக்கப்பட்டு உங்கள் ஒப்புதலுக்காக காத்திருக்கிறது.',
     '["documentNo", "value", "band"]', 'ACTIVE');

INSERT INTO integration.notification_rule (
    rule_id, owner_entity_id, event_type, predicate, template_id, audience_kind, audience_spec, channels,
    priority, status, name
)
VALUES
    ('0190f9a0-0000-7000-8000-000000000001', NULL, 'invoice.issued.v1', NULL, 'invoice-issued-email',
     'ROLE_AT_COUNTERPARTY', '{"role": "ACCOUNTS"}', ARRAY['EMAIL'], 100, 'ACTIVE',
     'Invoice issued: the buyer accounts desk'),
    ('0190f9a0-0000-7000-8000-000000000002', NULL, 'payment_receipt.recorded.v1', NULL, 'payment-received-email',
     'ROLE_AT_COUNTERPARTY', '{"role": "ACCOUNTS"}', ARRAY['EMAIL'], 100, 'ACTIVE',
     'Payment received: the buyer accounts desk'),
    ('0190f9a0-0000-7000-8000-000000000003', NULL, 'cheque.bounced.v1', NULL, 'cheque-bounced-email',
     'ROLE_AT_COUNTERPARTY', '{"role": "ACCOUNTS"}', ARRAY['EMAIL'], 100, 'ACTIVE',
     'Cheque bounced: the buyer accounts desk by e-mail'),
    ('0190f9a0-0000-7000-8000-000000000004', NULL, 'cheque.bounced.v1', NULL, 'cheque-bounced-sms',
     'ROLE_AT_COUNTERPARTY', '{"role": "ACCOUNTS"}', ARRAY['SMS'], 100, 'ACTIVE',
     'Cheque bounced: the buyer accounts desk by SMS'),
    ('0190f9a0-0000-7000-8000-000000000005', NULL, 'exposure.warning.v1', NULL, 'exposure-warning-email',
     'ROLE_AT_OWNER', '{"role": "ACCOUNTS"}', ARRAY['EMAIL'], 100, 'ACTIVE',
     'Exposure warning: the seller accounts desk'),
    ('0190f9a0-0000-7000-8000-000000000006', NULL, 'writeoff.submitted.v1', NULL, 'writeoff-approval-email',
     'ROLE_AT_OWNER', '{"role": "MANAGER"}', ARRAY['EMAIL'], 100, 'ACTIVE',
     'Write-off awaiting approval: the society manager');
