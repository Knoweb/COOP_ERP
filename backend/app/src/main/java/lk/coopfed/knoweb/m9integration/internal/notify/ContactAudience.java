package lk.coopfed.knoweb.m9integration.internal.notify;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.NotificationAudience;
import lk.coopfed.knoweb.kernel.api.NotificationRuleQueries.AudienceKind;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The AudienceResolver of 29A section 6.3 for the two role kinds: ROLE_AT_OWNER reaches the
 * entity whose event it is, ROLE_AT_COUNTERPARTY the other party of the document (the kernel
 * passes it). The role code names the entity's contacts in {@code notification_contact}: M1
 * keeps no telephone or e-mail of its users (doc 21 section 9.3), so the people behind a role
 * are reached at the addresses the entity gave for it (decided on the architect's delegation,
 * 29 September 2026; README). One bean per kind; only the channels the rule sends on are
 * returned, each in the contact's language.
 */
class ContactAudience implements NotificationAudience {

    private final AudienceKind kind;
    private final JdbcTemplate jdbc;

    ContactAudience(AudienceKind kind, JdbcTemplate jdbc) {
        if (kind != AudienceKind.ROLE_AT_OWNER && kind != AudienceKind.ROLE_AT_COUNTERPARTY) {
            throw new IllegalArgumentException("A contact audience is a role kind, not " + kind);
        }
        this.kind = kind;
        this.jdbc = jdbc;
    }

    @Override
    public AudienceKind kind() {
        return kind;
    }

    @Override
    public List<Recipient> resolve(UUID ownerEntityId, UUID counterpartyEntityId, String spec, List<String> channels) {
        UUID entity = kind == AudienceKind.ROLE_AT_OWNER ? ownerEntityId : counterpartyEntityId;
        if (entity == null || spec == null || spec.isBlank() || channels == null || channels.isEmpty()) {
            return List.of();
        }
        return jdbc
                .query(
                        // The one door across entities (m9integration V0003): the table itself
                        // answers an entity its own contacts only.
                        "select channel, address, language from integration.notification_recipients(?, ?)",
                        // The entity and the role go on the kernel's log, so its screen says who
                        // was reached without a hash, and the entity's quiet hours hold (wave 2,
                        // CR-19A-12).
                        (rs, i) -> new Recipient(
                                rs.getString("channel"),
                                rs.getString("address"),
                                rs.getString("language").strip(),
                                entity,
                                spec),
                        entity,
                        spec)
                .stream()
                .filter(recipient -> channels.contains(recipient.channel()))
                .toList();
    }
}
