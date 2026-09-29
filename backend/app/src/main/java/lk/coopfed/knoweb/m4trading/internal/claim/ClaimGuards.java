package lk.coopfed.knoweb.m4trading.internal.claim;

import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Attachments;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import org.springframework.jdbc.core.JdbcTemplate;

/** The guards the seller's decisions share. */
final class ClaimGuards {

    private ClaimGuards() {}

    /**
     * The claim raised with the seller, locked for the decision (an advisory lock per claim), and
     * undecided: a claim is decided once.
     */
    static ClaimReads.Claim claimWith(ClaimReads claims, JdbcTemplate jdbc, UUID claimId, UUID seller) {
        ClaimReads.Claim claim = claims.claim(claimId)
                .filter(found -> seller.equals(found.header().counterpartyEntityId()))
                .orElseThrow(() -> new ProblemException("m4.claim.not_found"));
        jdbc.queryForList("select pg_advisory_xact_lock(hashtext(?::text))", "claim-decide-" + claimId);
        if (claims.decision(claimId).isPresent()) {
            throw new ProblemException("m4.claim.decided");
        }
        return claim;
    }

    /** Doc 24 section 4.5: pending uploads are allowed, but every photograph is COMPLETE before a decision. */
    static void requireEvidenceComplete(ClaimReads.Claim claim, Attachments attachments) {
        for (UUID photo : claim.photoIds()) {
            String status = attachments.status(photo).orElse("PENDING");
            if (!"COMPLETE".equals(status)) {
                throw new ProblemException(
                        "m4.claim.evidence_pending", Map.of("attachmentId", photo, "status", status));
            }
        }
    }
}
