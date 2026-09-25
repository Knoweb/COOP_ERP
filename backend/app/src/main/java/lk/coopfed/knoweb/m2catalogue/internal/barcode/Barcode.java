package lk.coopfed.knoweb.m2catalogue.internal.barcode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "catalogue", name = "barcode")
public class Barcode implements Persistable<UUID> {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_RETIRED = "RETIRED";
    
    public static final String SYMBOLOGY_FACTORY = "FACTORY";
    public static final String SYMBOLOGY_INTERNAL = "INTERNAL";

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private String barcode;

    @Column(nullable = false, updatable = false)
    private String symbology;

    @Column(name = "sku_id", nullable = false, updatable = false)
    private UUID skuId;

    @Column(nullable = false, updatable = false)
    private String uom;

    @Column(name = "batch_id")
    private UUID batchId;

    @Column(name = "owner_entity_id", updatable = false)
    private UUID ownerEntityId;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    protected Barcode() {}

    public Barcode(UUID id, String barcode, String symbology, UUID skuId, String uom, UUID batchId, UUID ownerEntityId) {
        this.id = id;
        this.barcode = barcode;
        this.symbology = symbology;
        this.skuId = skuId;
        this.uom = uom;
        this.batchId = batchId;
        this.ownerEntityId = ownerEntityId;
        this.status = STATUS_ACTIVE;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getBarcode() {
        return barcode;
    }

    public String getSymbology() {
        return symbology;
    }

    public UUID getSkuId() {
        return skuId;
    }

    public String getUom() {
        return uom;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public UUID getOwnerEntityId() {
        return ownerEntityId;
    }

    public String getStatus() {
        return status;
    }

    public void retire() {
        this.status = STATUS_RETIRED;
    }

    public void linkBatch(UUID batchId) {
        this.batchId = batchId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
