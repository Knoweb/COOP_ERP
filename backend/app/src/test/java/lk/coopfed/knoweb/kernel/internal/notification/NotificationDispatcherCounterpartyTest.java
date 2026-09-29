package lk.coopfed.knoweb.kernel.internal.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The counterparty of a trading event that names seller and buyer rather than a counterparty. */
class NotificationDispatcherCounterpartyTest {

    private static final UUID SELLER = UUID.fromString("0190c200-0000-7000-8000-000000000001");
    private static final UUID BUYER = UUID.fromString("0190c200-0000-7000-8000-000000000002");
    private static final UUID OTHER = UUID.fromString("0190c200-0000-7000-8000-000000000003");

    private final ObjectMapper json = new ObjectMapper();

    private ObjectNode parties() {
        ObjectNode body = json.createObjectNode();
        body.put("sellerEntityId", SELLER.toString());
        body.put("buyerEntityId", BUYER.toString());
        return body;
    }

    @Test
    void theOtherPartyOfTheOwnerIsTheCounterparty() {
        assertThat(NotificationDispatcher.otherParty(parties(), SELLER)).isEqualTo(BUYER);
        assertThat(NotificationDispatcher.otherParty(parties(), BUYER)).isEqualTo(SELLER);
    }

    @Test
    void anOwnerWhoIsNeitherPartyOrAPayloadWithoutPartiesHasNone() {
        assertThat(NotificationDispatcher.otherParty(parties(), OTHER)).isNull();
        assertThat(NotificationDispatcher.otherParty(json.createObjectNode(), SELLER))
                .isNull();
        assertThat(NotificationDispatcher.otherParty(parties(), null)).isNull();
    }
}
