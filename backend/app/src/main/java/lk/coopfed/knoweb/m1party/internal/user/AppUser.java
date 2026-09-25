package lk.coopfed.knoweb.m1party.internal.user;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * A user of the platform (doc 21 section 3.6; 21A section 3.2). The password and the second
 * factor live at the identity provider; this row holds the provider's subject id, the till PIN's
 * Argon2id hash and the hashes of the last PINs, so that one is not reused. None of the three
 * ever leaves this class in an audit state, an event or a log line.
 */
@jakarta.persistence.Entity
@Table(schema = "security", name = "app_user")
public class AppUser implements Persistable<UUID> {

    static final String BACK_OFFICE = "BACK_OFFICE";
    static final String TILL = "TILL";
    static final String BOTH = "BOTH";
    static final String EXTERNAL = "EXTERNAL";

    static final Set<String> KINDS = Set.of(BACK_OFFICE, TILL, BOTH, EXTERNAL);

    static final String PENDING = "PENDING";
    static final String ACTIVE = "ACTIVE";
    static final String LOCKED = "LOCKED";
    static final String DEACTIVATED = "DEACTIVATED";

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "home_entity_id", nullable = false, updatable = false)
    private UUID homeEntityId;

    @Column(name = "username", nullable = false, updatable = false)
    private String username;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "language", nullable = false)
    private String language;

    @Column(name = "user_kind", nullable = false)
    private String userKind;

    @Column(name = "provider_subject")
    private String providerSubject;

    @Column(name = "pin_hash")
    private String pinHash;

    @Column(name = "pin_changed_at")
    private Instant pinChangedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pin_history", nullable = false, columnDefinition = "jsonb")
    private List<String> pinHistory = new ArrayList<>();

    @Column(name = "succeeds_user_id", updatable = false)
    private UUID succeedsUserId;

    @Column(name = "status", nullable = false)
    private String status;

    @jakarta.persistence.Transient
    private boolean isNew = true;

    protected AppUser() {}

    static AppUser create(
            UUID id,
            UUID homeEntityId,
            String username,
            String displayName,
            String language,
            String userKind,
            UUID succeedsUserId) {
        AppUser user = new AppUser();
        user.id = id;
        user.homeEntityId = homeEntityId;
        user.username = username;
        user.displayName = displayName;
        user.language = language;
        user.userKind = userKind;
        user.succeedsUserId = succeedsUserId;
        user.status = PENDING;
        return user;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        isNew = false;
    }

    // ---- facts ----

    UUID homeEntityId() {
        return homeEntityId;
    }

    String username() {
        return username;
    }

    String userKind() {
        return userKind;
    }

    String status() {
        return status;
    }

    String providerSubject() {
        return providerSubject;
    }

    boolean isDeactivated() {
        return DEACTIVATED.equals(status);
    }

    boolean hasTillCredential() {
        return TILL.equals(userKind) || BOTH.equals(userKind);
    }

    boolean hasLogin() {
        return BACK_OFFICE.equals(userKind) || BOTH.equals(userKind) || EXTERNAL.equals(userKind);
    }

    boolean isExternal() {
        return EXTERNAL.equals(userKind);
    }

    /** The hashes a new PIN must not match: the current one first, then the earlier ones. */
    List<String> recentPinHashes() {
        return Collections.unmodifiableList(pinHistory);
    }

    // ---- changes ----

    void linkLogin(String subject) {
        this.providerSubject = subject;
    }

    void changeDetails(String displayName, String language, String userKind) {
        this.displayName = displayName;
        this.language = language;
        if (!userKind.equals(this.userKind) && BACK_OFFICE.equals(userKind)) {
            // A user who no longer works a till keeps no till credential.
            clearPin();
        }
        this.userKind = userKind;
    }

    /**
     * Sets the PIN's hash and keeps the last {@code historyDepth} hashes, the new one first, so
     * the next PIN can be checked against them (doc 19 DR-5: no reuse of the last three).
     */
    void setPin(String hash, Instant changedAt, int historyDepth) {
        List<String> history = new ArrayList<>();
        history.add(hash);
        for (String earlier : pinHistory) {
            if (history.size() >= historyDepth) {
                break;
            }
            history.add(earlier);
        }
        this.pinHash = hash;
        this.pinChangedAt = changedAt;
        this.pinHistory = history;
    }

    /** PENDING or LOCKED becomes ACTIVE when a credential is issued; true when it did. */
    boolean activateOnCredential() {
        if (PENDING.equals(status) || LOCKED.equals(status)) {
            status = ACTIVE;
            return true;
        }
        return false;
    }

    void deactivate() {
        status = DEACTIVATED;
        // The till drops the operator at its next sync (doc 19 section 2.2); nothing needs the
        // hash of a user who can never sign in again.
        clearPin();
    }

    private void clearPin() {
        this.pinHash = null;
        this.pinChangedAt = null;
        this.pinHistory = new ArrayList<>();
    }

    /** What the audit record keeps: facts about the credential, never the credential. */
    Map<String, Object> auditState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("userId", id);
        state.put("homeEntityId", homeEntityId);
        state.put("username", username);
        state.put("displayName", displayName);
        state.put("language", language);
        state.put("userKind", userKind);
        state.put("status", status);
        state.put("loginLinked", providerSubject != null);
        state.put("pinSet", pinHash != null);
        state.put("pinChangedAt", pinChangedAt);
        state.put("succeedsUserId", succeedsUserId);
        return Collections.unmodifiableMap(state);
    }
}
