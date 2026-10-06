package lk.coopfed.knoweb.m7customers.internal.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ChangeLog;
import lk.coopfed.knoweb.kernel.api.ChangeLog.Change;
import lk.coopfed.knoweb.kernel.api.ChangeLog.Target;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m7customers.api.AccountAdjusted;
import lk.coopfed.knoweb.m7customers.api.AccountCharged;
import lk.coopfed.knoweb.m7customers.api.AccountClosed;
import lk.coopfed.knoweb.m7customers.api.AccountCredited;
import lk.coopfed.knoweb.m7customers.api.AccountLimitsAmended;
import lk.coopfed.knoweb.m7customers.api.AccountOpened;
import lk.coopfed.knoweb.m7customers.api.AccountReinstated;
import lk.coopfed.knoweb.m7customers.api.AccountReopened;
import lk.coopfed.knoweb.m7customers.api.AccountSuspended;
import lk.coopfed.knoweb.m7customers.api.CustomerAnonymised;
import lk.coopfed.knoweb.m7customers.api.CustomerDeactivated;
import lk.coopfed.knoweb.m7customers.api.CustomerPaymentRecorded;
import lk.coopfed.knoweb.m7customers.api.CustomerPaymentReversed;
import lk.coopfed.knoweb.m7customers.api.CustomerPhoneChanged;
import lk.coopfed.knoweb.m7customers.api.CustomerUpdated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The change-log fan-out of the customer snapshot (27A section 7.3: "customer.*, account.* ...
 * UPSERT; account.suspended urgent"; wave 2, M7CR-14; {@code 2026-10-06-wave2-m7-credit-book.md}
 * (6)). A consumer of the module's own events, {@code m7.snapshot}, not the hot path of the
 * handlers (19A: a producer that fans out many rows does so from a worker consumer of its own
 * event): for every event that changes a customer's row in the tills' snapshot it lists the
 * society's shops ({@link SocietyShops}) and appends one {@code UPSERT} of {@code customer /
 * customer id} for each. Always an UPSERT: when the contributor no longer returns the row (a closed
 * account, an INACTIVE or anonymised customer) the kernel's {@code SnapshotBuilder} turns the
 * entry into the tombstone itself.
 *
 * <p>Urgent (the tills download at their next heartbeat) for a suspension, a close, a reopening, a
 * deactivation and an erasure: those change what a till may accept. Not urgent for a new limit or
 * a balance. No coalescing: a row per charge per shop at the change log's thirty days of retention
 * is acceptable, and the builder's {@code lastChangePerRow} dedupes per delta. The identity events
 * (names, phone) are UPSERTs too; a registration alone is not in the snapshot (no account yet).
 */
@Component
class CustomerChangeLogFanOut {

    private static final Logger log = LoggerFactory.getLogger(CustomerChangeLogFanOut.class);

    static final String CONSUMER = "m7.snapshot";
    static final String TABLE = CustomerSnapshotContributor.CUSTOMER;

    /** The events after which a till must stop accepting, or start refusing, the customer's tenders. */
    static final Set<String> URGENT = Set.of(
            AccountSuspended.TYPE,
            AccountClosed.TYPE,
            AccountReopened.TYPE,
            CustomerDeactivated.TYPE,
            CustomerAnonymised.TYPE);

    private final ChangeLog changeLog;
    private final SocietyShops shops;

    CustomerChangeLogFanOut(ChangeLog changeLog, SocietyShops shops) {
        this.changeLog = changeLog;
        this.shops = shops;
    }

    @EventConsumer(
            types = {
                AccountSuspended.TYPE,
                AccountClosed.TYPE,
                AccountReopened.TYPE,
                CustomerDeactivated.TYPE,
                CustomerAnonymised.TYPE
            },
            consumer = CONSUMER)
    public void onStateChanged(JsonNode payload, ScopeContext scope) {
        fanOut(payload, scope, true);
    }

    @EventConsumer(
            types = {
                AccountOpened.TYPE,
                AccountReinstated.TYPE,
                AccountLimitsAmended.TYPE,
                CustomerUpdated.TYPE,
                CustomerPhoneChanged.TYPE,
                AccountCharged.TYPE,
                AccountCredited.TYPE,
                CustomerPaymentRecorded.TYPE,
                CustomerPaymentReversed.TYPE,
                AccountAdjusted.TYPE
            },
            consumer = CONSUMER)
    public void onRowChanged(JsonNode payload, ScopeContext scope) {
        fanOut(payload, scope, false);
    }

    /** Every M7 event carries the customer and the owning society as ids; nothing else is read. */
    void fanOut(JsonNode payload, ScopeContext scope, boolean urgent) {
        UUID customerId = uuid(payload, "customerId");
        UUID society = uuid(payload, "ownerEntityId");
        if (customerId == null || society == null) {
            throw new IllegalArgumentException("An M7 event names its customer and its society");
        }
        List<UUID> shopIds = shops.of(society, scope);
        if (shopIds.isEmpty()) {
            log.debug("Society {} has no shop: nothing to fan out for customer {}", society, customerId);
            return;
        }
        List<Target> targets =
                shopIds.stream().map(shop -> new Target(society, shop)).toList();
        changeLog.append(targets, List.of(Change.upsert(TABLE, customerId)), null, urgent, scope);
    }

    private static UUID uuid(JsonNode payload, String field) {
        JsonNode value = payload.path(field);
        return value.isMissingNode() || value.isNull() ? null : UUID.fromString(value.asText());
    }
}
