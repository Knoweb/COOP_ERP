package lk.coopfed.knoweb.m1party.internal.relationship;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** What {@link CreditLimitBackfillJob} reads: the pairs whose limit no event has announced yet. */
@Component
class CreditLimitBackfillRows {

    /** A relationship to announce, and the seller in whose scope the announcement is made. */
    record Due(UUID relationshipId, UUID sellerEntityId) {}

    private final JdbcTemplate jdbc;

    CreditLimitBackfillRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * For each pair with an ACTIVE row carrying a limit, in force on {@code today} or later, and
     * no announcement: the earliest such row (the one in force, or the next one). Read across
     * every entity in the scope the job passes (the federation-wide view): public and
     * transactional with a ScopeContext argument, which is what the kernel's connection
     * customizer applies.
     */
    @Transactional(readOnly = true)
    public List<Due> due(ScopeContext scope, LocalDate today) {
        return jdbc.query(
                """
                select distinct on (r.seller_entity_id, r.buyer_entity_id) r.relationship_id, r.seller_entity_id
                  from party.entity_relationship r
                 where r.status = 'ACTIVE'
                   and r.credit_limit is not null
                   and (r.effective_to is null or r.effective_to >= ?)
                   and not exists (select 1 from security.credit_limit_announcement a
                                    where a.owner_entity_id = r.seller_entity_id
                                      and a.buyer_entity_id = r.buyer_entity_id)
                 order by r.seller_entity_id, r.buyer_entity_id, r.effective_from, r.relationship_id
                """,
                (rs, i) -> new Due(
                        rs.getObject("relationship_id", UUID.class), rs.getObject("seller_entity_id", UUID.class)),
                today);
    }
}
