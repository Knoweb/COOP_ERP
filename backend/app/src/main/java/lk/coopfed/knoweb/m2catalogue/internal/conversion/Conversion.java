package lk.coopfed.knoweb.m2catalogue.internal.conversion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(schema = "catalogue", name = "conversion")
public class Conversion implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "sku_id", nullable = false, updatable = false)
    private UUID skuId;

    @Column(name = "from_uom", nullable = false, updatable = false)
    private String fromUom;

    @Column(name = "to_uom", nullable = false, updatable = false)
    private String toUom;

    @Column(nullable = false, updatable = false)
    private BigDecimal factor;

    @Column(name = "valid_from", nullable = false, updatable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to", nullable = false)
    private LocalDate validTo;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    protected Conversion() {}

    public Conversion(UUID id, UUID skuId, String fromUom, String toUom, BigDecimal factor, LocalDate validFrom) {
        this.id = id;
        this.skuId = skuId;
        this.fromUom = fromUom;
        this.toUom = toUom;
        this.factor = factor;
        this.validFrom = validFrom;
        this.validTo = LocalDate.of(9999, 12, 31);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getSkuId() {
        return skuId;
    }

    public String getFromUom() {
        return fromUom;
    }

    public String getToUom() {
        return toUom;
    }

    public BigDecimal getFactor() {
        return factor;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    public void close(LocalDate endDate) {
        this.validTo = endDate;
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
