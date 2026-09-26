package lk.coopfed.knoweb.m1party.internal.relationship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.PermissionResolver;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.api.ActivateRelationship;
import lk.coopfed.knoweb.m1party.api.AmendRelationshipTerms;
import lk.coopfed.knoweb.m1party.api.CreditLimitChanged;
import lk.coopfed.knoweb.m1party.api.OpenTradingRelationship;
import lk.coopfed.knoweb.m1party.api.RelationshipAmended;
import lk.coopfed.knoweb.m1party.api.RelationshipSuspended;
import lk.coopfed.knoweb.m1party.api.SuspendRelationship;
import lk.coopfed.knoweb.m1party.api.TradePriceListCheck;
import lk.coopfed.knoweb.m1party.internal.relationship.TradingStanding.Standing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Every guard of the four relationship handlers as a failing case with its message id (21A
 * section 9), the amendment in full (section 6.1), and the closure arithmetic at month ends.
 * The database is a mock here; RelationshipScenariosIntegrationTest runs the same rules against
 * PostgreSQL with its policies and its exclusion constraint.
 */
class RelationshipHandlersTest {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID FEDERATION = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID DISTRIBUTOR = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final UUID SOCIETY = UUID.fromString("00000000-0000-0000-0000-000000000203");
    private static final UUID SHOP = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID PRICE_LIST = UUID.fromString("00000000-0000-0000-0000-000000000401");

    private static final Instant NOW = Instant.parse("2026-09-25T06:00:00Z");
    private static final LocalDate APRIL = LocalDate.of(2026, 4, 1);
    private static final LocalDate JULY = LocalDate.of(2026, 7, 1);

