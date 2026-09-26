package lk.coopfed.knoweb.m2catalogue.internal.barcode;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.BarcodeRetired;
import lk.coopfed.knoweb.m2catalogue.api.RetireBarcode;
import lk.coopfed.knoweb.m2catalogue.internal.barcode.BarcodeStore.BarcodeRow;
import lk.coopfed.knoweb.m2catalogue.internal.sku.SkuAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RetireBarcode (22A section 6, doc 22 section 4.2): owner of the row; the row is ACTIVE; a
 * reason. Mutation: status RETIRED. A retired code is never reused for another item by
 * changing it; it stays as history and the barcode_factory_unique index frees the code.
 */
@Service
@CommandHandler(permission = "cat.barcode.manage")
public class RetireBarcodeHandler implements Handles<RetireBarcode, UUID> {

    static final String AUDIT_RETIRED = "BARCODE_RETIRED";

    private final SkuAccess skus;
    private final BarcodeStore store;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    RetireBarcodeHandler(
            SkuAccess skus, BarcodeStore store, JdbcTemplate jdbc, AuditFacade audit, EventPublisher events) {
        this.skus = skus;
        this.store = store;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RetireBarcode command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }

        String barcode = BarcodeGuards.requiredBarcode(command.barcode());
        String symbology = BarcodeGuards.requiredSymbology(command.symbology());
        String reason = reason(command.reasonCode(), command.reasonText());

        // The item must be visible to the caller (its own, or SHARED for an INTERNAL sticker);
        // the row must be the caller's own, which requireOwnActive reads by the caller's entity.
        UUID skuId = skus.requireVisible(command.skuId(), scope).skuId();

        BarcodeRow before = BarcodeGuards.requireOwnActive(store, skuId, barcode, symbology, scope);

        jdbc.update(
                """
                update catalogue.sku_barcode
                set status = ?
                where barcode = ? and symbology = ? and owner_entity_id = ?
                """,
                BarcodeGuards.RETIRED,
                barcode,
                symbology,
                scope.entityId());

        BarcodeRow after = new BarcodeRow(
                before.barcode(),
                before.symbology(),
                before.skuId(),
                before.uomCode(),
                before.batchId(),
                before.ownerEntityId(),
                BarcodeGuards.RETIRED);

        audit.record(
                AUDIT_RETIRED, Subject.of("sku", skuId), before.auditState(), after.auditState(), scope, reason, null);

        events.publish(new BarcodeRetired(
                skuId, after.ownerEntityId(), after.barcode(), after.symbology(), after.uomCode(), after.batchId()));

        return skuId;
    }

    /** As SkuGuards.reason: a code, a text, or both; at least one. */
    private static String reason(String reasonCode, String reasonText) {
        String code = reasonCode == null || reasonCode.isBlank() ? null : reasonCode.strip();
        String text = reasonText == null || reasonText.isBlank() ? null : reasonText.strip();

        if (code == null && text == null) {
            throw new ProblemException("m2.barcode.reason_required");
        }
        if (code == null) {
            return text;
        }
        if (text == null) {
            return code;
        }
        return code + ": " + text;
    }
}
