package lk.coopfed.knoweb.m4trading.internal.transfer;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** The guards the society's two decisions share. */
final class TransferDecisions {

    private TransferDecisions() {}

    /** A request of the caller's entity, locked for the decision and not decided yet: a request is decided once. */
    static TransferRequestReads.Request undecided(
            TransferRequestReads requests, JdbcTemplate jdbc, UUID requestId, ScopeContext scope) {
        TransferRequestReads.Request request = requests.request(requestId)
                .filter(found -> scope.entityId().equals(found.ownerEntityId()))
                .orElseThrow(() -> new ProblemException("m4.transfer.request_not_found"));
        jdbc.queryForList("select pg_advisory_xact_lock(hashtext(?::text))", "transfer-request-" + requestId);
        if (!TransferRequestReads.REQUESTED.equals(
                requests.request(requestId).orElseThrow().status())) {
            throw new ProblemException("m4.transfer.decided");
        }
        return request;
    }
}
