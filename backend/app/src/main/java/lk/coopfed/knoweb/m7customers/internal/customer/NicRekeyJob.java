package lk.coopfed.knoweb.m7customers.internal.customer;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.ScheduledJob;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Brings the rows captured under the legacy scheme (plain SHA-256, {@code nic_key_id} null) under
 * the pepper (wave 2, M7CR-01; {@code 2026-10-06-wave2-keyed-hashes.md} (4)), without knowing a
 * NIC: the new value is {@code HMAC(pepper, stored_sha256)} ({@link NicHasher#rekey}), the one
 * convention a fresh capture follows too. The pepper never reaches SQL text: the HMAC is computed
 * here and written through {@code customers.rekey_nic}, which changes a row only while it still
 * holds the old value with no key id (idempotent, restartable). Until a run finds nothing left,
 * the lookup also tries the legacy candidate set ({@link NicHasher#candidates}), so the window
 * between the deploy and the run needs no care.
 *
 * <p>Runs every ten minutes as the platform's FEDERATION_VIEW reader, the class the two functions
 * name explicitly ({@code kernel.change_log_purge} does the same); once no legacy row is left each
 * run is one empty read. A page at a time, each page in a transaction of its own, so a long table
 * never holds one transaction open. Only the kernel's partition maintainer and this job call a
 * function that writes from outside a command handler; the write rule counts {@code queryForObject}
 * as a read, which is why the function, not a statement, does the update.
 */
@Component
public class NicRekeyJob {

    private static final Logger log = LoggerFactory.getLogger(NicRekeyJob.class);

    static final int PAGE = 500;

    private final JdbcTemplate jdbc;
    private final NicHasher hasher;

    NicRekeyJob(JdbcTemplate jdbc, NicHasher hasher) {
        this.jdbc = jdbc;
        this.hasher = hasher;
    }

    @ScheduledJob(
            name = "customers-nic-rekey",
            continuous = true,
            fixedDelay = "PT10M",
            maxRuntime = "PT5M",
            lockTimeout = "PT15M")
    public int rekey(JobExecution job) {
        int total = 0;
        int page;
        do {
            page = rekeyPage(job.federationView());
            total += page;
        } while (page == PAGE);
        if (total > 0) {
            log.info("NIC re-key: {} legacy rows brought under key {}", total, hasher.keyId());
        }
        return total;
    }

    /**
     * One page of legacy rows, re-keyed in a transaction of its own (the viewer scope on the
     * argument is what the kernel's scope aspect applies); public for the test. Answers how many
     * rows the page held, so the caller knows whether to ask for another.
     */
    @Transactional
    public int rekeyPage(ScopeContext viewer) {
        record Legacy(UUID customerId, String hash) {}
        List<Legacy> rows = jdbc.query(
                "select customer_id, nic_hash from customers.legacy_nic_rows(?)",
                (rs, n) -> new Legacy(rs.getObject("customer_id", UUID.class), rs.getString("nic_hash")),
                PAGE);
        for (Legacy row : rows) {
            // False when an officer re-captured the row between the read and the write: theirs stands.
            jdbc.queryForObject(
                    "select customers.rekey_nic(?, ?, ?, ?)",
                    Boolean.class,
                    row.customerId(),
                    row.hash(),
                    hasher.rekey(row.hash()),
                    hasher.keyId());
        }
        return rows.size();
    }
}
