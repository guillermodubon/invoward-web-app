package io.github.guillermodubon.invoward.identity.infrastructure.config;

import io.github.guillermodubon.invoward.identity.application.port.GuestSessionRepository;
import io.github.guillermodubon.invoward.identity.application.service.GuestSessionService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GuestSessionProperties.class)
public class GuestSessionConfiguration {

    @Bean
    public GuestSessionService guestSessionService(
            GuestSessionRepository guestSessionRepository,
            GuestSessionProperties guestSessionProperties,
            Clock clock) {
        return new GuestSessionService(
                guestSessionRepository, guestSessionProperties.sessionTtl(), clock);
    }
}