    private final RelationshipRepository repository = mock(RelationshipRepository.class);
    private final TradingStanding standing = mock(TradingStanding.class);
    private final ConfigRegistry config = mock(ConfigRegistry.class);
    private final TradePriceListCheck priceLists = mock(TradePriceListCheck.class);
    private final PermissionResolver permissions = mock(PermissionResolver.class);
    private final AuditFacade audit = mock(AuditFacade.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private OpenTradingRelationshipHandler open;
    private ActivateRelationshipHandler activate;
    private AmendRelationshipTermsHandler amend;
    private SuspendRelationshipHandler suspend;

    @BeforeEach
    void setUp() {
        open = new OpenTradingRelationshipHandler(repository, standing, config, audit, events);
        activate = new ActivateRelationshipHandler(repository, priceLists, audit, events);
        amend = new AmendRelationshipTermsHandler(
                repository, priceLists, Optional.of(permissions), config, clock, audit, events);
        suspend = new SuspendRelationshipHandler(repository, clock, audit, events);

        when(standing.of(FEDERATION)).thenReturn(Optional.of(new Standing("FEDERATION", "ACTIVE")));
        when(standing.of(DISTRIBUTOR)).thenReturn(Optional.of(new Standing("DISTRIBUTOR", "ACTIVE")));
        when(standing.of(SOCIETY)).thenReturn(Optional.of(new Standing("MPCS", "ONBOARDING")));
        when(repository.activeOverlapping(any(), any(), any(), any(), any())).thenReturn(List.of());
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(repository.lockForUpdate(any())).thenAnswer(call -> call.getArgument(0));
        when(priceLists.refusal(any(), any(), any())).thenReturn(Optional.empty());
        when(permissions.allows(any(), anyString())).thenReturn(true);
        when(config.getDuration(eq(AmendRelationshipTermsHandler.MFA_MAX_AGE), any(), any()))
                .thenReturn(Duration.ofMinutes(10));
    }

    // ---- OpenTradingRelationship -----------------------------------------------------------

    @Nested
    class Open {

        @Test
        void aDistributorOpensADraftToASocietyWithTheDefaults() {
            UUID id = open.handle(openTo(SOCIETY, APRIL, null), own(DISTRIBUTOR));

            ArgumentCaptor<Relationship> saved = ArgumentCaptor.forClass(Relationship.class);
            verify(repository).saveAndFlush(saved.capture());
            Relationship r = saved.getValue();
            assertThat(r.getId()).isEqualTo(id);
            assertThat(r.sellerEntityId()).isEqualTo(DISTRIBUTOR);
            assertThat(r.buyerEntityId()).isEqualTo(SOCIETY);
            assertThat(r.isDraft()).isTrue();
            assertThat(r.terms().discrepancyWindowDays()).isEqualTo((short) 7);
            assertThat(r.terms().orderLockHoursBeforeEta()).isEqualTo((short) 24);
            assertThat(r.terms().allocationRule()).isEqualTo("FCFS");
            verify(audit).record(eq("RELATIONSHIP_OPENED"), any(), eq(null), any(), any());
            verify(events).publish(any());
        }

        @Test
        void aShopScopedCallerIsRefused() {
            refused(
                    () -> open.handle(openTo(SOCIETY, APRIL, null), atShop(DISTRIBUTOR)),
                    "m1.relationship.seller_scope_required");
        }

        @Test
        void aFederationViewCallerIsRefused() {
            refused(
                    () -> open.handle(
                            openTo(SOCIETY, APRIL, null), scope(DISTRIBUTOR, null, PolicyClass.FEDERATION_VIEW, null)),
                    "m1.relationship.seller_scope_required");
        }

        @Test
        void anEntityCannotSellToItself() {
            refused(() -> open.handle(openTo(DISTRIBUTOR, APRIL, null), own(DISTRIBUTOR)), "m1.relationship.self");
        }

        @Test
        void anUnknownBuyerIsRefused() {
            UUID nobody = UUID.randomUUID();
            when(standing.of(nobody)).thenReturn(Optional.empty());
            refused(
                    () -> open.handle(openTo(nobody, APRIL, null), own(DISTRIBUTOR)),
                    "m1.relationship.buyer_not_found");
        }

        @Test
        void aSuspendedBuyerIsRefused() {
            when(standing.of(SOCIETY)).thenReturn(Optional.of(new Standing("MPCS", "SUSPENDED")));
            refused(
                    () -> open.handle(openTo(SOCIETY, APRIL, null), own(DISTRIBUTOR)),
                    "m1.relationship.party_not_trading");
        }

        @Test
        void aSuspendedSellerIsRefused() {
            when(standing.of(DISTRIBUTOR)).thenReturn(Optional.of(new Standing("DISTRIBUTOR", "SUSPENDED")));
            refused(
                    () -> open.handle(openTo(SOCIETY, APRIL, null), own(DISTRIBUTOR)),
                    "m1.relationship.party_not_trading");
        }

        @Test
        void theTierRuleAllowsFederationToDistributorAndDistributorToSociety() {
            open.handle(openTo(DISTRIBUTOR, APRIL, null), own(FEDERATION));
            open.handle(openTo(SOCIETY, APRIL, null), own(DISTRIBUTOR));
            verify(repository, times(2)).saveAndFlush(any());
        }

        @Test
        void aSocietySellingUpwardsIsRefused() {
            refused(
                    () -> open.handle(openTo(DISTRIBUTOR, APRIL, null), own(SOCIETY)),
                    "m1.relationship.tier_not_allowed");
        }

        @Test
        void theFederationSellsToASocietyOnlyWhenTheFlagIsOn() {
            // assumes doc 10 F-03: the flag is present and off by default.
            refused(
                    () -> open.handle(openTo(SOCIETY, APRIL, null), own(FEDERATION)),
                    "m1.relationship.tier_not_allowed");

            when(config.getBoolean(eq(OpenTradingRelationshipHandler.FEDERATION_DIRECT), any(), eq(false)))
                    .thenReturn(true);
            open.handle(openTo(SOCIETY, APRIL, null), own(FEDERATION));
            verify(repository).saveAndFlush(any());
        }

        @Test
        void theLastDayMayNotBeBeforeTheFirst() {
            refused(
                    () -> open.handle(openTo(SOCIETY, JULY, APRIL), own(DISTRIBUTOR)),
                    "m1.relationship.effective_range_invalid");
        }

        @Test
        void anUnknownAllocationRuleIsRefused() {
            OpenTradingRelationship command =
                    new OpenTradingRelationship(SOCIETY, PRICE_LIST, null, 30, null, null, "LOTTERY", APRIL, null);
            refused(() -> open.handle(command, own(DISTRIBUTOR)), "m1.relationship.allocation_rule_invalid");
        }

        @Test
        void anOverlappingActiveRowIsNamedInTheRefusal() {
            Relationship existing = active(DISTRIBUTOR, SOCIETY, APRIL, null);
            when(repository.activeOverlapping(
                            eq(DISTRIBUTOR), eq(SOCIETY), eq(JULY), eq(RelationshipRules.OPEN_END), any()))
                    .thenReturn(List.of(existing));

            assertThatThrownBy(() -> open.handle(openTo(SOCIETY, JULY, null), own(DISTRIBUTOR)))
                    .isInstanceOfSatisfying(ProblemException.class, e -> {
                        assertThat(e.messageId()).isEqualTo("m1.relationship.overlap");
                        assertThat(e.parameters())
                                .containsEntry("existingRelationshipId", existing.getId())
                                .containsEntry("effectiveFrom", "2026-04-01");
                    });
            nothingWritten();
        }
    }

    // ---- ActivateRelationship --------------------------------------------------------------

    @Nested
    class Activate {

        @Test
        void theSellerActivatesADraftAfterMThreeAccepts() {
            Relationship draft = draft(DISTRIBUTOR, SOCIETY);
            ScopeContext seller = own(DISTRIBUTOR);

            activate.handle(new ActivateRelationship(draft.getId()), seller);

            assertThat(draft.isActive()).isTrue();
            verify(priceLists).refusal(PRICE_LIST, DISTRIBUTOR, seller);
            verify(audit).record(eq("RELATIONSHIP_ACTIVATED"), any(), any(), any(), eq(seller));
            verify(events).publish(any());
        }

        @Test
        void anUnknownRelationshipIsRefused() {
            refused(
                    () -> activate.handle(new ActivateRelationship(UUID.randomUUID()), own(DISTRIBUTOR)),
                    "m1.relationship.not_found");
        }

        @Test
        void theBuyerCannotActivate() {
            Relationship draft = draft(DISTRIBUTOR, SOCIETY);
            refused(
                    () -> activate.handle(new ActivateRelationship(draft.getId()), own(SOCIETY)),
                    "m1.relationship.not_seller");
        }

        @Test
        void onlyADraftIsActivated() {
            Relationship r = found(active(DISTRIBUTOR, SOCIETY, APRIL, null));
            refused(
                    () -> activate.handle(new ActivateRelationship(r.getId()), own(DISTRIBUTOR)),
                    "m1.relationship.not_draft");
        }

        @Test
        void aDraftWithoutAPriceListIsRefused() {
            Relationship r = found(Relationship.open(
                    UUID.randomUUID(), DISTRIBUTOR, SOCIETY, terms(null, null, (short) 30), APRIL, null));
            refused(
                    () -> activate.handle(new ActivateRelationship(r.getId()), own(DISTRIBUTOR)),
                    "m1.relationship.price_list_required");
        }

        @Test
        void aDraftWithoutPaymentTermsIsRefused() {
            Relationship r = found(Relationship.open(
                    UUID.randomUUID(), DISTRIBUTOR, SOCIETY, terms(PRICE_LIST, null, null), APRIL, null));
            refused(
                    () -> activate.handle(new ActivateRelationship(r.getId()), own(DISTRIBUTOR)),
                    "m1.relationship.payment_terms_required");
        }

        @Test
        void mThreesRefusalIsPassedOnWithItsReason() {
            Relationship draft = draft(DISTRIBUTOR, SOCIETY);
            when(priceLists.refusal(any(), any(), any())).thenReturn(Optional.of("prc.price_list.not_published"));

            assertThatThrownBy(() -> activate.handle(new ActivateRelationship(draft.getId()), own(DISTRIBUTOR)))
                    .isInstanceOfSatisfying(ProblemException.class, e -> {
                        assertThat(e.messageId()).isEqualTo("m1.relationship.price_list_refused");
                        assertThat(e.parameters()).containsEntry("reason", "prc.price_list.not_published");
                    });
            assertThat(draft.isDraft()).isTrue();
            nothingWritten();
        }

        @Test
        void anotherActiveRowOfThePairIsRefused() {
            Relationship draft = draft(DISTRIBUTOR, SOCIETY);
            when(repository.activeOverlapping(any(), any(), any(), any(), eq(draft.getId())))
                    .thenReturn(List.of(active(DISTRIBUTOR, SOCIETY, APRIL, null)));
            refused(
                    () -> activate.handle(new ActivateRelationship(draft.getId()), own(DISTRIBUTOR)),
                    "m1.relationship.overlap");
        }

        @Test
        void theExclusionConstraintIsTheBackstopOfARace() {
            // Two clerks activate at once and both pass the pre-check; PostgreSQL refuses the
            // second with SQLSTATE 23P01, which becomes the same problem as the pre-check's.
            Relationship draft = draft(DISTRIBUTOR, SOCIETY);
            when(repository.saveAndFlush(any()))
                    .thenThrow(new DataIntegrityViolationException(
                            "conflicting key value violates exclusion constraint",
                            new SQLException("exclusion", "23P01")));

            refused(
                    () -> activate.handle(new ActivateRelationship(draft.getId()), own(DISTRIBUTOR)),
                    "m1.relationship.overlap");
            verify(events, never()).publish(any());
        }
    }

    // ---- AmendRelationshipTerms (21A section 6.1) -------------------------------------------

    @Nested
    class Amend {

        @Test
        void aLimitChangeClosesTheOldRowAndOpensTheNextWithTwoAuditsAndTwoEvents() {
            Relationship current = found(active(DISTRIBUTOR, SOCIETY, APRIL, null));
            ScopeContext seller = withFreshMfa(DISTRIBUTOR);

            UUID next = amend.handle(limitTo(current, "5000000.00", JULY, "RENEGOTIATED"), seller);

            assertThat(next).isNotEqualTo(current.getId());
            assertThat(current.effectiveTo()).isEqualTo(LocalDate.of(2026, 6, 30));
            assertThat(current.creditLimit())
                    .as("the old row's terms are never edited")
                    .isEqualByComparingTo("3000000.00");

            ArgumentCaptor<Relationship> saved = ArgumentCaptor.forClass(Relationship.class);
            verify(repository, times(2)).saveAndFlush(saved.capture());
            assertThat(saved.getAllValues().get(0))
                    .as("the old row is closed first")
                    .isSameAs(current);
            Relationship nextRow = saved.getAllValues().get(1);
            assertThat(nextRow.isActive()).isTrue();
            assertThat(nextRow.effectiveFrom()).isEqualTo(JULY);
            assertThat(nextRow.effectiveTo()).isNull();
            assertThat(nextRow.creditLimit()).isEqualByComparingTo("5000000.00");
            assertThat(nextRow.paymentTermsDays())
                    .as("a term left out keeps its value")
                    .isEqualTo((short) 30);

            verify(permissions).allows(seller, "bil.creditlimit.change");
            verify(audit).record(eq("RELATIONSHIP_AMENDED"), any(), any(), any(), eq(seller), eq("RENEGOTIATED"));
            verify(audit).record(eq("CREDIT_LIMIT_CHANGED"), any(), any(), any(), eq(seller), eq("RENEGOTIATED"));

            ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
            verify(events, times(2)).publish(published.capture());
            assertThat(published.getAllValues().get(0)).isInstanceOfSatisfying(RelationshipAmended.class, e -> {
                assertThat(e.relationshipId()).isEqualTo(next);
                assertThat(e.previousRelationshipId()).isEqualTo(current.getId());
                assertThat(e.effectiveFrom()).isEqualTo(JULY);
            });
            assertThat(published.getAllValues().get(1)).isInstanceOfSatisfying(CreditLimitChanged.class, e -> {
                assertThat(e.previousCreditLimit()).isEqualByComparingTo("3000000.00");
                assertThat(e.creditLimit()).isEqualByComparingTo("5000000.00");
            });
        }

        @Test
        void aChangeOtherThanTheLimitNeedsNoSecondFactorAndPublishesNoLimitEvent() {
            Relationship current = found(active(DISTRIBUTOR, SOCIETY, APRIL, null));
            AmendRelationshipTerms command = new AmendRelationshipTerms(
                    current.getId(), JULY, null, null, 45, null, null, null, "TERMS_REVIEW", null);

            amend.handle(command, own(DISTRIBUTOR));

            verify(permissions, never()).allows(any(), anyString());
            verify(audit, never()).record(eq("CREDIT_LIMIT_CHANGED"), any(), any(), any(), any(), any());
            verify(events).publish(any(RelationshipAmended.class));
        }

        @Test
        void theSameLimitWithAnotherScaleIsNoLimitChange() {
            Relationship current = found(active(DISTRIBUTOR, SOCIETY, APRIL, null));
            AmendRelationshipTerms command = new AmendRelationshipTerms(
                    current.getId(), JULY, null, new BigDecimal("3000000"), 45, null, null, null, "TERMS_REVIEW", null);

            amend.handle(command, own(DISTRIBUTOR)); // no MFA in this scope, and none is asked for

            verify(permissions, never()).allows(any(), anyString());
        }

        @Test
        void aDraftOrASuspendedRowIsNotAmended() {
            Relationship draft = draft(DISTRIBUTOR, SOCIETY);
            refused(
                    () -> amend.handle(limitTo(draft, "5000000", JULY, "X"), withFreshMfa(DISTRIBUTOR)),
                    "m1.relationship.not_active");
        }

        @Test
        void theBuyerCannotAmend() {
            Relationship current = found(active(DISTRIBUTOR, SOCIETY, APRIL, null));
            refused(
                    () -> amend.handle(limitTo(current, "5000000", JULY, "X"), withFreshMfa(SOCIETY)),
                    "m1.relationship.not_seller");
        }

        @Test
        void theNewTermsStartAfterTheCurrentOnes() {
            Relationship current = found(active(DISTRIBUTOR, SOCIETY, APRIL, null));
            refused(
                    () -> amend.handle(limitTo(current, "5000000", APRIL, "X"), withFreshMfa(DISTRIBUTOR)),
                    "m1.relationship.effective_from_not_after");
        }

        @Test
        void theNewTermsStartWhileTheCurrentOnesRun() {
            Relationship current = found(active(DISTRIBUTOR, SOCIETY, APRIL, LocalDate.of(2026, 6, 30)));
            refused(
                    () -> amend.handle(limitTo(current, "5000000", JULY, "X"), withFreshMfa(DISTRIBUTOR)),
                    "m1.relationship.effective_from_after_end");
        }

        @Test
        void aLimitChangeWithoutTheExtraPermissionIsRefused() {
            Relationship current = found(active(DISTRIBUTOR, SOCIETY, APRIL, null));
            when(permissions.allows(any(), eq("bil.creditlimit.change"))).thenReturn(false);
            refused(
                    () -> amend.handle(limitTo(current, "5000000", JULY, "X"), withFreshMfa(DISTRIBUTOR)),
                    "m1.relationship.credit_limit_permission_required");
        }

        @Test
        void aLimitChangeWithAStaleSecondFactorIsRefused() {
            Relationship current = found(active(DISTRIBUTOR, SOCIETY, APRIL, null));
            ScopeContext stale = scope(DISTRIBUTOR, null, PolicyClass.OWN, NOW.minus(Duration.ofMinutes(11)));
            refused(() -> amend.handle(limitTo(current, "5000000", JULY, "X"), stale), "mfa.required");
            refused(() -> amend.handle(limitTo(current, "5000000", JULY, "X"), own(DISTRIBUTOR)), "mfa.required");
            assertThat(current.effectiveTo()).isNull();
        }

        @Test
        void aReasonIsRequired() {
            Relationship current = found(active(DISTRIBUTOR, SOCIETY, APRIL, null));
            refused(
                    () -> amend.handle(limitTo(current, "5000000", JULY, " "), withFreshMfa(DISTRIBUTOR)),
                    "m1.relationship.reason_required");
        }

        @Test
        void anAmendmentThatChangesNothingIsRefused() {
            Relationship current = found(active(DISTRIBUTOR, SOCIETY, APRIL, null));
            AmendRelationshipTerms command = new AmendRelationshipTerms(
                    current.getId(), JULY, PRICE_LIST, null, 30, 7, 24, "FCFS", "NOTHING", null);
            refused(() -> amend.handle(command, own(DISTRIBUTOR)), "m1.relationship.terms_unchanged");
        }

        @Test
        void anotherActiveRowInTheNewRangeIsRefused() {
            Relationship current = found(active(DISTRIBUTOR, SOCIETY, APRIL, null));
            when(repository.activeOverlapping(any(), any(), eq(JULY), any(), eq(current.getId())))
                    .thenReturn(List.of(active(DISTRIBUTOR, SOCIETY, LocalDate.of(2026, 8, 1), null)));
            refused(
                    () -> amend.handle(limitTo(current, "5000000", JULY, "X"), withFreshMfa(DISTRIBUTOR)),
                    "m1.relationship.overlap");
            assertThat(current.effectiveTo()).as("nothing was closed").isNull();
        }

        @Test
        void aRowALaterRowHasReplacedIsNotAmended() {
            // R1 [Apr, 30 Sep] and R2 [Oct, open] after an amendment: amending R1 would give the
            // change R1's closing date, so it would stop when R2 starts (the review of M1-04).
            Relationship r1 = found(active(DISTRIBUTOR, SOCIETY, APRIL, LocalDate.of(2026, 9, 30)));
            Relationship r2 = active(DISTRIBUTOR, SOCIETY, LocalDate.of(2026, 10, 1), null);
            when(repository.activeStartingAfter(DISTRIBUTOR, SOCIETY, APRIL)).thenReturn(List.of(r2));

            assertThatThrownBy(() -> amend.handle(limitTo(r1, "5000000", JULY, "X"), withFreshMfa(DISTRIBUTOR)))
                    .isInstanceOfSatisfying(ProblemException.class, e -> {
                        assertThat(e.messageId()).isEqualTo("m1.relationship.not_latest");
                        assertThat(e.parameters()).containsEntry("latestRelationshipId", r2.getId());
                    });
            nothingWritten();
            assertThat(r1.effectiveTo()).isEqualTo(LocalDate.of(2026, 9, 30));
        }

        @Test
        void aNewPriceListPassesTheM3CheckAndARefusedOneStopsTheAmendment() {
            Relationship current = found(active(DISTRIBUTOR, SOCIETY, APRIL, null));
            UUID otherList = UUID.randomUUID();
            when(priceLists.refusal(eq(otherList), eq(DISTRIBUTOR), any()))
                    .thenReturn(Optional.of("prc.price_list.not_published"));
            AmendRelationshipTerms toOtherList = new AmendRelationshipTerms(
                    current.getId(), JULY, otherList, null, null, null, null, null, "NEW_LIST", null);

            assertThatThrownBy(() -> amend.handle(toOtherList, own(DISTRIBUTOR)))
                    .isInstanceOfSatisfying(ProblemException.class, e -> {
                        assertThat(e.messageId()).isEqualTo("m1.relationship.price_list_refused");
                        assertThat(e.parameters())
                                .containsEntry("priceListId", otherList)
                                .containsEntry("reason", "prc.price_list.not_published");
                    });
            nothingWritten();

            // The same list as today is not checked again; an accepted new list goes through.
            amend.handle(
                    new AmendRelationshipTerms(
                            current.getId(), JULY, PRICE_LIST, null, 45, null, null, null, "TERMS", null),
                    own(DISTRIBUTOR));
            verify(priceLists, never()).refusal(eq(PRICE_LIST), any(), any());
            UUID acceptedList = UUID.randomUUID();
            Relationship again = found(active(DISTRIBUTOR, SOCIETY, APRIL, null));
            amend.handle(
                    new AmendRelationshipTerms(
                            again.getId(), JULY, acceptedList, null, null, null, null, null, "NEW_LIST", null),
                    own(DISTRIBUTOR));
            verify(priceLists).refusal(eq(acceptedList), eq(DISTRIBUTOR), any());
        }

        @Test
        void withoutAPermissionResolverTheLimitPermissionIsNotYetChecked() {
            // K-03b is not on main: no resolver bean exists, and the check is skipped rather than
            // refusing every limit change. The second factor is still required.
            AmendRelationshipTermsHandler withoutResolver = new AmendRelationshipTermsHandler(
                    repository, priceLists, Optional.empty(), config, clock, audit, events);
            Relationship current = found(active(DISTRIBUTOR, SOCIETY, APRIL, null));

            withoutResolver.handle(limitTo(current, "5000000", JULY, "X"), withFreshMfa(DISTRIBUTOR));

            verify(events).publish(any(CreditLimitChanged.class));
        }
    }

    // ---- The closure arithmetic at month ends (21A section 9) ---------------------------------

    @Test
    void theOldRowEndsOnTheLastDayOfTheMonthBeforeInLeapAndCommonYears() {
        assertThat(closedBefore(LocalDate.of(2026, 3, 1))).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(closedBefore(LocalDate.of(2028, 3, 1))).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(closedBefore(LocalDate.of(2027, 1, 1))).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(closedBefore(LocalDate.of(2026, 5, 1))).isEqualTo(LocalDate.of(2026, 4, 30));
    }

    private static LocalDate closedBefore(LocalDate nextFrom) {
        Relationship r = active(DISTRIBUTOR, SOCIETY, LocalDate.of(2025, 1, 1), null);
        r.closeBefore(nextFrom);
        return r.effectiveTo();
    }

    @Test
    void theTermsHashIgnoresTheScaleOfTheLimitAndSeesEveryTerm() {
        Relationship.Terms a = terms(PRICE_LIST, new BigDecimal("5000000"), (short) 30);
        Relationship.Terms b = terms(PRICE_LIST, new BigDecimal("5000000.00"), (short) 30);
        Relationship.Terms c = terms(PRICE_LIST, new BigDecimal("5000000.00"), (short) 45);
        assertThat(a.hash()).isEqualTo(b.hash()).hasSize(64);
        assertThat(c.hash()).isNotEqualTo(a.hash());
    }

    // ---- SuspendRelationship ------------------------------------------------------------------

    @Nested
    class Suspend {

        @Test
        void theSellerSuspendsAnActiveRelationshipWithAReason() {
            Relationship r = found(active(DISTRIBUTOR, SOCIETY, APRIL, null));

            suspend.handle(new SuspendRelationship(r.getId(), "OVERDUE", "three invoices overdue"), own(DISTRIBUTOR));

            assertThat(r.status()).isEqualTo("SUSPENDED");
            verify(audit)
                    .record(
                            eq("RELATIONSHIP_SUSPENDED"),
                            any(),
                            any(),
                            any(),
                            any(),
                            eq("OVERDUE: three invoices overdue"));
            verify(events).publish(any(RelationshipSuspended.class));
        }

        @Test
        void everyActiveRowOfThePairInForceTodayOrLaterIsSuspendedWithIt() {
            // R1 [Apr, 30 Sep] and R2 [Oct, open] on 25 Sep: suspending R1 alone would let
            // trading come back on 1 Oct (the review of M1-04); an ended row is history.
            Relationship r1 = found(active(DISTRIBUTOR, SOCIETY, APRIL, LocalDate.of(2026, 9, 30)));
            Relationship r2 = active(DISTRIBUTOR, SOCIETY, LocalDate.of(2026, 10, 1), null);
            when(repository.activeOnOrAfterForUpdate(DISTRIBUTOR, SOCIETY, LocalDate.of(2026, 9, 25)))
                    .thenReturn(List.of(r1, r2));

            suspend.handle(new SuspendRelationship(r1.getId(), "OVERDUE", null), own(DISTRIBUTOR));

            assertThat(r1.status()).isEqualTo("SUSPENDED");
            assertThat(r2.status()).isEqualTo("SUSPENDED");
            verify(repository, times(2)).saveAndFlush(any());
            verify(audit, times(2)).record(eq("RELATIONSHIP_SUSPENDED"), any(), any(), any(), any(), eq("OVERDUE"));
            ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
            verify(events, times(2)).publish(published.capture());
            assertThat(published.getAllValues())
                    .allSatisfy(event -> assertThat(event).isInstanceOf(RelationshipSuspended.class))
                    .extracting(event -> ((RelationshipSuspended) event).relationshipId())
                    .containsExactly(r1.getId(), r2.getId());
        }

        @Test
        void theBuyerCannotSuspend() {
            Relationship r = found(active(DISTRIBUTOR, SOCIETY, APRIL, null));
            refused(
                    () -> suspend.handle(new SuspendRelationship(r.getId(), "X", null), own(SOCIETY)),
                    "m1.relationship.not_seller");
        }

        @Test
        void onlyAnActiveRelationshipIsSuspended() {
            Relationship draft = draft(DISTRIBUTOR, SOCIETY);
            refused(
                    () -> suspend.handle(new SuspendRelationship(draft.getId(), "X", null), own(DISTRIBUTOR)),
                    "m1.relationship.not_active");
        }

        @Test
        void aReasonIsRequired() {
            Relationship r = found(active(DISTRIBUTOR, SOCIETY, APRIL, null));
            refused(
                    () -> suspend.handle(new SuspendRelationship(r.getId(), "", null), own(DISTRIBUTOR)),
                    "m1.relationship.reason_required");
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private void refused(Runnable call, String messageId) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                .isEqualTo(messageId));
        nothingPublished();
    }

    private void nothingWritten() {
        verify(repository, never()).saveAndFlush(any());
        nothingPublished();
    }

    private void nothingPublished() {
        verifyNoInteractions(audit, events);
    }

    private static OpenTradingRelationship openTo(UUID buyer, LocalDate from, LocalDate to) {
        return new OpenTradingRelationship(
                buyer, PRICE_LIST, new BigDecimal("3000000.00"), 30, null, null, null, from, to);
    }

    private static AmendRelationshipTerms limitTo(Relationship r, String limit, LocalDate from, String reason) {
        return new AmendRelationshipTerms(
                r.getId(), from, null, new BigDecimal(limit), null, null, null, null, reason, null);
    }

    private static Relationship.Terms terms(UUID priceList, BigDecimal limit, Short paymentDays) {
        return new Relationship.Terms(priceList, limit, paymentDays, (short) 7, (short) 24, "FCFS");
    }

    private Relationship draft(UUID seller, UUID buyer) {
        return found(Relationship.open(
                UUID.randomUUID(),
                seller,
                buyer,
                terms(PRICE_LIST, new BigDecimal("3000000.00"), (short) 30),
                APRIL,
                null));
    }

    private static Relationship active(UUID seller, UUID buyer, LocalDate from, LocalDate to) {
        Relationship r = Relationship.open(
                UUID.randomUUID(),
                seller,
                buyer,
                terms(PRICE_LIST, new BigDecimal("3000000.00"), (short) 30),
                from,
                to);
        r.activate();
        return r;
    }

    private Relationship found(Relationship r) {
        when(repository.findById(r.getId())).thenReturn(Optional.of(r));
        return r;
    }

    private static ScopeContext own(UUID entity) {
        return scope(entity, null, PolicyClass.OWN, null);
    }

    private static ScopeContext atShop(UUID entity) {
        return scope(entity, SHOP, PolicyClass.OWN, null);
    }

    private static ScopeContext withFreshMfa(UUID entity) {
        return scope(entity, null, PolicyClass.OWN, NOW.minus(Duration.ofMinutes(2)));
    }

    private static ScopeContext scope(UUID entity, UUID location, PolicyClass policyClass, Instant mfaAt) {
        Scope active = new Scope(entity, location);
        return new ScopeContext(
                USER, null, entity, List.of(active), active, policyClass, Set.of(), mfaAt, Locale.ENGLISH, null);
    }
}
