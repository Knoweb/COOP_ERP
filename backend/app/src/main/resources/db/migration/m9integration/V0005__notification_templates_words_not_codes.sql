-- A notification names the tender and the approval band in words, in the recipient's language
-- (demo re-check, 29 September 2026): the payment mail said "(CHEQUE)" and the write-off mail
-- "(කාණ්ඩය 1)". The words live in the template text, next to the rest of the sentence, as ICU
-- choices on the payload value: the tender is one of the four methods of m4trading V0006
-- (select; an unknown code still shows as it is), the band is ControlPolicy's 1, 2 or 3 (a
-- number, so plural with exact values). The words are the ones the screens use
-- (trading.payment.method.*). V0002 and V0004 are merged and never edited.
UPDATE integration.notification_template
   SET body_en = replace(body_en, '({method})',
           '({method, select, CASH {cash} CHEQUE {cheque} TRANSFER {bank transfer} DEPOSIT {deposit} other {{method}}})'),
       body_si = replace(body_si, '({method})',
           '({method, select, CASH {මුදල්} CHEQUE {චෙක්පත} TRANSFER {බැංකු හුවමාරුව} DEPOSIT {තැන්පතුව} other {{method}}})'),
       body_ta = replace(body_ta, '({method})',
           '({method, select, CASH {பணம்} CHEQUE {காசோலை} TRANSFER {வங்கி மாற்றம்} DEPOSIT {வைப்பு} other {{method}}})')
 WHERE owner_entity_id IS NULL
   AND template_id = 'payment-received-email';

UPDATE integration.notification_template
   SET body_en = replace(body_en, '(band {band})',
           '({band, plural, =1 {low-value band} =2 {middle-value band} other {high-value band}})'),
       body_si = replace(body_si, '(කාණ්ඩය {band})',
           '({band, plural, =1 {අඩු වටිනාකම් කාණ්ඩය} =2 {මධ්‍යම වටිනාකම් කාණ්ඩය} other {ඉහළ වටිනාකම් කාණ්ඩය}})'),
       body_ta = replace(body_ta, '(பிரிவு {band})',
           '({band, plural, =1 {குறைந்த மதிப்புப் பிரிவு} =2 {நடுத்தர மதிப்புப் பிரிவு} other {உயர் மதிப்புப் பிரிவு}})')
 WHERE owner_entity_id IS NULL
   AND template_id = 'writeoff-approval-email';
