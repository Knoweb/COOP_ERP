package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * ApproveClaim (24A section 6; doc 24 section 4.5): the seller accepts the claim, in whole or in
 * part, and credits its invoice of the GRN for what it accepts, in the same act. With the return
 * required the buyer sends the accepted goods back ({@link DispatchClaimReturn}).
 *
 * @param lines the accepted quantity of claimed lines; empty accepts every line in full
 */
public record ApproveClaim(UUID claimId, String findings, boolean returnRequired, List<Line> lines) {

    public ApproveClaim {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** What is accepted of one claimed line: zero or more, at most the claimed quantity. */
    public record Line(UUID claimLineId, BigDecimal qty) {}
}
