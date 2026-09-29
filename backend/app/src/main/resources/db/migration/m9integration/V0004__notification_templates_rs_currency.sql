-- Money in a notification reads "Rs 1,234.00" in every language (doc 19 section 5.1): the
-- phase-1 templates of V0002 wrote the Sinhala and Tamil amounts with the local abbreviations.
-- The number pattern (#,##0.00, Latin digits) was already the same in the three languages.
UPDATE integration.notification_template
   SET body_si = replace(body_si, 'රු. {', 'Rs {'),
       body_ta = replace(body_ta, 'ரூ. {', 'Rs {'),
       subject_si = replace(subject_si, 'රු. {', 'Rs {'),
       subject_ta = replace(subject_ta, 'ரூ. {', 'Rs {')
 WHERE owner_entity_id IS NULL
   AND template_id IN ('invoice-issued-email', 'payment-received-email', 'cheque-bounced-email',
                       'cheque-bounced-sms', 'exposure-warning-email', 'writeoff-approval-email');
