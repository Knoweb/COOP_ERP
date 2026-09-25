package lk.coopfed.knoweb.m1party.internal.relationship;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * One row of a trading relationship (21A section 3.1; doc 18 part A, entity_relationship). The
 * seller owns the row ({@code owner_entity_id} is generated from {@code seller_entity_id}).
 *
 * <p>A row's terms never change once it is ACTIVE: an amendment closes the row the day before
 * the new terms start ({@link #closeBefore}) and opens the next row ({@link #amendedFrom}).
 * Only the status and the closing date of a row are ever updated.
 */
@jakarta.persistence.Entity
@Table(schema = "party", name = "entity_relationship")
public class Relationship implements Persistable<UUID> {

    static final String STATUS_DRAFT = "DRAFT";
    static final String STATUS_ACTIVE = "ACTIVE";
    static final String STATUS_SUSPENDED = "SUSPENDED";

    static final String DEFAULT_ALLOCATION_RULE = "FCFS";
    static final short DEFAULT_DISCREPANCY_WINDOW_DAYS = 7;
    static final short DEFAULT_ORDER_LOCK_HOURS = 24;

    @Id
    @Column(name = "relationship_id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "seller_entity_id", nullable = false, updatable = false)
    private UUID sellerEntityId;

    @Column(name = "buyer_entity_id", nullable = false, updatable = false)
    private UUID buyerEntityId;

    @Column(name = "price_list_id", updatable = false)
    private UUID priceListId;

    @Column(name = "credit_limit", updatable = false)
    private BigDecimal creditLimit;

    @Column(name = "payment_terms_days", updatable = false)
    private Short paymentTermsDays;

    @Column(name = "discrepancy_window_days", nullable = false, updatable = false)
    private short discrepancyWindowDays;

    @Column(name = "order_lock_hours_before_eta", nullable = false, updatable = false)
    private short orderLockHoursBeforeEta;

    @Column(name = "allocation_rule", nullable = false, updatable = false)
    private String allocationRule;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @jakarta.persistence.Transient
    private boolean isNew = true;

    protected Relationship() {}

    /** The terms a row carries, as one value: what an amendment compares and audits. */
    record Terms(
            UUID priceListId,
            BigDecimal creditLimit,
            Short paymentTermsDays,
            short discrepancyWindowDays,
            short orderLockHoursBeforeEta,
            String allocationRule) {

        /** Two limits are the same when they are the same amount, whatever their scale. */
        boolean sameCreditLimitAs(BigDecimal other) {
            if (creditLimit == null || other == null) {
                return creditLimit == null && other == null;
            }
            return creditLimit.compareTo(other) == 0;
        }

        boolean sameAs(Terms other) {
            return Objects.equals(priceListId, other.priceListId)
                    && sameCreditLimitAs(other.creditLimit)
                    && Objects.equals(paymentTermsDays, other.paymentTermsDays)
                    && discrepancyWindowDays == other.discrepancyWindowDays
                    && orderLockHoursBeforeEta == other.orderLockHoursBeforeEta
                    && allocationRule.equals(other.allocationRule);
        }

        Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("priceListId", priceListId);
            map.put("creditLimit", creditLimit);
            map.put("paymentTermsDays", paymentTermsDays);
            map.put("discrepancyWindowDays", discrepancyWindowDays);
            map.put("orderLockHoursBeforeEta", orderLockHoursBeforeEta);
            map.put("allocationRule", allocationRule);
            return Collections.unmodifiableMap(map);
        }

        /**
         * SHA-256 of the terms in one fixed text form (doc 21 section 5.3: the events carry a
         * hash of the terms, not the terms). The amount is written with two decimals so that
         * 5000000 and 5000000.00 hash alike.
         */
        String hash() {
            String text = String.join(
                    "|",
                    String.valueOf(priceListId),
                    creditLimit == null
                            ? "null"
                            : creditLimit.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                    String.valueOf(paymentTermsDays),
                    String.valueOf(discrepancyWindowDays),
                    String.valueOf(orderLockHoursBeforeEta),
                    allocationRule);
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(digest);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
            }
        }
    }

    /** A new DRAFT row. */
    static Relationship open(
            UUID id,
            UUID sellerEntityId,
            UUID buyerEntityId,
            Terms terms,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
        Relationship r = new Relationship();
        r.id = id;
        r.sellerEntityId = sellerEntityId;
        r.buyerEntityId = buyerEntityId;
        r.applyTerms(terms);
        r.status = STATUS_DRAFT;
        r.effectiveFrom = effectiveFrom;
        r.effectiveTo = effectiveTo;
        return r;
    }

    /**
     * The next ACTIVE row of an amendment: the same parties, the new terms, from
     * {@code effectiveFrom} to where the current row would have ended.
     */
    Relationship amendedFrom(UUID id, Terms terms, LocalDate effectiveFrom) {
        Relationship next = new Relationship();
        next.id = id;
        next.sellerEntityId = sellerEntityId;
        next.buyerEntityId = buyerEntityId;
        next.applyTerms(terms);
        next.status = STATUS_ACTIVE;
        next.effectiveFrom = effectiveFrom;
        next.effectiveTo = effectiveTo;
        return next;
    }

    private void applyTerms(Terms terms) {
        this.priceListId = terms.priceListId();
        this.creditLimit = terms.creditLimit();
        this.paymentTermsDays = terms.paymentTermsDays();
        this.discrepancyWindowDays = terms.discrepancyWindowDays();
        this.orderLockHoursBeforeEta = terms.orderLockHoursBeforeEta();
        this.allocationRule = terms.allocationRule();
    }

    /** Ends this row the day before {@code nextFrom}; its terms stay as they were. */
    void closeBefore(LocalDate nextFrom) {
        this.effectiveTo = nextFrom.minusDays(1);
    }

    void activate() {
        this.status = STATUS_ACTIVE;
    }

    void suspend() {
        this.status = STATUS_SUSPENDED;
    }

    boolean isDraft() {
        return STATUS_DRAFT.equals(status);
    }

    boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }

    Terms terms() {
        return new Terms(
                priceListId,
                creditLimit,
                paymentTermsDays,
                discrepancyWindowDays,
                orderLockHoursBeforeEta,
                allocationRule);
    }

    UUID sellerEntityId() {
        return sellerEntityId;
    }

    UUID buyerEntityId() {
        return buyerEntityId;
    }

    UUID priceListId() {
        return priceListId;
    }

    BigDecimal creditLimit() {
        return creditLimit;
    }

    Short paymentTermsDays() {
        return paymentTermsDays;
    }

    String status() {
        return status;
    }

    LocalDate effectiveFrom() {
        return effectiveFrom;
    }

    LocalDate effectiveTo() {
        return effectiveTo;
    }

    Map<String, Object> auditState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("relationshipId", id);
        state.put("sellerEntityId", sellerEntityId);
        state.put("buyerEntityId", buyerEntityId);
        state.putAll(terms().asMap());
        state.put("status", status);
        state.put("effectiveFrom", effectiveFrom);
        state.put("effectiveTo", effectiveTo);
        return Collections.unmodifiableMap(state);
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        isNew = false;
    }
}
