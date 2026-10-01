package io.github.guillermodubon.invoward.analysis.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GuestCookiePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(GuestCookiePropertiesConfiguration.class);

    @Test
    void defaultsToInsecureLocalCookieAndLaxSameSite() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            GuestCookieProperties properties = context.getBean(GuestCookieProperties.class);
            assertThat(properties.secure()).isFalse();
            assertEquals("Lax", properties.sameSite());
        });
    }

    @Test
    void bindsSecureAndSupportedSameSiteValues() {
        contextRunner
                .withPropertyValues(
                        "invoward.guest.cookie.secure=true",
                        "invoward.guest.cookie.same-site=none")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GuestCookieProperties properties = context.getBean(GuestCookieProperties.class);
                    assertThat(properties.secure()).isTrue();
                    assertEquals("None", properties.sameSite());
                });

        assertEquals("Strict", new GuestCookieProperties(true, "STRICT").sameSite());
    }

    @Test
    void rejectsUnsupportedSameSiteAndNoneWithoutSecure() {
        contextRunner.withPropertyValues("invoward.guest.cookie.same-site=invalid")
                .run(context -> assertThat(context).hasFailed());

        assertThrows(IllegalArgumentException.class, () -> new GuestCookieProperties(false, "none"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GuestCookieProperties.class)
    static class GuestCookiePropertiesConfiguration {
    }
}
