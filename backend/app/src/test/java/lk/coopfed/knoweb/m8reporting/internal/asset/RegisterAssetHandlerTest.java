package lk.coopfed.knoweb.m8reporting.internal.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import lk.coopfed.knoweb.m1party.query.LocationView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m8reporting.api.AssetRegistered;
import lk.coopfed.knoweb.m8reporting.api.RegisterAsset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RegisterAssetHandlerTest {

    private static final UUID ENTITY_ID = UUID.fromString("0190e888-0000-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("0190e888-0000-7000-8000-000000000010");
    private static final UUID LOCATION_ID = UUID.fromString("0190e888-0000-7000-8000-000000000020");
    private static final UUID OTHER_LOCATION_ID = UUID.fromString("0190e888-0000-7000-8000-000000000030");

    private JdbcTemplate jdbc;
    private AuditFacade audit;
    private EventPublisher events;
    private PartyQueries party;
    private Clock clock;
    private RegisterAssetHandler handler;
    private ScopeContext entityWideScope;
    private ScopeContext locationScoped;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);
        party = mock(PartyQueries.class);
        clock = Clock.fixed(Instant.parse("2026-10-28T08:00:00Z"), ZoneId.of("Asia/Colombo"));

        handler = new RegisterAssetHandler(jdbc, audit, events, party, clock);

        Scope entityScope = new Scope(ENTITY_ID, null);
        entityWideScope = new ScopeContext(
                USER_ID,
                null,
                ENTITY_ID,
                List.of(entityScope),
                entityScope,
                PolicyClass.OWN,
                Set.of(),
                null,
                java.util.Locale.ENGLISH,
                null);

        Scope locScope = new Scope(ENTITY_ID, LOCATION_ID);
        locationScoped = new ScopeContext(
                USER_ID,
                null,
                ENTITY_ID,
                List.of(locScope),
                locScope,
                PolicyClass.OWN,
                Set.of(),
                null,
                java.util.Locale.ENGLISH,
                null);

        when(party.getLocation(eq(LOCATION_ID), any())).thenReturn(Optional.of(location(LOCATION_ID, ENTITY_ID)));
    }

    private static LocationView location(UUID id, UUID owner) {
        return new LocationView(
                id,
                owner,
                "LOC-01",
                "SHOP",
                "Polgolla Shop",
                null,
                null,
                null,
                null,
                null,
                null,
                "en",
                List.of(),
                null,
                false,
                null,
                "ACTIVE");
    }

    @Test
    void rejectsCallerWithoutEntityScope() {
        ScopeContext fedScope = new ScopeContext(
                USER_ID,
                null,
                null,
                List.of(),
                null,
                PolicyClass.FEDERATION_VIEW,
                Set.of(),
                null,
                java.util.Locale.ENGLISH,
                null);

        RegisterAsset cmd = new RegisterAsset(
                "AST-01",
                "Main Land",
                "Property deed",
                "LAND",
                null,
                LocalDate.of(2024, 1, 1),
                new BigDecimal("1000000.00"));

        assertThatThrownBy(() -> handler.handle(cmd, fedScope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m8.asset.own_required");

        verifyNoInteractions(jdbc, audit, events);
    }

    @Test
    void rejectsInvalidAssetType() {
        RegisterAsset cmd = new RegisterAsset("AST-01", "Main Land", "Property deed", "INVALID_TYPE", null, null, null);

        assertThatThrownBy(() -> handler.handle(cmd, entityWideScope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m8.asset.type_invalid");

        verifyNoInteractions(jdbc, audit, events);
    }

    @Test
    void rejectsBlankAssetCode() {
        RegisterAsset cmd = new RegisterAsset("  ", "Van", "Delivery van", "VEHICLE", null, null, null);

        assertThatThrownBy(() -> handler.handle(cmd, entityWideScope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m8.asset.code_required");

        verifyNoInteractions(jdbc, audit, events);
    }

    @Test
    void rejectsBlankName() {
        RegisterAsset cmd = new RegisterAsset("AST-01", "   ", "Delivery van", "VEHICLE", null, null, null);

        assertThatThrownBy(() -> handler.handle(cmd, entityWideScope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m8.asset.name_required");

        verifyNoInteractions(jdbc, audit, events);
    }

    @Test
    void rejectsLocationCallerWithoutLocation() {
        RegisterAsset cmd = new RegisterAsset("AST-01", "Office Chair", "Chairs", "FURNITURE", null, null, null);

        assertThatThrownBy(() -> handler.handle(cmd, locationScoped))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m8.asset.location_scope_mismatch");

        verifyNoInteractions(jdbc, audit, events);
    }

    @Test
    void rejectsLocationCallerWithDifferentLocation() {
        RegisterAsset cmd =
                new RegisterAsset("AST-01", "Office Chair", "Chairs", "FURNITURE", OTHER_LOCATION_ID, null, null);

        assertThatThrownBy(() -> handler.handle(cmd, locationScoped))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m8.asset.location_scope_mismatch");

        verifyNoInteractions(party, jdbc, audit, events);
    }

    @Test
    void rejectsLocationCallerWithOwnLocationWhenUnknown() {
        when(party.getLocation(eq(LOCATION_ID), any())).thenReturn(Optional.empty());

        RegisterAsset cmd = new RegisterAsset("AST-01", "Office Chair", "Chairs", "FURNITURE", LOCATION_ID, null, null);

        assertThatThrownBy(() -> handler.handle(cmd, locationScoped))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m8.asset.location_unknown");

        verifyNoInteractions(jdbc, audit, events);
    }

    @Test
    void rejectsUnknownLocation() {
        when(party.getLocation(eq(LOCATION_ID), any())).thenReturn(Optional.empty());

        RegisterAsset cmd = new RegisterAsset("AST-01", "Office Chair", "Chairs", "FURNITURE", LOCATION_ID, null, null);

        assertThatThrownBy(() -> handler.handle(cmd, entityWideScope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m8.asset.location_unknown");

        verifyNoInteractions(jdbc, audit, events);
    }

    @Test
    void rejectsLocationBelongingToAnotherEntity() {
        UUID otherEntity = UUID.randomUUID();
        when(party.getLocation(eq(LOCATION_ID), any())).thenReturn(Optional.of(location(LOCATION_ID, otherEntity)));

        RegisterAsset cmd = new RegisterAsset("AST-01", "Office Chair", "Chairs", "FURNITURE", LOCATION_ID, null, null);

        assertThatThrownBy(() -> handler.handle(cmd, entityWideScope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m8.asset.location_scope_mismatch");

        verifyNoInteractions(jdbc, audit, events);
    }

    @Test
    void rejectsDuplicateAssetCode() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(ENTITY_ID), eq("AST-01")))
                .thenReturn(1);

        RegisterAsset cmd = new RegisterAsset("AST-01", "Office Chair", "Chairs", "FURNITURE", null, null, null);

        assertThatThrownBy(() -> handler.handle(cmd, entityWideScope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m8.asset.duplicate_code");

        verifyNoInteractions(audit, events);
    }

    @Test
    void registersAssetSuccessfullyEntityWide() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(ENTITY_ID), eq("AST-LAND-01")))
                .thenReturn(0);

        RegisterAsset cmd = new RegisterAsset(
                "AST-LAND-01",
                "Polgolla Main Land",
                "Commercial plot deed #441",
                "LAND",
                null,
                LocalDate.of(2024, 5, 20),
                new BigDecimal("25000000.00"));

        UUID assetId = handler.handle(cmd, entityWideScope);

        assertThat(assetId).isNotNull();
        verify(jdbc)
                .update(
                        anyString(),
                        eq(assetId),
                        eq(ENTITY_ID),
                        eq("AST-LAND-01"),
                        eq("Polgolla Main Land"),
                        eq("Commercial plot deed #441"),
                        eq("LAND"),
                        isNull(),
                        any(java.sql.Date.class),
                        eq(new BigDecimal("25000000.00")),
                        any(java.sql.Timestamp.class),
                        eq(USER_ID),
                        any(java.sql.Timestamp.class),
                        eq(USER_ID));

        verify(audit)
                .record(eq("ASSET_REGISTERED"), eq(Subject.of("asset", assetId)), isNull(), any(), eq(entityWideScope));
        verify(events).publish(any(AssetRegistered.class));
    }

    @Test
    void registersAssetSuccessfullyWithLocation() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(ENTITY_ID), eq("AST-POS-01")))
                .thenReturn(0);

        RegisterAsset cmd = new RegisterAsset(
                "AST-POS-01",
                "POS Terminal A",
                "Touchscreen register",
                "EQUIPMENT",
                LOCATION_ID,
                LocalDate.of(2025, 2, 1),
                new BigDecimal("150000.00"));

        UUID assetId = handler.handle(cmd, locationScoped);

        assertThat(assetId).isNotNull();
        verify(jdbc)
                .update(
                        anyString(),
                        eq(assetId),
                        eq(ENTITY_ID),
                        eq("AST-POS-01"),
                        eq("POS Terminal A"),
                        eq("Touchscreen register"),
                        eq("EQUIPMENT"),
                        eq(LOCATION_ID),
                        any(java.sql.Date.class),
                        eq(new BigDecimal("150000.00")),
                        any(java.sql.Timestamp.class),
                        eq(USER_ID),
                        any(java.sql.Timestamp.class),
                        eq(USER_ID));

        verify(audit)
                .record(eq("ASSET_REGISTERED"), eq(Subject.of("asset", assetId)), isNull(), any(), eq(locationScoped));
        verify(events).publish(any(AssetRegistered.class));
    }
}
