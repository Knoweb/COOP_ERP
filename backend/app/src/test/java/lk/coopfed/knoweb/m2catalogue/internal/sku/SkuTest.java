package lk.coopfed.knoweb.m2catalogue.internal.sku;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.m2catalogue.api.SkuDetails;
import org.junit.jupiter.api.Test;

class SkuTest {

    @Test
    void createStartsAsDraft() {
        Sku sku = Sku.create(
                UUID.randomUUID(),
                "SKU-23456789",
                UUID.randomUUID(),
                details("Milk", "කිරි", "பால்", "EA", false, false, false));

        assertThat(sku.status()).isEqualTo("DRAFT");
    }

    @Test
    void localActivationIsDraftOnly() {
        Sku sku = sku(details("Milk", null, null, "EA", false, false, false));

        sku.activateLocal();

        assertThat(sku.status()).isEqualTo("LOCAL");

        assertThatThrownBy(sku::activateLocal).isInstanceOf(ProblemException.class);
    }

    @Test
    void sharedActivationRequiresAllThreeNames() {
        Sku sku = sku(details("Milk", null, null, "EA", false, false, false));

        assertThatThrownBy(sku::activateShared).isInstanceOf(ProblemException.class);
    }

    @Test
    void deactivateAndReactivateRestorePriorClass() {
        Sku sku = sku(details("Milk", "කිරි", "பால்", "EA", false, false, false));

        sku.activateShared();
        sku.deactivate();

        assertThat(sku.status()).isEqualTo("INACTIVE");
        assertThat(sku.priorStatus()).isEqualTo("SHARED");

        sku.reactivate();

        assertThat(sku.status()).isEqualTo("SHARED");
        assertThat(sku.priorStatus()).isNull();
    }

    @Test
    void weightRequiresKilograms() {
        assertThatThrownBy(() -> sku(details("Rice", null, null, "EA", true, false, false)))
                .isInstanceOf(ProblemException.class);
    }

    @Test
    void expiryRequiresBatchTracking() {
        assertThatThrownBy(() -> sku(details("Milk", null, null, "EA", false, false, true)))
                .isInstanceOf(ProblemException.class);
    }

    private static Sku sku(SkuDetails details) {
        return Sku.create(UUID.randomUUID(), "SKU-23456789", UUID.randomUUID(), details);
    }

    private static SkuDetails details(
            String en, String si, String ta, String uom, boolean weight, boolean batch, boolean expiry) {

        return new SkuDetails(
                en,
                si,
                ta,
                null,
                null,
                null,
                uom,
                weight,
                batch,
                expiry,
                true,
                null,
                UUID.randomUUID(),
                "AUTO_LOWEST",
                "PURCHASED",
                Map.of());
    }
}
