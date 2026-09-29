package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * RaiseClaim (24A section 6; doc 24 sections 3.5 and 4.5): the buyer claims against the seller for
 * goods of a confirmed GRN found later to be damaged, expired on arrival, the wrong goods or of poor
 * quality, within {@code trading.claim_window_days} of the confirmation. Photographs are added to
 * the raised claim afterwards ({@link AddClaimPhoto}).
 *
 * @param kind            DAMAGED, EXPIRED_ON_ARRIVAL, WRONG_GOODS or QUALITY
 * @param returnRequested the buyer offers the goods back; the seller decides
 */
public record RaiseClaim(UUID grnId, String kind, boolean returnRequested, String note, List<Line> lines) {

    public RaiseClaim {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** A quantity of one GRN line, at most what was received less what earlier claims hold. */
    public record Line(UUID grnLineId, BigDecimal qty) {}
}
