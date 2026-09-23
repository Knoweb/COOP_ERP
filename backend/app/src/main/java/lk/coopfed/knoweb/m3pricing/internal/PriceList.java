package lk.coopfed.knoweb.m3pricing.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lk.coopfed.knoweb.m3pricing.api.PriceListView;
import org.springframework.data.domain.Persistable;

/**
 * The price list aggregate. Rules every entity in the system follows:
 * <ul>
 *   <li>{@code @Table} names the module's own schema; the architecture test R4 fails otherwise</li>
 *   <li>no setters: a price list is created once and never changed (the application's database
 *       role has no UPDATE grant on the table anyway)</li>
 *   <li>the entity stays inside {@code internal}; callers get a {@link PriceListView}</li>
 * </ul>
 *
 * <p>Why {@link Persistable}: the id comes from the kernel ({@code Ids.next()}), not from the
 * database. Spring Data decides between INSERT and UPDATE by asking "is this entity new?";
 * with an id already set it would guess "not new" and run a needless SELECT first.
 * {@link #isNew()} answers the question truthfully.
 */
@Entity
@Table(schema = "pricing", name = "price_list")
public class PriceList implements Persistable<UUID> {

    static final String STATUS_REGISTERED = "REGISTERED";

    @Id
    private UUID id;

    @Column(name = "owner_entity_id", nullable = false, updatable = false)
    private UUID ownerEntityId;

    @Column(name = "text_en", nullable = false, updatable = false)
    private String textEn;

    @Column(name = "text_si", updatable = false)
    private String textSi;

    @Column(name = "text_ta", updatable = false)
    private String textTa;

    @Column(nullable = false, updatable = false)
    private String status;

    /** Set by the database default (now()); never written by the application. */
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    /** For JPA only. */
    protected PriceList() {}

    private PriceList(UUID id, UUID ownerEntityId, String textEn, String textSi, String textTa) {
        this.id = id;
        this.ownerEntityId = ownerEntityId;
        this.textEn = textEn;
        this.textSi = textSi;
        this.textTa = textTa;
        this.status = STATUS_REGISTERED;
    }

    /** The only way to make a price list. Blank translations are stored as null ("not translated"). */
    static PriceList create(UUID id, UUID ownerEntityId, String textEn, String textSi, String textTa) {
        return new PriceList(id, ownerEntityId, textEn.strip(), blankToNull(textSi), blankToNull(textTa));
    }

    /**
     * The read-only copy handed to callers, and the "after" state recorded in the audit log.
     * createdAt is set by the database, so it is null until the price list has been read back.
     */
    PriceListView snapshot() {
        return new PriceListView(id, ownerEntityId, textEn, textSi, textTa, status, createdAt);
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
        this.isNew = false;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
