package ai.strgm.dealer.ui;

import com.vaadin.flow.component.page.AppShellConfigurator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The DEALER UI: a separate Vaadin app that reads DEALER's and DECK's metrics REST APIs through the clients
 * generated from their OpenAPI documents. The servers define no UI (D103).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DealerUiApplication implements AppShellConfigurator {
    public static void main(final String[] args) {
        SpringApplication.run(DealerUiApplication.class, args);
    }
}
