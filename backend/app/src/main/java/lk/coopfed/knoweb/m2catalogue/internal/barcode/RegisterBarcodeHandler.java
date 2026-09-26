package lk.coopfed.knoweb.m2catalogue.internal.barcode;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.BarcodeRegistered;
import lk.coopfed.knoweb.m2catalogue.api.RegisterBarcode;
import lk.coopfed.knoweb.m2catalogue.internal.barcode.BarcodeStore.BarcodeRow;
import lk.coopfed.knoweb.m2catalogue.internal.sku.SkuAccess;
import lk.coopfed.knoweb.m2catalogue.internal.sku.SkuIdentity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RegisterBarcode (22A section 6): sku active; symbology valid; check digit valid for EAN/UPC;
 * uniqueness per rule (B-I2: a factory code once federation-wide, an INTERNAL code once per
 * owner); INTERNAL only on a SKU the caller sells. Mutation: insert the registry row with the
 * caller as owner.
 *
 * <p>Whose row it is (doc 22 section 4.2: "owner of SKU; any entity for INTERNAL on its own
 * SKUs"): a factory code belongs to the item, so only the SKU's owner registers it; an INTERNAL
 * code is the entity's own sticker or weigh label (doc 22 section 3.3, GS1 prefixes 20 to 29)
 * on an item it sells, its own or a SHARED one, and is unique within that entity only. Either
 * way the row's owner is the caller, which is what the own_write policy admits.
 */
@Service
@CommandHandler(permission = "cat.barcode.manage")
public class RegisterBarcodeHandler implements Handles<RegisterBarcode, UUID> {

    static final String AUDIT_REGISTERED = "BARCODE_REGISTERED";

    /** PostgreSQL: unique_violation, from the primary key or barcode_factory_unique. */
    private static final String UNIQUE_VIOLATION = "23505";

    private final SkuAccess skus;
    private final BarcodeStore store;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    RegisterBarcodeHandler(
            SkuAccess skus, BarcodeStore store, JdbcTemplate jdbc, AuditFacade audit, EventPublisher events) {
        this.skus = skus;
        this.store = store;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RegisterBarcode command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }

        String barcode = BarcodeGuards.requiredBarcode(command.barcode());
        String symbology = BarcodeGuards.requiredSymbology(command.symbology());
        boolean internal = BarcodeGuards.INTERNAL.equals(symbology);

        SkuIdentity sku =
                internal ? skus.requireVisible(command.skuId(), scope) : skus.requireOwned(command.skuId(), scope);

        if (!sku.active()) {
            throw new ProblemException("m2.barcode.sku_not_active", Map.of("skuId", sku.skuId()));
        }

        BarcodeGuards.requireCheckDigit(barcode, symbology);

        String uom = requiredUnit(command.uomCode());
        if (!store.uomExists(uom)) {
            throw new ProblemException("m2.barcode.uom_unknown", Map.of("uomCode", uom));
        }

        if (command.batchId() != null) {
            BarcodeGuards.requireBatchOfSku(store, command.batchId(), sku.skuId());
        }

        // Uniqueness per rule, read first so the usual answer is a problem and not a constraint;
        // the constraints stay the arbiter under concurrency and for rows RLS hides.
        boolean taken = internal
                ? store.findOwn(barcode, symbology, scope.entityId())
                        .map(BarcodeRow::active)
                        .orElse(false)
                : store.activeFactoryCodeExists(barcode, symbology);
        if (taken) {
            throw new ProblemException("m2.barcode.already_registered", Map.of("barcode", barcode));
        }

        BarcodeRow row = new BarcodeRow(
                barcode, symbology, sku.skuId(), uom, command.batchId(), scope.entityId(), BarcodeGuards.ACTIVE);

        try {
            jdbc.update(
                    """
                    insert into catalogue.sku_barcode
                        (barcode, symbology, sku_id, uom_code, batch_id, owner_entity_id, status)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """,
                    row.barcode(),
                    row.symbology(),
                    row.skuId(),
                    row.uomCode(),
                    row.batchId(),
                    row.ownerEntityId(),
                    row.status());
        } catch (DataIntegrityViolationException e) {
            if (isUniqueViolation(e)) {
                throw new ProblemException("m2.barcode.already_registered", Map.of("barcode", barcode));
            }
            throw e;
        }

        audit.record(AUDIT_REGISTERED, Subject.of("sku", sku.skuId()), null, row.auditState(), scope);

        events.publish(new BarcodeRegistered(
                sku.skuId(), row.ownerEntityId(), row.barcode(), row.symbology(), row.uomCode(), row.batchId()));

        return sku.skuId();
    }

    private static String requiredUnit(String uomCode) {
        if (uomCode == null || uomCode.isBlank()) {
            throw new ProblemException("request.field.required", Map.of("field", "uomCode"));
        }
        return uomCode.strip().toUpperCase();
    }

    private static boolean isUniqueViolation(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
