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
 * customers.phone_holders}, which (wave 2, RLS-03) answers an OWN caller its own customers' ids,
 * whether the caller holds the number, and when each holder let it go; another society's customer
 * is a row with no id. Nothing here names a person.
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
     * @param ownHolders   the customers concerned that the caller's society registered: the current
     *                     holder, or the recent holders
     * @param otherHolders how many customers of other societies are concerned
     * @param heldByCaller whether the current holder (CONFLICT only) is the caller's customer
     */
    record Outcome(Kind kind, List<UUID> ownHolders, int otherHolders, boolean heldByCaller) {}

    /** The previous holders a confirmation covered, for the audit record: own ids and a count of others. */
    public record Confirmed(List<UUID> ownHolders, int otherHolders) {

        static final Confirmed NONE = new Confirmed(List.of(), 0);

        /** Writes itself into an audit map, only when there is something to say. */
        public void describe(Map<String, Object> after) {
            if (!ownHolders.isEmpty()) {
                after.put("previousHolders", ownHolders);
            }
            if (otherHolders > 0) {
                after.put("previousHoldersElsewhere", otherHolders);
            }
        }
    }

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
        record Holder(UUID ownCustomerId, boolean heldByCaller, Instant validTo) {}
        List<Holder> holders = jdbc.query(
                "select own_customer_id, held_by_caller, valid_to from customers.phone_holders(?)",
                (rs, n) -> {
                    Timestamp validTo = rs.getTimestamp("valid_to");
                    return new Holder(
                            rs.getObject("own_customer_id", UUID.class),
                            rs.getBoolean("held_by_caller"),
                            validTo == null ? null : validTo.toInstant());
                },
                phone);
        for (Holder holder : holders) {
            boolean itself =
                    holder.ownCustomerId() != null && holder.ownCustomerId().equals(forCustomer);
            if (holder.validTo() == null && !itself) {
                return new Outcome(
                        Kind.CONFLICT,
                        holder.ownCustomerId() == null ? List.of() : List.of(holder.ownCustomerId()),
                        holder.ownCustomerId() == null ? 1 : 0,
                        holder.heldByCaller());
            }
        }
        int months = config.getInt(WINDOW_KEY, scope, DEFAULT_WINDOW_MONTHS);
        Instant since = clock.now().atOffset(ZoneOffset.UTC).minusMonths(months).toInstant();
        List<Holder> recent = holders.stream()
                .filter(h -> h.validTo() != null && h.validTo().isAfter(since))
                .filter(h -> h.ownCustomerId() == null || !h.ownCustomerId().equals(forCustomer))
                .toList();
        List<UUID> own = recent.stream()
                .map(Holder::ownCustomerId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        int others =
                (int) recent.stream().filter(h -> h.ownCustomerId() == null).count();
        return recent.isEmpty()
                ? new Outcome(Kind.CLEAR, List.of(), 0, false)
                : new Outcome(Kind.NEEDS_CONFIRMATION, own, others, false);
    }

    /**
     * The check turned into the handlers' guard: a CONFLICT is refused (with the holder's id when
     * the caller's society registered them, so the screen can open that card; with nothing when
     * another society did), a NEEDS_CONFIRMATION is refused until confirmed. Answers the previous
     * holders a confirmation covered, for the audit record (own ids and a count only).
     */
    Confirmed guard(String phone, UUID forCustomer, boolean confirmed, ScopeContext scope) {
        Outcome outcome = check(phone, forCustomer, scope);
        switch (outcome.kind()) {
            case CONFLICT -> {
                if (outcome.heldByCaller() && !outcome.ownHolders().isEmpty()) {
                    throw new ProblemException(
                            "m7.customer.phone_held",
                            Map.of("customerId", outcome.ownHolders().get(0).toString()));
                }
                throw new ProblemException("m7.customer.phone_held_elsewhere");
            }
            case NEEDS_CONFIRMATION -> {
                if (!confirmed) {
                    throw new ProblemException(
                            "m7.customer.phone_reuse_confirm",
                            Map.of("holders", outcome.ownHolders().size() + outcome.otherHolders()));
                }
                return new Confirmed(outcome.ownHolders(), outcome.otherHolders());
            }
            default -> {
                return Confirmed.NONE;
            }
        }
    }
}
