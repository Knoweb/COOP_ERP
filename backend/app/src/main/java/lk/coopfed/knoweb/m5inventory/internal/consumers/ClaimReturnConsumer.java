package lk.coopfed.knoweb.m5inventory.internal.consumers;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;

/**
 * ClaimReturnConsumer (25A section 4, "consumers/"; section 6.2): the buyer sent the goods of an
 * approved claim back, so they leave the buyer's lots. 25A binds this to {@code claim.approved.v1};
 * that event is the seller's and is delivered in the seller's scope, which cannot write the
 * buyer's stock, so M4 publishes {@code claim.return_dispatched.v1} from the buyer's own act and
 * this consumes that (m4trading README, "Claims"). This class only reads the payload and hands the
 * command to {@link ApplyClaimReturnHandler}, which audits and publishes.
 *
 * <p>The payload is M4's {@code ClaimReturnDispatched} record (claimId, buyerEntityId, locationId,
 * lines of claimLineId, batchId, qty), read as JSON: M5 imports nothing of M4.
 */
@Component
class ClaimReturnConsumer {

    static final String CONSUMER = "m5.claim-returns";
    static final String RETURN_DISPATCHED = "claim.return_dispatched.v1";

    private final Handles<ApplyClaimReturn, Integer> apply;

    ClaimReturnConsumer(Handles<ApplyClaimReturn, Integer> apply) {
        this.apply = apply;
    }

    @EventConsumer(types = RETURN_DISPATCHED, consumer = CONSUMER)
    public void onReturnDispatched(JsonNode payload, ScopeContext scope) {
        List<ApplyClaimReturn.Line> lines = new ArrayList<>();
        for (JsonNode line : payload.path("lines")) {
            lines.add(new ApplyClaimReturn.Line(
                    Payloads.uuid(line, "claimLineId"), Payloads.uuid(line, "batchId"), Payloads.decimal(line, "qty")));
        }
        apply.handle(
                new ApplyClaimReturn(
                        Payloads.uuid(payload, "claimId"),
                        Payloads.uuid(payload, "buyerEntityId"),
                        Payloads.uuid(payload, "locationId"),
                        lines),
                scope);
    }
}
