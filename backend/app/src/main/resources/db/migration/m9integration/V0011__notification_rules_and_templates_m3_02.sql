-- M9 Integration: Notification Rules and Templates for PGL-M3-02
-- Low-stock alert (M5), customer price-update notification (M3), customer birthday greeting (M7).
-- Federation-owned (owner null), ACTIVE.

INSERT INTO integration.notification_template (
    template_id, owner_entity_id, channel, subject_en, subject_si, subject_ta, body_en, body_si, body_ta,
    placeholders_schema, status
)
VALUES
    ('low-stock-alert-sms', NULL, 'SMS',
     NULL, NULL, NULL,
     'Low stock alert: SKU {skuId} at location {locationId} is at {onHandQty} (reorder level: {reorderLevel}). Please reorder.',
     'අඩු තොග අනතුරු ඇඟවීම: ස්ථානය {locationId} හි SKU {skuId} තොගය {onHandQty} කි (නැවත ඇණවුම් මට්ටම: {reorderLevel}). කරුණාකර නැවත ඇණවුම් කරන්න.',
     'குறைந்த கையிருப்பு எச்சரிக்கை: இடம் {locationId} இல் SKU {skuId} கையிருப்பு {onHandQty} ஆக உள்ளது (மறுவரிசை நிலை: {reorderLevel}). தயவுசெய்து மீண்டும் ஆர்டர் செய்யவும்.',
     '["skuId", "locationId", "onHandQty", "reorderLevel"]', 'ACTIVE'),
    ('price-update-sms', NULL, 'SMS',
     NULL, NULL, NULL,
     'Price Update Notice: {kind} price list (v{version}) is now active, effective from {applyFrom}. Visit your local branch for special rates.',
     'මිල යාවත්කාලීන කිරීමේ නිවේදනය: {kind} මිල ලැයිස්තුව (v{version}) {applyFrom} දින සිට සක්‍රීය වේ. විශේෂ මිල ගණන් සඳහා ඔබගේ ප්‍රාදේශීය ශාඛාවට පැමිණෙන්න.',
     'விலை புதுப்பிப்பு அறிவிப்பு: {kind} விலைப்பட்டியல் (v{version}) {applyFrom} முதல் நடைமுறைக்கு வருகிறது. சிறப்பு கட்டணங்களுக்கு உங்கள் உள்ளூர் கிளையைப் பார்வையிடவும்.',
     '["kind", "version", "applyFrom"]', 'ACTIVE'),
    ('birthday-greeting-sms', NULL, 'SMS',
     NULL, NULL, NULL,
     'Happy Birthday from your Cooperative Society! Wishing valued member {customerRef} health and prosperity in {greetingYear}.',
     'ඔබගේ සමුපකාර සමිතියෙන් සුබ උපන්දිනයක්! {greetingYear} වසරේදී {customerRef} සාමාජිකයාට සුවය සහ සෞභාග්‍යය ප්‍රාර්ථනා කරමු.',
     'உங்கள் கூட்டுறவு சங்கத்திலிருந்து பிறந்தநாள் வாழ்த்துக்கள்! {greetingYear} இல் {customerRef} உறுப்பினர் நலம் மற்றும் செழிப்பு பெற வாழ்த்துகிறோம்.',
     '["customerRef", "greetingYear"]', 'ACTIVE');

INSERT INTO integration.notification_rule (
    rule_id, owner_entity_id, event_type, predicate, template_id, audience_kind, audience_spec, channels,
    priority, status, name
)
VALUES
    ('0190f9a0-0000-7000-8000-000000000007', NULL, 'inventory.low_stock_detected.v1', NULL, 'low-stock-alert-sms',
     'ROLE_AT_OWNER', '{"role": "STORE_KEEPER"}', ARRAY['SMS'], 100, 'ACTIVE',
     'Low stock: store keeper alert by SMS'),
    ('0190f9a0-0000-7000-8000-000000000008', NULL, 'price_list.published.v1', NULL, 'price-update-sms',
     'ROLE_AT_OWNER', '{"role": "CUSTOMER"}', ARRAY['SMS'], 100, 'ACTIVE',
     'Price update: customer notice by SMS'),
    ('0190f9a0-0000-7000-8000-000000000009', NULL, 'customer.birthday_alert.v1', NULL, 'birthday-greeting-sms',
     'CUSTOMER', '{"from_payload": "customerId"}', ARRAY['SMS'], 100, 'ACTIVE',
     'Birthday greeting: member SMS alert');
