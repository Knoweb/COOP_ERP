package lk.coopfed.knoweb.m1party.internal.relationship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.api.ActivateRelationship;
import lk.coopfed.knoweb.m1party.api.AmendRelationshipTerms;
import lk.coopfed.knoweb.m1party.api.CreditLimitChanged;
import lk.coopfed.knoweb.m1party.api.OpenTradingRelationship;
import lk.coopfed.knoweb.m1party.api.RelationshipActivated;
import lk.coopfed.knoweb.m1party.api.RelationshipAmended;
import lk.coopfed.knoweb.m1party.api.RelationshipOpened;
import lk.coopfed.knoweb.m1party.api.RelationshipSuspended;
import lk.coopfed.knoweb.m1party.api.SuspendRelationship;
import lk.coopfed.knoweb.m1party.api.TradePriceListCheck;
import lk.coopfed.knoweb.m1party.query.RelationshipQueries;
import lk.coopfed.knoweb.m1party.query.RelationshipSide;
import lk.coopfed.knoweb.m1party.query.RelationshipView;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The two flows of M1-04's "done when" (21A section 10: scenarios 6.2 and 6.4 of doc 21), run
 * through the handlers against PostgreSQL as the application user, with its policies and the
 * A-I3 exclusion constraint; and the operations of the slice over HTTP.
 *
 * <p>M3's price-list check is a mock here: the stub on main accepts every list, and the refusal
 * path ("unpublished list rejected with the M3 reason") needs a list M3 refuses.
 */
class RelationshipScenariosIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-00000000e001");
    private static final UUID FEDERATION = UUID.fromString("00000000-0000-0000-0000-00000000e101");
    private static final UUID DISTRIBUTOR = UUID.fromString("00000000-0000-0000-0000-00000000e102");
    private static final UUID SOCIETY = UUID.fromString("00000000-0000-0000-0000-00000000e103");
    private static final UUID OTHER_SOCIETY = UUID.fromString("00000000-0000-0000-0000-00000000e104");
    private static final UUID PRICE_LIST = UUID.fromString("00000000-0000-0000-0000-00000000e501");
    private static final UUID UNPUBLISHED_LIST = UUID.fromString("00000000-0000-0000-0000-00000000e502");

    private static final LocalDate APRIL = LocalDate.of(2026, 4, 1);
    private static final LocalDate JULY = LocalDate.of(2026, 7, 1);

    @Autowired
    private Handles<OpenTradingRelationship, UUID> open;

    @Autowired
    private Handles<ActivateRelationship, UUID> activate;

    @Autowired
    private Handles<AmendRelationshipTerms, UUID> amend;

    @Autowired
    private Handles<SuspendRelationship, UUID> suspend;

    @Autowired
    private RelationshipQueries queries;

    @Autowired
    private Clock clock;

    @Autowired
    private TestRestTemplate http;

    @MockBean
    private TradePriceListCheck priceLists;

    @BeforeEach
    void theChainExists() {
        clean();
        JdbcTemplate admin = superuserJdbc();
        insertEntity(admin, FEDERATION, "FED", "FEDERATION", "ACTIVE");
        insertEntity(admin, DISTRIBUTOR, "D01", "DISTRIBUTOR", "ACTIVE");
        insertEntity(admin, SOCIETY, "M01", "MPCS", "ONBOARDING");
        insertEntity(admin, OTHER_SOCIETY, "M02", "MPCS", "ACTIVE");
        when(priceLists.refusal(any(), any(), any())).thenReturn(Optional.empty());
        when(priceLists.refusal(eq(UNPUBLISHED_LIST), any(), any()))
                .thenReturn(Optional.of("prc.price_list.not_published"));
        kernel.reset();
    }

    @AfterEach
    void clean() {
        superuserJdbc()
                .execute("truncate table party.entity_relationship, party.entity_party_directory,"
                        + " party.federation_identity, party.entity cascade");
    }

    // ---- Scenario 6.2: opening a distributor to MPCS relationship ------------------------------

    @Test
    void scenario62_theDistributorOpensADraftActivatesItAndTheSocietyTradesUnderIt() {
        UUID id = open.handle(openTo(SOCIETY, PRICE_LIST, APRIL, null), own(DISTRIBUTOR));

        assertThat(status(id)).isEqualTo("DRAFT");
        assertThat(kernel.committedAudit()).singleElement().satisfies(a -> {
            assertThat(a.eventType()).isEqualTo("RELATIONSHIP_OPENED");
            assertThat(a.subject().id()).isEqualTo(id);
            assertThat(a.scope().entityId()).isEqualTo(DISTRIBUTOR);
        });
        assertThat(kernel.committedEvents()).singleElement().isInstanceOfSatisfying(RelationshipOpened.class, e -> {
            assertThat(e.relationshipId()).isEqualTo(id);
            assertThat(e.sellerEntityId()).isEqualTo(DISTRIBUTOR);
            assertThat(e.buyerEntityId()).isEqualTo(SOCIETY);
            assertThat(e.effectiveFrom()).isEqualTo(APRIL);
            assertThat(e.termsHash()).hasSize(64);
        });
        assertThat(queries.lookupRelationship(DISTRIBUTOR, SOCIETY, APRIL, party(SOCIETY)))
                .as("nothing trades under a DRAFT")
                .isEmpty();

        kernel.reset();
        ScopeContext seller = own(DISTRIBUTOR);
        activate.handle(new ActivateRelationship(id), seller);

        verify(priceLists).refusal(eq(PRICE_LIST), eq(DISTRIBUTOR), any());
        assertThat(status(id)).isEqualTo("ACTIVE");
        assertThat(kernel.committedAudit()).singleElement().satisfies(a -> {
            assertThat(a.eventType()).isEqualTo("RELATIONSHIP_ACTIVATED");
            assertThat(((Map<?, ?>) a.before()).get("status")).isEqualTo("DRAFT");
            assertThat(((Map<?, ?>) a.after()).get("status")).isEqualTo("ACTIVE");
        });
        assertThat(kernel.committedEvents())
                .singleElement()
                .isInstanceOfSatisfying(RelationshipActivated.class, e -> assertThat(e.relationshipId())
                        .isEqualTo(id));

        // M4 may now accept orders from the buyer: the buyer, in PARTY and in its own scope,
        // and the Federation view find the terms on any date of the range.
        Optional<RelationshipView> asBuyer = queries.lookupRelationship(DISTRIBUTOR, SOCIETY, JULY, party(SOCIETY));
        assertThat(asBuyer).hasValueSatisfying(r -> {
            assertThat(r.relationshipId()).isEqualTo(id);
            assertThat(r.priceListId()).isEqualTo(PRICE_LIST);
            assertThat(r.creditLimit()).isEqualByComparingTo("3000000.00");
            assertThat(r.paymentTermsDays()).isEqualTo(30);
            assertThat(r.allocationRule()).isEqualTo("FCFS");
        });
        assertThat(queries.lookupRelationship(DISTRIBUTOR, SOCIETY, JULY, own(SOCIETY)))
                .isPresent();
        assertThat(queries.lookupRelationship(DISTRIBUTOR, SOCIETY, JULY, federationView()))
                .isPresent();
        assertThat(queries.lookupRelationship(DISTRIBUTOR, SOCIETY, APRIL.minusDays(1), own(SOCIETY)))
                .as("not before the first day")
                .isEmpty();
        assertThat(queries.lookupRelationship(DISTRIBUTOR, SOCIETY, JULY, own(OTHER_SOCIETY)))
                .as("a stranger sees nothing")
                .isEmpty();
        assertThat(queries.listRelationships(RelationshipSide.BUYER, own(SOCIETY)))
                .extracting(RelationshipView::relationshipId)
                .containsExactly(id);
        assertThat(queries.listRelationships(RelationshipSide.SELLER, own(SOCIETY)))
                .isEmpty();
    }

    @Test
    void scenario62_anOverlappingActiveRowIsRejectedByThePreCheckAndByTheExclusionConstraint() {
        UUID first = open.handle(openTo(SOCIETY, PRICE_LIST, APRIL, null), own(DISTRIBUTOR));
        UUID second = open.handle(openTo(SOCIETY, PRICE_LIST, JULY, null), own(DISTRIBUTOR));
        activate.handle(new ActivateRelationship(first), own(DISTRIBUTOR));
        kernel.reset();

        // A second DRAFT of the pair cannot become ACTIVE over the first.
        assertThatThrownBy(() -> activate.handle(new ActivateRelationship(second), own(DISTRIBUTOR)))
                .isInstanceOfSatisfying(ProblemException.class, e -> {
                    assertThat(e.messageId()).isEqualTo("m1.relationship.overlap");
                    assertThat(e.parameters()).containsEntry("existingRelationshipId", first);
                });
        // Nor can a new DRAFT be opened over it.
        assertThatThrownBy(() -> open.handle(openTo(SOCIETY, PRICE_LIST, JULY, null), own(DISTRIBUTOR)))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m1.relationship.overlap"));
        assertThat(status(second)).isEqualTo("DRAFT");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();

        // The constraint holds whoever writes: even the superuser cannot put a second ACTIVE row
        // over the first (A-I3), and gets SQLSTATE 23P01.
        assertThatThrownBy(() -> superuserJdbc()
                        .update(
                                "update party.entity_relationship set status = 'ACTIVE' where relationship_id = ?",
                                second))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("exclu");
    }

    @Test
    void scenario62_anUnpublishedListIsRejectedWithTheM3Reason() {
        UUID id = open.handle(openTo(SOCIETY, UNPUBLISHED_LIST, APRIL, null), own(DISTRIBUTOR));
        kernel.reset();

        assertThatThrownBy(() -> activate.handle(new ActivateRelationship(id), own(DISTRIBUTOR)))
                .isInstanceOfSatisfying(ProblemException.class, e -> {
                    assertThat(e.messageId()).isEqualTo("m1.relationship.price_list_refused");
                    assertThat(e.parameters())
                            .containsEntry("priceListId", UNPUBLISHED_LIST)
                            .containsEntry("reason", "prc.price_list.not_published");
                });
        verify(priceLists).refusal(eq(UNPUBLISHED_LIST), eq(DISTRIBUTOR), any());
        assertThat(status(id)).isEqualTo("DRAFT");
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void scenario62_theTierRuleAndTheFlagHoldAgainstTheDatabase() {
        assertThatThrownBy(() -> open.handle(openTo(DISTRIBUTOR, PRICE_LIST, APRIL, null), own(SOCIETY)))
                .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                        .isEqualTo("m1.relationship.tier_not_allowed"));
        assertThatThrownBy(() -> open.handle(openTo(SOCIETY, PRICE_LIST, APRIL, null), own(FEDERATION)))
                .as("Federation to society needs the flag, off by default (doc 10 F-03)")
                .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                        .isEqualTo("m1.relationship.tier_not_allowed"));
        assertThat(open.handle(openTo(DISTRIBUTOR, PRICE_LIST, APRIL, null), own(FEDERATION)))
                .isNotNull();
    }

    // ---- Scenario 6.4: raising a credit limit ------------------------------------------------

    @Test
    void scenario64_theSellerRaisesTheLimitWithAReasonAndAFreshSecondFactor() {
        UUID current = activeRelationship(APRIL);
        kernel.reset();
        ScopeContext seller = withFreshMfa(DISTRIBUTOR);

        UUID next = amend.handle(raiseLimit(current, "5000000.00", JULY, "RENEGOTIATED"), seller);

        // The old row is closed the day before and keeps its terms; the new row carries the limit.
        Map<String, Object> old = row(current);
        assertThat(old.get("effective_to").toString()).isEqualTo("2026-06-30");
        assertThat((BigDecimal) old.get("credit_limit")).isEqualByComparingTo("3000000.00");
        assertThat(old.get("status")).isEqualTo("ACTIVE");
        Map<String, Object> amended = row(next);
        assertThat(amended.get("effective_from").toString()).isEqualTo("2026-07-01");
        assertThat(amended.get("effective_to")).isNull();
        assertThat((BigDecimal) amended.get("credit_limit")).isEqualByComparingTo("5000000.00");
        assertThat(amended.get("status")).isEqualTo("ACTIVE");

        assertThat(kernel.committedAudit())
                .extracting(a -> a.eventType() + " " + a.reason())
                .containsExactly("RELATIONSHIP_AMENDED RENEGOTIATED", "CREDIT_LIMIT_CHANGED RENEGOTIATED");
        assertThat(kernel.committedEvents()).hasSize(2);
        assertThat(kernel.committedEvents().get(0)).isInstanceOfSatisfying(RelationshipAmended.class, e -> {
            assertThat(e.relationshipId()).isEqualTo(next);
            assertThat(e.previousRelationshipId()).isEqualTo(current);
            assertThat(e.effectiveFrom()).isEqualTo(JULY);
        });
        assertThat(kernel.committedEvents().get(1)).isInstanceOfSatisfying(CreditLimitChanged.class, e -> {
            assertThat(e.relationshipId()).isEqualTo(next);
            assertThat(e.previousCreditLimit()).isEqualByComparingTo("3000000.00");
            assertThat(e.creditLimit()).isEqualByComparingTo("5000000.00");
        });

        // M4 reads the limit in force on each date.
        assertThat(queries.lookupRelationship(DISTRIBUTOR, SOCIETY, JULY.minusDays(1), own(DISTRIBUTOR)))
                .hasValueSatisfying(r -> assertThat(r.relationshipId()).isEqualTo(current));
        assertThat(queries.lookupRelationship(DISTRIBUTOR, SOCIETY, JULY, own(DISTRIBUTOR)))
                .hasValueSatisfying(r -> assertThat(r.relationshipId()).isEqualTo(next));
    }

    @Test
    void scenario64_aMissingReasonOrAStaleSecondFactorIsRejectedAndTheOldRowIsNeverEdited() {
        UUID current = activeRelationship(APRIL);
        Map<String, Object> before = row(current);
        kernel.reset();

        assertThatThrownBy(() -> amend.handle(raiseLimit(current, "5000000.00", JULY, null), withFreshMfa(DISTRIBUTOR)))
                .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                        .isEqualTo("m1.relationship.reason_required"));
        ScopeContext stale = scope(DISTRIBUTOR, PolicyClass.OWN, clock.instant().minus(Duration.ofHours(1)));
        assertThatThrownBy(() -> amend.handle(raiseLimit(current, "5000000.00", JULY, "RENEGOTIATED"), stale))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("mfa.required"));

        assertThat(row(current)).isEqualTo(before);
        assertThat(count()).isEqualTo(1);
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void theSellerSuspendsAndTheBuyerCanNeitherAmendNorSuspend() {
        UUID id = activeRelationship(APRIL);
        kernel.reset();

        assertThatThrownBy(() -> suspend.handle(new SuspendRelationship(id, "OVERDUE", null), own(SOCIETY)))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m1.relationship.not_seller"));
        assertThatThrownBy(() -> amend.handle(raiseLimit(id, "1.00", JULY, "X"), withFreshMfa(SOCIETY)))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m1.relationship.not_seller"));

        suspend.handle(new SuspendRelationship(id, "OVERDUE", "three invoices overdue"), own(DISTRIBUTOR));

        assertThat(status(id)).isEqualTo("SUSPENDED");
        assertThat(kernel.committedAudit()).singleElement().satisfies(a -> {
            assertThat(a.eventType()).isEqualTo("RELATIONSHIP_SUSPENDED");
            assertThat(a.reason()).isEqualTo("OVERDUE: three invoices overdue");
        });
        assertThat(kernel.committedEvents()).singleElement().isInstanceOf(RelationshipSuspended.class);
        assertThat(queries.lookupRelationship(DISTRIBUTOR, SOCIETY, JULY, own(SOCIETY)))
                .as("a suspended relationship is not in force")
                .isEmpty();
    }

    /**
     * 21A section 9: "random effective ranges never violate the exclusion constraint". Random
     * opens, activations and amendments on one pair: whatever the order and the dates, every
     * refusal is a problem with a message id (the pre-check saw it first), never a database
     * error, and the table never holds two ACTIVE rows of the pair on one date.
     */
    @Test
    void randomEffectiveRangesNeverEscapeThePreCheck() {
        Random random = new Random(20260925L);
        LocalDate base = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 40; i++) {
            LocalDate from = base.plusDays(random.nextInt(365));
            LocalDate to = random.nextBoolean() ? null : from.plusDays(random.nextInt(200));
            try {
                switch (random.nextInt(3)) {
                    case 0 -> {
                        UUID id = open.handle(openTo(SOCIETY, PRICE_LIST, from, to), own(DISTRIBUTOR));
                        activate.handle(new ActivateRelationship(id), own(DISTRIBUTOR));
                    }
                    case 1 -> open.handle(openTo(SOCIETY, PRICE_LIST, from, to), own(DISTRIBUTOR));
                    default -> {
                        List<UUID> active = superuserJdbc()
                                .queryForList(
                                        "select relationship_id from party.entity_relationship where status = 'ACTIVE'",
                                        UUID.class);
                        if (!active.isEmpty()) {
                            UUID target = active.get(random.nextInt(active.size()));
                            amend.handle(
                                    new AmendRelationshipTerms(
                                            target,
                                            from,
                                            null,
                                            null,
                                            10 + random.nextInt(60),
                                            null,
                                            null,
                                            null,
                                            "RANDOM",
                                            null),
                                    own(DISTRIBUTOR));
                        }
                    }
                }
            } catch (ProblemException expected) {
                assertThat(expected.messageId()).startsWith("m1.relationship.");
            }
        }
        Integer overlapping = superuserJdbc()
                .queryForObject(
                        """
                        select count(*) from party.entity_relationship a
                          join party.entity_relationship b
                            on a.seller_entity_id = b.seller_entity_id and a.buyer_entity_id = b.buyer_entity_id
                           and a.relationship_id < b.relationship_id
                         where a.status = 'ACTIVE' and b.status = 'ACTIVE'
                           and daterange(a.effective_from, coalesce(a.effective_to, 'infinity'), '[]')
                            && daterange(b.effective_from, coalesce(b.effective_to, 'infinity'), '[]')
                        """,
                        Integer.class);
        assertThat(overlapping).isZero();
    }

    // ---- Over HTTP: the slice, the generated interface, the kernel's request checks ------------

    @Test
    void theOperationsOfTheSliceOverHttp() {
        ResponseEntity<Map> opened = http.exchange(
                "/v1/party/relationships",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "buyerEntityId",
                                SOCIETY.toString(),
                                "priceListId",
                                PRICE_LIST.toString(),
                                "creditLimit",
                                3000000.00,
                                "paymentTermsDays",
                                30,
                                "effectiveFrom",
                                "2026-04-01"),
                        headers(DISTRIBUTOR, "OWN")),
                Map.class);
        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(opened.getBody()).containsEntry("status", "DRAFT").containsEntry("allocationRule", "FCFS");
        String id = (String) opened.getBody().get("relationshipId");

        assertThat(post("/v1/party/relationships/" + id + "/activate", null, DISTRIBUTOR)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<List> asBuyer = http.exchange(
                "/v1/party/relationships?side=BUYER",
                HttpMethod.GET,
                new HttpEntity<>(headers(SOCIETY, "PARTY")),
                List.class);
        assertThat(asBuyer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(asBuyer.getBody()).hasSize(1);

        // The development scope carries no second factor, so a limit change asks for one.
        ResponseEntity<Map> limit = post(
                "/v1/party/relationships/" + id + "/amend",
                Map.of("effectiveFrom", "2026-07-01", "creditLimit", 5000000, "reasonCode", "RENEGOTIATED"),
                DISTRIBUTOR);
        assertThat(limit.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(limit.getBody()).containsEntry("code", "mfa.required");

        ResponseEntity<Map> terms = post(
                "/v1/party/relationships/" + id + "/amend",
                Map.of("effectiveFrom", "2026-07-01", "paymentTermsDays", 45, "reasonCode", "TERMS_REVIEW"),
                DISTRIBUTOR);
        assertThat(terms.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(terms.getBody()).containsEntry("paymentTermsDays", 45).containsEntry("effectiveFrom", "2026-07-01");

        ResponseEntity<Map> overlap = post(
                "/v1/party/relationships",
                Map.of("buyerEntityId", SOCIETY.toString(), "effectiveFrom", "2026-08-01"),
                DISTRIBUTOR);
        assertThat(overlap.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(overlap.getBody()).containsEntry("code", "m1.relationship.overlap");

        ResponseEntity<Map> noReason = post("/v1/party/relationships/" + id + "/suspend", Map.of(), DISTRIBUTOR);
        assertThat(noReason.getStatusCode())
                .as("the shape of the request is the slice's: 400, not the handler's 422")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> got = http.exchange(
                "/v1/party/relationships/" + terms.getBody().get("relationshipId"),
                HttpMethod.GET,
                new HttpEntity<>(headers(SOCIETY, "OWN")),
                Map.class);
        assertThat(got.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.exchange(
                                "/v1/party/relationships/" + id,
                                HttpMethod.GET,
                                new HttpEntity<>(headers(OTHER_SOCIETY, "OWN")),
                                Map.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- helpers --------------------------------------------------------------------------------

    private UUID activeRelationship(LocalDate from) {
        UUID id = open.handle(openTo(SOCIETY, PRICE_LIST, from, null), own(DISTRIBUTOR));
        activate.handle(new ActivateRelationship(id), own(DISTRIBUTOR));
        return id;
    }

    private static OpenTradingRelationship openTo(UUID buyer, UUID priceList, LocalDate from, LocalDate to) {
        return new OpenTradingRelationship(
                buyer, priceList, new BigDecimal("3000000.00"), 30, null, null, null, from, to);
    }

    private static AmendRelationshipTerms raiseLimit(UUID id, String limit, LocalDate from, String reason) {
        return new AmendRelationshipTerms(id, from, null, new BigDecimal(limit), null, null, null, null, reason, null);
    }

    private ResponseEntity<Map> post(String url, Object body, UUID entity) {
        return http.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers(entity, "OWN")), Map.class);
    }

    private static HttpHeaders headers(UUID entity, String policyClass) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Dev-User", USER.toString());
        headers.set("X-Scope-Entity", entity.toString());
        headers.set("X-Dev-Scope-Class", policyClass);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return headers;
    }

    private static ScopeContext own(UUID entity) {
        return scope(entity, PolicyClass.OWN, null);
    }

    private static ScopeContext party(UUID entity) {
        return scope(entity, PolicyClass.PARTY, null);
    }

    private static ScopeContext federationView() {
        return scope(FEDERATION, PolicyClass.FEDERATION_VIEW, null);
    }

    private ScopeContext withFreshMfa(UUID entity) {
        return scope(entity, PolicyClass.OWN, clock.instant().minus(Duration.ofMinutes(1)));
    }

    private static ScopeContext scope(UUID entity, PolicyClass policyClass, Instant mfaAt) {
        Scope active = new Scope(entity, null);
        return new ScopeContext(
                USER, null, entity, List.of(active), active, policyClass, Set.of(), mfaAt, Locale.ENGLISH, null);
    }

    private static String status(UUID id) {
        return superuserJdbc()
                .queryForObject(
                        "select status from party.entity_relationship where relationship_id = ?", String.class, id);
    }

    private static Map<String, Object> row(UUID id) {
        return superuserJdbc().queryForMap("select * from party.entity_relationship where relationship_id = ?", id);
    }

    private static int count() {
        return superuserJdbc().queryForObject("select count(*) from party.entity_relationship", Integer.class);
    }

    private static void insertEntity(JdbcTemplate admin, UUID id, String code, String type, String status) {
        admin.update(
                "insert into party.entity (entity_id, entity_code, entity_type, legal_name_en, status)"
                        + " values (?, ?, ?, ?, ?)",
                id,
                code,
                type,
                code + " legal name",
                status);
    }
}
