package io.github.guillermodubon.invoward;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class InvoWardApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @Test
    void contextLoads() {
    }

    @Test
    void applicationUsesFoundationSafeDefaults() {
        assertEquals("invoward", environment.getProperty("spring.application.name"));
        assertEquals("health,info", environment.getProperty("management.endpoints.web.exposure.include"));
        assertEquals("never", environment.getProperty("management.endpoint.health.show-details"));
        assertEquals("never", environment.getProperty("server.error.include-message"));
        assertEquals("never", environment.getProperty("server.error.include-binding-errors"));
        assertEquals("never", environment.getProperty("server.error.include-stacktrace"));
        assertEquals("false", environment.getProperty("server.error.include-exception"));
    }

    @Test
    void actuatorHealthEndpointIsPresent() {
        assertNotNull(applicationContext.getBean(HealthEndpoint.class));
    }

}
