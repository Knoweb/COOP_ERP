package lk.coopfed.knoweb.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * {@code make demo-data}: a one-off backend container started with {@code COOP_ERP_DEMO_LOAD=true}
 * runs the loader once after start-up and exits, with status 0 when the demo is complete and 1
 * when a command was refused (the log names it). Without the property the bean does not exist.
 */
@Component
@ConditionalOnProperty(name = "coop-erp.demo.load", havingValue = "true")
class DemoDataRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataRunner.class);

    private final DemoDataLoader loader;
    private final ConfigurableApplicationContext context;

    DemoDataRunner(DemoDataLoader loader, ConfigurableApplicationContext context) {
        this.loader = loader;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        int status = 0;
        try {
            loader.load();
        } catch (RuntimeException refused) {
            log.error("Demo data: the load stopped", refused);
            status = 1;
        }
        int exitCode = status;
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }
}
