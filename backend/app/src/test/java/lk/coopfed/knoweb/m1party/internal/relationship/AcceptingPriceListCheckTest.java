package lk.coopfed.knoweb.m1party.internal.relationship;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.junit.jupiter.api.Test;

/** The stub of 21A section 11 accepts the seeded lists and nothing else (the review of M1-04). */
class AcceptingPriceListCheckTest {

    private static final UUID SEEDED = UUID.fromString("0190f000-0000-7000-8000-000000000501");
    private static final UUID SELLER = UUID.randomUUID();

    private final AcceptingPriceListCheck stub = new AcceptingPriceListCheck(List.of(SEEDED));

    @Test
    void aSeededListIsAccepted() {
        assertThat(stub.refusal(SEEDED, SELLER, ScopeContext.dev(UUID.randomUUID(), SELLER, null)))
                .isEmpty();
    }

    @Test
    void anyOtherListIsRefusedWithTheStubsOwnReason() {
        assertThat(stub.refusal(UUID.randomUUID(), SELLER, ScopeContext.dev(UUID.randomUUID(), SELLER, null)))
                .contains("m1.relationship.price_list_unknown");
        assertThat(stub.refusal(null, SELLER, null)).contains("m1.relationship.price_list_unknown");
    }
}
