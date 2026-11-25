package lk.coopfed.knoweb.m5inventory.internal.opening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m5inventory.api.OpeningBalanceChanged;
import lk.coopfed.knoweb.m5inventory.api.RejectOpeningBalance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RejectOpeningBalanceHandlerTest {

    private static final UUID ENTITY = UUID.fromString("0190e679-0000-7000-8000-000000000001");
    private static final UUID USER = UUID.fromString("0190e679-0000-7000-8000-000000000002");
    private static final UUID LOCATION = UUID.fromString("0190e679-0000-7000-8000-000000000003");
    private static final UUID OPB_ID = UUID.fromString("0190e679-0000-7000-8000-000000000004");

    private OpeningBalanceStore store;
    private JdbcTemplate jdbc;
    private AuditFacade audit;
    private EventPublisher events;
    private Clock clock;
    private RejectOpeningBalanceHandler handler;

    private final ScopeContext scope = ScopeContext.dev(USER, ENTITY, null);

    @BeforeEach
    void setUp() {
        store = mock(OpeningBalanceStore.class);
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);
        clock = Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), ZoneOffset.UTC);

        handler = new RejectOpeningBalanceHandler(store, jdbc, audit, events, clock);
    }

    @Test
    @DisplayName("Guards: user required in scope")
    void requiresUser() {
        Scope sc = new Scope(ENTITY, null);
        ScopeContext noUser = new ScopeContext(
                null, null, ENTITY, List.of(sc), sc, PolicyClass.OWN, Set.of(), null, java.util.Locale.ENGLISH, null);

        assertThatThrownBy(() -> handler.handle(new RejectOpeningBalance(OPB_ID, "Count discrepancy detected"), noUser))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m5.opening.user_required"));
    }

    @Test
    @DisplayName("Guards: rejection reason is mandatory and minimum length 5")
    void requiresRejectionReason() {
        assertThatThrownBy(() -> handler.handle(new RejectOpeningBalance(OPB_ID, null), scope))
                .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                        .isEqualTo("m5.opening.rejection_reason_required"));

        assertThatThrownBy(() -> handler.handle(new RejectOpeningBalance(OPB_ID, "bad"), scope))
                .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                        .isEqualTo("m5.opening.rejection_reason_required"));
    }

    @Test
    @DisplayName("Guards: balance exists")
    void openingBalanceNotFound() {
        when(store.lock(OPB_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(new RejectOpeningBalance(OPB_ID, "Discrepancy in batch count"), scope))
                .isInstanceOfSatisfying(
                        ProblemException.class, e -> assertThat(e.messageId()).isEqualTo("m5.opening.not_found"));
    }

    @Test
    @DisplayName("Guards: cannot reject POSTED opening balance")
    void cannotRejectPosted() {
        OpeningBalanceStore.Header header =
                new OpeningBalanceStore.Header(OPB_ID, ENTITY, LOCATION, "POSTED", USER, null);
        when(store.lock(OPB_ID)).thenReturn(Optional.of(header));

        assertThatThrownBy(() -> handler.handle(new RejectOpeningBalance(OPB_ID, "Discrepancy in batch count"), scope))
                .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                        .isEqualTo("m5.opening.cannot_reject_posted"));
    }

    @Test
    @DisplayName("Guards: cannot reject ALREADY REJECTED opening balance")
    void cannotRejectAlreadyRejected() {
        OpeningBalanceStore.Header header =
                new OpeningBalanceStore.Header(OPB_ID, ENTITY, LOCATION, "REJECTED", USER, null);
        when(store.lock(OPB_ID)).thenReturn(Optional.of(header));

        assertThatThrownBy(() -> handler.handle(new RejectOpeningBalance(OPB_ID, "Discrepancy in batch count"), scope))
                .isInstanceOfSatisfying(ProblemException.class, e -> assertThat(e.messageId())
                        .isEqualTo("m5.opening.already_rejected"));
    }

    @Test
    @DisplayName("Successfully rejects DRAFT balance: updates status, records audit, publishes event")
    void rejectsDraftBalance() {
        OpeningBalanceStore.Header header =
                new OpeningBalanceStore.Header(OPB_ID, ENTITY, LOCATION, "DRAFT", USER, null);
        when(store.lock(OPB_ID)).thenReturn(Optional.of(header));
        when(jdbc.update(contains("update inventory.opening_balance"), any(), any(), any(), eq(OPB_ID)))
                .thenReturn(1);

        UUID result = handler.handle(new RejectOpeningBalance(OPB_ID, "Discrepancy in physical count"), scope);
        assertThat(result).isEqualTo(OPB_ID);

        verify(jdbc)
                .update(
                        contains("update inventory.opening_balance"),
                        eq(USER),
                        any(),
                        eq("Discrepancy in physical count"),
                        eq(OPB_ID));
        verify(audit)
                .record(
                        eq(RejectOpeningBalanceHandler.AUDIT_REJECTED),
                        eq(Subject.of("opening_balance", OPB_ID)),
                        any(),
                        any(),
                        eq(scope));
        verify(events).publish(new OpeningBalanceChanged(OPB_ID, ENTITY, LOCATION, "REJECTED"));
    }

    @Test
    @DisplayName("Successfully rejects SIGNED_ENTITY balance")
    void rejectsSignedBalance() {
        OpeningBalanceStore.Header header =
                new OpeningBalanceStore.Header(OPB_ID, ENTITY, LOCATION, "SIGNED_ENTITY", USER, USER);
        when(store.lock(OPB_ID)).thenReturn(Optional.of(header));
        when(jdbc.update(contains("update inventory.opening_balance"), any(), any(), any(), eq(OPB_ID)))
                .thenReturn(1);

        UUID result = handler.handle(new RejectOpeningBalance(OPB_ID, "Discrepancy in physical count"), scope);
        assertThat(result).isEqualTo(OPB_ID);

        verify(events).publish(new OpeningBalanceChanged(OPB_ID, ENTITY, LOCATION, "REJECTED"));
    }

    @Test
    @DisplayName("Security: Handler requires inv.opening.sign permission with MFA")
    void handlerCarriesProperSecurityAnnotation() {
        var annotation =
                RejectOpeningBalanceHandler.class.getAnnotation(lk.coopfed.knoweb.kernel.api.CommandHandler.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.permission()).isEqualTo("inv.opening.sign");
        assertThat(annotation.requiresMfa()).isTrue();
    }
}
