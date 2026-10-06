package lk.coopfed.knoweb.m7customers.internal.customer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The capture of a NIC, shared by OpenAccount, AmendAccountLimits and RecaptureNic (wave 2,
 * M7CR-01, -02, -03, -15). It reads and decides; the handler writes with {@link #WRITE_SQL}.
 * <ol>
 *   <li>the number as typed is brought to its canonical form ({@code m7.account.nic_required}
 *       when it has neither form);</li>
 *   <li>unless the capture is a re-capture, the customer's own recorded NIC must be the same
 *       card ({@code m7.account.nic_mismatch}): under the current key by the keyed hash, on a
 *       legacy row by any of the legacy forms, and on a row under a retired key never (RecaptureNic
 *       is the way out);</li>
 *   <li>nobody else holds the card: {@code customers.nic_holders} answers across societies
 *       with the caller's own id ({@code m7.account.nic_held}, so the screen can open that card)
 *       or that it is held elsewhere ({@code m7.account.nic_held_elsewhere}, naming nobody).</li>
 * </ol>
 * What the handler writes is the keyed canonical hash, the last four of the canonical form and the
 * key id, which also cleans a legacy row the moment its member presents the card again.
 */
@Component
public class NicCapture {

    /** The write, for the handler: hash, last four, key id, customer id. */
    public static final String WRITE_SQL =
            "update customers.customer set nic_hash = ?, nic_last4 = ?, nic_key_id = ? where customer_id = ?";

    /** What the handler writes; never the number. */
    public record Captured(String hash, String last4, String keyId) {}

    private final JdbcTemplate jdbc;
    private final NicHasher hasher;

    NicCapture(JdbcTemplate jdbc, NicHasher hasher) {
        this.jdbc = jdbc;
        this.hasher = hasher;
    }

    /** The capture for {@code customerId}, read under the caller's policies. */
    public Captured capture(String typed, UUID customerId) {
        return capture(typed, customerId, false);
    }

    /** A re-capture: the recorded NIC is overwritten, so no mismatch is asked; the holders are. */
    public Captured recapture(String typed, UUID customerId) {
        return capture(typed, customerId, true);
    }

    private Captured capture(String typed, UUID customerId, boolean recapture) {
        String canonical =
                NicNumbers.canonical(typed).orElseThrow(() -> new ProblemException("m7.account.nic_required"));
        String keyed = hasher.keyed(canonical);
        if (!recapture) {
            requireSameCard(customerId, canonical, keyed);
        }
        requireNotHeldByAnotherPerson(customerId, canonical);
        return new Captured(keyed, NicNumbers.last4(canonical), hasher.keyId());
    }

    private void requireSameCard(UUID customerId, String canonical, String keyed) {
        record Held(String hash, String keyId) {}
        List<Held> held = jdbc.query(
                "select nic_hash, nic_key_id from customers.customer where customer_id = ?",
                (rs, n) -> new Held(rs.getString("nic_hash"), rs.getString("nic_key_id")),
                customerId);
        if (held.isEmpty() || held.get(0).hash() == null) {
            return;
        }
        Held current = held.get(0);
        boolean same;
        if (current.keyId() == null) {
            // A legacy row: the plain SHA-256 of whichever form was typed then.
            same = NicNumbers.legacyForms(canonical).stream()
                    .map(NicNumbers::legacySha256)
                    .anyMatch(current.hash()::equals);
        } else {
            // Under the current key: the keyed canonical value (a fresh capture) or the keyed value
            // of a legacy form (a row the re-key job converted). Under a retired key: never; the
            // officer re-captures.
            same = current.keyId().equals(hasher.keyId())
                    && (current.hash().equals(keyed)
                            || hasher.keyedForms(canonical).contains(current.hash()));
        }
        if (!same) {
            throw new ProblemException("m7.account.nic_mismatch");
        }
    }

    private void requireNotHeldByAnotherPerson(UUID customerId, String canonical) {
        record Holder(UUID ownCustomerId, boolean heldElsewhere) {}
        String[] candidates = hasher.candidates(canonical).toArray(String[]::new);
        List<Holder> holders = jdbc.query(
                "select own_customer_id, held_elsewhere from customers.nic_holders(cast(? as char(64)[]))",
                (rs, n) -> new Holder(rs.getObject("own_customer_id", UUID.class), rs.getBoolean("held_elsewhere")),
                (Object) candidates);
        for (Holder holder : holders) {
            if (holder.ownCustomerId() != null && !holder.ownCustomerId().equals(customerId)) {
                throw new ProblemException(
                        "m7.account.nic_held",
                        Map.of("customerId", holder.ownCustomerId().toString()));
            }
        }
        if (holders.stream().anyMatch(Holder::heldElsewhere)) {
            throw new ProblemException("m7.account.nic_held_elsewhere");
        }
    }
}
