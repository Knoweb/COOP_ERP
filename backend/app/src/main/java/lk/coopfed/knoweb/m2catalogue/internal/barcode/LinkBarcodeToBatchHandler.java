package lk.coopfed.knoweb.m2catalogue.internal.barcode;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.BarcodeLinked;
import lk.coopfed.knoweb.m2catalogue.api.LinkBarcodeToBatch;
import lk.coopfed.knoweb.m2catalogue.internal.barcode.BarcodeStore.BarcodeRow;
import lk.coopfed.knoweb.m2catalogue.internal.sku.SkuAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * LinkBarcodeToBatch (22A section 6, doc 22 section 4.2): owner of the row; the row is ACTIVE;
 * the batch belongs to the SKU. Mutation: batch_id. Relinking an already linked code is the
 * same command again (ACTIVE to ACTIVE, "relinked").
 */
@Service
@CommandHandler(permission = "cat.barcode.manage")
public class LinkBarcodeToBatchHandler implements Handles<LinkBarcodeToBatch, UUID> {

    static final String AUDIT_LINKED = "BARCODE_LINKED";

    private final SkuAccess skus;
    private final BarcodeStore store;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    LinkBarcodeToBatchHandler(
            SkuAccess skus, BarcodeStore store, JdbcTemplate jdbc, AuditFacade audit, EventPublisher events) {
        this.skus = skus;
        this.store = store;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(LinkBarcodeToBatch command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }

        String barcode = BarcodeGuards.requiredBarcode(command.barcode());
        String symbology = BarcodeGuards.requiredSymbology(command.symbology());

        UUID skuId = skus.requireVisible(command.skuId(), scope).skuId();

        BarcodeRow before = BarcodeGuards.requireOwnActive(store, skuId, barcode, symbology, scope);

        BarcodeGuards.requireBatchOfSku(store, command.batchId(), skuId);

        jdbc.update(
                """
                update catalogue.sku_barcode
                set batch_id = ?
                where barcode = ? and symbology = ? and owner_entity_id = ?
                """,
                command.batchId(),
                barcode,
                symbology,
                scope.entityId());

        BarcodeRow after = new BarcodeRow(
                before.barcode(),
                before.symbology(),
                before.skuId(),
                before.uomCode(),
                command.batchId(),
                before.ownerEntityId(),
                before.status());

        audit.record(AUDIT_LINKED, Subject.of("sku", skuId), before.auditState(), after.auditState(), scope);

        events.publish(new BarcodeLinked(
                skuId, after.ownerEntityId(), after.barcode(), after.symbology(), after.uomCode(), after.batchId()));

        return skuId;
    }
}
