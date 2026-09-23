package lk.coopfed.knoweb.m1party.internal.queries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.EntityView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class PartyQueriesImplTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    private static final UUID HOME_ENTITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");

    private static final UUID TARGET_ENTITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000301");

    @Mock
    private JdbcTemplate jdbc;

    private PartyQueriesImpl queries;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        queries = new PartyQueriesImpl(jdbc);
    }

    @Test
    @SuppressWarnings("unchecked")
    void partyGetEntityUsesNamesOnlyDirectory() {
        EntityView partyView = new EntityView(
                TARGET_ENTITY_ID, null, null, "MPCS 301", null, null, null, null, null, null, null, null, null, null);

        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of(partyView));

        EntityView result =
                queries.getEntity(TARGET_ENTITY_ID, scope(PolicyClass.PARTY)).orElseThrow();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);

        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));

        assertThat(sql.getValue()).contains("party.entity_party_directory");

        assertThat(sql.getValue()).doesNotContain("vat_registration_no");

        assertThat(result.entityId()).isEqualTo(TARGET_ENTITY_ID);

        assertThat(result.legalNameEn()).isEqualTo("MPCS 301");

        assertThat(result.entityCode()).isNull();

        assertThat(result.entityType()).isNull();

        assertThat(result.registrationNo()).isNull();

        assertThat(result.vatRegistrationNo()).isNull();

        assertThat(result.district()).isNull();

        assertThat(result.responsibleOfficerUserId()).isNull();

        assertThat(result.dataGovernanceSignedOn()).isNull();

        assertThat(result.status()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void ownGetEntityUsesFullEntityProjection() {
        EntityView fullView = new EntityView(
                TARGET_ENTITY_ID,
                "M301",
                "MPCS",
                "MPCS 301",
                null,
                null,
                "REG-301",
                "VAT-301",
                "Colombo",
                1,
                "en",
                null,
                null,
                "ONBOARDING");

        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of(fullView));

        EntityView result =
                queries.getEntity(TARGET_ENTITY_ID, scope(PolicyClass.OWN)).orElseThrow();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);

        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));

        assertThat(sql.getValue()).contains("from party.entity");

        assertThat(sql.getValue()).contains("vat_registration_no");

        assertThat(result.entityCode()).isEqualTo("M301");

        assertThat(result.entityType()).isEqualTo("MPCS");

        assertThat(result.vatRegistrationNo()).isEqualTo("VAT-301");

        assertThat(result.status()).isEqualTo("ONBOARDING");
    }

    @Test
    void missingScopeReturnsEmptyWithoutDatabaseRead() {
        assertThat(queries.getEntity(TARGET_ENTITY_ID, null)).isEmpty();

        verify(jdbc, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    private ScopeContext scope(PolicyClass policyClass) {

        Scope activeScope = new Scope(HOME_ENTITY_ID, null);

        return new ScopeContext(
                USER_ID,
                null,
                HOME_ENTITY_ID,
                List.of(activeScope),
                activeScope,
                policyClass,
                Set.of(),
                null,
                Locale.ENGLISH,
                UUID.fromString("00000000-0000-0000-0000-000000000501"));
    }
}
