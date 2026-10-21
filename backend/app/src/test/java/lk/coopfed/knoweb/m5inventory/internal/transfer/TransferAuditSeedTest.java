package lk.coopfed.knoweb.m5inventory.internal.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.core.io.ClassPathResource;

class TransferAuditSeedTest {

    @Test
    void transferDispatchedIsRegisteredAsAnOnlineInfoAuditEvent() {
        YamlMapFactoryBean yaml = new YamlMapFactoryBean();
        yaml.setResources(new ClassPathResource("seed/m5inventory/audit-event-types.yaml"));

        Map<String, Object> root = yaml.getObject();
        assertThat(root).isNotNull();
        assertThat(root.get("audit_event_types"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(Map.class))
                .filteredOn(event -> "TRANSFER_DISPATCHED".equals(event.get("code")))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.get("severity")).isEqualTo("INFO");
                    assertThat(event.get("offline_capturable")).isEqualTo(false);
                });
    }
}
