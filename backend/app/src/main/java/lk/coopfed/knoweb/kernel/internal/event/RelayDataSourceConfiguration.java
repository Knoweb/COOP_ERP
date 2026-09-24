package lk.coopfed.knoweb.kernel.internal.event;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("worker")
public class RelayDataSourceConfiguration {

    @Bean(name = "relayDataSource", destroyMethod = "close", defaultCandidate = false)
    public HikariDataSource relayDataSource(
            @Value("${coop-erp.relay.url:jdbc:postgresql://localhost:5434/coop_erp}") String url,
            @Value("${coop-erp.relay.user:coop_relay}") String user,
            @Value("${coop-erp.relay.password:coop_relay}") String password,
            @Value("${coop-erp.relay.pool-size:2}") int poolSize) {

        return create(url, user, password, poolSize);
    }

    static HikariDataSource create(String url, String user, String password, int poolSize) {

        HikariConfig config = new HikariConfig();

        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(0);
        config.setPoolName("coop-relay");

        return new HikariDataSource(config);
    }
}
