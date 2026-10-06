package lk.coopfed.knoweb.m7customers.internal.customer;

import java.util.ArrayList;
import java.util.List;
import lk.coopfed.knoweb.kernel.api.KeyedHash;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * What a NIC is stored as on {@code customers.customer.nic_hash} (wave 2, M7CR-01;
 * {@code docs/progress/deviations/2026-10-06-wave2-keyed-hashes.md} (4)):
 * {@code HMAC-SHA-256(pepper, SHA-256("nic:" + canonical NIC))}, the pepper being the application
 * property {@code coop-erp.customers.nic-pepper} (the environment or a secret store, never a
 * {@code ConfigRegistry} item, which would live in the database the pepper protects). The key id
 * ({@link #keyId()}) is stored beside every hash.
 *
 * <p>The one HMAC input convention, written here once: the HMAC is taken over the <b>64 lower-case
 * hex characters</b> of the plain SHA-256 (the legacy scheme's value), never over the NIC. That is
 * what lets {@link NicRekeyJob} re-key a legacy row without knowing its NIC
 * ({@code HMAC(pepper, stored_sha256)}) and have it match a fresh capture of the same card
 * exactly when the legacy row held the canonical form. {@code NicIdentityIntegrationTest}
 * pins the convention: capture under the legacy scheme, re-key, find.
 *
 * <p>Residual exposure, recorded in the README: whoever holds both the pepper and a dump (the
 * application host) can still enumerate; that is the application, which must compare anyway.
 * Losing the pepper makes every captured NIC unmatchable; the way out is {@code RecaptureNic}.
 */
@Component
public class NicHasher {

    static final String PROPERTY = "coop-erp.customers.nic-pepper";

    /** Development only: what the development pepper is derived from when nothing is configured. */
    static final String DEVELOPMENT_SEED = "coop-erp-customers-nic-dev";

    private final KeyedHash hash;

    NicHasher(@Value("${" + PROPERTY + ":}") String pepper, @Value("${coop-erp.security.oidc.issuer:}") String issuer) {
        this.hash =
                KeyedHash.fromProperty(PROPERTY + " (COOP_ERP_CUSTOMERS_NIC_PEPPER)", pepper, issuer, DEVELOPMENT_SEED);
    }

    /** The stored form of a canonical NIC under the current key: 64 hex characters, never the number. */
    public String keyed(String canonical) {
        return rekey(NicNumbers.legacySha256(canonical));
    }

    /** A legacy value (plain SHA-256, as a row held it) brought under the current key. */
    public String rekey(String legacySha256) {
        return hash.hex(legacySha256);
    }

    /** Which key made the hashes written now. */
    public String keyId() {
        return hash.keyId();
    }

    /**
     * Every stored value a row holding this NIC may carry: for each legacy form (the canonical
     * form first), its value under the current key and, for the rows the re-key job has not
     * reached, its plain SHA-256. A re-keyed legacy row holds {@code HMAC(pepper, SHA-256(form as
     * typed))}, which is why the keyed value of every form is in the set and not the canonical one
     * alone. At most six. The lookup asks {@code customers.nic_holders} for all of them at once; a
     * capture then writes the keyed canonical value, which cleans the row.
     */
    public List<String> candidates(String canonical) {
        return keyedCandidates(canonical, true);
    }

    /** The values a row under the current key may hold for this NIC: the keyed value of each legacy form. */
    public List<String> keyedForms(String canonical) {
        return keyedCandidates(canonical, false);
    }

    private List<String> keyedCandidates(String canonical, boolean withLegacy) {
        List<String> candidates = new ArrayList<>();
        for (String form : NicNumbers.legacyForms(canonical)) {
            String legacy = NicNumbers.legacySha256(form);
            candidates.add(rekey(legacy));
            if (withLegacy) {
                candidates.add(legacy);
            }
        }
        return List.copyOf(candidates);
    }
}
