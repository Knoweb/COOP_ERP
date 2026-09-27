package lk.coopfed.knoweb.m2catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.BatchRegistered;
import lk.coopfed.knoweb.m2catalogue.api.BatchRegistration;
import lk.coopfed.knoweb.m2catalogue.api.RegisterBatch;
import lk.coopfed.knoweb.m2catalogue.api.RegisterSupplier;
import lk.coopfed.knoweb.m2catalogue.api.RegisteredBatch;
import lk.coopfed.knoweb.m2catalogue.internal.supplier.RegisterSupplierHandler;
import lk.coopfed.knoweb.testsupport.OuterCommand;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The guard rails of an internal command (CR-19A-6), with RegisterBatch, the first one: it runs
 * only inside another command handler, which checked the permission and claimed the request's
 * idempotency key once for both. Called from a bare transaction it is refused; called from a
 * GRN-like command over HTTP it runs, one idempotency row is written, and the outer command's
 * replay answers the stored result without registering again. An ordinary command called
 * inside another one is refused as a nested command.
 */
@Import({
    InternalCommandInterceptorPostgresIntegrationTest.GrnLikeCommand.class,
    InternalCommandInterceptorPostgresIntegrationTest.GrnLikeController.class
})
class InternalCommandInterceptorPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID FEDERATION = TEST_FEDERATION;
    private static final UUID USER = UUID.fromString("0190e671-0000-7000-8000-000000000010");
    private static final UUID TAX_CATEGORY = UUID.fromString("0190e671-0000-7000-8000-000000000100");
    private static final UUID SKU = UUID.fromString("0190e671-0000-7000-8000-000000000200");
    private static final String PATH = "/v1/test/internal-command/grn-lines";

    @Autowired
    TestRestTemplate http;

    @Autowired
    BatchRegistration registration;

    @Autowired
    RegisterSupplierHandler suppliers;

    @Autowired
    OuterCommand outer;

    @Autowired
    TransactionTemplate transactions;

    private UUID supplier;

    @BeforeEach
    void aBatchTrackedItemAndItsSupplier() {
        clean();
        JdbcTemplate admin = superuserJdbc();
        admin.update(
                "insert into catalogue.uom (uom_code, name_en, is_weight) values ('EA', 'EA', false) on conflict do nothing");
        admin.update(
                "insert into catalogue.tax_category (tax_category_id, code, name_en, owner_entity_id)"
                        + " values (?, 'M2INTERNAL', 'M2 internal command tax', ?) on conflict do nothing",
                TAX_CATEGORY,
                FEDERATION);
        admin.update(
                """
                insert into catalogue.sku
                    (sku_id, sku_code, owner_entity_id, status, short_name_en, short_name_si, short_name_ta,
                     base_uom_code, tax_category_id, batch_tracked, expiry_tracked, has_printed_mrp)
                values (?, 'INT-MILK', ?, 'SHARED', 'Milk', 'Milk', 'Milk', 'EA', ?, true, true, true)
                """,
                SKU,
                FEDERATION,
                TAX_CATEGORY);
        supplier = suppliers.handle(new RegisterSupplier("Internal Command Dairy"), scope());
        GrnLikeCommand.RUNS.set(0);
        kernel.reset();
    }

    @AfterEach
    void clean() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("truncate table catalogue.batch, catalogue.batch_key, catalogue.supplier, catalogue.sku cascade");
        admin.update("delete from catalogue.tax_category where tax_category_id = ?", TAX_CATEGORY);
    }

    @Test
    void anInternalCommandCalledOutsideACommandIsRefused() {
        // A bare transaction is not a command: nobody checked a permission or claimed a key.
        assertThatThrownBy(() -> transactions.execute(status -> registration.register(line("B-BARE"), scope())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is an internal command");
        // Nor is no transaction at all.
        assertThatThrownBy(() -> registration.register(line("B-BARE"), scope()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is an internal command");

        assertThat(superuserJdbc().queryForObject("select count(*) from catalogue.batch", Integer.class))
                .isZero();
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void anInternalCommandRunsInsideAnOuterCommandOverHttpUnderItsOneIdempotencyKey() {
        String key = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> first = post(key, "B-HTTP-1");

        assertThat(first.getStatusCode()).as(String.valueOf(first.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().get("created").asBoolean()).isTrue();
        UUID batchId = UUID.fromString(first.getBody().get("batchId").asText());
        assertThat(idempotencyRows(key))
                .as("the outer command's claim, and no second one")
                .isEqualTo(1);
        assertThat(kernel.committedAudit())
                .extracting(record -> record.eventType())
                .containsExactly("BATCH_REGISTERED");
        assertThat(kernel.committedEvents())
                .singleElement()
                .isInstanceOfSatisfying(BatchRegistered.class, event -> assertThat(event.batchId())
                        .isEqualTo(batchId));

        // The same request again: the stored answer of the first (created: true), the outer
        // command not run, nothing registered, audited or published.
        kernel.reset();
        ResponseEntity<JsonNode> replay = post(key, "B-HTTP-1");

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(GrnLikeCommand.RUNS.get()).isEqualTo(1);
        assertThat(idempotencyRows(key)).isEqualTo(1);
        assertThat(superuserJdbc().queryForObject("select count(*) from catalogue.batch", Integer.class))
                .isEqualTo(1);
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void anOrdinaryCommandInsideAnotherIsRefusedAsANestedCommand() {
        assertThatThrownBy(
                        () -> outer.run(scope(), () -> suppliers.handle(new RegisterSupplier("Nested Dairy"), scope())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Nested command")
                .hasMessageContaining("RegisterSupplierHandler");

        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    // ---- helpers and fixtures ---------------------------------------------------------------

    private ResponseEntity<JsonNode> post(String key, String batchNo) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestIdentityProvider.entityWideToken(USER, FEDERATION));
        headers.set("X-Scope-Entity", FEDERATION.toString());
        headers.set("Idempotency-Key", key);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(PATH, HttpMethod.POST, new HttpEntity<>(line(batchNo), headers), JsonNode.class);
    }

    private RegisterBatch line(String batchNo) {
        return new RegisterBatch(
                SKU,
                supplier,
                batchNo,
                null,
                LocalDate.of(2027, 3, 31),
                new BigDecimal("1080.00"),
                UUID.fromString("0190e671-0000-7000-8000-000000000300"),
                "GRN-1",
                1);
    }

    private static int idempotencyRows(String key) {
        return superuserJdbc()
                .queryForObject(
                        "select count(*) from kernel.idempotency_key where idempotency_key = ?", Integer.class, key);
    }

    private static ScopeContext scope() {
        return ScopeContext.dev(USER, FEDERATION, null);
    }

    /** What M4's GRN confirmation will be: a command of its own that registers the batch of a line. */
    @CommandHandler(permission = "test.grn.confirm")
    public static class GrnLikeCommand {

        // Static: the bean is a proxy, and a field read through a proxy reads the proxy's own, empty one.
        static final AtomicInteger RUNS = new AtomicInteger();

        private final BatchRegistration registration;

        public GrnLikeCommand(BatchRegistration registration) {
            this.registration = registration;
        }

        @Transactional
        public RegisteredBatch confirm(RegisterBatch line, ScopeContext scope) {
            RUNS.incrementAndGet();
            return registration.register(line, scope);
        }
    }

    /** Its operation: a test path, so that the request carries an Idempotency-Key through the kernel's filter. */
    @RestController
    public static class GrnLikeController {

        private final GrnLikeCommand grn;
        private final CurrentScope currentScope;

        public GrnLikeController(GrnLikeCommand grn, CurrentScope currentScope) {
            this.grn = grn;
            this.currentScope = currentScope;
        }

        @PostMapping(PATH)
        public RegisteredBatch confirm(@RequestBody RegisterBatch line) {
            return grn.confirm(line, currentScope.get());
        }
    }
}
