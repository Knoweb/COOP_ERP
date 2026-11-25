package lk.coopfed.knoweb.m1party.internal.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.IdentityProviderClient;
import lk.coopfed.knoweb.kernel.api.PinHasher;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.api.BulkImportUsers;
import lk.coopfed.knoweb.m1party.api.BulkUserReport;
import lk.coopfed.knoweb.m1party.api.UsersBulkImported;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

class BulkUserImporterTest {

    private AppUserRepository users;
    private IdentityProviderClient provider;
    private PinHasher pinHasher;
    private JdbcTemplate jdbc;
    private AuditFacade audit;
    private EventPublisher events;
    private Clock clock;
    private BulkUserImporter importer;

    private static final UUID ENTITY_ID = UUID.fromString("0190e000-0000-7000-8000-000000000001");
    private static final ScopeContext SCOPE = ScopeContext.dev(UUID.randomUUID(), ENTITY_ID, null);

    @BeforeEach
    void setUp() {
        users = mock(AppUserRepository.class);
        provider = mock(IdentityProviderClient.class);
        pinHasher = mock(PinHasher.class);
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);
        clock = Clock.fixed(Instant.parse("2026-10-26T10:00:00Z"), ZoneId.of("UTC"));

        when(pinHasher.hash(anyString())).thenReturn("$argon2id$mockhash");
        when(provider.createUser(any(), any(), any(), any(), any())).thenReturn("kc-sub-123");

        importer = new BulkUserImporter(users, provider, pinHasher, jdbc, audit, events, clock);
    }

    @Test
    void requiresEntityScope() {
        ScopeContext shopScope = ScopeContext.dev(UUID.randomUUID(), ENTITY_ID, UUID.randomUUID());
        BulkImportUsers cmd = new BulkImportUsers(
                null, List.of(new BulkImportUsers.UserRow("user1", "User 1", "en", "TILL", "1234", null, null)));

        assertThatThrownBy(() -> importer.handle(cmd, shopScope))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m1.user.entity_scope_required");
    }

    @Test
    void returnsEmptyReportWhenNoUsersGiven() {
        BulkImportUsers cmd = new BulkImportUsers("", List.of());
        BulkUserReport report = importer.handle(cmd, SCOPE);

        assertThat(report.status()).isEqualTo(BulkUserReport.EMPTY);
        assertThat(report.imported()).isZero();
        assertThat(report.problems()).hasSize(1);
    }

    @Test
    void rejectsInvalidAndDuplicateUsernamesAndWeakPins() {
        BulkImportUsers cmd = new BulkImportUsers(
                null,
                List.of(
                        new BulkImportUsers.UserRow("@invalid!", "User 1", "en", "BACK_OFFICE", null, null, null),
                        new BulkImportUsers.UserRow("duplicate.user", "User 2", "en", "BACK_OFFICE", null, null, null),
                        new BulkImportUsers.UserRow(
                                "duplicate.user", "User 2 dup", "en", "BACK_OFFICE", null, null, null),
                        new BulkImportUsers.UserRow("bad.pin", "User 3", "en", "TILL", "12", null, null)));

        BulkUserReport report = importer.handle(cmd, SCOPE);

        assertThat(report.status()).isEqualTo(BulkUserReport.REJECTED);
        assertThat(report.problems())
                .extracting(BulkUserReport.Problem::reason)
                .contains("bulk.user.username_invalid", "bulk.user.username_duplicate", "bulk.user.pin_invalid");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsUsernamesAlreadyExistingInDatabase() {
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of("existing.user"));

        BulkImportUsers cmd = new BulkImportUsers(
                null,
                List.of(new BulkImportUsers.UserRow(
                        "existing.user", "Existing User", "en", "BACK_OFFICE", null, null, null)));

        BulkUserReport report = importer.handle(cmd, SCOPE);

        assertThat(report.status()).isEqualTo(BulkUserReport.REJECTED);
        assertThat(report.problems())
                .extracting(BulkUserReport.Problem::reason)
                .containsExactly("bulk.user.username_duplicate");
    }

    @Test
    void importsUsersProvisionsKeycloakAndEmitsAuditAndEvents() {
        String csv =
                """
                username,display_name,language,user_kind,pin,password
                colombo.officer,Colombo Officer,en,BACK_OFFICE,,Secret123!
                colombo.cashier,Colombo Cashier,si,BOTH,1234,
                """;

        BulkImportUsers cmd = BulkImportUsers.ofSheet(csv);
        BulkUserReport report = importer.handle(cmd, SCOPE);

        assertThat(report.status()).isEqualTo(BulkUserReport.IMPORTED);
        assertThat(report.imported()).isEqualTo(2);
        assertThat(report.failed()).isZero();

        // Keycloak provisioning verified
        verify(provider).createUser(eq(SCOPE), any(), eq(ENTITY_ID), eq("colombo.officer"), any());
        verify(provider).setPassword(eq(SCOPE), eq("kc-sub-123"), eq("Secret123!"), eq(false));
        verify(provider).createUser(eq(SCOPE), any(), eq(ENTITY_ID), eq("colombo.cashier"), any());
        verify(provider).setTemporaryPassword(eq(SCOPE), eq("kc-sub-123"));

        // Audit & events verified
        verify(audit).record(eq(BulkUserImporter.AUDIT_USERS_BULK_IMPORTED), any(), any(), any(), eq(SCOPE));
        verify(events).publish(any(UsersBulkImported.class));
    }
}
