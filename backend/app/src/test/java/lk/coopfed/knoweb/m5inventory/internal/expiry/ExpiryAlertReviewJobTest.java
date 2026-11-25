package lk.coopfed.knoweb.m5inventory.internal.expiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lk.coopfed.knoweb.kernel.api.JobExecution;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.LocationFilter;
import lk.coopfed.knoweb.m1party.query.LocationPage;
import lk.coopfed.knoweb.m1party.query.LocationView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m5inventory.api.ExpiryAlertScanResult;
import org.junit.jupiter.api.Test;

class ExpiryAlertReviewJobTest {

    private static final UUID ENTITY = UUID.fromString("0190e67e-0000-7000-8000-000000000002");

    private final ExpiryTaskStore store = mock(ExpiryTaskStore.class);
    private final EvaluateExpiryAlertsHandler handler = mock(EvaluateExpiryAlertsHandler.class);
    private final PartyQueries parties = mock(PartyQueries.class);
    private final ExpiryAlertReviewJob job = new ExpiryAlertReviewJob(store, handler, parties);

    @Test
    void failureAtOneLocationDoesNotPreventTheNextLocationFromBeingProcessed() {
        UUID failingLocation = locationId(1);
        UUID successfulLocation = locationId(2);
        AtomicInteger processed = new AtomicInteger();
        JobExecution execution = execution(processed);

        when(store.findLocationsWithPositiveStock(any(ScopeContext.class)))
                .thenReturn(List.of(
                        new ExpiryTaskStore.LocationRef(ENTITY, failingLocation),
                        new ExpiryTaskStore.LocationRef(ENTITY, successfulLocation)));
        when(parties.listLocations(any(LocationFilter.class), any(ScopeContext.class)))
                .thenReturn(new LocationPage(
                        List.of(location(failingLocation, "SHOP"), location(successfulLocation, "WAREHOUSE")), null));
        when(handler.evaluateLocation(eq(failingLocation), any(ScopeContext.class)))
                .thenThrow(new IllegalStateException("isolated test failure"));
        when(handler.evaluateLocation(eq(successfulLocation), any(ScopeContext.class)))
                .thenReturn(new ExpiryAlertScanResult(1, 0, 7));
        assertThat(job.review(execution)).isEqualTo(1);
        assertThat(processed).hasValue(7);
        verify(handler).evaluateLocation(eq(failingLocation), any(ScopeContext.class));
        verify(handler).evaluateLocation(eq(successfulLocation), any(ScopeContext.class));
    }

    @Test
    void processesMoreThanOneLocationPage() {
        List<ExpiryTaskStore.LocationRef> refs = new ArrayList<>();
        List<LocationView> firstPage = new ArrayList<>();
        for (int index = 1; index <= 100; index++) {
            UUID locationId = locationId(index);
            refs.add(new ExpiryTaskStore.LocationRef(ENTITY, locationId));
            firstPage.add(location(locationId, "SHOP"));
        }
        UUID finalLocation = locationId(101);
        refs.add(new ExpiryTaskStore.LocationRef(ENTITY, finalLocation));
        LocationPage pageOne = new LocationPage(firstPage, locationId(100).toString());
        LocationPage pageTwo = new LocationPage(List.of(location(finalLocation, "WAREHOUSE")), null);
        AtomicInteger processed = new AtomicInteger();
        JobExecution execution = execution(processed);

        when(store.findLocationsWithPositiveStock(any(ScopeContext.class))).thenReturn(refs);
        when(parties.listLocations(any(LocationFilter.class), any(ScopeContext.class)))
                .thenAnswer(invocation -> {
                    LocationFilter filter = invocation.getArgument(0);
                    return filter.cursor() == null ? pageOne : pageTwo;
                });
        when(handler.evaluateLocation(any(UUID.class), any(ScopeContext.class)))
                .thenReturn(new ExpiryAlertScanResult(0, 0, 1));

        assertThat(job.review(execution)).isZero();
        assertThat(processed).hasValue(101);
        verify(handler, times(101)).evaluateLocation(any(UUID.class), any(ScopeContext.class));
        verify(parties, times(2)).listLocations(any(LocationFilter.class), any(ScopeContext.class));
    }

    private static LocationView location(UUID locationId, String type) {
        return new LocationView(
                locationId,
                ENTITY,
                "L" + locationId.toString().substring(30),
                type,
                "Test location",
                null,
                null,
                null,
                null,
                null,
                null,
                "en",
                List.of(),
                null,
                true,
                null,
                "ACTIVE");
    }

    private static UUID locationId(int sequence) {
        return new UUID(0x0190e67e00007000L, 0x8000000000000000L + sequence);
    }

    private static JobExecution execution(AtomicInteger processed) {
        return new JobExecution() {
            @Override
            public UUID runId() {
                return UUID.randomUUID();
            }

            @Override
            public void itemsProcessed(int count) {
                processed.set(count);
            }

            @Override
            public Optional<ScopeContext> systemScope() {
                return Optional.empty();
            }
        };
    }
}
