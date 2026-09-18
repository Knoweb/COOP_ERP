package lk.coopfed.knoweb.kernel.api;

/**
 * The row-visibility class carried in the token claim {@code cls} and set on the
 * database session as {@code app.scope_class} (doc 18 §3.7, doc 19 §1).
 *
 * <p>The row-level security policies know OWN, PARTY, FEDERATION_VIEW and
 * EXTERNAL_TIMEBOXED, and treat anything else as NONE, which returns no rows.
 * API_CLIENT and DEVICE name the two non-user principals; the kernel's scope
 * customizer maps them to OWN or PARTY for the principal's own entity before the
 * session is set (doc 18 v0.3.1 §3.7; 19A §2).
 */
public enum PolicyClass {

    /** Rows owned by the caller's entity, and within the caller's location when one is set. */
    OWN,

    /** Trading documents where the caller's entity is seller or buyer; counterparty columns masked. */
    PARTY,

    /** Federation roles: SELECT on everything, no writes. */
    FEDERATION_VIEW,

    /** Regulator or auditor grant: SELECT only on the granted entities until the grant expires. */
    EXTERNAL_TIMEBOXED,

    /** An OAuth2 API client registered by an entity (M9); mapped to OWN or PARTY for that entity. */
    API_CLIENT,

    /** An enrolled till; sync endpoints only, mapped to OWN for its location. */
    DEVICE,

    /** No resolved scope. Every policy fails closed. */
    NONE
}
