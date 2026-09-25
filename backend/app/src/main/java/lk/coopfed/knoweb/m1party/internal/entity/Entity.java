package lk.coopfed.knoweb.m1party.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.m1party.api.RegisterEntity;
import org.springframework.data.domain.Persistable;

@jakarta.persistence.Entity
@Table(schema = "party", name = "entity")
public class Entity implements Persistable<UUID> {

    static final String TYPE_FEDERATION = "FEDERATION";

    static final String STATUS_ONBOARDING = "ONBOARDING";
    static final String STATUS_ACTIVE = "ACTIVE";
    static final String STATUS_SUSPENDED = "SUSPENDED";

    @Id
    @Column(name = "entity_id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "entity_code", nullable = false)
    private String entityCode;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "legal_name_en", nullable = false)
    private String legalNameEn;

    @Column(name = "legal_name_si")
    private String legalNameSi;

    @Column(name = "legal_name_ta")
    private String legalNameTa;

    @Column(name = "registration_no")
    private String registrationNo;

    @Column(name = "vat_registration_no")
    private String vatRegistrationNo;

    @Column(name = "district")
    private String district;

    @Column(name = "financial_year_start_month", nullable = false)
    private short financialYearStartMonth;

    @Column(name = "default_language", nullable = false)
    private String defaultLanguage;

    @Column(name = "responsible_officer_user_id")
    private UUID responsibleOfficerUserId;

    @Column(name = "data_governance_signed_on")
    private LocalDate dataGovernanceSignedOn;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "owner_entity_id", insertable = false, updatable = false)
    private UUID ownerEntityId;

    @jakarta.persistence.Transient
    private boolean isNew = true;

    protected Entity() {}

    private Entity(
            UUID id,
            RegisterEntity command,
            String normalizedCode,
            String normalizedType,
            String normalizedLanguage,
            short normalizedFinancialYearStartMonth) {

        this.id = id;
        this.entityCode = normalizedCode;
        this.entityType = normalizedType;
        this.legalNameEn = command.legalNameEn().strip();
        this.legalNameSi = blankToNull(command.legalNameSi());
        this.legalNameTa = blankToNull(command.legalNameTa());
        this.registrationNo = blankToNull(command.registrationNo());
        this.vatRegistrationNo = blankToNull(command.vatRegistrationNo());
        this.district = blankToNull(command.district());
        this.financialYearStartMonth = normalizedFinancialYearStartMonth;
        this.defaultLanguage = normalizedLanguage;
        this.status = STATUS_ONBOARDING;
    }

    static Entity register(
            UUID id,
            RegisterEntity command,
            String normalizedCode,
            String normalizedType,
            String normalizedLanguage,
            short normalizedFinancialYearStartMonth) {

        return new Entity(
                id, command, normalizedCode, normalizedType, normalizedLanguage, normalizedFinancialYearStartMonth);
    }

    boolean isFederation() {
        return TYPE_FEDERATION.equals(entityType);
    }

    boolean isOnboarding() {
        return STATUS_ONBOARDING.equals(status);
    }

    boolean hasResponsibleOfficer() {
        return responsibleOfficerUserId != null;
    }

    boolean hasVatRegistration() {
        return vatRegistrationNo != null && !vatRegistrationNo.isBlank();
    }

    boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }

    boolean isSuspended() {
        return STATUS_SUSPENDED.equals(status);
    }

    void suspend() {
        this.status = STATUS_SUSPENDED;
    }

    void reinstate() {
        this.status = STATUS_ACTIVE;
    }

    void activate() {
        this.status = STATUS_ACTIVE;
    }

    void appointResponsibleOfficer(UUID userId, LocalDate dataGovernanceSignedOn) {

        this.responsibleOfficerUserId = userId;
        this.dataGovernanceSignedOn = dataGovernanceSignedOn;
    }

    public String entityCode() {
        return entityCode;
    }

    public String entityType() {
        return entityType;
    }

    public String status() {
        return status;
    }

    /** True when the entity may register locations (doc 21 section 4.3: owner ACTIVE or ONBOARDING). */
    public boolean isTrading() {
        return STATUS_ACTIVE.equals(status) || STATUS_ONBOARDING.equals(status);
    }

    public String defaultLanguage() {
        return defaultLanguage;
    }

    public Map<String, Object> auditState() {
        Map<String, Object> state = new LinkedHashMap<>();

        state.put("entityId", id);
        state.put("entityCode", entityCode);
        state.put("entityType", entityType);
        state.put("legalNameEn", legalNameEn);
        state.put("legalNameSi", legalNameSi);
        state.put("legalNameTa", legalNameTa);
        state.put("registrationNo", registrationNo);
        state.put("vatRegistrationNo", vatRegistrationNo);
        state.put("district", district);
        state.put("financialYearStartMonth", financialYearStartMonth);
        state.put("defaultLanguage", defaultLanguage);
        state.put("responsibleOfficerUserId", responsibleOfficerUserId);
        state.put("dataGovernanceSignedOn", dataGovernanceSignedOn);
        state.put("status", status);
        state.put("ownerEntityId", id);

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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
