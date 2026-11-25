package lk.coopfed.knoweb.m3pricing.internal.list;

import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m3pricing.api.PriceListWithdrawn;
import lk.coopfed.knoweb.m3pricing.api.WithdrawDraft;
import lk.coopfed.knoweb.m3pricing.query.PriceListView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** WithdrawDraft: only an owner may withdraw its unpublished draft. */
@Service
@CommandHandler(permission = "prc.pricelist.author")
public class WithdrawDraftHandler implements Handles<WithdrawDraft, UUID> {

    static final String AUDIT_WITHDRAWN = "PRICELIST_WITHDRAWN";

    private final JdbcTemplate jdbc;
    private final PriceListStore store;
    private final AuditFacade audit;
    private final EventPublisher events;

    WithdrawDraftHandler(JdbcTemplate jdbc, PriceListStore store, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.store = store;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(WithdrawDraft command, ScopeContext scope) {
        if (command == null || command.priceListId() == null) {
            throw new ProblemException("request.invalid");
        }

        PriceListRules.requireOwnerScope(scope);

        PriceListView list = store.findForUpdate(command.priceListId())
                .filter(found -> found.ownerEntityId().equals(scope.entityId()))
                .orElseThrow(() -> new ProblemException("m3.price_list.not_found"));

        if (!PriceListStore.DRAFT.equals(list.status())) {
            throw new ProblemException("m3.price_list.not_draft");
        }

        if (command.reason() == null || command.reason().isBlank()) {
            throw new ProblemException("request.field.required", Map.of("field", "reason"));
        }

        jdbc.update(
                "update pricing.price_list set status = 'WITHDRAWN'" + " where price_list_id = ? and status = 'DRAFT'",
                list.priceListId());

        audit.record(
                AUDIT_WITHDRAWN,
                Subject.of("price_list", list.priceListId()),
                Map.of("status", PriceListStore.DRAFT),
                Map.of("status", "WITHDRAWN", "reason", command.reason().strip()),
                scope);

        events.publish(new PriceListWithdrawn(
                list.priceListId(), list.rootPriceListId(), list.ownerEntityId(), list.kind(), list.version()));

        return list.priceListId();
    }
}
