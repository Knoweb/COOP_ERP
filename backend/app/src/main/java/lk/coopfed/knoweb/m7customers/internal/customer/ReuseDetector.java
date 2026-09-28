package lk.coopfed.knoweb.m7customers.internal.customer;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m7customers.internal.ledger.CustomersClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Phone reuse detection (27A section 6.1). A number is reused in Sri Lanka: an operator hands a
 * number given up months ago to somebody else. So:
 * <ul>
 *   <li>a customer who holds the number now (current primary) is a CONFLICT: that customer's phone
 *       must be changed first;</li>
 *   <li>a customer who let the number go within the window ({@code customers.phone_reuse_window_months})
 *       NEEDS_CONFIRMATION: the officer confirms the new holder is another person (name, and the
 *       NIC's last four when an account exists), and the command carries {@code confirmedIdentity};</li>
 *   <li>otherwise the number is CLEAR.</li>
 * </ul>
 * Row-level security hides the phones of other societies, so the holders come from {@code
 * customers.phone_holders}, which answers ids only, never a name.
 */
@Component
public class ReuseDetector {

    static final String WINDOW_KEY = "customers.phone_reuse_window_months";
    static final int DEFAULT_WINDOW_MONTHS = 12;

    enum Kind {
        CLEAR,
        CONFLICT,
        NEEDS_CONFIRMATION
    }

    /**
     * What the check found.
     *
     * @param holders the customers concerned: the current holder, or the recent holders
     * @param holderEntityId the society that registered the current holder (CONFLICT only)
     */
    record Outcome(Kind kind, List<UUID> holders, UUID holderEntityId) {}

    private final JdbcTemplate jdbc;
    private final ConfigRegistry config;
    private final CustomersClock clock;

    ReuseDetector(JdbcTemplate jdbc, ConfigRegistry config, CustomersClock clock) {
        this.jdbc = jdbc;
        this.config = config;
        this.clock = clock;
    }

    /** 27A section 6.1, for {@code phone} (E.164) and the customer about to hold it (null when new). */
    Outcome check(String phone, UUID forCustomer, ScopeContext scope) {
        record Holder(UUID customerId, UUID ownerEntityId, Instant validTo) {}
        List<Holder> holders = jdbc.query(
                "select customer_id, owner_entity_id, valid_to from customers.phone_holders(?)",
                (rs, n) -> {
                    Timestamp validTo = rs.getTimestamp("valid_to");
                    return new Holder(
                            rs.getObject("customer_id", UUID.class),
                            rs.getObject("owner_entity_id", UUID.class),
                            validTo == null ? null : validTo.toInstant());
                },
                phone);
        for (Holder holder : holders) {
            if (holder.validTo() == null && !holder.customerId().equals(forCustomer)) {
                return new Outcome(Kind.CONFLICT, List.of(holder.customerId()), holder.ownerEntityId());
            }
        }
        int months = config.getInt(WINDOW_KEY, scope, DEFAULT_WINDOW_MONTHS);
        Instant since = clock.now().atOffset(ZoneOffset.UTC).minusMonths(months).toInstant();
        List<UUID> recent = holders.stream()
                .filter(h -> h.validTo() != null && h.validTo().isAfter(since))
                .map(Holder::customerId)
                .filter(id -> !id.equals(forCustomer))
                .distinct()
                .toList();
        return recent.isEmpty()
                ? new Outcome(Kind.CLEAR, List.of(), null)
                : new Outcome(Kind.NEEDS_CONFIRMATION, recent, null);
    }

    /**
     * The check turned into the handlers' guard: a CONFLICT is refused (with the holder's id when
     * the caller's society registered them, so the screen can open that card; with nothing when
     * another society did), a NEEDS_CONFIRMATION is refused until confirmed. Answers the previous
     * holders a confirmation covered, for the audit record (ids only).
     */
    List<UUID> guard(String phone, UUID forCustomer, boolean confirmed, ScopeContext scope) {
        Outcome outcome = check(phone, forCustomer, scope);
        switch (outcome.kind()) {
            case CONFLICT -> {
                if (scope.entityId().equals(outcome.holderEntityId())) {
                    throw new ProblemException(
                            "m7.customer.phone_held",
                            Map.of("customerId", outcome.holders().get(0).toString()));
                }
                throw new ProblemException("m7.customer.phone_held_elsewhere");
            }
            case NEEDS_CONFIRMATION -> {
                if (!confirmed) {
                    throw new ProblemException(
                            "m7.customer.phone_reuse_confirm",
                            Map.of("holders", outcome.holders().size()));
                }
                return outcome.holders();
            }
            default -> {
                return List.of();
            }
        }
    }
}
