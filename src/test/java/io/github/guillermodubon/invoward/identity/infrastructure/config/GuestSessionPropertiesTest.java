package io.github.guillermodubon.invoward.identity.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GuestSessionPropertiesTest {

    @Test
    void defaultsToTwentyFourHoursAndBindsAnApprovedShorterTtl() {
        new ApplicationContextRunner()
                .withUserConfiguration(GuestPropertiesConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertEquals(Duration.ofHours(24), context.getBean(GuestSessionProperties.class).sessionTtl());
                });

        new ApplicationContextRunner()
                .withUserConfiguration(GuestPropertiesConfiguration.class)
                .withPropertyValues("invoward.guest.session-ttl=12h")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertEquals(Duration.ofHours(12), context.getBean(GuestSessionProperties.class).sessionTtl());
                });
    }

    @Test
    void rejectsNonpositiveAndLongerThanOneDayTtls() {
        for (String value : new String[] {"0s", "-1s", "24h1s"}) {
            new ApplicationContextRunner()
                    .withUserConfiguration(GuestPropertiesConfiguration.class)
                    .withPropertyValues("invoward.guest.session-ttl=" + value)
                    .run(context -> assertThat(context).hasFailed());
        }

        assertEquals(Duration.ofNanos(1), new GuestSessionProperties(Duration.ofNanos(1)).sessionTtl());
        assertEquals(Duration.ofHours(24), new GuestSessionProperties(Duration.ofHours(24)).sessionTtl());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GuestSessionProperties.class)
    static class GuestPropertiesConfiguration {
    }
}
