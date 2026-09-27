package lk.coopfed.knoweb.m4trading.internal.document;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.NumberingService;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.SeriesRegistration;
import lk.coopfed.knoweb.m1party.query.EntityView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import org.springframework.stereotype.Component;

/**
 * The ENTITY series of a trading document type, registered before the type's first issuance by
 * its issuer (24B: ORD from the buyer's series, DN and INV from the seller's, a warehouse GRN from
 * the receiver's). Nothing else registers an ENTITY series (M1's SeriesHooks registers LOCATION
 * and TILL_POSITION series only), and {@link NumberingService#registerSeries} is idempotent and
 * audited once, so this is called on every issuance, inside the issuing transaction.
 */
@Component
public class TradingSeries {

    private final NumberingService numbering;
    private final PartyQueries parties;

    TradingSeries(NumberingService numbering, PartyQueries parties) {
        this.numbering = numbering;
        this.parties = parties;
    }

    public UUID ensureEntitySeries(String docTypeCode, ScopeContext scope) {
        UUID entityId = scope.entityId();
        String entityCode = parties.getEntity(entityId, scope)
                .map(EntityView::entityCode)
                .orElseThrow(() ->
                        new ProblemException("m4.party.entity_not_found", java.util.Map.of("entityId", entityId)));
        return numbering.registerSeries(SeriesRegistration.forEntity(docTypeCode, entityId, entityCode), scope);
    }
}
